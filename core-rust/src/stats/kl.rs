//! W2~W3 (文档 §5.3 检验 2):昼夜 KL 散度 $D_{KL}(P_{obs} \parallel P_{normal})$,阈值 0.35。
//! 纯函数:输入两个已归一化的概率分布(同为桶数),输出非负散度。
//! 约定:调用方负责归一化与分桶对齐;观测概率为 0 的桶贡献 0(0·log(0/q)≡0)。

/// 计算 $D_{KL}(obs \parallel normal)$(自然对数,nats)。
/// 两个切片长度必须相等;任一桶 normal 概率为 0 而 obs 非 0 时,
/// 散度发散(返回 f64::INFINITY)—— 观测到"正常分布中不可能的事件"。
pub fn kl_divergence(obs: &[f64], normal: &[f64]) -> f64 {
    debug_assert_eq!(obs.len(), normal.len(), "分布桶数必须一致");
    let mut d = 0.0f64;
    for (&p, &q) in obs.iter().zip(normal.iter()) {
        if p <= 0.0 {
            continue; // 0·log(0/q) ≡ 0
        }
        if q <= 0.0 {
            return f64::INFINITY;
        }
        d += p * (p / q).ln();
    }
    // 数值容差:理论下界 0,浮点可能微负
    if d < 0.0 && d > -1e-12 {
        0.0
    } else {
        d
    }
}

// 阈值由 `crate::thresholds::THRESHOLDS.kl_divergence` 提供(文档 §2),不再在此处硬编码。
// 调用方统一引用 `crate::thresholds::THRESHOLDS` 以追踪阈值来源。

#[cfg(test)]
mod tests {
    use super::*;
    use crate::thresholds::THRESHOLDS;

    #[test]
    fn identical_zero() {
        let p = vec![0.25, 0.25, 0.25, 0.25];
        assert!(kl_divergence(&p, &p).abs() < 1e-12);
    }

    #[test]
    fn zero_in_obs_skipped() {
        // obs 为 0 的桶不贡献,即使 normal 该桶为 0 也不发散
        let obs = vec![0.0, 0.5, 0.5];
        let normal = vec![0.0, 0.5, 0.5];
        assert!(kl_divergence(&obs, &normal).abs() < 1e-12);
    }

    #[test]
    fn divergence_is_nonnegative() {
        let obs = vec![0.6, 0.2, 0.2];
        let normal = vec![0.3, 0.3, 0.4];
        let d = kl_divergence(&obs, &normal);
        assert!(d >= 0.0, "KL 非负,d={d}");
        assert!(d > 0.0, "不同分布散度 > 0");
    }

    #[test]
    fn unsupported_event_diverges() {
        // obs 在 normal 为 0 的桶上有质量 ⇒ 发散(+inf)
        let obs = vec![0.5, 0.5, 0.0];
        let normal = vec![0.5, 0.0, 0.5];
        assert_eq!(kl_divergence(&obs, &normal), f64::INFINITY);
    }

    #[test]
    fn threshold_sanity() {
        // 近基线分布应低于 0.35(扰动后重新归一化,确保输入是合法概率分布)
        let norm = vec![1.0 / 24.0; 24];
        let mut near: Vec<f64> = (0..24u32)
            .map(|i| 1.0 / 24.0 + 0.005 * (i as f64).sin())
            .collect();
        let sum: f64 = near.iter().sum();
        for p in near.iter_mut() {
            *p /= sum;
        }
        let d_near = kl_divergence(&near, &norm);
        assert!(d_near < THRESHOLDS.kl_divergence, "近基线应低于阈值,d={d_near}");

        // 显著偏移:单个桶集中 95%,应超阈值
        let mut obs = vec![0.05 / 23.0; 24];
        obs[0] = 0.95;
        let d_far = kl_divergence(&obs, &norm);
        assert!(d_far > THRESHOLDS.kl_divergence, "尖峰分布应超阈值,d={d_far}");
    }
}
