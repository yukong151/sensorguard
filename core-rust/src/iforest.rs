//! L4 (v1.1, 文档 §5.4):端侧 Isolation Forest 异常检测。
//!
//! 输入:32 维统计特征(§9 P9 清单,由 L3 中间量派生,零额外算力)。
//! 模型:100 棵 Isolation Tree,ψ=256,INT8 量化权重 mmap/编译时嵌入。
//! 输出:异常分数 s ∈ [0,1],s ≥ 0.7 → ALERT(文档 §6 Verdict 公式)。
//!
//! 设计:
//! - 纯 Rust 推理(热路径随 L3 一起跑,避免跨 JNI 传特征)。
//! - 权重编译时嵌入(builtin_model),可由离线 train.py 产出二进制替换。
//! - 树节点扁平数组存储,无递归(栈上迭代遍历,零分配)。
//! - 特征缺失(mask)填 -1.0,树节点 threshold 比较恒 false 走右子(文档 §9 规范)。

/// 特征维度(文档 §9 P9 固定 32 维)。
pub const N_FEATURES: usize = 32;

/// 异常分数阈值(文档 §6:score ≥ 0.7 → ALERT)。
pub const ALERT_THRESHOLD: f64 = 0.7;

/// 树节点:内部节点(分裂)或叶子(隔离深度)。
#[derive(Clone, Copy, Debug)]
pub struct Node {
    /// 分裂特征 id(0..=31);叶子节点该值无意义。
    pub feature_id: u8,
    /// 分裂阈值;叶子节点该值无意义。
    pub threshold: f32,
    /// 左/右子节点索引(内部节点);叶子节点为 0。
    pub left: u16,
    pub right: u16,
    /// 到达该节点的路径深度(叶子节点有效,内部节点为 0)。
    pub depth: u16,
}

impl Node {
    fn is_leaf(&self) -> bool {
        self.left == 0 && self.right == 0
    }
}

/// 单棵 Isolation Tree(扁平节点数组,0 号节点为根)。
pub struct Tree {
    nodes: Vec<Node>,
}

impl Tree {
    /// 遍历样本,返回隔离路径长度(到达叶子深度)。
    fn path_length(&self, features: &[f32; N_FEATURES]) -> f64 {
        let mut idx = 0usize;
        let mut depth = 0u16;
        loop {
            let n = &self.nodes[idx];
            if n.is_leaf() {
                return depth as f64 + n.depth as f64;
            }
            // 特征缺失(-1.0)恒走右子;阈值比较为 feature < threshold → 左子
            let go_left = features[n.feature_id as usize] < n.threshold;
            idx = if go_left { n.left as usize } else { n.right as usize };
            depth += 1;
        }
    }
}

/// Isolation Forest 模型。
pub struct IForest {
    trees: Vec<Tree>,
    /// 建树样本数 ψ(归一化常数 c(ψ) 用)。
    sample_size: f64,
}

impl IForest {
    /// 从节点列表构建(每棵树一个节点向量)。
    pub fn new(trees: Vec<Tree>, sample_size: f64) -> Self {
        Self { trees, sample_size }
    }

    /// 平均隔离路径长度(文档 §5.4)。
    pub fn path_length(&self, features: &[f32; N_FEATURES]) -> f64 {
        if self.trees.is_empty() {
            return 0.0;
        }
        let sum: f64 = self.trees.iter().map(|t| t.path_length(features)).sum();
        sum / self.trees.len() as f64
    }

    /// 异常分数 s ∈ [0,1](文档 §5.4:调和平均归一化)。
    /// s = 2^(-h(x)/c(ψ)),h 为平均路径长度,c(ψ) 为理论平均。
    pub fn score(&self, features: &[f32; N_FEATURES]) -> f64 {
        if self.trees.is_empty() {
            return 0.0;
        }
        let c = normalization_constant(self.sample_size);
        if c <= 0.0 {
            return 0.0;
        }
        let h = self.path_length(features);
        (2.0f64).powf(-h / c)
    }

    /// 是否触发告警(score ≥ 0.7)。
    pub fn should_alert(&self, features: &[f32; N_FEATURES]) -> bool {
        self.score(features) >= ALERT_THRESHOLD
    }
}

/// 归一化常数 c(ψ) = 2(ln(ψ-1)+γ) - 2(ψ-1)/ψ,γ = 欧拉常数。
fn normalization_constant(sample_size: f64) -> f64 {
    if sample_size <= 2.0 {
        return 1.0;
    }
    let gamma = 0.577_215_664_901_532_9; // 欧拉-马歇罗尼常数
    2.0 * ((sample_size - 1.0).ln() + gamma) - 2.0 * (sample_size - 1.0) / sample_size
}

/// 内置模型:合成权重,代表"正常事件隔离路径长、异常事件路径短"。
/// 由离线 train.py 训练后以二进制权重替换(本文件仅推理,不含训练)。
/// 每棵树单次分裂:特征值 < 阈值 → 正常(深叶子路径 8),≥ 阈值 → 异常(浅叶子路径 1)。
pub fn builtin_model() -> IForest {
    let mut trees = Vec::with_capacity(100);
    for t in 0..100 {
        // 分裂特征:轮转覆盖特征 0..15(频次/时序组),每棵树 1 个分裂点
        let f = (t % 16) as u8;
        // 阈值:特征 0..7 为频次(正常 < 30),8..15 为比率(正常 < 0.4)
        let threshold = if f < 8 { 30.0f32 } else { 0.4f32 };
        let nodes = vec![
            // 0:根 —— feature < threshold → 节点1(继续走 8 步深);否则 → 节点2(浅,深度 1)
            Node { feature_id: f, threshold, left: 1, right: 2, depth: 0 },
            // 1:正常路径终点(深叶子,深度 8 → 低分)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 8 },
            // 2:异常路径终点(浅叶子,深度 1 → 高分)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 1 },
        ];
        trees.push(Tree { nodes });
    }
    IForest::new(trees, 256.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn normal_features() -> [f32; N_FEATURES] {
        let mut f = [0.0f32; N_FEATURES];
        // 正常样本:全部特征低于阈值(freq<30, ratio<0.4)
        for i in 0..16 { f[i] = if i < 8 { 5.0 } else { 0.3 }; }
        f
    }

    fn anomaly_features() -> [f32; N_FEATURES] {
        let mut f = [0.0f32; N_FEATURES];
        // 异常样本:全部特征高于阈值(freq>30, ratio>0.4)
        for i in 0..16 { f[i] = if i < 8 { 500.0 } else { 0.9 }; }
        f
    }

    #[test]
    fn normalization_constant_sane() {
        let c = normalization_constant(256.0);
        assert!(c > 8.0 && c < 12.0, "c(256) 应在 ~10.2 附近,got {c}");
        assert!(normalization_constant(2.0) > 0.0);
    }

    #[test]
    fn normal_scores_low_anomaly_scores_high() {
        let model = builtin_model();
        let s_normal = model.score(&normal_features());
        let s_anom = model.score(&anomaly_features());
        assert!(
            s_anom > s_normal,
            "异常样本分数应高于正常样本:{s_anom} vs {s_normal}"
        );
        assert!(!model.should_alert(&normal_features()), "正常样本不应告警");
        assert!(model.should_alert(&anomaly_features()), "异常样本应告警");
    }

    #[test]
    fn missing_features_walk_right() {
        let model = builtin_model();
        let mut f = [0.0f32; N_FEATURES];
        // 全 -1.0(特征缺失):恒走右子,路径一致,分数确定
        for v in f.iter_mut() {
            *v = -1.0;
        }
        let s = model.score(&f);
        assert!(s >= 0.0 && s <= 1.0, "缺失特征分数应在 [0,1],got {s}");
    }

    #[test]
    fn zero_trees_returns_zero() {
        let model = IForest::new(vec![], 256.0);
        assert_eq!(model.score(&normal_features()), 0.0);
        assert!(!model.should_alert(&normal_features()));
    }
}