use crate::event_window::{PairKey, PairWindow, PhaseKind, WINDOWS};
use crate::iforest;
use crate::ring::{Sample, RING};
use crate::sensor_baseline::SENSOR_BASELINE;
use crate::rules::{builtin_rules, EvalContext, RuleEngine, RuleHit, RuleKind};
use crate::schema::sg;
use crate::state::state;
use crate::thresholds::THRESHOLDS;
use crate::verdict;
use flatbuffers::{FlatBufferBuilder, WIPOffset};
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::panic::catch_unwind;
use std::ptr;
use std::slice;

/// 24 h 窗口(ns),文档 §5.3
const WINDOW_24H_NS: i64 = 24 * 3_600_000_000_000;

/// 全局 L2 规则引擎(20 条内置规则,无动态加载;debounce 状态贯穿进程)。
static RULES: Lazy<RuleEngine> = Lazy::new(|| RuleEngine::new(builtin_rules()));

/// L4 (v1.1, 文档 §5.4):端侧 Isolation Forest 异常检测模型。
static IFOREST: Lazy<iforest::IForest> = Lazy::new(iforest::builtin_model);

/// v1.0 证据分级由调用方(Kotlin)按设备能力评估后经 TickInput.tier 传入
/// (§4 P4:T0 基础 / T1 标准)。T1 规则(106/107/111/117/118/119/120)在
/// tier=0 的设备上按 min_tier 判定不触发,由规则引擎自动处理。

// 文档 §5.4:v1.0 8 维特征(KS/Burst/KL/事件数/活跃时间/CV/昼夜占比/S_ctx),
// feature_id 映射在 §9(v1.1 演进);v1.0 固定前 3 维 + 事件数。
const FEATURE_KS: u8 = 0;
const FEATURE_BURST_ENTROPY: u8 = 1;
const FEATURE_KL_DAY_NIGHT: u8 = 2;
const FEATURE_EVENT_TOTAL: u8 = 3;
// Deviation(doc-frozen): 活跃时间/CV/昼夜占比/S_ctx 特征 v1.0 引擎未实现,
// top3 仅输出已实现的 4 维,未实现维 feature_id 保留占位不输出。

/// S_ctx 上下文一致性分数(文档 §7):S_ctx = w1·fg + w2·user_present + w3·intent_hint
/// + w4·match(op, decl_purpose) + w5·system_proxy,阈值 0.6 以上直接判合法。
fn compute_s_ctx(pw: &PairWindow, op: u8) -> f32 {
    let snap = pw.ctx_snapshot();
    // 权重:文档 §7 公式
    const W1: f32 = 0.20;
    const W2: f32 = 0.20;
    const W3: f32 = 0.25;
    const W4: f32 = 0.25;
    const W5: f32 = 0.10;

    let fg = if snap.fg_state <= 1 { 1.0 } else { 0.0 }; // FG/VISIBLE_BG = 合法
    let user_present = if snap.user_present { 1.0 } else { 0.0 };
    let intent_hint = if snap.intent_hint { 1.0 } else { 0.0 }; // P2-2: 启用实际 intent_hint(由 Kotlin CtxProbe 产出)
    // P3: decl_purpose(1=相机,2=健身,3=导航,4=输入法)与 op 一致性匹配
    //  op: 0=MIC,1=CAM,2=LOC,10=ACCEL,11=GYRO,12=MAG
    let match_score = if purpose_matches(snap.decl_purpose, op) { 1.0 } else { 0.0 };
    let system_proxy = if snap.system_proxy { 1.0 } else { 0.0 };

    W1 * fg + W2 * user_present + W3 * intent_hint + W4 * match_score + W5 * system_proxy
}

/// P3:目的与操作的语义一致性(op 属于该用途类 App 的合理传感器集)。
fn purpose_matches(purpose: u8, op: u8) -> bool {
    match purpose {
        1 => op == 1,                       // 相机类 → CAMERA
        2 => (10..=12).contains(&op),       // 健身类 → ACCEL/GYRO/MAG(IMU)
        3 => op == 2,                       // 导航类 → LOCATION
        4 => op == 0,                       // 输入法 → RECORD_AUDIO
        _ => false,                         // 未知用途不贡献
    }
}

/// 组装单个 Verdict(§5.3 判定汇总 → fbs Verdict 表)。
/// kind: VERDICT_LEGIT/OBSERVE/ALERT(值域与 fbs VerdictKind 一致);
/// category/severity/rule_id: 当 L2 规则命中时取规则的 category/severity/rule_id,
/// 否则 category NONE、severity = L3 异常检验计数、rule_id 0;
/// s_ctx: L1 上下文分数由上层计算,引擎默认 0.0;
/// evidence_tier: 由 TickInput.tier 传入(见 §4 P4)。
/// L4 (v1.1): L2/L3 均未定论时,Isolation Forest 打分,score ≥ 0.7 升级 ALERT。
fn build_verdict<'a>(
    builder: &mut FlatBufferBuilder<'a>,
    r: &verdict::EvalResult,
    pkg_hash: &sg::PkgHash,
    op: sg::OpKind,
    now_ns: i64,
    degraded: bool,
    l2: Option<RuleHit>,
    tier: sg::EvidenceTier,
    l4_alert: bool,
    s_ctx: f32,
) -> WIPOffset<sg::Verdict<'a>> {
    // top3 特征:value=统计值, contrib=归一化贡献(0..=1,超阈值程度)
    let ks_contrib = (r.stats.ks_d / THRESHOLDS.ks_tau).clamp(0.0, 1.0) as f32;
    let burst_contrib = if r.stats.burst_entropy < THRESHOLDS.burst_entropy_min
        || r.stats.burst_entropy > THRESHOLDS.burst_entropy_max
    {
        // 越界程度归一化:以带宽(上限-下限)为基准
        let dev = if r.stats.burst_entropy < THRESHOLDS.burst_entropy_min {
            THRESHOLDS.burst_entropy_min - r.stats.burst_entropy
        } else {
            r.stats.burst_entropy - THRESHOLDS.burst_entropy_max
        };
        (dev / 2.0).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    let kl_contrib = (r.stats.kl_day_night / THRESHOLDS.kl_divergence).clamp(0.0, 1.0) as f32;
    let top3 = builder.create_vector(&[
        sg::FeatureContrib::new(FEATURE_KS, r.stats.ks_d as f32, ks_contrib),
        sg::FeatureContrib::new(
            FEATURE_BURST_ENTROPY,
            r.stats.burst_entropy as f32,
            burst_contrib,
        ),
        sg::FeatureContrib::new(
            FEATURE_KL_DAY_NIGHT,
            r.stats.kl_day_night as f32,
            kl_contrib,
        ),
        sg::FeatureContrib::new(FEATURE_EVENT_TOTAL, r.stats.event_total as f32, 0.0),
    ]);

    // L2 命中时覆盖判定字段。文档 §5.3: L2 命中(规则 kind=ALERT) → ALERT;
    // OBSERVE 级规则 → OBSERVE;LEGIT 级(v1.0 无内置)不覆盖 L3 判定。
    // 合并语义(fail-safe):L2 ALERT 优先于 L3 OBSERVE;L2 OBSERVE 不降级 L3 ALERT。
    let (kind, category, severity, rule_id) = match l2 {
        Some(h) if h.kind == RuleKind::Alert => (
            sg::VerdictKind::ALERT, sg::ViolationCat(h.category.to_ubyte()), h.severity, h.rule_id,
        ),
        Some(h) if h.kind == RuleKind::Observe && r.verdict != verdict::VERDICT_ALERT => (
            sg::VerdictKind::OBSERVE, sg::ViolationCat(h.category.to_ubyte()), h.severity, h.rule_id,
        ),
        _ => {
            // L2 未命中时:L3 ALERT 直接输出;L3 OBSERVE 则看 L4 是否升级
            if r.verdict == verdict::VERDICT_ALERT {
                (sg::VerdictKind::ALERT, sg::ViolationCat::NONE, r.alerts, 0)
            } else if l4_alert {
                (sg::VerdictKind::ALERT, sg::ViolationCat::NONE, 1.max(r.alerts), 0)
            } else {
                (sg::VerdictKind::OBSERVE, sg::ViolationCat::NONE, r.alerts, 0)
            }
        }
    };

    let mut vb = sg::VerdictBuilder::new(builder);
    vb.add_kind(kind);
    vb.add_category(category);
    vb.add_severity(severity);
    vb.add_s_ctx(s_ctx);
    vb.add_rule_id(rule_id);
    vb.add_top3(top3);
    vb.add_window_start_ns(now_ns - WINDOW_24H_NS);
    vb.add_window_end_ns(now_ns);
    vb.add_evidence_tier(tier);
    vb.add_pkg_hash(pkg_hash);
    vb.add_op(op);
    vb.add_degraded(degraded);
    vb.finish()
}

/// 跨窗口统计快照(Batch B 的 coupling/distinct 谓词输入)。
/// 在 tick 循环外一次性构建,避免与 get_or_create 的可变借用冲突。
struct CrossWindowStats {
    /// (uid, op) -> 24h 事件数(耦合比率分子)
    totals: HashMap<(i32, u8), u32>,
    /// uid -> 60s 窗口内活跃的敏感 op 种类数
    active_ops_60s: HashMap<i32, u32>,
}

impl CrossWindowStats {
    /// 遍历全部窗口构建快照。O(pairs × 1440),仅 tick 路径调用。
    fn build(windows: &crate::event_window::WindowStore, now_ns: i64) -> Self {
        let mut totals: HashMap<(i32, u8), u32> = HashMap::new();
        let mut active_ops_60s: HashMap<i32, HashSet<u8>> = HashMap::new();
        for pw in windows.iter() {
            let k = pw.key();
            if (0..=2).contains(&k.op) || (10..=15).contains(&k.op) {
                totals.insert((k.uid, k.op), pw.total(now_ns));
                if pw.count_in_window(now_ns, 60) > 0 {
                    active_ops_60s.entry(k.uid).or_default().insert(k.op);
                }
            }
        }
        Self {
            totals,
            active_ops_60s: active_ops_60s
                .into_iter()
                .map(|(uid, ops)| (uid, ops.len() as u32))
                .collect(),
        }
    }
}

/// 组装 L2 EvalContext:窗口聚合字段 + L3 统计量 + 跨窗口耦合/敏感操作统计。
/// Deviation(doc-frozen): coupling_ratio 取同 uid 下耦合 op 的 24h 事件数之比
/// (耦合 op 采样数 / 本 op 采样数),简化实现可审计;distinct_sensitive_ops
/// 取同 uid 下"60s 窗口内活跃的敏感 op 种类数"。
fn build_eval_ctx(
    pw: &PairWindow,
    uid: i32,
    op: u8,
    r: &verdict::EvalResult,
    cross: &CrossWindowStats,
    now_ns: i64,
) -> EvalContext {
    let snap = pw.ctx_snapshot();
    let mut count_in_window: HashMap<u32, u32> = HashMap::with_capacity(4);
    for ws in [60u32, 300, 600, 3600] {
        count_in_window.insert(ws, pw.count_in_window(now_ns, ws));
    }
    // 跨窗口:耦合比率(其他耦合 op 事件数 / 本 op 事件数)与 60s 敏感 op 种类数
    let self_total = cross.totals.get(&(uid, op)).copied().unwrap_or(0);
    let mut coupling_ratio: Option<HashMap<u8, f32>> = None;
    if self_total > 0 {
        let mut ratio = HashMap::new();
        for (&(u, o), &t) in cross.totals.iter() {
            if u == uid && o != op && t > 0 {
                ratio.insert(o, t as f32 / self_total as f32);
            }
        }
        if !ratio.is_empty() {
            coupling_ratio = Some(ratio);
        }
    }
    let distinct_sensitive_ops = cross
        .active_ops_60s
        .get(&uid)
        .map(|&n| HashMap::from([(60u32, n)]));

    EvalContext {
        op,
        uid,
        decl_purpose: snap.decl_purpose,
        duration_ms: pw.duration_ms(now_ns),
        avg_interval_ms: pw.avg_interval_ms(),
        sample_rate_hz: pw.sample_rate_hz(),
        user_present: snap.user_present,
        fg_state: snap.fg_state,
        power_state: snap.power_state,
        intent_hint: snap.intent_hint,
        system_proxy: snap.system_proxy,
        audio_focus: snap.audio_focus,
        net_egress_anomaly: snap.net_egress_anomaly,
        count_in_window,
        ks_d: Some(r.stats.ks_d as f32),
        burst_entropy: Some(r.stats.burst_entropy as f32),
        coupling_ratio,
        distinct_sensitive_ops,
    }
}

/// 评估 L2 规则:先 Batch A(纯字段),再 Batch B(L3 统计量就位后)。
/// 返回最高危命中的 RuleHit(单 Verdict 只出一个;规则引擎内部按 severity 排序)。
fn eval_l2_rules(
    pw: &PairWindow,
    uid: i32,
    op: u8,
    r: &verdict::EvalResult,
    cross: &CrossWindowStats,
    now_ns: i64,
    tier: u8,
) -> Option<RuleHit> {
    let ctx = build_eval_ctx(pw, uid, op, r, cross, now_ns);
    RULES
        .eval_batch_a(&ctx, tier, now_ns)
        .or_else(|| RULES.eval_batch_b(&ctx, tier, now_ns))
}

/// L4 (v1.1, 文档 §5.4):对窗口计算 Isolation Forest 异常分数。
/// 返回 [0,1] 分数;窗口数据不足(insufficient)时返回 None(不参与 L4)。
fn l4_score(r: &verdict::EvalResult, pw: &PairWindow, cross: &CrossWindowStats, uid: i32, op: u8, now_ns: i64) -> Option<f32> {
    if r.insufficient {
        return None; // 数据不足,L4 不判定(文档 §3:样本不足交长期观察)
    }
    let features = build_l4_features(pw, r, cross, uid, op, now_ns);
    let s = IFOREST.score(&features);
    Some(s as f32)
}
/// 各组维度:
///   A[0..7]  频次与强度:60s 事件数/持续时长/间隔
///   B[8..15] 时序节律:Burst 熵/主频/KL 散度/昼夜占比
///   C[16..23]上下文一致性:前台占比/user_present 等
///   D[24..27]传感器偏离:KS D/采样率偏离
///   E[28..31]跨传感器耦合:活跃 op 数/耦合比
/// 特征缺失填 -1.0(文档 §9 规范),树遍历时恒走右子。
fn build_l4_features(
    pw: &PairWindow,
    r: &verdict::EvalResult,
    cross: &CrossWindowStats,
    uid: i32,
    op: u8,
    now_ns: i64,
) -> [f32; iforest::N_FEATURES] {
    let mut f = [-1.0f32; iforest::N_FEATURES];
    // A 组:频次与强度
    f[0] = pw.count_in_window(now_ns, 60) as f32;          // 60s 事件数
    f[1] = pw.duration_ms(now_ns) as f32;                   // 累计活跃时长(ms)
    f[2] = r.stats.event_total as f32;                     // 24h 事件数
    // B 组:时序节律
    f[8] = r.stats.burst_entropy as f32;                   // Burst 熵
    f[9] = r.stats.kl_day_night as f32;                    // KL 昼夜散度
    f[10] = r.stats.ks_d as f32;                           // KS 统计量
    // C 组:上下文一致性
    let snap = pw.ctx_snapshot();
    f[16] = if snap.fg_state <= 1 { 1.0 } else { 0.0 };   // 前台占比
    f[17] = if snap.user_present { 1.0 } else { 0.0 };     // user_present
    // D 组:传感器偏离
    f[24] = r.stats.ks_d as f32;                           // KS D(复用)
    // E 组:跨传感器耦合
    if let Some(&total) = cross.totals.get(&(uid, op)) {
        f[28] = total as f32;
    }
    f[29] = cross.active_ops_60s.get(&uid).copied().unwrap_or(0) as f32;
    f
}

// W2(文档 §9):JNI C 桥接层,仅 Android 目标编译,直调本文件 sg_*
#[cfg(target_os = "android")]
mod jni_bridge;

pub const E_OK: i32 = 0;
pub const E_INVALID_ARG: i32 = -1;
pub const E_BUF_TOO_SMALL: i32 = -2;
pub const E_STATE: i32 = -3;
pub const E_INTERNAL: i32 = -4;
pub const E_RESOURCE: i32 = -5;
pub const E_PANIC: i32 = -6;

#[no_mangle]
pub extern "C" fn sg_init(_cfg: *const u8, _len: usize) -> i32 {
    match catch_unwind(|| {
        state().mark_ready();
        E_OK
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
pub extern "C" fn sg_push_sensor(ts_ns: i64, kind: u8, x: f32, y: f32, z: f32) -> i32 {
    match catch_unwind(|| {
        if !state().is_ready() {
            return E_STATE;
        }
        if ts_ns <= 0 {
            return E_INVALID_ARG;
        }
        RING.push(Sample {
            ts_ns,
            kind,
            x,
            y,
            z,
        })
        .map(|_| E_OK)
        .unwrap_or(E_RESOURCE)
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
// Deviation(doc-frozen): clippy::not_unsafe_ptr_arg_deref gate. Signature per §4.2 ABI contract;
// the only deref is null-guarded slice::from_raw_parts below.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn sg_push_op(buf: *const u8, len: usize) -> i32 {
    match catch_unwind(|| {
        if !state().is_ready() {
            return E_STATE;
        }
        if buf.is_null() || len == 0 {
            return E_INVALID_ARG;
        }
        // W2 (文档 §4.1):FlatBuffers 反序列化 OpEvent,落入 24h 事件窗口。
        // W6:phase/ctx 一并落入窗口(时长配对 + 上下文快照,L2 规则输入)。
        // root::<OpEvent> 泛型读取(非 root_type,生成代码无 root_as_op_event)。
        let input = unsafe { slice::from_raw_parts(buf, len) };
        let event = match flatbuffers::root::<sg::OpEvent>(input) {
            Ok(e) => e,
            Err(_) => return E_INVALID_ARG,
        };
        let ts_ns = event.ts_ns();
        if ts_ns <= 0 {
            return E_INVALID_ARG;
        }
        let phase = PhaseKind::from_fbs(event.phase().0);
        let ctx_snap = match event.ctx() {
            Some(t) => crate::event_window::CtxSnapshot {
                fg_state: t.fg_state(),
                user_present: t.user_present(),
                power_state: t.power_state(),
                intent_hint: t.intent_hint(),
                decl_purpose: t.decl_purpose(),
                system_proxy: t.system_proxy(),
                audio_focus: t.audio_focus(),
                net_egress_anomaly: t.net_egress_anomaly(),
            },
            None => crate::event_window::CtxSnapshot::default(),
        };
        let key = PairKey::new(event.uid(), event.op().0);
        let mut windows = WINDOWS.lock().unwrap();
        match windows.get_or_create(key) {
            Some(pw) => {
                pw.record_ctx(ts_ns, phase, ctx_snap);
                // W12/T2 (文档 §4 P4):写入 Shizuku 解析的物理采样周期(微秒),
                // 供 sample_rate_hz 优先采用(0 表示非 Shizuku 来源,set 内部忽略)。
                let sp = event.sampling_period_us();
                if sp > 0 {
                    pw.set_sampling_period_us(sp);
                }
                E_OK
            }
            // 组合数达上限:反压降级(文档 §9),拒绝新组合
            None => E_RESOURCE,
        }
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
// Deviation(doc-frozen): clippy::not_unsafe_ptr_arg_deref gate. Signature per §4.2 ABI contract;
// the only deref is null-guarded *out_len = 0 below.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn sg_tick(
    in_buf: *const u8,
    in_len: usize,
    out_buf: *mut u8,
    out_cap: usize,
    out_len: *mut usize,
) -> i32 {
    match catch_unwind(|| {
        if !state().is_ready() {
            return E_STATE;
        }
        // W5 (文档 §5.2):Batch Tick 消费 RING,更新传感器基线(第三方高频 IMU 采样检测)。
        // 在输入校验前执行,保证即便 TickInput 非法也不丢基线样本。
        SENSOR_BASELINE.lock().unwrap().drain_ring();
        if in_buf.is_null() || in_len == 0 || out_buf.is_null() || out_cap == 0 {
            unsafe {
                if !out_len.is_null() {
                    *out_len = 0;
                }
            }
            return E_INVALID_ARG;
        }
        // W2~W5 (文档 §4.1):TickInput → 评估 changed 窗口 → 组装 VerdictBatch。
        let input = unsafe { slice::from_raw_parts(in_buf, in_len) };
        let tick = match flatbuffers::root::<sg::TickInput>(input) {
            Ok(t) => t,
            Err(_) => {
                unsafe {
                    *out_len = 0;
                }
                return E_INVALID_ARG;
            }
        };
        let tick_id = tick.tick_id();
        let now_ns = tick.now_ns();
        // W9 (文档 §4 P4):证据分级由调用方评估,经 TickInput.tier 传入。
        let tier = tick.tier();
        if now_ns <= 0 {
            unsafe {
                *out_len = 0;
            }
            return E_INVALID_ARG;
        }

        let mut builder = FlatBufferBuilder::with_capacity(4096);
        let mut verdicts: Vec<WIPOffset<sg::Verdict>> = Vec::new();
        {
            let mut windows = WINDOWS.lock().unwrap();
            // W6:跨窗口统计快照(Batch B coupling/distinct 谓词输入),循环外构建
            let cross = CrossWindowStats::build(&windows, now_ns);
            if let Some(pairs) = tick.active_pairs() {
                for pair in pairs.iter() {
                    let uid = pair.uid();
                    let op = pair.op();
                    let key = PairKey::new(uid, op.0);
                    match windows.get_or_create(key) {
                        Some(pw) => {
                            if pw.changed() {
                                pw.clear_changed();
                                let r = verdict::evaluate(pw, now_ns);
                                // W6:L2 规则评估(Batch A → Batch B),命中覆盖判定字段
                                let l2 = eval_l2_rules(pw, uid, op.0, &r, &cross, now_ns, tier.0);
                                // L4 (v1.1):L2 未命中且 L3 未定论时,IForest 打分升级
                                let l4_alert = l2.is_none()
                                    && r.verdict != verdict::VERDICT_ALERT
                                    && l4_score(&r, pw, &cross, uid, op.0, now_ns)
                                        .map(|s| s >= iforest::ALERT_THRESHOLD as f32)
                                        .unwrap_or(false);
                                verdicts.push(build_verdict(
                                    &mut builder,
                                    &r,
                                    pair.pkg_hash(),
                                    op,
                                    now_ns,
                                    false,
                                    l2,
                                    tier,
                                    l4_alert,
                                    compute_s_ctx(pw, op.0),
                                ));
                            }
                        }
                        // 组合数达上限:该 (uid,op) 无法建档,输出降级 Verdict(文档 §9)
                        None => {
                            let r = verdict::EvalResult {
                                verdict: verdict::VERDICT_OBSERVE,
                                alerts: 0,
                                stats: verdict::L3Stats::default(),
                                insufficient: true,
                            };
                            verdicts.push(build_verdict(
                                &mut builder,
                                &r,
                                pair.pkg_hash(),
                                op,
                                now_ns,
                                true,
                                None,
                                tier,
                                false, // 降级路径不做 L4
                                0.0,   // 降级路径无上下文,默认 s_ctx=0
                            ));
                        }
                    }
                }
            }
        }

        // 组装 VerdictBatch(schema_version=1,文档 §4.1)
        let verdict_vec = builder.create_vector(&verdicts);
        let mut vb = sg::VerdictBatchBuilder::new(&mut builder);
        vb.add_verdicts(verdict_vec);
        vb.add_tick_id(tick_id);
        vb.add_wall_start_ns(now_ns - WINDOW_24H_NS);
        vb.add_wall_end_ns(now_ns);
        vb.add_schema_version(1);
        let root = vb.finish();
        builder.finish(root, None);
        let data = builder.finished_data();

        unsafe {
            if data.len() > out_cap {
                *out_len = 0;
                return E_BUF_TOO_SMALL;
            }
            ptr::copy_nonoverlapping(data.as_ptr(), out_buf, data.len());
            *out_len = data.len();
        }
        E_OK
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
// Deviation(doc-frozen): clippy::not_unsafe_ptr_arg_deref gate. Signature per §4.2 ABI contract;
// the only deref is null-guarded *out_len = 0 below.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn sg_snapshot(_out: *mut u8, _cap: usize, out_len: *mut usize) -> i32 {
    match catch_unwind(|| {
        unsafe {
            if !out_len.is_null() {
                *out_len = 0;
            }
        }
        E_OK
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
// Deviation(doc-frozen): clippy::not_unsafe_ptr_arg_deref gate. Signature per §4.2 ABI contract;
// the only deref is null-guarded *out_len = 0 below.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
pub extern "C" fn sg_sensor_health(out: *mut u8, cap: usize, out_len: *mut usize) -> i32 {
    match catch_unwind(|| {
        if out.is_null() || cap == 0 {
            unsafe {
                if !out_len.is_null() {
                    *out_len = 0;
                }
            }
            return E_INVALID_ARG;
        }
        if !state().is_ready() {
            unsafe {
                if !out_len.is_null() {
                    *out_len = 0;
                }
            }
            return E_STATE;
        }
        // W5 (文档 §5.2):消费 RING(若 sg_tick 尚未消费,确保基线推进),再序列化健康信号。
        let mut sb = SENSOR_BASELINE.lock().unwrap();
        sb.drain_ring();
        let bytes = sb.health_bytes();
        unsafe {
            if bytes.len() > cap {
                *out_len = 0;
                return E_BUF_TOO_SMALL;
            }
            ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len());
            *out_len = bytes.len();
        }
        E_OK
    }) {
        Ok(c) => c,
        Err(_) => {
            state().enter_safe_mode();
            E_PANIC
        }
    }
}

#[no_mangle]
pub extern "C" fn sg_shutdown() -> i32 {
    match catch_unwind(|| {
        state().shutdown();
        E_OK
    }) {
        Ok(c) => c,
        Err(_) => E_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ptr::{null, null_mut};

    /// 全局串行锁:ffi 测试共享静态 WINDOWS / state(),必须串行执行。
    static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// 每个测试的隔离前置:加锁 + 清空窗口 + 清空全局去抖 + 重新 init。
    /// 中毒锁(前一测试 panic)视为可恢复,取回内部值继续。
    fn isolate() -> std::sync::MutexGuard<'static, ()> {
        let guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        WINDOWS.lock().unwrap_or_else(|e| e.into_inner()).clear();
        RULES.debounce.clear();
        sg_shutdown();
        assert_eq!(sg_init(null(), 0), E_OK);
        guard
    }

    /// 构造合法 OpEvent FlatBuffers
    fn make_op_event(ts_ns: i64, uid: i32, op: u8) -> Vec<u8> {
        let mut b = FlatBufferBuilder::new();
        let mut eb = sg::OpEventBuilder::new(&mut b);
        eb.add_ts_ns(ts_ns);
        eb.add_uid(uid);
        eb.add_op(sg::OpKind(op));
        let root = eb.finish();
        b.finish(root, None);
        b.finished_data().to_vec()
    }

    /// phase: 0=START 1=STOP 2=TICK(fbs Phase 枚�数);
    /// ctx: 按 CtxTag::new 签名(fg_state, user_present, intent_hint, decl_purpose,
    /// system_proxy, audio_focus, power_state, net_egress_anomaly)逐字段传入。
    #[allow(clippy::too_many_arguments)]
    fn make_op_event_ctx(
        ts_ns: i64,
        uid: i32,
        op: u8,
        phase: u8,
        fg: u8,
        user_present: bool,
        intent_hint: bool,
        decl_purpose: u8,
        system_proxy: bool,
        power_state: bool,
    ) -> Vec<u8> {
        let mut b = FlatBufferBuilder::new();
        let mut eb = sg::OpEventBuilder::new(&mut b);
        eb.add_ts_ns(ts_ns);
        eb.add_uid(uid);
        eb.add_op(sg::OpKind(op));
        eb.add_phase(sg::Phase(phase));
        let ctx = sg::CtxTag::new(
            fg,
            user_present,
            intent_hint,
            decl_purpose,
            system_proxy,
            false, // audio_focus(P2-2 已建模,测试夹具固定 false)
            power_state,
            false, // net_egress_anomaly(P2-2 已建模,测试夹具固定 false)
        );
        eb.add_ctx(&ctx);
        let root = eb.finish();
        b.finish(root, None);
        b.finished_data().to_vec()
    }

    /// 构造包含全部字段的 OpEvent(全字段夹具:ts_ns/uid/pkg_hash/op/phase/ctx/sampling_period_us)
    /// 供 Kotlin FbSerde 字节级对齐:FbSerde.encodeOpEvent 必须输出完全相同的字节。
    fn make_op_event_full(ts_ns: i64, uid: i32, op: u8, phase: u8) -> Vec<u8> {
        let mut b = FlatBufferBuilder::new();
        let ph = sg::PkgHash([0xAAu8; 12]);
        let mut eb = sg::OpEventBuilder::new(&mut b);
        eb.add_ts_ns(ts_ns);
        eb.add_uid(uid);
        eb.add_pkg_hash(&ph);
        eb.add_op(sg::OpKind(op));
        eb.add_phase(sg::Phase(phase));
        // W12/T2:全字段夹具显式设置物理采样周期,覆盖 Kotlin golden 的采样率字段
        eb.add_sampling_period_us(20_000);
        let ctx = sg::CtxTag::new(2, true, true, 3, true, false, true, false);
        eb.add_ctx(&ctx);
        let root = eb.finish();
        b.finish(root, None);
        b.finished_data().to_vec()
    }

    /// 构造全字段 VerdictBatch 夹具(bytes 由真实 flatbuffers crate 编码,
    /// Kotlin FbSerde.decodeVerdictBatch 必须能解码出完全相同的字段值)。
    fn make_verdict_batch_fixture() -> Vec<u8> {
        let mut b = FlatBufferBuilder::new();
        let ph = sg::PkgHash([0xBBu8; 12]);
        let top3 = b.create_vector(&[
            sg::FeatureContrib::new(0, 0.125, 0.75),
            sg::FeatureContrib::new(1, 3.3, 1.0),
        ]);
        let mut vb = sg::VerdictBuilder::new(&mut b);
        vb.add_kind(sg::VerdictKind::ALERT);
        vb.add_category(sg::ViolationCat::STEALTH_HOURS);
        vb.add_severity(85);
        vb.add_s_ctx(0.5);
        vb.add_rule_id(103);
        vb.add_top3(top3);
        vb.add_window_start_ns(1_600_000_000_000_000_000i64);
        vb.add_window_end_ns(1_700_000_000_000_000_000i64);
vb.add_evidence_tier(sg::EvidenceTier::T0_BASIC);
        vb.add_pkg_hash(&ph);
        vb.add_op(sg::OpKind::RECORD_AUDIO);
        vb.add_degraded(false);
        let verdict = vb.finish();
        let verdicts = b.create_vector(&[verdict]);
        let mut bb = sg::VerdictBatchBuilder::new(&mut b);
        bb.add_verdicts(verdicts);
        bb.add_tick_id(42);
        bb.add_wall_start_ns(1_600_000_000_000_000_000i64);
        bb.add_wall_end_ns(1_700_000_000_000_000_000i64);
        bb.add_schema_version(1);
        let root = bb.finish();
        b.finish(root, None);
        b.finished_data().to_vec()
    }

    #[test]
    fn dump_fb_fixtures_hex() {
        // 手工夹具:打印 hex 供 Kotlin FbSerde 侧嵌入 golden 断言。
        // 非互斥需求但保持串行无害。
        let _guard = isolate();
        let hex = |v: &[u8]| v.iter().map(|x| format!("{:02X}", x)).collect::<String>();
        println!("FB_FIXTURE_OP_EVENT_FULL={}", hex(&make_op_event_full(
            1_700_000_000_000_000_000i64, 12_345, 1, 1,
        )));
        println!("FB_FIXTURE_TICK_INPUT={}", hex(&make_tick_input(42, 1_700_000_000_000_000_000i64, &[(12_345, 1), (67_890, 14)])));
        println!("FB_FIXTURE_VERDICT_BATCH={}", hex(&make_verdict_batch_fixture()));
        sg_shutdown();
    }

    /// 构造合法 TickInput FlatBuffers,active_pairs 含给定 (uid, op)
    fn make_tick_input(tick_id: u64, now_ns: i64, pairs: &[(i32, u8)]) -> Vec<u8> {
        let mut b = FlatBufferBuilder::new();
        let ph = sg::PkgHash([1u8; 12]);
        let mut aps = Vec::new();
        for &(uid, op) in pairs {
            aps.push(sg::ActivePair::new(uid, sg::OpKind(op), &ph));
        }
        let v = b.create_vector(&aps);
        let mut tb = sg::TickInputBuilder::new(&mut b);
        tb.add_tick_id(tick_id);
        tb.add_now_ns(now_ns);
        tb.add_active_pairs(v);
        tb.add_tier(sg::EvidenceTier::T0_BASIC);
        let root = tb.finish();
        b.finish(root, None);
        b.finished_data().to_vec()
    }

    #[test]
    fn init_push_shutdown_ok() {
        // 独占串行锁;此测试特意验证"未 init 前拒绝",不能预先 init。
        let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        WINDOWS.lock().unwrap_or_else(|e| e.into_inner()).clear();
        while RING.pop().is_some() {} // 清空 RING,避免前序测试残留致 E_RESOURCE
        sg_shutdown();
        // state 路径:未 init 前,入口一律拒绝
        assert_eq!(sg_tick(null(), 0, null_mut(), 0, null_mut()), E_STATE);
        assert_eq!(sg_push_sensor(1, 10, 0.0, 0.0, 0.0), E_STATE);
        // init 路径
        assert_eq!(sg_init(std::ptr::null(), 0), E_OK);
        // push 路径
        assert_eq!(sg_push_sensor(1, 10, 0.1, 0.2, 0.3), E_OK);
        assert_eq!(sg_push_sensor(0, 10, 0.0, 0.0, 0.0), E_INVALID_ARG);
        // shutdown 路径
        assert_eq!(sg_shutdown(), E_OK);
        // state 路径:shutdown 后拒绝
        assert_eq!(sg_push_sensor(1, 10, 0.0, 0.0, 0.0), E_STATE);
    }

    #[test]
    fn push_op_flatbuffers_roundtrip() {
        let _guard = isolate();
        let buf = make_op_event(1_700_000_000_000_000_000i64, 10_000, 0);
        let rc = sg_push_op(buf.as_ptr(), buf.len());
        assert_eq!(rc, E_OK);
        // 窗口已建档且计数正确
        let mut windows = WINDOWS.lock().unwrap();
        let pw = windows
            .get_or_create(PairKey::new(10_000, 0))
            .expect("window created");
        assert_eq!(pw.total(1_700_000_000_000_000_000i64), 1);
        sg_shutdown();
    }

    /// W10 (文档 §9):反压降级 —— ring 满时 sg_push_sensor 返回 E_RESOURCE 而非覆盖/阻塞。
    /// 预填 CAP(4096)后下一次 push 应拒绝;消费一个后恢复可入。
    #[test]
    fn push_sensor_backpressure_returns_resource() {
        let _guard = isolate();
        assert_eq!(sg_init(std::ptr::null(), 0), E_OK);
        // 清空 ring 至空态,从空开始填
        while RING.pop().is_some() {}
        let cap = RING.capacity();
        for i in 0..cap as i64 {
            assert_eq!(
                sg_push_sensor(1_000 + i, 10, 0.1, 0.2, 0.3),
                E_OK,
                "slot {i} should accept"
            );
        }
        // 满态:反压拒绝,不覆盖
        assert_eq!(sg_push_sensor(9_999, 10, 0.0, 0.0, 0.0), E_RESOURCE);
        assert_eq!(RING.len(), cap, "满态不驱逐");
        // 消费一个后恢复可入
        assert!(RING.pop().is_some());
        assert_eq!(sg_push_sensor(10_000, 10, 0.0, 0.0, 0.0), E_OK);
        sg_shutdown();
    }

    #[test]
    fn push_op_rejects_garbage() {
        let _guard = isolate();
        let garbage = [0xdeu8, 0xad, 0xbe, 0xef, 1, 2, 3];
        assert_eq!(sg_push_op(garbage.as_ptr(), garbage.len()), E_INVALID_ARG);
        sg_shutdown();
    }

    #[test]
    fn tick_builds_verdict_batch() {
        let _guard = isolate();
        // 预填:20+ 事件(达到 MIN_EVENTS)
        let t0 = 1_700_000_000_000_000_000i64;
        for i in 0..60 {
            let buf = make_op_event(t0 + i * 1_000_000_000, 10_000, 0);
            assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
        }
        // tick:active_pairs 含 (10000, op=0),now 在最后一个事件后
        let now = t0 + 60 * 1_000_000_000;
        let input = make_tick_input(42, now, &[(10_000, 0)]);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        assert!(out_len > 0, "VerdictBatch 应有输出");
        // 反序列化验证
        let batch = flatbuffers::root::<sg::VerdictBatch>(&out[..out_len]).expect("valid batch");
        assert_eq!(batch.tick_id(), 42);
        assert_eq!(batch.schema_version(), 1);
        let verdicts = batch.verdicts().expect("has verdicts");
        assert_eq!(verdicts.len(), 1);
        let v = verdicts.get(0);
        assert_eq!(v.op(), sg::OpKind(0));
        assert!(!v.degraded());
        // 60 个 1s 间隔事件集中在单分钟桶 → 突发模式,引擎判 ALERT(verdict.rs 语义)
        assert_eq!(v.kind(), sg::VerdictKind::ALERT);
        sg_shutdown();
    }

    #[test]
    fn tick_insufficient_data_reports_observe() {
        let _guard = isolate();
        let t0 = 1_700_000_000_000_000_000i64;
        // 仅 3 事件(< MIN_EVENTS=20)
        for i in 0..3 {
            let buf = make_op_event(t0 + i * 1_000_000_000, 20_000, 1);
            assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
        }
        let now = t0 + 3 * 1_000_000_000;
        let input = make_tick_input(7, now, &[(20_000, 1)]);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        let batch = flatbuffers::root::<sg::VerdictBatch>(&out[..out_len]).expect("valid batch");
        let verdicts = batch.verdicts().expect("has verdicts");
        assert_eq!(verdicts.len(), 1);
        // 数据不足仍输出 OBSERVE(引擎不输出 LEGIT)
        assert_eq!(verdicts.get(0).kind(), sg::VerdictKind::OBSERVE);
        sg_shutdown();
    }

    #[test]
    fn tick_rejects_bad_input_and_small_buffer() {
        let _guard = isolate();
        let t0 = 1_700_000_000_000_000_000i64;
        let input = make_tick_input(1, t0, &[]);
        let mut out = vec![0u8; 16];
        let mut out_len = 0usize;
        // 输出缓冲太小 → E_BUF_TOO_SMALL
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_BUF_TOO_SMALL);
        // 非法输入 buffer → E_INVALID_ARG
        let garbage = [0xffu8, 0xff, 0xff, 0xff];
        let mut out2 = vec![0u8; 4096];
        let rc2 = sg_tick(
            garbage.as_ptr(),
            garbage.len(),
            out2.as_mut_ptr(),
            out2.len(),
            &mut out_len,
        );
        assert_eq!(rc2, E_INVALID_ARG);
        sg_shutdown();
    }

    #[test]
    fn tick_l2_r103_stealth_recording_alerts() {
        let _guard = isolate();
        // R103(RECORD_AUDIO + 用户不在场 + 电源关闭 + 时长>3000ms)→ ALERT SealthHours
        let t0 = 1_700_000_000_000_000_000i64;
        // START 事件(phase=0),ctx:fgs=INVISIBLE_BG, 用户缺席, 电源关闭
        let buf = make_op_event_ctx(t0, 30_001, 0, 0, 2, false, false, 0, false, false);
        assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
        // 仅 1 事件(< MIN_EVENTS=20)→ L3 数据不足会判 OBSERVE,但 L2 命中应覆盖为 ALERT
        let now = t0 + 5 * 1_000_000_000; // 距 START 5s → duration 5000ms > 3000ms
        let input = make_tick_input(90, now, &[(30_001, 0)]);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        let batch = flatbuffers::root::<sg::VerdictBatch>(&out[..out_len]).expect("valid batch");
        let verdicts = batch.verdicts().expect("has verdicts");
        assert_eq!(verdicts.len(), 1);
        let v = verdicts.get(0);
        assert_eq!(v.kind(), sg::VerdictKind::ALERT);
        assert_eq!(v.category(), sg::ViolationCat::STEALTH_HOURS);
        assert_eq!(v.severity(), 85);
        assert_eq!(v.rule_id(), 103);
        sg_shutdown();
    }

    #[test]
    fn tick_l2_r105_stealth_camera_alerts() {
        let _guard = isolate();
        // R105(CAMERA + 用户不在场 + fg_state=INVISIBLE_BG)→ ALERT StealthHours sev=90
        let t0 = 1_700_000_000_000_000_000i64;
        let buf = make_op_event_ctx(t0, 30_002, 1, 0, 2, false, true, 3, false, true);
        assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
        let now = t0 + 2_000_000_000;
        let input = make_tick_input(91, now, &[(30_002, 1)]);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        let batch = flatbuffers::root::<sg::VerdictBatch>(&out[..out_len]).expect("valid batch");
        let verdicts = batch.verdicts().expect("has verdicts");
        assert_eq!(verdicts.len(), 1);
        let v = verdicts.get(0);
        assert_eq!(v.kind(), sg::VerdictKind::ALERT);
        assert_eq!(v.category(), sg::ViolationCat::STEALTH_HOURS);
        assert_eq!(v.severity(), 90);
        assert_eq!(v.rule_id(), 105);
        sg_shutdown();
    }

    #[test]
    fn tick_l2_benign_ctx_does_not_hit() {
        let _guard = isolate();
        // 无谓词命中:用户在场+前台 → 不触发 L2,回退 L3 语义(数据不足 → OBSERVE)
        let t0 = 1_700_000_000_000_000_000i64;
        let buf = make_op_event_ctx(t0, 30_003, 0, 0, 0, true, true, 2, false, true);
        assert_eq!(sg_push_op(buf.as_ptr(), buf.len()), E_OK);
        let now = t0 + 5 * 1_000_000_000;
        let input = make_tick_input(92, now, &[(30_003, 0)]);
        let mut out = vec![0u8; 4096];
        let mut out_len = 0usize;
        let rc = sg_tick(
            input.as_ptr(),
            input.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut out_len,
        );
        assert_eq!(rc, E_OK);
        let batch = flatbuffers::root::<sg::VerdictBatch>(&out[..out_len]).expect("valid batch");
        let verdicts = batch.verdicts().expect("has verdicts");
        assert_eq!(verdicts.len(), 1);
        let v = verdicts.get(0);
        assert_eq!(v.kind(), sg::VerdictKind::OBSERVE);
        assert_eq!(v.category(), sg::ViolationCat::NONE);
        assert_eq!(v.rule_id(), 0);
        sg_shutdown();
    }

    #[test]
    fn sensor_health_detects_third_party() {
        let _guard = isolate();
        // 隔离全局 RING 与传感器基线(跨测试串行锁已保证无并发,仍显式复位)。
        while RING.pop().is_some() {}
        SENSOR_BASELINE.lock().unwrap().reset();
        assert_eq!(sg_init(std::ptr::null(), 0), E_OK);
        let mut ts = 1_700_000_000_000_000_000i64;
        // warm:1500 个 20ms 间隔(kind=10, 基线 D0 ≈ 50Hz)
        for _ in 0..1500u32 {
            ts += 20_000_000;
            assert_eq!(sg_push_sensor(ts, 10, 0.0, 0.0, 0.0), E_OK);
        }
        // 观测窗口:600 个 5ms 间隔(第三方高频抢档,抖动分布系统性偏移 Dt)
        for _ in 0..600u32 {
            ts += 5_000_000;
            assert_eq!(sg_push_sensor(ts, 10, 0.0, 0.0, 0.0), E_OK);
        }
        let mut out = vec![0u8; 256];
        let mut out_len = 0usize;
        let rc = sg_sensor_health(out.as_mut_ptr(), out.len(), &mut out_len);
        assert_eq!(rc, E_OK);
        assert!(out_len >= 11, "应至少 11 字节(1 count + 1 record)");
        assert_eq!(out[0], 1, "应报告 1 个 kind");
        assert_eq!(out[1], 10, "kind=ACCEL");
        // ks_d 高 → anomaly=1
        assert_eq!(out[6], 1, "应标记 anomaly");
        sg_shutdown();
    }

    #[test]
    fn sensor_health_not_ready_rejected() {
        // 独占:未 init 前 sg_sensor_health 必须拒绝
        let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        WINDOWS.lock().unwrap_or_else(|e| e.into_inner()).clear();
        SENSOR_BASELINE.lock().unwrap_or_else(|e| e.into_inner()).reset();
        while RING.pop().is_some() {}
        sg_shutdown();
        let mut out = vec![0u8; 64];
        let mut out_len = 0usize;
        assert_eq!(
            sg_sensor_health(out.as_mut_ptr(), out.len(), &mut out_len),
            E_STATE
        );
    }
}
