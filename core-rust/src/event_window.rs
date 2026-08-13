//! W2~W3 (文档 §5.3):24h 滑动事件窗口。
//! 每 (uid, op) 一个独立环形缓冲:60 s × 1440 桶计数 + 采样间隔对数直方图。
//! 内存预算:总内存上限 512 KB(全 (uid,op) 组合合计)。
//! 调用时机:Event Tick 仅 O(1) 桶自增(record);Batch Tick 由 sg_tick 每 60 s
//! 评估"上一分钟有变化"的组合;Fast Tick(单分钟超历史 P99×3)由调用方节流(≤6 次/时)。

/// 60 s 桶宽(ns)
pub const BUCKET_NS: i64 = 60_000_000_000;
/// 24 h = 1440 桶
pub const NUM_BUCKETS: usize = 1440;
/// 数据不足阈值:24 h 内事件数 < 20 直接跳过,记 INSUFFICIENT_DATA
pub const MIN_EVENTS: u32 = 20;
/// 间隔直方图桶数(对数分桶,2^-6 ms ~ 2^25 ms)
pub const INTERVAL_BINS: usize = 32;
/// 内存预算上限(文档 §5.3)
pub const MEMORY_BUDGET_BYTES: usize = 512 * 1024;

/// 事件阶段(fbs Phase 枚举值:START=0, STOP=1, TICK=2)。
/// 用于 START→STOP 时长配对(L2 规则 DurationLt/Gt 的输入)。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PhaseKind {
    Start,
    Stop,
    Tick,
}

impl PhaseKind {
    pub fn from_fbs(v: u8) -> Self {
        match v {
            0 => PhaseKind::Start,
            1 => PhaseKind::Stop,
            _ => PhaseKind::Tick,
        }
    }
}

/// 事件上下文快照(fbs CtxTag 字段,仅保留 L2 规则谓词所需的子集)。
/// 存"最近一次事件"的上下文,L2 评估在 Batch Tick 用该快照作窗口代表值。
/// 由 ffi 层从 sg::CtxTag 构造(ffi/mod.rs),保持本模块无 flatbuffers 依赖。
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct CtxSnapshot {
    pub fg_state: u8,
    pub user_present: bool,
    pub power_state: bool,
    pub intent_hint: bool,
    pub decl_purpose: u8,
    pub system_proxy: bool,
}

/// (uid, op) 组合键。OpKind 取值 0..=15(文档 fbs),装入低 8 位。
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct PairKey {
    pub uid: i32,
    pub op: u8,
}

impl PairKey {
    pub fn new(uid: i32, op: u8) -> Self {
        Self { uid, op }
    }
    /// 压缩为 u64,供 HashMap 索引 / 全局注册表 key。
    pub fn as_u64(self) -> u64 {
        ((self.uid as u32 as u64) << 8) | u64::from(self.op)
    }
}

/// 单个 60s 桶:绝对桶号 + 计数。
#[derive(Clone, Copy)]
pub struct Slot {
    pub bucket: u32,
    pub count: u16,
}

/// 单 (uid,op) 窗口。固定大小,无堆分配:
/// slots 1440×8B = 11.25 KB + 间隔直方图 32×4B = 128 B + 元数据。
/// 40 组合 × ~11.4 KB ≈ 456 KB < 512 KB 预算。
pub struct PairWindow {
    key: PairKey,
    slots: [Slot; NUM_BUCKETS],
    /// 间隔直方图(对数分桶,单位为 ms)
    interval_hist: [u32; INTERVAL_BINS],
    interval_count: u32,
    last_ts_ns: i64,
    first_ts_ns: i64,
    /// 自上次 Batch Tick 后是否有新事件
    changed: bool,
    // W6 (文档 §5.3):L2 规则输入的窗口级状态
    /// 最近 START 事件 ts_ns(0 = 无进行中的会话),用于 START→STOP 时长配对
    pending_start_ns: i64,
    /// 最近一次 START→STOP 配对的会话时长(ms)
    last_duration_ms: u32,
    /// 最近一次事件的上下文快照(L2 Batch A 纯字段规则的输入)
    ctx: CtxSnapshot,
    /// W12/T2 (文档 §4 P4):物理采样周期(微秒),由 Shizuku 探针经 OpEvent.sampling_period_us
    /// 注入。0 = 未知(非 Shizuku 来源 / 尚未收到过采样率)。sample_rate_hz 优先采用此值。
    sampling_period_us: i64,
}

impl PairWindow {
    pub fn new(key: PairKey) -> Self {
        Self {
            key,
            slots: [Slot {
                bucket: 0,
                count: 0,
            }; NUM_BUCKETS],
            interval_hist: [0; INTERVAL_BINS],
            interval_count: 0,
            last_ts_ns: 0,
            first_ts_ns: 0,
            changed: false,
            pending_start_ns: 0,
            last_duration_ms: 0,
            ctx: CtxSnapshot::default(),
            sampling_period_us: 0,
        }
    }

    pub fn key(&self) -> PairKey {
        self.key
    }

    /// Event Tick:O(1) 桶自增 + 间隔直方图更新。不做任何检验(文档 §5.3)。
    /// 兼容无上下文记录(测试 / 旧路径),等价于 TICK 阶段 + 默认快照。
    pub fn record(&mut self, ts_ns: i64) {
        self.record_ctx(ts_ns, PhaseKind::Tick, CtxSnapshot::default());
    }

    /// Event Tick(带 phase 与 ctx):额外维护 START→STOP 时长配对与上下文快照,
    /// 供 L2 规则引擎(Batch A 纯字段规则)在 Batch Tick 消费。
    pub fn record_ctx(&mut self, ts_ns: i64, phase: PhaseKind, ctx: CtxSnapshot) {
        if ts_ns <= 0 {
            return;
        }
        let b = (ts_ns / BUCKET_NS) as u32;
        let idx = (b as usize) % NUM_BUCKETS;
        let slot = &mut self.slots[idx];
        if slot.bucket == b {
            slot.count = slot.count.saturating_add(1);
        } else {
            slot.bucket = b;
            slot.count = 1;
        }
        // 间隔直方图:与上一事件的时间差(ms),对数分桶
        if self.last_ts_ns > 0 {
            let delta_ms = (ts_ns - self.last_ts_ns) / 1_000_000;
            if delta_ms > 0 {
                // bin = floor(log2(delta_ms)),截断到 [0, INTERVAL_BINS)
                let bin = (63 - (delta_ms as u64).leading_zeros()) as usize;
                let bin = bin.min(INTERVAL_BINS - 1);
                self.interval_hist[bin] = self.interval_hist[bin].saturating_add(1);
                self.interval_count = self.interval_count.saturating_add(1);
            }
        }
        // 时长配对(仅 START/STOP 阶段;TICK 不改变配对状态)
        match phase {
            PhaseKind::Start => self.pending_start_ns = ts_ns,
            PhaseKind::Stop => {
                if self.pending_start_ns > 0 && ts_ns >= self.pending_start_ns {
                    self.last_duration_ms = ((ts_ns - self.pending_start_ns) / 1_000_000) as u32;
                    self.pending_start_ns = 0;
                }
            }
            PhaseKind::Tick => {}
        }
        self.ctx = ctx;
        if self.first_ts_ns == 0 {
            self.first_ts_ns = ts_ns;
        }
        self.last_ts_ns = ts_ns;
        self.changed = true;
    }

    /// 是否有待评估的新事件(自上次 tick)。
    pub fn changed(&self) -> bool {
        self.changed
    }
    /// 清除 change 标记(Batch Tick 后调用)。
    pub fn clear_changed(&mut self) {
        self.changed = false;
    }

    /// W12/T2 (文档 §4 P4):写入由 Shizuku 探针解析得到的物理采样周期(微秒)。
    /// 仅在 >0 时更新(0 表示未知,不覆盖已有值)。每次 START/TICK 携带最新值。
    pub fn set_sampling_period_us(&mut self, us: i64) {
        if us > 0 {
            self.sampling_period_us = us;
        }
    }

    /// 读取当前已知的物理采样周期(微秒);0 表示未知。
    pub fn sampling_period_us(&self) -> i64 {
        self.sampling_period_us
    }

    /// 24 h 窗口内事件总数(扫描在窗口内的桶;O(1440),仅 tick 路径调用)。
    pub fn total(&self, now_ns: i64) -> u32 {
        let now_bucket = (now_ns / BUCKET_NS) as u32;
        self.slots
            .iter()
            .filter(|s| s.count > 0 && now_bucket.saturating_sub(s.bucket) < NUM_BUCKETS as u32)
            .map(|s| u32::from(s.count))
            .sum()
    }

    /// 最近 window_s 秒窗口内的事件数(L2 CountInWindowGte 谓词的输入)。
    /// O(1440) 扫描,仅 tick 路径调用;window_s 需为 60 的倍数(文档规则用
    /// 300/600/3600,均为整分钟)。
    pub fn count_in_window(&self, now_ns: i64, window_s: u32) -> u32 {
        let buckets = (window_s / 60).max(1);
        let now_bucket = (now_ns / BUCKET_NS) as u32;
        self.slots
            .iter()
            .filter(|s| s.count > 0 && now_bucket.saturating_sub(s.bucket) < buckets)
            .map(|s| u32::from(s.count))
            .sum()
    }

    /// 当前会话时长(ms):进行中的会话自最近 START 起算;否则取最近完成的
    /// START→STOP 配对时长。L2 DurationLt/DurationGt 谓词的输入。
    pub fn duration_ms(&self, now_ns: i64) -> u32 {
        if self.pending_start_ns > 0 {
            ((now_ns - self.pending_start_ns).max(0) / 1_000_000) as u32
        } else {
            self.last_duration_ms
        }
    }

    /// 最近一次事件的上下文快照(L2 Batch A 纯字段规则的输入)。
    pub fn ctx_snapshot(&self) -> CtxSnapshot {
        self.ctx
    }

    /// 平均采样间隔(ms):间隔直方图的对数桶中心值的加权平均。
    /// L2 IntervalLt 谓词的输入。O(32),tick 路径可接受。
    pub fn avg_interval_ms(&self) -> u32 {
        if self.interval_count == 0 {
            return 0;
        }
        let mut sum: u64 = 0;
        let mut n: u64 = 0;
        for (bin, &c) in self.interval_hist.iter().enumerate() {
            if c == 0 {
                continue;
            }
            // bin 中心 ≈ 2^bin × 1.5(对数桶下限 2^bin)
            let center = (1u64 << bin) + (1u64 << bin) / 2;
            sum += center * u64::from(c);
            n += u64::from(c);
        }
        if n == 0 {
            return 0;
        }
        (sum / n) as u32
    }

    /// 采样率(Hz)。W12/T2 优先采用 Shizuku 注入的物理采样周期(1e6 / period_us);
    /// 未知时回退到由事件间隔反推(1000 / 平均间隔 ms)。L2 SampleRateGte 谓词输入。
    pub fn sample_rate_hz(&self) -> f32 {
        if self.sampling_period_us > 0 {
            return 1_000_000.0 / self.sampling_period_us as f32;
        }
        let avg = self.avg_interval_ms();
        if avg == 0 {
            0.0
        } else {
            1000.0 / avg as f32
        }
    }

    /// 当前分钟桶计数(Fast Tick 触发判定用)。
    pub fn current_minute_count(&self, now_ns: i64) -> u32 {
        let b = (now_ns / BUCKET_NS) as u32;
        let idx = (b as usize) % NUM_BUCKETS;
        let s = self.slots[idx];
        if s.bucket == b {
            u32::from(s.count)
        } else {
            0
        }
    }

    /// 24 h 窗口内历史单分钟计数的 P99(用桶计数的近似分位数)。
    pub fn p99_minute_count(&self, now_ns: i64) -> u32 {
        let now_bucket = (now_ns / BUCKET_NS) as u32;
        let mut counts: Vec<u32> = self
            .slots
            .iter()
            .filter(|s| s.count > 0 && now_bucket.saturating_sub(s.bucket) < NUM_BUCKETS as u32)
            .map(|s| u32::from(s.count))
            .collect();
        if counts.is_empty() {
            return 0;
        }
        counts.sort_unstable();
        let idx = ((counts.len() as f64 * 0.99).ceil() as usize)
            .saturating_sub(1)
            .min(counts.len() - 1);
        counts[idx]
    }

    /// 复制当前窗口的间隔直方图(供 Burst 熵计算)。
    pub fn interval_histogram(&self) -> [u32; INTERVAL_BINS] {
        self.interval_hist
    }

    /// 复制当前窗口在 24h 内的桶计数(供 KS / KL 计算)。
    pub fn bucket_counts(&self, now_ns: i64) -> Vec<u32> {
        let now_bucket = (now_ns / BUCKET_NS) as u32;
        self.slots
            .iter()
            .filter(|s| s.count > 0 && now_bucket.saturating_sub(s.bucket) < NUM_BUCKETS as u32)
            .map(|s| u32::from(s.count))
            .collect()
    }

    /// 每窗口静态大小(含对齐)。新增 L2 状态:pending_start_ns(8B) +
    /// last_duration_ms(4B) + ctx 快照(6B + 对齐) + sampling_period_us(8B)。
    pub const fn size_bytes() -> usize {
        NUM_BUCKETS * 8 + INTERVAL_BINS * 4 + 52
    }

    /// 只读访问全部桶槽(评估引擎计算昼夜 KL 等聚合时使用)。
    pub fn slots(&self) -> &[Slot; NUM_BUCKETS] {
        &self.slots
    }
}

/// 全量窗口存储:固定容量数组,按 (uid,op) 键索引。
/// 总内存 = MAX_PAIRS × PairWindow::size_bytes() ≤ 512 KB。
pub const MAX_PAIRS: usize = MEMORY_BUDGET_BYTES / PairWindow::size_bytes();

/// 全局窗口注册表。
pub struct WindowStore {
    pairs: Vec<Option<PairWindow>>,
    index: std::collections::HashMap<u64, usize>,
}

impl WindowStore {
    pub fn new() -> Self {
        Self {
            pairs: Vec::new(),
            index: std::collections::HashMap::new(),
        }
    }

    /// 取或创建 (uid,op) 窗口。组合数达上限时拒绝新组合(返回 None,由调用方
    /// 记 degraded=true;文档 §9 反压降级语义)。
    pub fn get_or_create(&mut self, key: PairKey) -> Option<&mut PairWindow> {
        let k = key.as_u64();
        if let Some(&i) = self.index.get(&k) {
            return self.pairs.get_mut(i).and_then(Option::as_mut);
        }
        if self.pairs.len() >= MAX_PAIRS {
            return None;
        }
        let i = self.pairs.len();
        self.pairs.push(Some(PairWindow::new(key)));
        self.index.insert(k, i);
        self.pairs.get_mut(i).and_then(Option::as_mut)
    }

    /// 全部组合的当前事件记录。
    pub fn iter_mut(&mut self) -> impl Iterator<Item = &mut PairWindow> {
        self.pairs.iter_mut().filter_map(Option::as_mut)
    }

    /// 只读遍历全部组合(Batch B 跨窗口聚合:L2 coupling/distinct 统计)。
    pub fn iter(&self) -> impl Iterator<Item = &PairWindow> {
        self.pairs.iter().filter_map(Option::as_ref)
    }

    /// 清空全部窗口(测试隔离 / 运行时复位用)。
    pub fn clear(&mut self) {
        self.pairs.clear();
        self.index.clear();
    }

    pub fn len(&self) -> usize {
        self.pairs.len()
    }
    pub fn is_empty(&self) -> bool {
        self.pairs.is_empty()
    }

    /// 当前已用内存字节(仅组合主体;HashMap 开销不计入,量级小)。
    pub fn used_bytes(&self) -> usize {
        self.pairs.len() * PairWindow::size_bytes()
    }
}

impl Default for WindowStore {
    fn default() -> Self {
        Self::new()
    }
}

use once_cell::sync::Lazy;
use std::sync::Mutex;
/// 全局事件窗口(OpEvent 回调 ≤20 Hz,互斥锁开销可忽略)。
pub static WINDOWS: Lazy<Mutex<WindowStore>> = Lazy::new(|| Mutex::new(WindowStore::new()));

#[cfg(test)]
mod tests {
    use super::*;

    const HOUR: i64 = 3_600_000_000_000;
    const DAY: i64 = 24 * HOUR;

    fn key() -> PairKey {
        PairKey::new(10_000, 0)
    }

    #[test]
    fn record_and_total_within_window() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64; // 任意 boottime 基准
        for i in 0..60 {
            w.record(t0 + i * 1_000_000_000); // 每秒 1 事件,共 60 事件
        }
        assert_eq!(w.total(t0 + 60 * 1_000_000_000), 60);
        assert!(w.changed());
        w.clear_changed();
        assert!(!w.changed());
    }

    #[test]
    fn sliding_window_evicts_old() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 第 1 天 10 事件
        for i in 0..10 {
            w.record(t0 + i * 1_000_000_000);
        }
        // 第 3 天(超出 24h 窗口)后,第 1 天事件应被淘汰
        for i in 0..10 {
            w.record(t0 + 2 * DAY + i * 1_000_000_000);
        }
        assert_eq!(w.total(t0 + 2 * DAY + 10 * 1_000_000_000), 10);
    }

    #[test]
    fn insufficient_data_threshold() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        for i in 0..19 {
            w.record(t0 + i * 1_000_000_000);
        }
        assert!(w.total(t0 + 19 * 1_000_000_000) < MIN_EVENTS);
    }

    #[test]
    fn interval_histogram_and_entropy() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 恒定 1s 间隔 × 60 → 间隔直方图集中在单桶,熵 ≈ 0
        for i in 0..60 {
            w.record(t0 + i * 1_000_000_000);
        }
        let hist = w.interval_histogram();
        let total_intervals: u32 = hist.iter().sum();
        assert_eq!(total_intervals, 59);
        let hist64: Vec<u64> = hist.iter().map(|&c| u64::from(c)).collect();
        let h = crate::stats::entropy::shannon_entropy_bits(&hist64);
        assert!(h.abs() < 1e-6, "恒定间隔熵应≈0,h={h}");
        assert!(h < crate::thresholds::THRESHOLDS.burst_entropy_min);
    }

    #[test]
    fn varied_intervals_raise_entropy() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 间隔 1s~8s 交替 → 熵显著 > 0
        let mut t = t0;
        for i in 0..120 {
            w.record(t);
            t += (1 + (i % 8)) * 1_000_000_000;
        }
        let hist = w.interval_histogram();
        let hist64: Vec<u64> = hist.iter().map(|&c| u64::from(c)).collect();
        let h = crate::stats::entropy::shannon_entropy_bits(&hist64);
        assert!(h > 1.0, "多间隔熵应>1,h={h}");
    }

    #[test]
    fn memory_budget_respected() {
        assert!(
            MAX_PAIRS * PairWindow::size_bytes() <= MEMORY_BUDGET_BYTES,
            "预算超限: {} × {} = {} > {}",
            MAX_PAIRS,
            PairWindow::size_bytes(),
            MAX_PAIRS * PairWindow::size_bytes(),
            MEMORY_BUDGET_BYTES
        );
        // Deviation(doc-frozen): 编译期常量断言,clippy 建议 const 块;
        // 预算关系在 const 层面已由 MAX_PAIRS 定义保证,此处为回归护栏。
        const { assert!(MAX_PAIRS >= 40) };
    }

    #[test]
    fn store_get_or_create_and_cap() {
        let mut store = WindowStore::new();
        let k1 = key();
        let k2 = PairKey::new(20_000, 1);
        {
            let w = store.get_or_create(k1).expect("k1 created");
            w.record(1_700_000_000_000_000_000i64);
        }
        {
            let w = store.get_or_create(k2).expect("k2 created");
            w.record(1_700_000_000_000_000_000i64);
        }
        assert_eq!(store.len(), 2);
        // 同一 key 复用
        assert!(store.get_or_create(k1).is_some());
        assert_eq!(store.len(), 2);
        // 达到上限后拒绝
        for i in 0..MAX_PAIRS {
            store.get_or_create(PairKey::new(i as i32 + 1, 2));
        }
        assert!(store.get_or_create(PairKey::new(999_999, 3)).is_none());
        assert!(store.used_bytes() <= MEMORY_BUDGET_BYTES);
    }

    #[test]
    fn p99_minute_count_basic() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 100 分钟,每分钟 1~100 事件(递增)
        for m in 0..100u32 {
            for _ in 0..(m + 1) {
                w.record(t0 + i64::from(m) * BUCKET_NS);
            }
        }
        let p99 = w.p99_minute_count(t0 + 100 * BUCKET_NS);
        assert!((99..=100).contains(&p99), "P99 应在 99~100,p99={p99}");
    }

    #[test]
    fn duration_pairing_start_stop() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        let dflt = CtxSnapshot::default();
        // START 事件
        w.record_ctx(t0, PhaseKind::Start, dflt);
        // 进行中:duration_ms 自 START 起算
        assert_eq!(w.duration_ms(t0 + 5_000_000_000), 5000, "进行中会话时长");
        // STOP 事件:5s 后结束 → 配对时长 5000ms
        w.record_ctx(t0 + 5_000_000_000, PhaseKind::Stop, dflt);
        assert_eq!(w.duration_ms(t0 + 5_000_000_000), 5000, "配对后固化时长");
        // pending 已清空:此后 duration 保持配对值
        assert_eq!(w.duration_ms(t0 + 60_000_000_000), 5000);
    }

    #[test]
    fn duration_pairing_stop_without_start_ignored() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        let dflt = CtxSnapshot::default();
        // 无 START 的 STOP 不产生配对,时长保持 0
        w.record_ctx(t0, PhaseKind::Stop, dflt);
        assert_eq!(w.duration_ms(t0), 0);
    }

    #[test]
    fn ctx_snapshot_tracks_latest_event() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        let ctx_a = CtxSnapshot {
            fg_state: 2,
            user_present: false,
            power_state: false,
            intent_hint: false,
            decl_purpose: 0,
            system_proxy: false,
        };
        let ctx_b = CtxSnapshot {
            fg_state: 0,
            user_present: true,
            power_state: true,
            intent_hint: true,
            decl_purpose: 4,
            system_proxy: false,
        };
        w.record_ctx(t0, PhaseKind::Start, ctx_a);
        assert_eq!(w.ctx_snapshot(), ctx_a);
        w.record_ctx(t0 + 1_000_000_000, PhaseKind::Tick, ctx_b);
        assert_eq!(w.ctx_snapshot(), ctx_b, "快照取最近事件");
    }

    #[test]
    fn count_in_window_buckets() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 5 个连续分钟桶各 1 事件(m=0..4)
        for m in 0..5u32 {
            w.record(t0 + i64::from(m) * BUCKET_NS);
        }
        let now = t0 + 5 * BUCKET_NS;
        // 事件桶 ages=1..=5;age<5(300s/60=5 桶)含 4 桶,age=5 恰在边界外
        assert_eq!(w.count_in_window(now, 300), 4);
        // 当前分钟(age 0)无事件
        assert_eq!(w.count_in_window(now, 60), 0);
        // 第 7 分钟桶记 2 事件
        let t7 = t0 + 7 * BUCKET_NS;
        w.record(t7);
        w.record(t7 + 1_000_000_000);
        let now2 = t7 + 30_000_000_000; // 同一分钟桶(age 0)
        assert_eq!(w.count_in_window(now2, 60), 2, "当前分钟桶 2 事件");
        // 300s=5 桶窗口:age<5 → m=3..4(age 3..4)+ t7(age 0)= 4 事件
        assert_eq!(w.count_in_window(now2, 300), 4);
        // 距 t7 事件满 5 分钟(age=5,恰在 300s=5 桶窗口边界外)→ 无事件
        let now3 = t0 + 12 * BUCKET_NS;
        assert_eq!(w.count_in_window(now3, 300), 0);
    }

    #[test]
    fn sample_rate_from_interval_hist() {
        let mut w = PairWindow::new(key());
        let t0 = 1_700_000_000_000_000_000i64;
        // 恒定 5ms 间隔 × 120 → 平均间隔 ≈5ms → 采样率 ≈200Hz
        for i in 0..120u32 {
            w.record(t0 + i64::from(i) * 5_000_000);
        }
        let avg = w.avg_interval_ms();
        let hz = w.sample_rate_hz();
        assert!((4..=6).contains(&avg), "平均间隔应≈5ms,got {avg}ms");
        assert!(hz >= 150.0, "采样率应≥~200Hz,got {hz}");
    }
}
