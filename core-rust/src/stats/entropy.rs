//! W2~W3 (文档 §5.3 检验 3):Burst 熵 —— 采样间隔直方图的 Shannon 熵。
//! 合法区间 $[2.5, 4.5]$ 位(§7:EER 边界标定)。区间外可疑。
//! 纯函数:输入间隔计数直方图,输出熵(位)。零计数桶不参与(0·log0 ≡ 0)。

/// 由直方图计数计算 Shannon 熵(位)。
/// `counts` 的每个元素是一个分桶的采样间隔出现次数。
/// 空直方图返回 0.0。
pub fn shannon_entropy_bits(counts: &[u64]) -> f64 {
    let total: u64 = counts.iter().sum();
    if total == 0 {
        return 0.0;
    }
    let total_f = total as f64;
    let mut h = 0.0f64;
    for &c in counts {
        if c == 0 {
            continue;
        }
        let p = c as f64 / total_f;
        // H = -Σ p·log2(p)
        h -= p * p.log2();
    }
    h
}

// 阈值由 `crate::thresholds::THRESHOLDS.burst_entropy_min/max` 提供(文档 §2),
// 不再在此处硬编码。调用方统一引用 `crate::thresholds::THRESHOLDS`。

#[cfg(test)]
mod tests {
    use super::*;
    use crate::thresholds::THRESHOLDS;

    #[test]
    fn empty_histogram_zero() {
        assert_eq!(shannon_entropy_bits(&[]), 0.0);
        assert_eq!(shannon_entropy_bits(&[0, 0, 0]), 0.0);
    }

    #[test]
    fn single_bucket_zero_entropy() {
        // 所有间隔落同一桶 ⇒ 熵 0(确定性,无 burst 变异)
        assert!(shannon_entropy_bits(&[10]).abs() < 1e-9);
        assert!(shannon_entropy_bits(&[0, 7]).abs() < 1e-9);
    }

    #[test]
    fn uniform_two_buckets_one_bit() {
        // 2 个等概率桶 ⇒ 恰好 1 位
        let h = shannon_entropy_bits(&[3, 3]);
        assert!((h - 1.0).abs() < 1e-9, "h={h}");
    }

    #[test]
    fn uniform_16_buckets_four_bits() {
        // 2^4 个等概率桶 ⇒ 4 位,应接近或略高于上界(取决于 corpus 标定)
        let counts: Vec<u64> = vec![1; 16];
        let h = shannon_entropy_bits(&counts);
        assert!((h - 4.0).abs() < 1e-9, "h={h}");
        // 极端均匀分布熵应接近或超过上界(阈值为合法区间,不是硬上限)
        assert!(h >= THRESHOLDS.burst_entropy_min, "h={h} 应不低于下界");
    }

    #[test]
    fn skewed_below_min() {
        // 高度集中 ⇒ 熵 < 2.5(可疑)
        let counts = vec![100, 1, 1, 1];
        let h = shannon_entropy_bits(&counts);
        assert!(h < THRESHOLDS.burst_entropy_min, "h={h} 应低于 2.5");
    }

    #[test]
    fn scattered_above_max() {
        // 32 个近似等概率桶 ⇒ 熵 ≈ 5 位,超过上界 4.5(可疑)
        let counts: Vec<u64> = vec![1; 32];
        let h = shannon_entropy_bits(&counts);
        assert!(h > THRESHOLDS.burst_entropy_max, "h={h} 应高于 4.5");
    }

    #[test]
    fn probability_invariance() {
        // 等比例放大计数不改变熵
        let a = shannon_entropy_bits(&[2, 4, 8]);
        let b = shannon_entropy_bits(&[20, 40, 80]);
        assert!((a - b).abs() < 1e-12);
    }
}
