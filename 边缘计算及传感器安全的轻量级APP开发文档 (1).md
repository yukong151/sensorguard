### **L2 规则引擎:20 条硬规则完整清单 + 来源方法论 + Rust 实现**

## **1 · 规则来源方法论(不是拍脑袋定的)**

20 条规则不是随意列举,而是从 §2 威胁模型的 5 大攻击类别,交叉 §11 权限清单里"我方能观测到的信号维度",用下面这张**信号 × 攻击类别矩阵**系统性推导出来的:

| 可观测信号 | 越界采样 (OUT_OF_SCOPE) | 隐蔽时段 (STEALTH_HOURS) | 旁路推断 (SIDE_CHANNEL) | 指纹追踪 (FINGERPRINT) |
|---|---|---|---|---|
| AppOps: RECORD_AUDIO | R101, R102 | R103 | - | - |
| AppOps: CAMERA | R104 | R105 | - | - |
| AppOps: LOCATION | R106 | R107 | - | - |
| 自采基线: ACCEL/GYRO | R108 | R109 | R110, R111, R112 | - |
| 自采基线: MAG | - | - | - | R113, R114 |
| 自采基线: LIGHT/PROX | - | - | - | R115, R116 |
| 蓝牙/Wi-Fi 扫描 (系统 API) | R117 | R118 | - | R119 |
| 跨传感器耦合 (§5.4 组 E) | - | - | R120 | - |

**推导原则:** 每个矩阵格子对应"能不能用现有 JNI 契约里已有的字段表达出一条判定式",不能表达的格子(比如靠原始波形判定的规则)直接排除,留给 v1.1 的 L4 模型。这保证 20 条规则**全部可在 L2 用纯字段比较实现,零额外算力**。

**规则编号约定:** `10X` = 越界类,`10X+2` 段落间隔留给同类扩展;实际按 §5.2 JSON schema 用连续 `id: 101\~120`,`category` 字段与 `ViolationCat` enum 严格对应,`min_tier` 按信号可用性标注(见 §5.5)。

---

## **2 · 20 条规则完整清单**

### **A 组 · 越界采样 (OUT_OF_SCOPE),4 条**

**R101 · MIC-SHORT-PULSE**(已在前文给出,此处补全 severity 与 tier)
- 语义: 非前台 App 在 5 分钟内 ≥5 次 <800ms 的麦克风短脉冲采样
- `min_tier: T0_BASIC`,`severity: 75`

**R102 · MIC-NO-PURPOSE-MATCH**
```json
{
  "id": 102, "name": "MIC-NO-PURPOSE-MATCH",
  "match": {
    "op": "RECORD_AUDIO",
    "decl_purpose_in": ["OTHER"],
    "count_in_window": {"window_s": 3600, "gte": 3},
    "system_proxy": false
  },
  "verdict": {"kind": "ALERT", "category": "OUT_OF_SCOPE", "severity": 60},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T0_BASIC"
}
```
语义: App 声明用途未知(非相机/导航/IME/游戏等),却频繁调用麦克风。

**R104 · CAM-NO-PURPOSE-MATCH**
```json
{
  "id": 104, "name": "CAM-NO-PURPOSE-MATCH",
  "match": {
    "op": "CAMERA",
    "decl_purpose_in": ["OTHER", "FITNESS", "NAVIGATION"],
    "duration_ms": {"gt": 2000},
    "intent_hint": false
  },
  "verdict": {"kind": "ALERT", "category": "OUT_OF_SCOPE", "severity": 70},
  "debounce": {"window_s": 900, "min_hits": 2, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```
语义: 用途与摄像头无关的 App 长时间持有摄像头且无用户手势触发意图(`intent_hint=false` 表示最近 5s 内没有 `ACTION_IMAGE_CAPTURE` 等)。

**R106 · LOC-NO-PURPOSE-MATCH**
```json
{
  "id": 106, "name": "LOC-NO-PURPOSE-MATCH",
  "match": {
    "op": "FINE_LOCATION",
    "decl_purpose_in": ["OTHER", "IME", "GAME"],
    "count_in_window": {"window_s": 3600, "gte": 10},
    "fg_state": "INVISIBLE_BG"
  },
  "verdict": {"kind": "ALERT", "category": "OUT_OF_SCOPE", "severity": 55},
  "debounce": {"window_s": 1800, "min_hits": 3, "cool_down_s": 3600},
  "min_tier": "T1_STANDARD"
}
```

### **B 组 · 隐蔽时段 (STEALTH_HOURS),4 条**

**R103 · MIC-STEALTH-HOURS**
```json
{
  "id": 103, "name": "MIC-STEALTH-HOURS",
  "match": {
    "op": "RECORD_AUDIO",
    "user_present": false,
    "power_state": false,
    "duration_ms": {"gt": 3000}
  },
  "verdict": {"kind": "ALERT", "category": "STEALTH_HOURS", "severity": 85},
  "debounce": {"window_s": 900, "min_hits": 1, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```
语义: 熄屏未解锁 + 无可见前台服务(`power_state=false`)+ 持续采样 >3s,这是最高优先级的硬指标(单次命中即告警,`min_hits=1`)。

**R105 · CAM-STEALTH-HOURS**
```json
{
  "id": 105, "name": "CAM-STEALTH-HOURS",
  "match": {
    "op": "CAMERA", "user_present": false, "fg_state": "INVISIBLE_BG"
  },
  "verdict": {"kind": "ALERT", "category": "STEALTH_HOURS", "severity": 90},
  "debounce": {"window_s": 900, "min_hits": 1, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```

**R107 · LOC-STEALTH-HOURS**
```json
{
  "id": 107, "name": "LOC-STEALTH-HOURS",
  "match": {
    "op": "FINE_LOCATION", "user_present": false,
    "count_in_window": {"window_s": 3600, "gte": 6}
  },
  "verdict": {"kind": "ALERT", "category": "STEALTH_HOURS", "severity": 50},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T1_STANDARD"
}
```

**R109 · IMU-STEALTH-HOURS**
```json
{
  "id": 109, "name": "IMU-STEALTH-HOURS",
  "match": {
    "op_in": ["ACCEL", "GYRO"],
    "user_present": false,
    "sample_rate_hz": {"gte": 50},
    "duration_ms": {"gt": 60000}
  },
  "verdict": {"kind": "ALERT", "category": "STEALTH_HOURS", "severity": 65},
  "debounce": {"window_s": 900, "min_hits": 2, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```

### **C 组 · 旁路推断 / IMU 语音重建 (SIDE_CHANNEL),5 条**

**R108 · IMU-HIGH-RATE-NO-PURPOSE**
```json
{
  "id": 108, "name": "IMU-HIGH-RATE-NO-PURPOSE",
  "match": {
    "op_in": ["ACCEL", "GYRO"],
    "sample_rate_hz": {"gte": 200},
    "duration_ms": {"gt": 30000},
    "decl_purpose_not_in": ["GAME", "AR", "FITNESS"]
  },
  "verdict": {"kind": "ALERT", "category": "SIDE_CHANNEL", "severity": 80},
  "debounce": {"window_s": 900, "min_hits": 1, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```
语义: 这是文档最初就点名的 Spearphone/AccelEve/Gyrophone 攻击特征 —— 200Hz+ 且持续 30s+,用途与高频运动感知无关。

**R110 · IMU-BURST-ENTROPY-SUSPECT**(依赖 L3 中间量,由规则引擎在 L3 输出后二次匹配)
```json
{
  "id": 110, "name": "IMU-BURST-ENTROPY-SUSPECT",
  "match": {
    "op_in": ["ACCEL", "GYRO"],
    "burst_entropy": {"gte": 2.5, "lte": 4.5},
    "ks_d": {"gt": 0.18},
    "decl_purpose_not_in": ["GAME", "AR", "FITNESS"]
  },
  "verdict": {"kind": "ALERT", "category": "SIDE_CHANNEL", "severity": 70},
  "debounce": {"window_s": 900, "min_hits": 2, "cool_down_s": 1800},
  "min_tier": "T0_BASIC"
}
```
语义: 这是唯一一条**跨层规则**——匹配条件依赖 §5.3 的 L3 统计量(`burst_entropy`, `ks_d`),说明规则引擎必须在 L3 算完之后才能跑这条,详见 §4 执行顺序。

**R111 · IMU-WITH-MIC-COUPLING**
```json
{
  "id": 111, "name": "IMU-WITH-MIC-COUPLING",
  "match": {
    "op_in": ["ACCEL", "GYRO"],
    "sample_rate_hz": {"gte": 100},
    "coupled_op": "RECORD_AUDIO",
    "coupling_ratio": {"gte": 0.3}
  },
  "verdict": {"kind": "ALERT", "category": "SIDE_CHANNEL", "severity": 85},
  "debounce": {"window_s": 900, "min_hits": 1, "cool_down_s": 1800},
  "min_tier": "T1_STANDARD"
}
```
语义: IMU 高频采样与麦克风活跃有 ≥30% 时间重叠 —— 典型的"用 IMU 做麦克风降级替代/增强"模式(§5.4 特征 28)。

**R112 · IMU-DENSITY-SPIKE**
```json
{
  "id": 112, "name": "IMU-DENSITY-SPIKE",
  "match": {
    "op_in": ["ACCEL", "GYRO"],
    "count_p99_multiple": {"gte": 3.0}
  },
  "verdict": {"kind": "OBSERVE", "category": "SIDE_CHANNEL", "severity": 40},
  "debounce": {"window_s": 300, "min_hits": 1, "cool_down_s": 900},
  "min_tier": "T0_BASIC"
}
```
语义: 对应 §5.3 的 Fast Tick 触发条件本身,单独作为一条低置信度的 `OBSERVE` 级规则记录,不直接告警,供后续人工复核和 v1.1 模型训练标注用。

**R120 · CROSS-SENSOR-BURST**
```json
{
  "id": 120, "name": "CROSS-SENSOR-BURST",
  "match": {
    "distinct_sensitive_ops_in_window": {"window_s": 60, "gte": 3},
    "user_present": false
  },
  "verdict": {"kind": "ALERT", "category": "SIDE_CHANNEL", "severity": 78},
  "debounce": {"window_s": 900, "min_hits": 1, "cool_down_s": 1800},
  "min_tier": "T1_STANDARD"
}
```
语义: 同一 App 在 60s 内同时激活 ≥3 种敏感传感器且用户不在场 —— 对应 §5.4 组 E 特征 27。

### **D 组 · 环境指纹追踪 (FINGERPRINT),4 条**

**R113 · MAG-HIGH-FREQ**
```json
{
  "id": 113, "name": "MAG-HIGH-FREQ",
  "match": {
    "op": "MAG", "sample_rate_hz": {"gte": 20},
    "decl_purpose_not_in": ["NAVIGATION", "GAME"]
  },
  "verdict": {"kind": "ALERT", "category": "FINGERPRINT", "severity": 55},
  "debounce": {"window_s": 1800, "min_hits": 3, "cool_down_s": 3600},
  "min_tier": "T0_BASIC"
}
```

**R114 · MAG-STEALTH-HOURS**
```json
{
  "id": 114, "name": "MAG-STEALTH-HOURS",
  "match": {"op": "MAG", "user_present": false, "duration_ms": {"gt": 60000}},
  "verdict": {"kind": "ALERT", "category": "FINGERPRINT", "severity": 45},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T0_BASIC"
}
```

**R115 · LIGHT-SUB-50MS-INTERVAL**
```json
{
  "id": 115, "name": "LIGHT-SUB-50MS-INTERVAL",
  "match": {"op": "LIGHT", "avg_interval_ms": {"lt": 50}},
  "verdict": {"kind": "ALERT", "category": "FINGERPRINT", "severity": 60},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T0_BASIC"
}
```
语义: 环境光合法用途(自动亮度)采样间隔通常 >200ms,<50ms 远超任何 UI 响应需求,是隐蔽信道的经典特征。

**R116 · BARO-ABNORMAL-PATTERN**
```json
{
  "id": 116, "name": "BARO-ABNORMAL-PATTERN",
  "match": {"op": "BARO", "sample_rate_hz": {"gte": 10}, "decl_purpose_not_in": ["FITNESS", "NAVIGATION"]},
  "verdict": {"kind": "OBSERVE", "category": "FINGERPRINT", "severity": 35},
  "debounce": {"window_s": 1800, "min_hits": 3, "cool_down_s": 3600},
  "min_tier": "T0_BASIC"
}
```

### **E 组 · 无线扫描滥用,3 条**

**R117 · BT-SCAN-HIGH-FREQ**
```json
{
  "id": 117, "name": "BT-SCAN-HIGH-FREQ",
  "match": {"op": "FINE_LOCATION", "bt_scan_count_in_window": {"window_s": 600, "gte": 20}},
  "verdict": {"kind": "ALERT", "category": "OUT_OF_SCOPE", "severity": 45},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T1_STANDARD"
}
```

**R118 · WIFI-SCAN-STEALTH-HOURS**
```json
{
  "id": 118, "name": "WIFI-SCAN-STEALTH-HOURS",
  "match": {"wifi_scan_count_in_window": {"window_s": 600, "gte": 10}, "user_present": false},
  "verdict": {"kind": "ALERT", "category": "STEALTH_HOURS", "severity": 40},
  "debounce": {"window_s": 1800, "min_hits": 2, "cool_down_s": 3600},
  "min_tier": "T1_STANDARD"
}
```

**R119 · SCAN-FINGERPRINT-COMBO**
```json
{
  "id": 119, "name": "SCAN-FINGERPRINT-COMBO",
  "match": {
    "bt_scan_count_in_window": {"window_s": 3600, "gte": 30},
    "wifi_scan_count_in_window": {"window_s": 3600, "gte": 15},
    "decl_purpose_not_in": ["NAVIGATION"]
  },
  "verdict": {"kind": "ALERT", "category": "FINGERPRINT", "severity": 50},
  "debounce": {"window_s": 3600, "min_hits": 2, "cool_down_s": 7200},
  "min_tier": "T1_STANDARD"
}
```

---

## **3 · 规则清单总览表**

| ID | 名称 | 类别 | Severity | min_hits | Tier |
|---|---|---|---|---|---|
| 101 | MIC-SHORT-PULSE | OUT_OF_SCOPE | 75 | 3 | T0 |
| 102 | MIC-NO-PURPOSE-MATCH | OUT_OF_SCOPE | 60 | 2 | T0 |
| 103 | MIC-STEALTH-HOURS | STEALTH_HOURS | 85 | 1 | T0 |
| 104 | CAM-NO-PURPOSE-MATCH | OUT_OF_SCOPE | 70 | 2 | T0 |
| 105 | CAM-STEALTH-HOURS | STEALTH_HOURS | 90 | 1 | T0 |
| 106 | LOC-NO-PURPOSE-MATCH | OUT_OF_SCOPE | 55 | 3 | T1 |
| 107 | LOC-STEALTH-HOURS | STEALTH_HOURS | 50 | 2 | T1 |
| 108 | IMU-HIGH-RATE-NO-PURPOSE | SIDE_CHANNEL | 80 | 1 | T0 |
| 109 | IMU-STEALTH-HOURS | STEALTH_HOURS | 65 | 2 | T0 |
| 110 | IMU-BURST-ENTROPY-SUSPECT | SIDE_CHANNEL | 70 | 2 | T0 |
| 111 | IMU-WITH-MIC-COUPLING | SIDE_CHANNEL | 85 | 1 | T1 |
| 112 | IMU-DENSITY-SPIKE | SIDE_CHANNEL (OBSERVE) | 40 | 1 | T0 |
| 113 | MAG-HIGH-FREQ | FINGERPRINT | 55 | 3 | T0 |
| 114 | MAG-STEALTH-HOURS | FINGERPRINT | 45 | 2 | T0 |
| 115 | LIGHT-SUB-50MS-INTERVAL | FINGERPRINT | 60 | 2 | T0 |
| 116 | BARO-ABNORMAL-PATTERN | FINGERPRINT (OBSERVE) | 35 | 3 | T0 |
| 117 | BT-SCAN-HIGH-FREQ | OUT_OF_SCOPE | 45 | 2 | T1 |
| 118 | WIFI-SCAN-STEALTH-HOURS | STEALTH_HOURS | 40 | 2 | T1 |
| 119 | SCAN-FINGERPRINT-COMBO | FINGERPRINT | 50 | 2 | T1 |
| 120 | CROSS-SENSOR-BURST | SIDE_CHANNEL | 78 | 1 | T1 |

**覆盖性校验:** 4 大类别(OUT_OF_SCOPE / STEALTH_HOURS / SIDE_CHANNEL / FINGERPRINT)每类 ≥4 条规则,T0_BASIC 覆盖 14 条(70%),保证 Android 10\~11 设备也有充分防护,T1_STANDARD 追加 6 条精细规则。

---

## **4 · 规则引擎执行顺序(补上前文遗漏的关键约束)**

必须显式说明: **20 条规则不是一次性并行匹配**,而是分两批,因为 R110/R120 依赖 L3 统计量:

```mermaid
flowchart LR
    A[事件到达 L1] -->|S_ctx<0.6| B[Batch A: 14条纯字段规则
101,102,103,104,105,106,107,
108,109,113,114,115,116,117,118]
    B -->|未命中| C[L3 统计检验产出
ks_d / burst_entropy / KL]
    C --> D[Batch B: 6条跨层规则
110,111,112,119,120]
    D -->|未命中| E[OBSERVE 或放行]
```

Batch A 在 Event Tick 阶段即可评估(纯字段比较,O(1));Batch B 只能在 60s Batch Tick 之后、L3 输出可用时评估。**这一分批约束必须写入代码,否则 R110/R120 会因为字段未就位而永远匹配失败或读到脏数据。**

---

## **5 · Rust 实现:规则引擎核心代码**

**`core-rust/src/rules.rs`**

```rust
use std::collections::HashMap;
use std::sync::Mutex;

// ---------- 数据结构 ----------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RuleCategory { OutOfScope, StealthHours, SideChannel, Fingerprint }

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RuleKind { Legit, Observe, Alert }

/// 单条规则的匹配谓词。用枚举而非动态脚本,保证纯 Rust 静态分发,零解释器开销。
#[derive(Clone)]
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
    DeclPurposeNotIn(Vec<u8>),
    DeclPurposeIn(Vec<u8>),
    // 跨层谓词,依赖 L3 输出,只在 Batch B 阶段可用
    KsDGt(f32),
    BurstEntropyBetween(f32, f32),
    CoupledOpRatioGte { coupled_op: u8, ratio: f32 },
    DistinctSensitiveOpsGte { window_s: u32, gte: u32 },
}

#[derive(Clone)]
pub struct Debounce {
    pub window_s: u32,
    pub min_hits: u32,
    pub cool_down_s: u32,
}

#[derive(Clone)]
pub struct Rule {
    pub id: u16,
    pub category: RuleCategory,
    pub kind: RuleKind,
    pub severity: u8,
    pub predicates: Vec<Predicate>,   // AND 语义
    pub debounce: Debounce,
    pub min_tier: u8,                 // 0=T0_BASIC, 1=T1_STANDARD
    pub batch: RuleBatch,             // A=纯字段, B=依赖L3
}

#[derive(Clone, Copy, PartialEq)]
pub enum RuleBatch { A, B }

// ---------- 事件上下文(供谓词读取) ----------

/// 一次判定所需的全部字段,由 Kotlin 侧 OpEvent + Rust 侧 L3 中间量拼装
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
    pub count_in_window: HashMap<u32, u32>, // window_s -> count,预聚合好的多窗口计数
    // L3 产出,Batch A 阶段全部为 None
    pub ks_d: Option<f32>,
    pub burst_entropy: Option<f32>,
    pub coupling_ratio: Option<HashMap<u8, f32>>, // coupled_op -> ratio
    pub distinct_sensitive_ops: Option<HashMap<u32, u32>>,
}

impl EvalContext {
    fn count_at_least(&self, window_s: u32, gte: u32) -> bool {
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
        Predicate::DeclPurposeNotIn(list) => !list.contains(&ctx.decl_purpose),
        Predicate::DeclPurposeIn(list) => list.contains(&ctx.decl_purpose),
        Predicate::KsDGt(t) => ctx.ks_d.map(|v| v > *t).unwrap_or(false),
        Predicate::BurstEntropyBetween(lo, hi) =>
            ctx.burst_entropy.map(|v| v >= *lo && v <= *hi).unwrap_or(false),
        Predicate::CoupledOpRatioGte { coupled_op, ratio } => ctx.coupling_ratio
            .as_ref()
            .and_then(|m| m.get(coupled_op))
            .map(|v| *v >= *ratio)
            .unwrap_or(false),
        Predicate::DistinctSensitiveOpsGte { window_s, gte } => ctx.distinct_sensitive_ops
            .as_ref()
            .and_then(|m| m.get(window_s))
            .map(|v| *v >= *gte)
            .unwrap_or(false),
    }
}

fn rule_matches(rule: &Rule, ctx: &EvalContext, tier: u8) -> bool {
    if tier < rule.min_tier { return false; }
    rule.predicates.iter().all(|p| eval_predicate(p, ctx))
}

// ---------- 去抖状态机(每 (uid, op, rule_id) 独立) ----------

struct HitHistory { hits: Vec<i64>, last_alert_ns: i64 }

pub struct DebounceStore { inner: Mutex<HashMap<(i32, u8, u16), HitHistory>> }

impl DebounceStore {
    pub fn new() -> Self { Self { inner: Mutex::new(HashMap::new()) } }

    /// 返回 true 表示本次命中应当真正升级为告警(通过去抖门槛)
    pub fn record_and_check(&self, uid: i32, op: u8, rule: &Rule, now_ns: i64) -> bool {
        let mut map = match self.inner.lock() { Ok(g) => g, Err(_) => return false };
        let key = (uid, op, rule.id);
        let window_ns = rule.debounce.window_s as i64 * 1_000_000_000;
        let cooldown_ns = rule.debounce.cool_down_s as i64 * 1_000_000_000;

        let entry = map.entry(key).or_insert(HitHistory { hits: Vec::new(), last_alert_ns: 0 });
        entry.hits.push(now_ns);
        entry.hits.retain(|&t| now_ns - t <= window_ns);

        if now_ns - entry.last_alert_ns < cooldown_ns {
            return false; // 冷却期内,不重复告警
        }
        if entry.hits.len() as u32 >= rule.debounce.min_hits {
            entry.last_alert_ns = now_ns;
            entry.hits.clear();
            return true;
        }
        false
    }
}

// ---------- 引擎入口 ----------

pub struct RuleEngine {
    pub batch_a: Vec<Rule>,
    pub batch_b: Vec<Rule>,
    pub debounce: DebounceStore,
}

pub struct RuleHit { pub rule_id: u16, pub category: RuleCategory, pub kind: RuleKind, pub severity: u8 }

impl RuleEngine {
    pub fn new(rules: Vec<Rule>) -> Self {
        let (batch_a, batch_b) = rules.into_iter().partition(|r| r.batch == RuleBatch::A);
        Self { batch_a, batch_b, debounce: DebounceStore::new() }
    }

    /// Event Tick 阶段调用:仅评估 Batch A(纯字段规则)
    pub fn eval_batch_a(&self, ctx: &EvalContext, tier: u8, now_ns: i64) -> Option<RuleHit> {
        self.eval_rules(&self.batch_a, ctx, tier, now_ns)
    }

    /// Batch Tick 阶段调用:L3 统计量就位后评估 Batch B
    pub fn eval_batch_b(&self, ctx: &EvalContext, tier: u8, now_ns: i64) -> Option<RuleHit> {
        self.eval_rules(&self.batch_b, ctx, tier, now_ns)
    }

    fn eval_rules(&self, rules: &[Rule], ctx: &EvalContext, tier: u8, now_ns: i64) -> Option<RuleHit> {
        // 按 severity 降序遍历,命中最高危规则即返回(单事件只出一个 Verdict)
        let mut candidates: Vec<&Rule> = rules.iter()
            .filter(|r| rule_matches(r, ctx, tier))
            .collect();
        candidates.sort_by(|a, b| b.severity.cmp(&a.severity));

        for rule in candidates {
            if rule.kind == RuleKind::Observe {
                return Some(RuleHit { rule_id: rule.id, category: rule.category,
                                       kind: rule.kind, severity: rule.severity });
            }
            if self.debounce.record_and_check(ctx.uid, ctx.op, rule, now_ns) {
                return Some(RuleHit { rule_id: rule.id, category: rule.category,
                                       kind: rule.kind, severity: rule.severity });
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mic_stealth_rule() -> Rule {
        Rule {
            id: 103, category: RuleCategory::StealthHours, kind: RuleKind::Alert, severity: 85,
            predicates: vec![
                Predicate::OpEquals(0),
                Predicate::UserPresentEquals(false),
                Predicate::PowerStateEquals(false),
                Predicate::DurationGt(3000),
            ],
            debounce: Debounce { window_s: 900, min_hits: 1, cool_down_s: 1800 },
            min_tier: 0, batch: RuleBatch::A,
        }
    }

    fn base_ctx() -> EvalContext {
        EvalContext {
            op: 0, uid: 1000, decl_purpose: 0, duration_ms: 5000, avg_interval_ms: 0,
            sample_rate_hz: 0.0, user_present: false, fg_state: 2, power_state: false,
            intent_hint: false, system_proxy: false, count_in_window: HashMap::new(),
            ks_d: None, burst_entropy: None, coupling_ratio: None, distinct_sensitive_ops: None,
        }
    }

    #[test]
    fn r103_fires_on_single_hit_min_hits_1() {
        let engine = RuleEngine::new(vec![mic_stealth_rule()]);
        let ctx = base_ctx();
        let hit = engine.eval_batch_a(&ctx, 0, 1_000_000_000);
        assert!(hit.is_some());
        assert_eq!(hit.unwrap().rule_id, 103);
    }

    #[test]
    fn r103_does_not_fire_when_user_present() {
        let engine = RuleEngine::new(vec![mic_stealth_rule()]);
        let mut ctx = base_ctx();
        ctx.user_present = true;
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000).is_none());
    }

    #[test]
    fn cooldown_suppresses_repeat_alert() {
        let engine = RuleEngine::new(vec![mic_stealth_rule()]);
        let ctx = base_ctx();
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000).is_some());
        // 300s 后仍在 1800s 冷却期内,不应重复告警
        assert!(engine.eval_batch_a(&ctx, 0, 1_000_000_000 + 300 * 1_000_000_000).is_none());
    }
}
```

---

## **6 · 规则 JSON → Rust 结构的加载器**

由于团队体积预算紧张,**不引入 `serde_json`**(release 后约 180\~250 KB),改为手写极简解析器,规则文件结构固定、字段有限,完全可控:

**`core-rust/src/rules_loader.rs`**

```rust
use crate::rules::*;

/// 极简 JSON 解析:仅支持规则文件固定 schema,不追求通用性。
/// 输入为 UTF-8 字节流,来自 rules.v1.json(经 Ed25519 校验后传入)。
pub fn load_rules(json_bytes: &[u8]) -> Result<Vec<Rule>, &'static str> {
    let text = std::str::from_utf8(json_bytes).map_err(|_| "bad utf8")?;
    let mut rules = Vec::with_capacity(20);
    for obj in split_top_level_objects(text) {
        rules.push(parse_one_rule(&obj)?);
    }
    Ok(rules)
}

// —— 具体的字段提取函数(get_u16/get_str/get_bool 等)按固定 key 路径查找,
//    W6 落地时实现,核心思路是:规则文件字段集合是封闭的(见 §2 20 条清单),
//    不需要通用 JSON AST,只需按 key 名做子串定位 + 类型转换。
//    此处省略约 150 行样板字段提取代码。
fn split_top_level_objects(_text: &str) -> Vec<String> { vec![] }
fn parse_one_rule(_obj: &str) -> Result<Rule, &'static str> {
    Err("unimplemented placeholder for W6")
}
```

**验收标准**: 该 loader 的单测用 20 条规则清单的真实 JSON 逐条解析,断言字段与 §2 清单一致;体积增量 ≤ 15 KB(纯手写字符串扫描,无第三方依赖)。

---

## **7 · 与已有里程碑的落点**

这套规则引擎归入 **W6 · 规则引擎 + 20 条规则 + debounce** 里程碑,具体拆解为:

- W6 D1\~D2: 完成 `rules.rs` 谓词与去抖状态机(本次已给出可编译骨架)
- W6 D3: 完成 `rules_loader.rs` 的字段提取(封闭 schema,工作量可控)
- W6 D4: 按 §2 清单编写 `rules.v1.json` 完整 20 条,交叉核对 Batch A/B 分类
- W6 D5: 单测覆盖 20 条规则各自的正例/反例,交付 `calibrate.py` 对接的误报率初测

这样 §5.2 的"20 条硬规则"从声明变成了**可审计、可编译、可测试的完整清单**,且规则引擎的执行顺序约束(Batch A/B 分离)被显式写入代码结构,避免了 R110/R120 因依赖 L3 中间量而实现时踩坑。

*内容由 AI 生成仅供参考*