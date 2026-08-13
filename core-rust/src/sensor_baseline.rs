//! W5 (文档 §5.2):传感器基线探针的 HAL 竞争推断 —— 自采基线 D0 与观测抖动 Dt 的 KS 检验。
//!
//! 我方以 ~50 Hz 自采 accel/gyro(由 Android `SensorBaselineProbe` 经 `sg_push_sensor`
//! 注入环形缓冲 `RING`)。当第三方以更高频率激活同一物理传感器时,HAL 切到更高档位,
//! 我方看到的 `event.timestamp` 抖动分布 D_t 会系统性偏离基线 D0。用两样本 KS 检验
//! `D_KS = sup|F0 - Ft|` 检测(阈值 τ = THRESHOLDS.ks_tau,与 §5.3 事件级 KS 同源,
//! 由 calibrate.py 从 §7 标定 corpus 产出)。
//!
//! 设计要点:
//! - 热路径 `sg_push_sensor` 仅做 `RING.push`(lock-free),此处不在热路径加锁;
//! - 基线维护与 KS 在 `sg_tick` / `sg_sensor_health` 内(每 60s 一次)从 RING 批量消费,
//!   与文档 §5.3 "Batch Tick 消费 ring" 模型一致,保持传感器热路径 3µs 预算。
//! - 无 Shizuku 时只能标"存在未知采样方"(T0);归因到具体 uid 由 Shizuku 探针负责(§4 P4)。

use crate::ring::{RING, Sample};
use crate::stats::ks::ks_statistic;
use crate::thresholds::THRESHOLDS;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::collections::VecDeque;
use std::sync::Mutex;

/// 自采基线 warmup 样本数(~30s @50Hz),达到后冻结 D0。
const BASELINE_CAP: usize = 1500;
/// 滚动观测窗口样本数(~12s @50Hz),用于计算 D_t。
const RECENT_CAP: usize = 600;

/// 传感器基线 KS 阈值 —— 统一从 THRESHOLDS.ks_tau 取值(P0-3 修复)。
/// 原先硬编码 0.18 与 THRESHOLDS.ks_tau(0.0897)冲突,现统一为单一来源,
/// 由 calibrate.py 从标定 corpus 产出,杜绝双源不一致风险。
#[inline]
fn sensor_ks_tau() -> f64 {
    THRESHOLDS.ks_tau
}

#[derive(Clone, Default)]
struct PerKind {
    kind: u8,
    last_ts: i64,
    warm: bool,
    baseline: Vec<f64>,
    /// P2-8: 使用 VecDeque 替代 Vec,pop_front() 为 O(1)。
    /// 原 Vec::remove(0) 为 O(n),在 600 样本窗口上每次 feed 调用需移位 ~600 元素。
    recent: VecDeque<f64>,
}

impl PerKind {
    fn new(kind: u8) -> Self {
        Self { kind, ..Default::default() }
    }

    /// 喂入一个样本,更新基线/观测抖动直方图。
    fn feed(&mut self, s: &Sample) {
        if self.last_ts > 0 && s.ts_ns > self.last_ts {
            let jitter_ms = (s.ts_ns - self.last_ts) as f64 / 1e6;
            // 防御:丢弃非正/异常大的间隔(传感器时基跳变)。
            if jitter_ms > 0.0 && jitter_ms < 1_000_000.0 {
                if !self.warm {
                    if self.baseline.len() < BASELINE_CAP {
                        self.baseline.push(jitter_ms);
                    }
                    if self.baseline.len() >= BASELINE_CAP {
                        self.warm = true;
                    }
                } else {
                    self.recent.push_back(jitter_ms);
                    if self.recent.len() > RECENT_CAP {
                        self.recent.pop_front();
                    }
                }
            }
        }
        self.last_ts = s.ts_ns;
    }

    /// KS(D0, Dt);未 warm 或样本不足返回 0(无证据表明分布偏离)。
    fn ks_d(&self) -> f64 {
        if self.baseline.len() < 2 || self.recent.len() < 2 {
            return 0.0;
        }
        // VecDeque 可能非连续,转 Vec 传给 ks_statistic。
        // 此方法仅在 60s tick 路径调用,分配开销可忽略。
        let recent: Vec<f64> = self.recent.iter().copied().collect();
        ks_statistic(&self.baseline, &recent)
    }

    /// 估算采样率(Hz):观测窗口均值间隔倒数的 1000 倍。
    fn sample_hz(&self) -> f32 {
        let src: &[f64] = if self.recent.is_empty() {
            &self.baseline
        } else {
            // VecDeque as_slices 返回 (front, back) 两段;遍历即可,无需合并。
            // 为简化接口,当 recent 非空时从 recent 取值。
            // 使用迭代器求和避免分配。
            // 此处借用 self.recent 的数据,但需要 &[f64] 切片。
            // VecDeque 不保证连续,用 make_contiguous 需要 &mut。
            // 退而求其次:recent 为空时用 baseline(Vec<f64> → &[f64])。
            // recent 非空时也转为 Vec。
            // 但 sample_hz 在 60s tick 路径调用,分配开销可忽略。
            return self.sample_hz_from_recent();
        };
        if src.is_empty() {
            return 0.0;
        }
        let mean = src.iter().sum::<f64>() / src.len() as f64;
        if mean <= 0.0 {
            return 0.0;
        }
        (1000.0 / mean) as f32
    }

    /// P2-8: 从 VecDeque 计算采样率(避免 as_slices 的两段拼接问题)。
    fn sample_hz_from_recent(&self) -> f32 {
        if self.recent.is_empty() {
            return 0.0;
        }
        let mean = self.recent.iter().sum::<f64>() / self.recent.len() as f64;
        if mean <= 0.0 {
            return 0.0;
        }
        (1000.0 / mean) as f32
    }
}

/// 单 kind 传感器健康信号。
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SensorHealth {
    pub kind: u8,
    pub ks_d: f32,
    pub anomaly: bool,
    pub sample_hz: f32,
}

/// 全局传感器基线状态(每 kind 独立)。
pub struct SensorBaseline {
    states: HashMap<u8, PerKind>,
}

impl SensorBaseline {
    pub fn new() -> Self {
        Self {
            states: HashMap::new(),
        }
    }

    /// 从 RING 批量消费全部样本,喂入各 kind 基线。SPSC:仅 sg_tick 调用。
    pub fn drain_ring(&mut self) {
        while let Some(s) = RING.pop() {
            self.feed(&s);
        }
    }

    /// 单 kind 喂入(测试用,不触碰全局 RING)。
    pub fn feed_sample(&mut self, s: Sample) {
        self.feed(&s);
    }

    /// 喂入一个样本:定位/创建对应 kind 的基线状态并委托更新。
    fn feed(&mut self, s: &Sample) {
        let st = self
            .states
            .entry(s.kind)
            .or_insert_with(|| PerKind::new(s.kind));
        st.feed(s);
    }

    /// 复位(测试隔离)。
    pub fn reset(&mut self) {
        self.states.clear();
    }

    /// 当前异常信号列表(warm 且有观测窗口的 kind)。
    pub fn health(&self) -> Vec<SensorHealth> {
        let mut out = Vec::new();
        for st in self.states.values() {
            if st.warm && !st.recent.is_empty() {
                let d = st.ks_d();
                out.push(SensorHealth {
                    kind: st.kind,
                    ks_d: d as f32,
                    anomaly: d > sensor_ks_tau(),
                    sample_hz: st.sample_hz(),
                });
            }
        }
        out
    }

    /// 编码为稳定二进制 ABI(小端),供 Kotlin 解码:
    ///   [0]      = count N (u8)
    ///   每条 10B: kind(u8) | ks_d(f32 LE) | anomaly(u8) | sample_hz(f32 LE)
    pub fn health_bytes(&self) -> Vec<u8> {
        let items = self.health();
        let mut buf = Vec::with_capacity(1 + items.len() * 10);
        buf.push(items.len() as u8);
        for it in items {
            buf.push(it.kind);
            buf.extend_from_slice(&it.ks_d.to_le_bytes());
            buf.push(if it.anomaly { 1u8 } else { 0u8 });
            buf.extend_from_slice(&it.sample_hz.to_le_bytes());
        }
        buf
    }
}

impl Default for SensorBaseline {
    fn default() -> Self {
        Self::new()
    }
}

/// 全局实例。
pub static SENSOR_BASELINE: Lazy<Mutex<SensorBaseline>> =
    Lazy::new(|| Mutex::new(SensorBaseline::new()));

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(kind: u8, ts: i64) -> Sample {
        Sample {
            ts_ns: ts,
            kind,
            x: 0.0,
            y: 0.0,
            z: 0.0,
        }
    }

    #[test]
    fn warmup_then_rolling_recent() {
        let mut sb = SensorBaseline::new();
        // BASELINE_CAP+1 个样本:首个仅 primer(last_ts),其后 BASELINE_CAP 个 jitter 填满基线。
        let mut ts = 1_700_000_000_000_000_000i64;
        for _ in 0..=BASELINE_CAP {
            ts += 20_000_000; // 20ms
            sb.feed_sample(sample(10, ts));
        }
        let st = sb.states.get(&10).expect("kind 10 present");
        assert!(st.warm);
        assert_eq!(st.baseline.len(), BASELINE_CAP);
        // 再喂 600 个 20ms(进入观测窗口 Dt,同分布)
        for _ in 0..RECENT_CAP {
            ts += 20_000_000;
            sb.feed_sample(sample(10, ts));
        }
        let h = sb.health();
        assert_eq!(h.len(), 1);
        assert!(
            !h[0].anomaly,
            "同分布 20ms 不应异常, ks_d={}",
            h[0].ks_d
        );
        assert!(
            (h[0].sample_hz - 50.0).abs() < 1.0,
            "采样率应≈50Hz, got {}",
            h[0].sample_hz
        );
    }

    #[test]
    fn third_party_high_freq_triggers_anomaly() {
        let mut sb = SensorBaseline::new();
        let mut ts = 1_700_000_000_000_000_000i64;
        // warm:20ms 间隔(基线 D0),BASELINE_CAP+1 样本确保基线填满。
        for _ in 0..=BASELINE_CAP {
            ts += 20_000_000;
            sb.feed_sample(sample(10, ts));
        }
        // 观测窗口:5ms 间隔(第三方高频抢档,我方抖动分布系统性偏移 Dt)
        for _ in 0..RECENT_CAP {
            ts += 5_000_000;
            sb.feed_sample(sample(10, ts));
        }
        let h = sb.health();
        assert_eq!(h.len(), 1);
        assert!(
            h[0].anomaly,
            "分布偏移应触发异常, ks_d={}",
            h[0].ks_d
        );
        assert!(h[0].ks_d > sensor_ks_tau() as f32);
    }

    #[test]
    fn health_bytes_layout() {
        let mut sb = SensorBaseline::new();
        let mut ts = 1_700_000_000_000_000_000i64;
        // warm: BASELINE_CAP+1 样本填满基线
        for _ in 0..=BASELINE_CAP {
            ts += 20_000_000;
            sb.feed_sample(sample(11, ts));
        }
        // 观测窗口: RECENT_CAP+1 样本填满 recent(同分布)
        for _ in 0..=RECENT_CAP {
            ts += 20_000_000;
            sb.feed_sample(sample(11, ts));
        }
        let bytes = sb.health_bytes();
        // 1 (count) + 1*10
        assert_eq!(bytes.len(), 11, "布局应为 11 字节");
        assert_eq!(bytes[0], 1); // count
        assert_eq!(bytes[1], 11); // kind = GYRO
        // ks_d 在 [2..6] 小端;anomaly 在 [6];sample_hz 在 [7..11]
        let ks_bits = i32::from_le_bytes([bytes[2], bytes[3], bytes[4], bytes[5]]);
        let ks = f32::from_bits(ks_bits as u32);
        assert!(ks >= 0.0 && ks < 0.1, "同分布 ks 应低, got {ks}");
        assert_eq!(bytes[6], 0); // anomaly false
        let hz_bits = i32::from_le_bytes([bytes[7], bytes[8], bytes[9], bytes[10]]);
        let hz = f32::from_bits(hz_bits as u32);
        assert!((hz - 50.0).abs() < 1.0, "采样率应≈50Hz, got {hz}");
    }

    #[test]
    fn empty_before_warmup_reports_nothing() {
        let mut sb = SensorBaseline::new();
        let mut ts = 1_700_000_000_000_000_000i64;
        for _ in 0..10 {
            ts += 20_000_000;
            sb.feed_sample(sample(10, ts));
        }
        assert!(sb.health().is_empty(), "未 warm 不应产出信号");
        let b = sb.health_bytes();
        assert_eq!(b.len(), 1); // 仅 count=0
        assert_eq!(b[0], 0);
    }
}
