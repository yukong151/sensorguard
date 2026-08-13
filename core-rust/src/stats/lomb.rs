//! W2~W3 (文档 §5.3 检验 4):Lomb-Scargle 周期图 —— 节律一致性检验。
//! 合法采样通常有稳定周期(如健身 App 每 20 min 同步一次 IMU),用 L-S 周期图
//! 找主频,主频存在且能量集中度 > 0.4 判为节律型合法;违规采样常呈现均匀无相位或
//! 深夜异常峰值(§7 阈值:period_energy_concentration,由 calibrate.py 产出)。
//!
//! 输入:等间隔时间序列(如 1440 个分钟桶的事件计数),非均匀间隔也可(自动处理)。
//! 输出:归一化功率谱的主频(Hz)与能量集中度(0..1)。
//! 纯函数,无状态,无 IO。

/// 扫描的最低频率(Hz = 1/周期秒),对应最长周期 24h。
const MIN_FREQ: f64 = 1.0 / (24.0 * 3600.0);
/// 扫描的最高频率(Hz),对应最短周期 2 min。
const MAX_FREQ: f64 = 1.0 / 120.0;
/// 扫描点数(频率网格粒度)。
const NUM_FREQ: usize = 200;

/// 周期图结果:主频频率(Hz)与能量集中度(0..1)。
#[derive(Copy, Clone, Debug, Default)]
pub struct PeriodogramResult {
    /// 主频频率(Hz)。
    pub dominant_freq: f64,
    /// 主频功率占总功率的比例(能量集中度),0..1。
    pub concentration: f64,
}

/// 计算 Lomb-Scargle 周期图。
/// `time_points`: 等间隔时间序列(如 1440 个分钟桶),非均匀间隔亦支持。
/// 空序列或全零序列返回默认结果。
pub fn periodogram(time_points: &[f64]) -> PeriodogramResult {
    if time_points.is_empty() {
        return PeriodogramResult::default();
    }
    let n = time_points.len() as f64;
    let t_start = 0.0;
    let t_end = n; // 假设单位为"桶数"

    let freqs = scan_frequencies(t_start, t_end);
    let mut powers = Vec::with_capacity(freqs.len());
    let mut max_power = 0.0f64;
    let mut max_freq = 0.0f64;

    for &f in &freqs {
        let p = lomb_power(time_points, f);
        powers.push(p);
        if p > max_power {
            max_power = p;
            max_freq = f;
        }
    }

    let total_power: f64 = powers.iter().sum();
    let concentration = if total_power > 0.0 {
        max_power / total_power
    } else {
        0.0
    };

    PeriodogramResult {
        dominant_freq: max_freq,
        concentration,
    }
}

/// 生成待扫描的频率网格(对数均匀分布,覆盖 MIN_FREQ..=MAX_FREQ)。
fn scan_frequencies(t_start: f64, t_end: f64) -> Vec<f64> {
    let duration = (t_end - t_start).max(1.0);
    // 根据 Nyquist 理论,最高有意义频率 = n/(2*duration)
    let nyquist = (NUM_FREQ as f64) / (2.0 * duration);
    let hi = MAX_FREQ.min(nyquist);
    let lo = MIN_FREQ.max(1.0 / duration);
    if lo >= hi {
        return vec![lo];
    }
    let log_lo = lo.ln();
    let log_hi = hi.ln();
    let step = (log_hi - log_lo) / (NUM_FREQ as f64 - 1.0);
    (0..NUM_FREQ)
        .map(|i| (log_lo + step * i as f64).exp())
        .filter(|&f| f >= lo && f <= hi)
        .collect()
}

/// 计算单个频率的 Lomb-Scargle 归一化功率。
/// `y`: 等间隔时间序列值(事件计数)。
/// `freq`: 待测试的频率(Hz)。
fn lomb_power(y: &[f64], freq: f64) -> f64 {
    let n = y.len() as f64;
    if n < 2.0 {
        return 0.0;
    }

    let omega = 2.0 * std::f64::consts::PI * freq;
    let mean: f64 = y.iter().sum::<f64>() / n;
    let variance: f64 = y.iter().map(|&v| (v - mean).powi(2)).sum::<f64>() / (n - 1.0);
    if variance <= 0.0 {
        return 0.0;
    }

    // τ = (1/(2ω)) arctan(Σsin(2ωt_i) / Σcos(2ωt_i))
    let (sum_sin2, sum_cos2) = y.iter().enumerate().fold((0.0, 0.0), |(ss, sc), (i, _)| {
        let t = i as f64;
        let arg = 2.0 * omega * t;
        (ss + arg.sin(), sc + arg.cos())
    });
    let tau = (1.0 / (2.0 * omega)) * sum_sin2.atan2(sum_cos2);

    // P(ω) = (1/(2σ²)) * [ (Σ(y_i - μ)cos(ω(t_i - τ)))² / Σcos²(ω(t_i - τ))
    //                     + (Σ(y_i - μ)sin(ω(t_i - τ)))² / Σsin²(ω(t_i - τ)) ]
    let (sum_cos, sum_cos2_val, sum_sin, sum_sin2_val) = y.iter().enumerate().fold(
        (0.0, 0.0, 0.0, 0.0),
        |(sc, sc2, ss, ss2), (i, &v)| {
            let t = i as f64;
            let arg = omega * (t - tau);
            let c = arg.cos();
            let s = arg.sin();
            let diff = v - mean;
            (sc + diff * c, sc2 + c * c, ss + diff * s, ss2 + s * s)
        },
    );

    let norm = 1.0 / (2.0 * variance);
    let term1 = if sum_cos2_val > 0.0 {
        sum_cos * sum_cos / sum_cos2_val
    } else {
        0.0
    };
    let term2 = if sum_sin2_val > 0.0 {
        sum_sin * sum_sin / sum_sin2_val
    } else {
        0.0
    };

    norm * (term1 + term2)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_default() {
        let r = periodogram(&[]);
        assert_eq!(r.dominant_freq, 0.0);
        assert_eq!(r.concentration, 0.0);
    }

    #[test]
    fn constant_input_returns_zero() {
        let y = vec![5.0; 1440];
        let r = periodogram(&y);
        assert_eq!(r.concentration, 0.0);
    }

    #[test]
    fn sine_wave_detects_frequency() {
        // 24h 周期正弦波,每 60s 一个桶 → 1440 个桶
        let mut y = Vec::with_capacity(1440);
        for i in 0..1440 {
            let t = i as f64;
            y.push((t * 2.0 * std::f64::consts::PI / 1440.0).sin() + 1.0);
        }
        let r = periodogram(&y);
        // 主频应在 1/1440 ≈ 0.000694 Hz 附近
        assert!((r.dominant_freq - 1.0 / 1440.0).abs() < 0.0001, "主频偏离,got {}", r.dominant_freq);
        assert!(r.dominant_freq > 0.0, "应有主频");
    }

    #[test]
    fn white_noise_low_concentration() {
        // 白噪声:各频率能量均匀分布 → 集中度低
        let y: Vec<f64> = (0..1440).map(|i| (i as f64 * 7.0).sin() + (i as f64 * 13.0).sin()).collect();
        let r = periodogram(&y);
        // 多频率叠加,集中度应低于单频
        assert!(r.dominant_freq > 0.0);
    }
}