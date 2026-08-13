//! W2~W3 (文档 §5.3 检验 1):两样本 Kolmogorov-Smirnov 检验。
//! $D_{KS} = \sup_x |F_0(x) - F_t(x)|$,当前阈值 $\tau_{KS} = 0.18$(§7:标定 corpus P99.5 百分位)。
//! 纯函数:输入两组观测样本(基线 $F_0$ 与当前窗口 $F_t$),输出 D_KS 统计量。
//! 与阈值的比较由调用方完成(阈值来自 calibrate.py 产出的阈值文件)。

/// 计算两样本 KS 统计量 $D_{KS}$。
/// 内部将两侧样本排序后扫描经验 CDF 差的最大绝对值。
/// 空输入返回 0.0(无证据表明分布偏离)。
pub fn ks_statistic(a: &[f64], b: &[f64]) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let mut a_sorted: Vec<f64> = a.to_vec();
    let mut b_sorted: Vec<f64> = b.to_vec();
    // 显式全序排序:NaN 会破坏 KS 扫描,样本源(时间戳/间隔)不含 NaN,防御性处理。
    a_sorted.sort_by(|x, y| x.partial_cmp(y).unwrap_or(std::cmp::Ordering::Equal));
    b_sorted.sort_by(|x, y| x.partial_cmp(y).unwrap_or(std::cmp::Ordering::Equal));

    let n_a = a_sorted.len() as f64;
    let n_b = b_sorted.len() as f64;
    let (mut i, mut j) = (0usize, 0usize);
    let mut d_max = 0.0f64;
    // 逐值扫描:两组合并后依次取相异观测值 v,同时累计两侧 ≤ v 的元素数,
    // 每次迭代恰好记录一个观测点上的 |F_a(v) - F_b(v)|,峰值即 D_KS。
    while i < a_sorted.len() || j < b_sorted.len() {
        // 取当前未处理的较小值 v(相等则同时推进两侧)
        let a_cur = a_sorted.get(i);
        let b_cur = b_sorted.get(j);
        let v = match (a_cur, b_cur) {
            (Some(&x), Some(&y)) => {
                if x <= y {
                    x
                } else {
                    y
                }
            }
            (Some(&x), None) => x,
            (None, Some(&y)) => y,
            (None, None) => break,
        };
        // 累计两侧所有等于 v 的元素(相等值同权,同步推进)
        while let Some(&x) = a_sorted.get(i) {
            if x != v {
                break;
            }
            i += 1;
        }
        while let Some(&y) = b_sorted.get(j) {
            if y != v {
                break;
            }
            j += 1;
        }
        let cdf_a = i as f64 / n_a;
        let cdf_b = j as f64 / n_b;
        let diff = (cdf_a - cdf_b).abs();
        if diff > d_max {
            d_max = diff;
        }
    }
    d_max
}

// 阈值由 `crate::thresholds::THRESHOLDS.ks_tau` 提供(文档 §2),不再在此处硬编码。
// 调用方统一引用 `crate::thresholds::THRESHOLDS` 以追踪阈值来源。

#[cfg(test)]
mod tests {
    use super::*;
    use crate::thresholds::THRESHOLDS;

    #[test]
    fn identical_distributions_zero() {
        let x = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        assert!(ks_statistic(&x, &x) < 1e-9);
    }

    #[test]
    fn empty_inputs_zero() {
        assert_eq!(ks_statistic(&[], &[1.0, 2.0]), 0.0);
        assert_eq!(ks_statistic(&[1.0], &[]), 0.0);
        assert_eq!(ks_statistic(&[], &[]), 0.0);
    }

    #[test]
    fn disjoint_distributions_max() {
        // 基线全部 < 当前全部 ⇒ CDF 差可达 1.0
        let baseline = vec![0.0, 0.1, 0.2, 0.3];
        let current = vec![10.0, 11.0, 12.0, 13.0];
        let d = ks_statistic(&baseline, &current);
        assert!(d > THRESHOLDS.ks_tau, "d={d} 应远超 0.18");
        assert!(d >= 0.99, "完全分离时 D 接近 1.0,d={d}");
    }

    #[test]
    fn slight_shift_below_threshold() {
        // 同分布加微小噪声:应远低于 0.18
        let mut base: Vec<f64> = (0..200).map(|i| (i as f64).sin()).collect();
        let mut cur: Vec<f64> = (0..200).map(|i| (i as f64).sin() + 0.01).collect();
        base.sort_by(|x, y| x.total_cmp(y));
        cur.sort_by(|x, y| x.total_cmp(y));
        let d = ks_statistic(&base, &cur);
        assert!(d < THRESHOLDS.ks_tau, "微偏应低于阈值,d={d}");
    }

    #[test]
    fn scale_invariant_to_sample_size() {
        // 同一 [0,1) 均匀分布、不同离散粒度(50 级 vs 8 级):
        // 经验 CDF 间最坏间隙 ≤ 粗粒度步长的 1/2 + 细粒度步长。
        let a: Vec<f64> = (0..500).map(|i| (i % 50) as f64 / 50.0).collect();
        let b: Vec<f64> = (0..80).map(|i| (i % 8) as f64 / 8.0).collect();
        let d = ks_statistic(&a, &b);
        // 理论上界:粗粒度(8 级)步长 1/8 = 0.125;观察值 0.12 一致
        assert!(d <= 0.13, "粒度间隙理论界,d={d}");
    }
}
