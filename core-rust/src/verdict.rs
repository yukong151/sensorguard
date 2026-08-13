//! W2~W3 (文档 §5.3):L3 评估引擎 —— 从事件窗口提取统计量并判定 Verdict。
//! 聚合三项纯统计(KS / Burst 熵 / KL),按 §5.3 判定汇总输出 VerdictKind。
//! 阈值与基线分布由阈值文件提供(§7:calibrate.py 产出,不可手改);
//! v1.0 内置默认基线(均匀分布近似),后续由阈值文件覆盖。

use crate::event_window::{PairWindow, BUCKET_NS, MIN_EVENTS, NUM_BUCKETS};
use crate::stats::entropy::shannon_entropy_bits;
use crate::stats::kl::kl_divergence;
use crate::stats::lomb;
use crate::thresholds::THRESHOLDS;

/// VerdictKind 枚举值(与 schemas/sensorguard.fbs 一致)
pub const VERDICT_LEGIT: u8 = 0;
pub const VERDICT_OBSERVE: u8 = 1;
pub const VERDICT_ALERT: u8 = 2;

/// L3 单项检验结果
#[derive(Clone, Copy, Debug, Default)]
pub struct L3Stats {
    pub ks_d: f64,
    pub burst_entropy: f64,
    pub kl_day_night: f64,
    /// 24h 事件数(数据不足判定)
    pub event_total: u32,
    /// Lomb-Scargle 周期图能量集中度(节律一致性,0..1)
    pub period_energy: f64,
}

/// 评估结果
#[derive(Clone, Copy, Debug)]
pub struct EvalResult {
    pub verdict: u8,
    /// 触发异常标记的检验个数(0..=3),供 debounce 逻辑使用
    pub alerts: u8,
    pub stats: L3Stats,
    /// 数据不足(事件数 < MIN_EVENTS)
    pub insufficient: bool,
}

/// 一天内分钟桶数 = 24h × 60 = 1440。
const DAY_BUCKETS: u32 = NUM_BUCKETS as u32;

/// KS 检验(基线偏离):事件在"一天内时间"维度上的经验 CDF vs 均匀基线 CDF。
/// F_t(t) = 当日自 0 至 t 的累计事件占比;F_0(t) = t/DAY(均匀)。
/// 用于检测夜间异常集中 / 活动时段漂移。O(1440),tick 路径允许。
fn ks_time_of_day(pw: &PairWindow, now_ns: i64, total: u32) -> f64 {
    if total == 0 {
        return 0.0;
    }
    let now_bucket = (now_ns / BUCKET_NS) as u32;
    // 一天内时间直方图:t = bucket % 1440(绝对桶号对一天桶数取模)
    let mut tod = [0u32; DAY_BUCKETS as usize];
    for slot in pw.slots().iter() {
        if slot.count > 0 && now_bucket.saturating_sub(slot.bucket) < DAY_BUCKETS {
            let t = (slot.bucket % DAY_BUCKETS) as usize;
            tod[t] = tod[t].saturating_add(u32::from(slot.count));
        }
    }
    let mut cum = 0u32;
    let mut d_max = 0.0f64;
    for (i, &c) in tod.iter().enumerate() {
        if c == 0 {
            continue;
        }
        cum = cum.saturating_add(c);
        let f_cur = f64::from(cum) / f64::from(total);
        // 均匀基线累计比例 F0 = (i + 1) / DAY_BUCKETS(步进点)
        let f0 = (i as f64 + 1.0) / f64::from(DAY_BUCKETS);
        let d = (f_cur - f0).abs();
        if d > d_max {
            d_max = d;
        }
    }
    d_max
}

/// 昼夜 KL 散度:按 24 小时聚合观测分布 vs 均匀"正常昼夜"分布。
fn kl_day_night(pw: &PairWindow, now_ns: i64) -> f64 {
    let now_bucket = (now_ns / BUCKET_NS) as u32;
    let mut hour_obs = [0.0f64; 24];
    for slot in pw.slots().iter() {
        if slot.count > 0 && now_bucket.saturating_sub(slot.bucket) < DAY_BUCKETS {
            let hour = ((slot.bucket % DAY_BUCKETS) / 60) as usize; // 1440/60=24
            hour_obs[hour] += f64::from(slot.count);
        }
    }
    let obs_sum: f64 = hour_obs.iter().sum();
    if obs_sum <= 0.0 {
        return 0.0;
    }
    let obs_norm: Vec<f64> = hour_obs.iter().map(|&v| v / obs_sum).collect();
    let normal = [1.0 / 24.0; 24];
    kl_divergence(&obs_norm, &normal)
}

/// 默认基线 / 阈值评估(§5.3 文档默认值;阈值文件接入后由 calibrate 覆盖)。
pub fn evaluate(pw: &PairWindow, now_ns: i64) -> EvalResult {
    let total = pw.total(now_ns);
    let insufficient = total < MIN_EVENTS;

    // Burst 熵:采样间隔直方图 → Shannon 熵(位)
    let hist = pw.interval_histogram();
    let hist64: Vec<u64> = hist.iter().map(|&c| u64::from(c)).collect();
    let burst = shannon_entropy_bits(&hist64);

    let ks_d = ks_time_of_day(pw, now_ns, total);
    let kl = kl_day_night(pw, now_ns);

    // P3: Lomb-Scargle 周期图(节律一致性)
    let buckets = pw.bucket_counts(now_ns);
    let bucket_f64: Vec<f64> = buckets.iter().map(|&c| c as f64).collect();
    let periodogram = lomb::periodogram(&bucket_f64);
    let period_energy = periodogram.concentration;

    // 判定汇总(§5.3):L3 异常 ≥2 → ALERT;数据不足(< MIN_EVENTS)即使异常
    // 也多记 OBSERVE(INSUFFICIENT_DATA,不产出 ALERT)。
    // LEGIT 需要 S_ctx ≥ 0.6 或白名单,由上层(Kotlin / 后续 ctx 模块)判定,
    // 此处 L3 引擎不输出 LEGIT。
    let mut alerts = 0u8;
    if ks_d > THRESHOLDS.ks_tau {
        alerts += 1;
    }
    if !(THRESHOLDS.burst_entropy_min..=THRESHOLDS.burst_entropy_max).contains(&burst) {
        alerts += 1;
    }
    if kl > THRESHOLDS.kl_divergence {
        alerts += 1;
    }

    let verdict = if !insufficient && alerts >= 2 {
        VERDICT_ALERT
    } else {
        VERDICT_OBSERVE
    };

    EvalResult {
        verdict,
        alerts,
        stats: L3Stats {
            ks_d,
            burst_entropy: burst,
            kl_day_night: kl,
            event_total: total,
            period_energy,
        },
        insufficient,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::event_window::PairKey;

    const DAY: i64 = 24 * 3_600_000_000_000;

    #[test]
    fn insufficient_data_reported() {
        let mut pw = PairWindow::new(PairKey::new(1, 0));
        let t0 = 1_700_000_000_000_000_000i64;
        for i in 0..19 {
            pw.record(t0 + i * 1_000_000_000);
        }
        let r = evaluate(&pw, t0 + 19 * 1_000_000_000);
        assert!(r.insufficient);
        assert_eq!(r.stats.event_total, 19);
    }

    #[test]
    fn steady_uniform_day_observe() {
        let mut pw = PairWindow::new(PairKey::new(1, 0));
        let t0 = 1_700_000_000_000_000_000i64;
        // 整天均匀:每小时 60 分钟 × 每分钟 10 事件
        for h in 0..24u32 {
            for m in 0..60u32 {
                for _ in 0..10 {
                    pw.record(t0 + i64::from(h * 3600 + m * 60) * 1_000_000_000);
                }
            }
        }
        // 评估时刻取 24h 前一刻,保证首末桶均在窗口内(边界桶等距时会被淘汰)
        let now = t0 + DAY - 1_000_000;
        let r = evaluate(&pw, now);
        assert!(!r.insufficient);
        // 输入事件覆盖整整 24h;窗口=最近 1440 个桶(当前桶 + 1439 个完整桶),
        // 最早的桶(距 now 恰好 24h)按语义出窗,故少 1 桶(10 条)。
        assert_eq!(r.stats.event_total, 24 * 60 * 10 - 10);
        assert!(
            r.stats.ks_d < THRESHOLDS.ks_tau,
            "全天均匀 ks_d 应低, got {}",
            r.stats.ks_d
        );
        assert!(
            r.stats.kl_day_night < THRESHOLDS.kl_divergence,
            "kl 应低, got {}",
            r.stats.kl_day_night
        );
        assert_eq!(
            r.verdict, VERDICT_OBSERVE,
            "均匀分布不应 ALERT, stats={:?}",
            r.stats
        );
    }

    #[test]
    fn bursty_spike_alerts() {
        let mut pw = PairWindow::new(PairKey::new(1, 0));
        let t0 = 1_700_000_000_000_000_000i64;
        // 23h 均匀低峰基线(每分钟 1 事件 → 1380 条)
        for m in 0..1380u32 {
            pw.record(t0 + i64::from(m) * 60_000_000_000);
        }
        // 最后一分钟 9000 事件突发(同一桶集中)→ KS 大幅偏移
        let burst_start = t0 + i64::from(1380u32) * 60_000_000_000;
        for i in 0..9000u32 {
            pw.record(burst_start + i64::from(i) * 1_000_000);
        }
        let now = burst_start + 60_000_000_000;
        let r = evaluate(&pw, now);
        assert!(!r.insufficient);
        assert_eq!(r.stats.event_total, 1380 + 9000);
        assert_eq!(
            r.verdict, VERDICT_ALERT,
            "burst 突发应 ALERT, stats={:?}",
            r.stats
        );
    }

    #[test]
    fn degraded_entropy_alerts() {
        let mut pw = PairWindow::new(PairKey::new(1, 0));
        let t0 = 1_700_000_000_000_000_000i64;
        // 全部事件 100ms 周期密集 → 熵 ≈ 0(< 2.5)
        for i in 0..300u32 {
            pw.record(t0 + i64::from(i) * 100_000_000);
        }
        let r = evaluate(&pw, t0 + 300 * 100_000_000);
        assert!(!r.insufficient, "事件数 {} 应≥20", r.stats.event_total);
        assert!(
            r.stats.burst_entropy < THRESHOLDS.burst_entropy_min,
            "熵应低于 2.5, got {}",
            r.stats.burst_entropy
        );
    }

    #[test]
    fn night_cluster_kl_alerts() {
        let mut pw = PairWindow::new(PairKey::new(1, 0));
        let t0 = 1_700_000_000_000_000_000i64;
        // 全部事件集中在 2:00~3:00(小时桶 2)→ 昼夜 KL 显著为正
        for m in 0..60u32 {
            for _ in 0..30 {
                pw.record(t0 + i64::from(2 * 3600 + m * 60) * 1_000_000_000);
            }
        }
        let r = evaluate(&pw, t0 + DAY);
        assert!(!r.insufficient);
        assert!(
            r.stats.kl_day_night > 0.1,
            "夜间集中 kl 应显著, got {}",
            r.stats.kl_day_night
        );
    }
}
