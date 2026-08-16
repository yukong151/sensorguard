//! W6 (sensorguard_文档(1).md §1~§5):L2 硬编码规则引擎。
//! 20 条规则从 §2 威胁模型 × §11 可观测信号矩阵推导,全部可用
//! JNI 契约已有字段表达,零额外算力。
//! 执行顺序约束(§4):Batch A 纯字段规则(Event Tick 阶段可评估),
//! Batch B 依赖 L3 统计量(仅 Batch Tick 后 L3 输出可用时评估)。

use std::collections::HashMap;
use std::sync::Mutex;

// ---------- 数据结构 ----------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RuleCategory {
    OutOfScope,
    StealthHours,
    SideChannel,
    Fingerprint,
}

impl RuleCategory {
    /// 映射到 fbs ViolationCat 枚举值(文档 §2 约定 category 与 enum 严格对应)。
    /// NONE=0, OUT_OF_SCOPE=1, STEALTH_HOURS=2, SIDE_CHANNEL=3, FINGERPRINT=4
    pub fn to_ubyte(self) -> u8 {
        match self {
            RuleCategory::OutOfScope => 1,
            RuleCategory::StealthHours => 2,
            RuleCategory::SideChannel => 3,
            RuleCategory::Fingerprint => 4,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RuleKind {
    Legit,
    Observe,
    Alert,
}

impl RuleKind {
    /// 映射到 fbs VerdictKind 枚举值:LEGIT=0, OBSERVE=1, ALERT=2
    pub fn to_ubyte(self) -> u8 {
        match self {
            RuleKind::Legit => 0,
            RuleKind::Observe => 1,
            RuleKind::Alert => 2,
        }
    }
}

/// 单条规则的匹配谓词。枚举而非动态脚本,纯 Rust 静态分发,零解释器开销。
#[derive(Clone, PartialEq, Debug)]
pub enum Predicate {
    OpEquals(u8),
    OpIn(Vec<u8>),
    DurationLt(u32),
    DurationGt(u32),
    IntervalLt(u32),
    CountInWindowGte { window_s: u32, gte: u32 },
    SampleRateGte(f32),
    UserPresentEquals(bool),
    FgStateEquals(u8),
    PowerStateEquals(bool),
    IntentHintEquals(bool),
    SystemProxyEquals(bool),
    UidNotIn(Vec<i32>),
    UidGte(u32),
    DeclPurposeNotIn(Vec<u8>),
    DeclPurposeIn(Vec<u8>),
    // 跨层谓词,依赖 L3 输出,只在 Batch B 阶段可用
    KsDGt(f32),
    BurstEntropyBetween(f32, f32),
    CoupledOpRatioGte { coupled_op: u8, ratio: f32 },
    DistinctSensitiveOpsGte { window_s: u32, gte: u32 },
}

#[derive(Clone, PartialEq, Debug)]
pub struct Debounce {
    pub window_s: u32,
    pub min_hits: u32,
    pub cool_down_s: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RuleBatch {
    A,
    B,
}

#[derive(Clone, PartialEq, Debug)]
pub struct Rule {
    pub id: u16,
    pub category: RuleCategory,
    pub kind: RuleKind,
    pub severity: u8,
    pub predicates: Vec<Predicate>, // AND 语义
    pub debounce: Debounce,
    pub min_tier: u8, // 0=T0_BASIC, 1=T1_STANDARD
    pub batch: RuleBatch,
}

// ---------- 事件上下文(供谓词读取) ----------

/// 一次判定所需的全部字段,由 Rust 侧 OpEvent + L3 中间量拼装。
/// count_in_window 为预聚合好的多窗口计数(window_s -> count)。
#[derive(Clone, Default)]
pub struct EvalContext {
    pub op: u8,
    pub uid: i32,
    pub decl_purpose: u8,
    pub duration_ms: u32,
    pub avg_interval_ms: u32,
    pub sample_rate_hz: f32,
    pub user_present: bool,
    pub fg_state: u8,
    pub power_state: bool,
    pub intent_hint: bool,
    pub system_proxy: bool,
    /// P2-2: 设备级音频活跃信号(预留,v1.1 侧信道关联规则可用)。
    pub audio_focus: bool,
    /// P2-2: 网络出端异常标记(预留,v1.1 侧信道+回传关联规则可用)。
    pub net_egress_anomaly: bool,
    pub count_in_window: HashMap<u32, u32>,
    // L3 产出,Batch A 阶段全部为 None
    pub ks_d: Option<f32>,
    pub burst_entropy: Option<f32>,
    pub coupling_ratio: Option<HashMap<u8, f32>>, // coupled_op -> ratio
    pub distinct_sensitive_ops: Option<HashMap<u32, u32>>,
}

impl EvalContext {
    pub fn count_at_least(&self, window_s: u32, gte: u32) -> bool {
        self.count_in_window.get(&window_s).copied().unwrap_or(0) >= gte
    }
}

fn eval_predicate(p: &Predicate, ctx: &EvalContext) -> bool {
    match p {
        Predicate::OpEquals(op) => ctx.op == *op,
        Predicate::OpIn(ops) => ops.contains(&ctx.op),
        Predicate::DurationLt(ms) => ctx.duration_ms < *ms,
        Predicate::DurationGt(ms) => ctx.duration_ms > *ms,
        Predicate::IntervalLt(ms) => ctx.avg_interval_ms < *ms,
        Predicate::CountInWindowGte { window_s, gte } => ctx.count_at_least(*window_s, *gte),
        Predicate::SampleRateGte(hz) => ctx.sample_rate_hz >= *hz,
        Predicate::UserPresentEquals(v) => ctx.user_present == *v,
        Predicate::FgStateEquals(v) => ctx.fg_state == *v,
        Predicate::PowerStateEquals(v) => ctx.power_state == *v,
        Predicate::IntentHintEquals(v) => ctx.intent_hint == *v,
        Predicate::SystemProxyEquals(v) => ctx.system_proxy == *v,
        Predicate::UidNotIn(list) => !list.contains(&ctx.uid),
        Predicate::UidGte(threshold) => (ctx.uid as u32) >= *threshold,
        Predicate::DeclPurposeNotIn(list) => !list.contains(&ctx.decl_purpose),
        Predicate::DeclPurposeIn(list) => list.contains(&ctx.decl_purpose),
        Predicate::KsDGt(t) => ctx.ks_d.map(|v| v > *t).unwrap_or(false),
        Predicate::BurstEntropyBetween(lo, hi) => ctx
            .burst_entropy
            .map(|v| v >= *lo && v <= *hi)
            .unwrap_or(false),
        Predicate::CoupledOpRatioGte { coupled_op, ratio } => ctx
            .coupling_ratio
            .as_ref()
            .and_then(|m| m.get(coupled_op))
            .map(|v| *v >= *ratio)
            .unwrap_or(false),
        Predicate::DistinctSensitiveOpsGte { window_s, gte } => ctx
            .distinct_sensitive_ops
            .as_ref()
            .and_then(|m| m.get(window_s))
            .map(|v| *v >= *gte)
            .unwrap_or(false),
    }
}

fn rule_matches(rule: &Rule, ctx: &EvalContext, tier: u8) -> bool {
    if tier < rule.min_tier {
        return false;
    }
    rule.predicates.iter().all(|p| eval_predicate(p, ctx))
}

// ---------- 去抖状态机(每 (uid, op, rule_id) 独立) ----------

struct HitHistory {
    hits: Vec<i64>,
    last_alert_ns: i64,
}

/// 规则引擎内共享的全局去抖存储。静态实例经 once_cell 初始化。
pub struct DebounceStore {
    inner: Mutex<HashMap<(i32, u8, u16), HitHistory>>,
}

impl DebounceStore {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(HashMap::new()),
        }
    }

    /// 清空全部去抖历史(测试隔离 / 运行时复位用)。
    pub fn clear(&self) {
        if let Ok(mut g) = self.inner.lock() {
            g.clear();
        }
    }

    /// 返回 true 表示本次命中应当真正升级为告警(通过去抖门槛)。
    pub fn record_and_check(&self, uid: i32, op: u8, rule: &Rule, now_ns: i64) -> bool {
        let mut map = match self.inner.lock() {
            Ok(g) => g,
            Err(_) => return false,
        };
        let key = (uid, op, rule.id);
        let window_ns = i64::from(rule.debounce.window_s) * 1_000_000_000;
        let cooldown_ns = i64::from(rule.debounce.cool_down_s) * 1_000_000_000;

        let entry = map.entry(key).or_insert_with(|| HitHistory {
            hits: Vec::new(),
            last_alert_ns: 0,
        });
        entry.hits.push(now_ns);
        entry.hits.retain(|&t| now_ns - t <= window_ns);

        // last_alert_ns == 0 表示该 (uid,op,rule) 从未告警,不进入冷却检查;
        // 否则冷却期内不重复告警。
        if entry.last_alert_ns != 0 && now_ns - entry.last_alert_ns < cooldown_ns {
            return false;
        }
        if entry.hits.len() as u32 >= rule.debounce.min_hits {
            entry.last_alert_ns = now_ns;
            entry.hits.clear();
            return true;
        }
        false
    }
}

impl Default for DebounceStore {
    fn default() -> Self {
        Self::new()
    }
}

// ---------- 引擎入口 ----------

#[derive(Clone, Copy, Debug)]
pub struct RuleHit {
    pub rule_id: u16,
    pub category: RuleCategory,
    pub kind: RuleKind,
    pub severity: u8,
}

pub struct RuleEngine {
    pub batch_a: Vec<Rule>,
    pub batch_b: Vec<Rule>,
    pub debounce: DebounceStore,
}

impl RuleEngine {
    pub fn new(rules: Vec<Rule>) -> Self {
        let (batch_a, batch_b) = rules.into_iter().partition(|r| r.batch == RuleBatch::A);
        Self {
            batch_a,
            batch_b,
            debounce: DebounceStore::new(),
        }
    }

    /// Event Tick 阶段调用:仅评估 Batch A(纯字段规则)。
    pub fn eval_batch_a(&self, ctx: &EvalContext, tier: u8, now_ns: i64) -> Option<RuleHit> {
        self.eval_rules(&self.batch_a, ctx, tier, now_ns)
    }

    /// Batch Tick 阶段调用:L3 统计量就位后评估 Batch B。
    pub fn eval_batch_b(&self, ctx: &EvalContext, tier: u8, now_ns: i64) -> Option<RuleHit> {
        self.eval_rules(&self.batch_b, ctx, tier, now_ns)
    }

    fn eval_rules(
        &self,
        rules: &[Rule],
        ctx: &EvalContext,
        tier: u8,
        now_ns: i64,
    ) -> Option<RuleHit> {
        // 按 severity 降序遍历,命中最高危规则即返回(单事件只出一个 Verdict)
        let mut candidates: Vec<&Rule> = rules
            .iter()
            .filter(|r| rule_matches(r, ctx, tier))
            .collect();
        candidates.sort_by_key(|a| std::cmp::Reverse(a.severity));

        for rule in candidates {
            if rule.kind == RuleKind::Observe {
                // OBSERVE 级规则不经过 debounce,直接记录(§2 R112/R116 语义)
                return Some(RuleHit {
                    rule_id: rule.id,
                    category: rule.category,
                    kind: rule.kind,
                    severity: rule.severity,
                });
            }
            if self
                .debounce
                .record_and_check(ctx.uid, ctx.op, rule, now_ns)
            {
                return Some(RuleHit {
                    rule_id: rule.id,
                    category: rule.category,
                    kind: rule.kind,
                    severity: rule.severity,
                });
            }
        }
        None
    }
}

// ---------- 20 条内置规则(文档 §2 清单) ----------

/// op 常量(与 fbs OpKind 一致):RECORD_AUDIO=0, CAMERA=1, FINE_LOCATION=2,
/// ACCEL=10, GYRO=11, MAG=12, BARO=13, LIGHT=14, PROX=15
pub mod ops {
    pub const RECORD_AUDIO: u8 = 0;
    pub const CAMERA: u8 = 1;
    pub const FINE_LOCATION: u8 = 2;
    pub const ACCEL: u8 = 10;
    pub const GYRO: u8 = 11;
    pub const MAG: u8 = 12;
    pub const BARO: u8 = 13;
    pub const LIGHT: u8 = 14;
    pub const PROX: u8 = 15;
}

/// 声明用途(decl_purpose)枚举值(文档 §5.2 示例域)。v1.0 固定子集。
pub mod purposes {
    pub const OTHER: u8 = 0;
    pub const IME: u8 = 1;
    pub const GAME: u8 = 2;
    pub const FITNESS: u8 = 3;
    pub const NAVIGATION: u8 = 4;
    pub const AR: u8 = 5;
}

/// fg_state 前台状态值:fbs CtxTag.fg_state 域,文档示例用 INVISIBLE_BG。
pub mod fg {
    pub const FOREGROUND: u8 = 0;
    pub const VISIBLE_BG: u8 = 1;
    pub const INVISIBLE_BG: u8 = 2;
}

/// 文档 §2 清单 → Rule 结构。返回全部 20 条。
pub fn builtin_rules() -> Vec<Rule> {
    let t0 = 0u8; // T0_BASIC
    let t1 = 1u8; // T1_STANDARD
    let db = |w: u32, m: u32, c: u32| Debounce {
        window_s: w,
        min_hits: m,
        cool_down_s: c,
    };
    let r = |id: u16,
             cat: RuleCategory,
             kind: RuleKind,
             sev: u8,
             preds: Vec<Predicate>,
             d: Debounce,
             tier: u8,
             batch: RuleBatch| Rule {
        id,
        category: cat,
        kind,
        severity: sev,
        predicates: preds,
        debounce: d,
        min_tier: tier,
        batch,
    };

    vec![
        // A 组 · 越界采样 OUT_OF_SCOPE
        r(
            101,
            RuleCategory::OutOfScope,
            RuleKind::Alert,
            75,
            vec![
                Predicate::OpEquals(ops::RECORD_AUDIO),
                Predicate::DurationLt(800),
                Predicate::IntervalLt(60_000),
                Predicate::CountInWindowGte {
                    window_s: 300,
                    gte: 5,
                },
                Predicate::FgStateEquals(fg::INVISIBLE_BG),
                Predicate::UserPresentEquals(false),
            ],
            db(900, 3, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            102,
            RuleCategory::OutOfScope,
            RuleKind::Alert,
            60,
            vec![
                Predicate::OpEquals(ops::RECORD_AUDIO),
                Predicate::DeclPurposeIn(vec![purposes::OTHER]),
                Predicate::CountInWindowGte {
                    window_s: 3600,
                    gte: 3,
                },
                Predicate::SystemProxyEquals(false),
            ],
            db(1800, 2, 3600),
            t0,
            RuleBatch::A,
        ),
        // B 组 · 隐蔽时段 STEALTH_HOURS
        r(
            103,
            RuleCategory::StealthHours,
            RuleKind::Alert,
            85,
            vec![
                Predicate::OpEquals(ops::RECORD_AUDIO),
                Predicate::UserPresentEquals(false),
                Predicate::PowerStateEquals(false),
                Predicate::DurationGt(3000),
            ],
            db(900, 1, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            104,
            RuleCategory::OutOfScope,
            RuleKind::Alert,
            70,
            vec![
                Predicate::OpEquals(ops::CAMERA),
                Predicate::DeclPurposeIn(vec![
                    purposes::OTHER,
                    purposes::FITNESS,
                    purposes::NAVIGATION,
                ]),
                Predicate::DurationGt(2000),
                Predicate::IntentHintEquals(false),
            ],
            db(900, 2, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            105,
            RuleCategory::StealthHours,
            RuleKind::Alert,
            90,
            vec![
                Predicate::OpEquals(ops::CAMERA),
                Predicate::UserPresentEquals(false),
                Predicate::FgStateEquals(fg::INVISIBLE_BG),
            ],
            db(900, 1, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            106,
            RuleCategory::OutOfScope,
            RuleKind::Alert,
            55,
            vec![
                Predicate::OpEquals(ops::FINE_LOCATION),
                Predicate::DeclPurposeIn(vec![purposes::OTHER, purposes::IME, purposes::GAME]),
                Predicate::CountInWindowGte {
                    window_s: 3600,
                    gte: 10,
                },
                Predicate::FgStateEquals(fg::INVISIBLE_BG),
            ],
            db(1800, 3, 3600),
            t1,
            RuleBatch::A,
        ),
        r(
            107,
            RuleCategory::StealthHours,
            RuleKind::Alert,
            50,
            vec![
                Predicate::OpEquals(ops::FINE_LOCATION),
                Predicate::UserPresentEquals(false),
                Predicate::CountInWindowGte {
                    window_s: 3600,
                    gte: 6,
                },
            ],
            db(1800, 2, 3600),
            t1,
            RuleBatch::A,
        ),
        // C 组 · 旁路推断 SIDE_CHANNEL
        r(
            108,
            RuleCategory::SideChannel,
            RuleKind::Alert,
            80,
            vec![
                Predicate::OpIn(vec![ops::ACCEL, ops::GYRO]),
                Predicate::SampleRateGte(200.0),
                Predicate::DurationGt(30_000),
                Predicate::DeclPurposeNotIn(vec![purposes::GAME, purposes::AR, purposes::FITNESS]),
            ],
            db(900, 1, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            109,
            RuleCategory::StealthHours,
            RuleKind::Alert,
            65,
            vec![
                Predicate::OpIn(vec![ops::ACCEL, ops::GYRO]),
                Predicate::UserPresentEquals(false),
                Predicate::SampleRateGte(50.0),
                Predicate::DurationGt(60_000),
            ],
            db(900, 2, 1800),
            t0,
            RuleBatch::A,
        ),
        r(
            110,
            RuleCategory::SideChannel,
            RuleKind::Alert,
            70,
            vec![
                Predicate::OpIn(vec![ops::ACCEL, ops::GYRO]),
                Predicate::BurstEntropyBetween(2.5, 4.5),
                Predicate::KsDGt(0.18),
                Predicate::DeclPurposeNotIn(vec![purposes::GAME, purposes::AR, purposes::FITNESS]),
            ],
            db(900, 2, 1800),
            t0,
            RuleBatch::B,
        ),
        r(
            111,
            RuleCategory::SideChannel,
            RuleKind::Alert,
            85,
            vec![
                Predicate::OpIn(vec![ops::ACCEL, ops::GYRO]),
                Predicate::SampleRateGte(100.0),
                Predicate::CoupledOpRatioGte {
                    coupled_op: ops::RECORD_AUDIO,
                    ratio: 0.3,
                },
            ],
            db(900, 1, 1800),
            t1,
            RuleBatch::B,
        ),
        r(
            112,
            RuleCategory::SideChannel,
            RuleKind::Observe,
            40,
            vec![
                // P0-1 修复:原 always-match 对 ACCEL/GYRO 无条件触发,导致系统组件
                // (FaceDownDetector uid=1000 / GMS uid=10213 / SystemUI) 被误报为侧信道。
                // 加 UidGte(10000) 排除所有平台系统 uid(1000-9999);
                // 加 UidNotIn([10213]) 排除 GMS(uid=10213,虽在 app 范围但由平台签名)。
                // 普通 App(uid >= 10000 且非 GMS)仍正常观察。
                Predicate::OpIn(vec![ops::ACCEL, ops::GYRO]),
                Predicate::UidGte(10000),
                Predicate::UidNotIn(vec![10213]),
                Predicate::SystemProxyEquals(false),
                Predicate::DeclPurposeNotIn(vec![2, 3]),
            ],
            // count_p99_multiple 由 Fast Tick 触发条件在引擎外表达(§5.3);
            // 此处以 always-match 占位,ffi 层仅在 Fast Tick 触发时调用。
            db(300, 1, 900),
            t0,
            RuleBatch::B,
        ),
        // D 组 · 环境指纹 FINGERPRINT
        r(
            113,
            RuleCategory::Fingerprint,
            RuleKind::Alert,
            55,
            vec![
                Predicate::OpEquals(ops::MAG),
                Predicate::SampleRateGte(20.0),
                Predicate::DeclPurposeNotIn(vec![purposes::NAVIGATION, purposes::GAME]),
            ],
            db(1800, 3, 3600),
            t0,
            RuleBatch::A,
        ),
        r(
            114,
            RuleCategory::Fingerprint,
            RuleKind::Alert,
            45,
            vec![
                Predicate::OpEquals(ops::MAG),
                Predicate::UserPresentEquals(false),
                Predicate::DurationGt(60_000),
            ],
            db(1800, 2, 3600),
            t0,
            RuleBatch::A,
        ),
        r(
            115,
            RuleCategory::Fingerprint,
            RuleKind::Alert,
            60,
            vec![Predicate::OpEquals(ops::LIGHT), Predicate::IntervalLt(50)],
            db(1800, 2, 3600),
            t0,
            RuleBatch::A,
        ),
        r(
            116,
            RuleCategory::Fingerprint,
            RuleKind::Observe,
            35,
            vec![
                Predicate::OpEquals(ops::BARO),
                Predicate::SampleRateGte(10.0),
                Predicate::DeclPurposeNotIn(vec![purposes::FITNESS, purposes::NAVIGATION]),
            ],
            db(1800, 3, 3600),
            t0,
            RuleBatch::A,
        ),
        // E 组 · 无线扫描滥用
        r(
            117,
            RuleCategory::OutOfScope,
            RuleKind::Alert,
            45,
            vec![
                Predicate::OpEquals(ops::FINE_LOCATION),
                Predicate::CountInWindowGte {
                    window_s: 600,
                    gte: 20,
                },
            ],
            db(1800, 2, 3600),
            t1,
            RuleBatch::A,
        ),
        r(
            118,
            RuleCategory::StealthHours,
            RuleKind::Alert,
            40,
            vec![
                Predicate::CountInWindowGte {
                    window_s: 600,
                    gte: 10,
                },
                Predicate::UserPresentEquals(false),
            ],
            db(1800, 2, 3600),
            t1,
            RuleBatch::A,
        ),
        r(
            119,
            RuleCategory::Fingerprint,
            RuleKind::Alert,
            50,
            vec![
                Predicate::CountInWindowGte {
                    window_s: 3600,
                    gte: 30,
                },
                Predicate::CountInWindowGte {
                    window_s: 3600,
                    gte: 15,
                },
                Predicate::DeclPurposeNotIn(vec![purposes::NAVIGATION]),
            ],
            db(3600, 2, 7200),
            t1,
            RuleBatch::B,
        ),
        r(
            120,
            RuleCategory::SideChannel,
            RuleKind::Alert,
            78,
            vec![
                Predicate::DistinctSensitiveOpsGte {
                    window_s: 60,
                    gte: 3,
                },
                Predicate::UserPresentEquals(false),
            ],
            db(900, 1, 1800),
            t1,
            RuleBatch::B,
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_ctx() -> EvalContext {
        EvalContext {
            op: 0,
            uid: 1000,
            decl_purpose: 0,
            duration_ms: 5000,
            avg_interval_ms: 0,
            sample_rate_hz: 0.0,
            user_present: false,
            fg_state: 2,
            power_state: false,
            intent_hint: false,
            system_proxy: false,
            audio_focus: false,
            net_egress_anomaly: false,
            count_in_window: HashMap::new(),
            ks_d: None,
            burst_entropy: None,
            coupling_ratio: None,
            distinct_sensitive_ops: None,
        }
    }

    #[test]
    fn builtin_has_20_rules() {
        let rules = builtin_rules();
        assert_eq!(rules.len(), 20, "文档 §2 声明 20 条规则");
        // Batch 分布:文档 §4 列表为 15 条 Batch A + 5 条 Batch B
        // (101,102,103,104,105,106,107,108,109,113,114,115,116,117,118 = 15;
        //  110,111,112,119,120 = 5;文字"14/6"与列表不符,以列表为准)
        let a = rules.iter().filter(|r| r.batch == RuleBatch::A).count();
        let b = rules.iter().filter(|r| r.batch == RuleBatch::B).count();
        assert_eq!(a, 15, "Batch A 应有 15 条,got {a}");
        assert_eq!(b, 5, "Batch B 应有 5 条,got {b}");
        // id 连续 101..=120 且唯一
        let mut ids: Vec<u16> = rules.iter().map(|r| r.id).collect();
        ids.sort_unstable();
        assert_eq!(ids, (101..=120).collect::<Vec<u16>>());
        // T0 覆盖:文档 §3 表格实际 13 条 T0(101,102,103,104,105,108,109,110,
        // 112,113,114,115,116),§2 文字"14 条"与表格不符,以表格为准。
        let t0_count = rules.iter().filter(|r| r.min_tier == 0).count();
        assert!(t0_count >= 13, "T0 覆盖应 ≥13,got {t0_count}");
    }

    #[test]
    fn r103_fires_on_single_hit_min_hits_1() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 103)
            .expect("r103")]);
        let ctx = base_ctx(); // op=RECORD_AUDIO, user_present=false, power=false, duration=5000>3000
        let hit = engine.eval_batch_a(&ctx, 0, 1_000_000_000);
        assert!(hit.is_some());
        assert_eq!(hit.unwrap().rule_id, 103);
    }

    #[test]
    fn r103_does_not_fire_when_user_present() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 103)
            .expect("r103")]);
        let mut ctx = base_ctx();
        ctx.user_present = true;
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000).is_none());
    }

    #[test]
    fn cooldown_suppresses_repeat_alert() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 103)
            .expect("r103")]);
        let ctx = base_ctx();
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000).is_some());
        // 300s 后仍在 1800s 冷却期内,不应重复告警
        assert!(engine
            .eval_batch_a(&ctx, 0, 1_000_000_000 + 300 * 1_000_000_000)
            .is_none());
        // 2000s 后冷却期已过,再次命中应告警
        assert!(engine
            .eval_batch_a(&ctx, 0, 1_000_000_000 + 2000 * 1_000_000_000)
            .is_some());
    }

    #[test]
    fn min_hits_required_before_alert() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 101)
            .expect("r101")]);
        // R101: min_hits=3, 5 分钟内 ≥5 次 <800ms 短脉冲
        let mut ctx = base_ctx();
        ctx.duration_ms = 500; // <800
        ctx.avg_interval_ms = 1000; // <60000
        ctx.fg_state = fg::INVISIBLE_BG;
        ctx.user_present = false;
        ctx.count_in_window.insert(300, 5);
        // 前两次命中不够 min_hits
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000).is_none());
        assert!(engine
            .eval_batch_a(&ctx, 0, 1_000_000_000 + 60 * 1_000_000_000)
            .is_none());
        // 第三次命中,达 min_hits=3
        assert!(engine
            .eval_batch_a(&ctx, 0, 1_000_000_000 + 120 * 1_000_000_000)
            .is_some());
    }

    #[test]
    fn tier_gate_blocks_rule() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 106)
            .expect("r106")]);
        // R106 min_tier=T1;T0 设备不评估
        let mut ctx = base_ctx();
        ctx.op = ops::FINE_LOCATION;
        ctx.decl_purpose = purposes::OTHER;
        ctx.fg_state = fg::INVISIBLE_BG;
        ctx.count_in_window.insert(3600, 10);
        // min_hits=3,需 3 次命中
        let t0 = 1_000_000_000i64;
        for i in 0..3 {
            assert!(
                engine
                    .eval_batch_a(&ctx, 0, t0 + i * 60 * 1_000_000_000)
                    .is_none(),
                "T0 设备不应命中 T1 规则"
            );
        }
        // T1 设备:第 3 次命中满足 min_hits=3 即告警
        let mut hit = None;
        for i in 0..3 {
            hit = engine.eval_batch_a(&ctx, 1, t0 + (i + 10) * 60 * 1_000_000_000);
        }
        assert!(hit.is_some(), "第 3 次命中应满足 min_hits=3");
    }

    #[test]
    fn observe_rule_bypasses_debounce() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 116)
            .expect("r116")]);
        let mut ctx = base_ctx();
        ctx.op = ops::BARO;
        ctx.sample_rate_hz = 10.0;
        ctx.decl_purpose = purposes::OTHER;
        // OBSERVE 级:单次即返回,不经过 debounce min_hits=3
        let hit = engine.eval_batch_a(&ctx, 0, 1_000_000_000);
        assert!(hit.is_some());
        assert_eq!(hit.unwrap().kind, RuleKind::Observe);
        assert_eq!(hit.unwrap().category, RuleCategory::Fingerprint);
    }

    #[test]
    fn r110_cross_layer_requires_l3() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 110)
            .expect("r110")]);
        // Batch B 阶段 L3 未就位(ks_d/burst_entropy=None)→ 不命中
        let mut ctx = base_ctx();
        ctx.op = ops::ACCEL;
        ctx.decl_purpose = purposes::OTHER;
        let t0 = 1_000_000_000i64;
        for i in 0..3 {
            assert!(
                engine
                    .eval_batch_b(&ctx, 0, t0 + i * 60 * 1_000_000_000)
                    .is_none(),
                "L3 未就位不应命中"
            );
        }
        // L3 就位后命中(需 2 次命中满足 min_hits=2)
        ctx.ks_d = Some(0.3);
        ctx.burst_entropy = Some(3.0);
        ctx.count_in_window.insert(3600, 5);
        assert!(engine
            .eval_batch_b(&ctx, 0, t0 + 100 * 1_000_000_000)
            .is_none());
         assert!(engine
             .eval_batch_b(&ctx, 0, t0 + 160 * 1_000_000_000)
             .is_some());
     }

    #[test]
    fn r112_system_uid_whitelist_blocks_system_and_gms() {
        let engine = RuleEngine::new(vec![builtin_rules()
            .into_iter()
            .find(|r| r.id == 112)
            .expect("r112")]);
        let t0 = 1_000_000_000i64;

        // 正例:普通 App(uid=10000+) + ACCEL + decl_purpose=OTHER(非 GAME/FITNESS) → 命中
        let mut ctx = base_ctx();
        ctx.op = ops::ACCEL;
        ctx.uid = 10000;
        ctx.decl_purpose = purposes::OTHER;
        ctx.system_proxy = false;
        assert!(
            engine.eval_batch_b(&ctx, 0, t0).is_some(),
            "普通 App 应触发 R112 OBSERVE"
        );

        // 反例 1:系统 uid=1000(FaceDownDetector) → UidGte(10000) 失败,不命中
        ctx.uid = 1000;
        assert!(
            engine.eval_batch_b(&ctx, 0, t0 + 100 * 1_000_000_000).is_none(),
            "系统 uid=1000 不应触发 R112"
        );

        // 反例 2:GMS uid=10213 → UidNotIn([10213]) 失败,不命中
        ctx.uid = 10213;
        assert!(
            engine.eval_batch_b(&ctx, 0, t0 + 200 * 1_000_000_000).is_none(),
            "GMS uid=10213 不应触发 R112"
        );

        // 反例 3:其他平台 uid=1001(radio) → UidGte(10000) 失败
        ctx.uid = 1001;
        assert!(
            engine.eval_batch_b(&ctx, 0, t0 + 300 * 1_000_000_000).is_none(),
            "平台 uid=1001 不应触发 R112"
        );

        // 反例 4:GMS 但用 GYRO(op=11)→ 同样被 uid_not_in 挡住
        ctx.uid = 10213;
        ctx.op = ops::GYRO;
        assert!(
            engine.eval_batch_b(&ctx, 0, t0 + 400 * 1_000_000_000).is_none(),
            "GMS + GYRO 不应触发 R112"
        );
    }
}
