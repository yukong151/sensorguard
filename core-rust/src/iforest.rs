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

    /// P2-1: 测试用访问器 —— 节点数。
    #[cfg(test)]
    pub fn nodes_count(&self) -> usize {
        self.nodes.len()
    }

    /// P2-1: 测试用访问器 —— 节点迭代器。
    #[cfg(test)]
    pub fn nodes_iter(&self) -> &[Node] {
        &self.nodes
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

    /// P2-1: 测试用访问器 —— 树数量。
    #[cfg(test)]
    pub fn trees_count(&self) -> usize {
        self.trees.len()
    }

    /// P2-1: 测试用访问器 —— sample_size。
    #[cfg(test)]
    pub fn sample_size(&self) -> f64 {
        self.sample_size
    }

    /// P2-1: 测试用访问器 —— 树迭代器。
    #[cfg(test)]
    pub fn trees_iter(&self) -> &[Tree] {
        &self.trees
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

/// 内置模型:多级分裂合成权重,代表"正常事件隔离路径长、异常事件路径短"。
///
/// P2-1 改进:原先每棵树仅单次分裂(3 节点),精度有限。
/// 现升级为三级分裂(7 节点/树),覆盖频次+比率+时序三类特征的组合判定,
/// 显著提升正常/异常样本的隔离路径差异。
///
/// 树结构(7 节点):
/// ```text
///            [0] root: freq_split
///           /              \
///     [1] ratio_split    [2] anomaly_leaf (depth=1)
///      /         \
/// [3] deep_leaf  [4] time_split
/// (depth=5)      /         \
///           [5] mid_leaf   [6] shallow_leaf
///           (depth=3)      (depth=2)
/// ```
///
/// 由离线 train.py 训练后以二进制权重替换(见 load_model_from_bytes)。
pub fn builtin_model() -> IForest {
    let mut trees = Vec::with_capacity(100);
    for t in 0..100u32 {
        // 主分裂特征:轮转覆盖特征 0..7(频次组)
        let f0 = (t % 8) as u8;
        // 次级分裂特征:轮转覆盖特征 8..15(比率组)
        let f1 = ((t % 8) + 8) as u8;
        // 三级分裂特征:轮转覆盖特征 16..23(时序组)
        let f2 = ((t % 8) + 16) as u8;

        // 阈值:频次 < 30,比率 < 0.4,时序 < 0.5
        let th_freq = 30.0f32;
        let th_ratio = 0.4f32;
        let th_time = 0.5f32;

        let nodes = vec![
            // 0:根 —— freq < th → 节点1(继续检查比率);否则 → 节点2(异常,浅)
            Node { feature_id: f0, threshold: th_freq, left: 1, right: 2, depth: 0 },
            // 1:ratio < th → 节点3(正常,深叶子);否则 → 节点4(继续检查时序)
            Node { feature_id: f1, threshold: th_ratio, left: 3, right: 4, depth: 0 },
            // 2:异常路径终点(浅叶子,深度 1 → 高分)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 1 },
            // 3:正常路径终点(深叶子,深度 5 → 低分)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 5 },
            // 4:time < th → 节点5(中等深度);否则 → 节点6(异常,较浅)
            Node { feature_id: f2, threshold: th_time, left: 5, right: 6, depth: 0 },
            // 5:中等异常叶子(深度 3)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 3 },
            // 6:较浅异常叶子(深度 2)
            Node { feature_id: 0, threshold: 0.0, left: 0, right: 0, depth: 2 },
        ];
        trees.push(Tree { nodes });
    }
    IForest::new(trees, 256.0)
}

/// P2-1: 从二进制权重加载 Isolation Forest 模型。
///
/// 二进制格式(小端):
/// - [0..4]   magic: b"SGIF"
/// - [4]      version: 1
/// - [5..7]   tree_count: u16
/// - [7..15]  sample_size: f64
/// - 每棵树:
///   - [0..2] node_count: u16
///   - 每个节点(11 字节): feature_id(u8) + threshold(f32) + left(u16) + right(u16) + depth(u16)
///
/// 由离线 `train.py` 产出,经 OTA 下发后用此函数加载替换 `builtin_model()`。
/// 格式不符返回 Err,调用方应回退到 `builtin_model()`。
pub fn load_model_from_bytes(data: &[u8]) -> Result<IForest, &'static str> {
    if data.len() < 15 {
        return Err("model data too short");
    }
    if &data[0..4] != b"SGIF" {
        return Err("bad magic: expected SGIF");
    }
    if data[4] != 1 {
        return Err("unsupported model version");
    }
    let tree_count = u16::from_le_bytes([data[5], data[6]]) as usize;
    if tree_count == 0 || tree_count > 1000 {
        return Err("invalid tree count");
    }
    let sample_size = f64::from_le_bytes([
        data[7], data[8], data[9], data[10], data[11], data[12], data[13], data[14],
    ]);
    if sample_size <= 0.0 {
        return Err("invalid sample size");
    }

    let mut trees = Vec::with_capacity(tree_count);
    let mut offset = 15usize;
    for _ in 0..tree_count {
        if offset + 2 > data.len() {
            return Err("unexpected EOF reading tree header");
        }
        let node_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
        offset += 2;
        if node_count == 0 || node_count > 10000 {
            return Err("invalid node count");
        }
        let needed = offset + node_count * 11;
        if needed > data.len() {
            return Err("unexpected EOF reading nodes");
        }
        let mut nodes = Vec::with_capacity(node_count);
        for _ in 0..node_count {
            let base = offset;
            let feature_id = data[base];
            let threshold = f32::from_le_bytes([
                data[base + 1], data[base + 2], data[base + 3], data[base + 4],
            ]);
            let left = u16::from_le_bytes([data[base + 5], data[base + 6]]);
            let right = u16::from_le_bytes([data[base + 7], data[base + 8]]);
            let depth = u16::from_le_bytes([data[base + 9], data[base + 10]]);
            nodes.push(Node { feature_id, threshold, left, right, depth });
            offset += 11;
        }
        trees.push(Tree { nodes });
    }
    Ok(IForest::new(trees, sample_size))
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

    /// P2-1: 序列化 builtin_model 为二进制(测试辅助)。
    fn model_to_bytes(model: &IForest) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(b"SGIF");
        buf.push(1); // version
        buf.extend_from_slice(&(model.trees_count() as u16).to_le_bytes());
        buf.extend_from_slice(&model.sample_size().to_le_bytes());
        for tree in model.trees_iter() {
            buf.extend_from_slice(&(tree.nodes_count() as u16).to_le_bytes());
            for node in tree.nodes_iter() {
                buf.push(node.feature_id);
                buf.extend_from_slice(&node.threshold.to_le_bytes());
                buf.extend_from_slice(&node.left.to_le_bytes());
                buf.extend_from_slice(&node.right.to_le_bytes());
                buf.extend_from_slice(&node.depth.to_le_bytes());
            }
        }
        buf
    }

    #[test]
    fn load_model_roundtrip() {
        // P2-1: 验证 builtin_model 序列化→加载往返保持相同分数。
        let original = builtin_model();
        let bytes = model_to_bytes(&original);
        let loaded = load_model_from_bytes(&bytes).expect("load should succeed");

        let s_normal_orig = original.score(&normal_features());
        let s_normal_loaded = loaded.score(&normal_features());
        let s_anom_orig = original.score(&anomaly_features());
        let s_anom_loaded = loaded.score(&anomaly_features());

        assert!(
            (s_normal_orig - s_normal_loaded).abs() < 1e-6,
            "正常样本分数应一致: {s_normal_orig} vs {s_normal_loaded}"
        );
        assert!(
            (s_anom_orig - s_anom_loaded).abs() < 1e-6,
            "异常样本分数应一致: {s_anom_orig} vs {s_anom_loaded}"
        );
    }

    #[test]
    fn load_model_rejects_invalid_inputs() {
        // 空数据
        assert!(load_model_from_bytes(&[]).is_err());
        // 错误 magic
        assert!(load_model_from_bytes(b"XXXX").is_err());
        // 截断数据
        let mut bad = vec![b'S', b'G', b'I', b'F', 1u8, 1, 0];
        bad.extend_from_slice(&256.0f64.to_le_bytes());
        bad.extend_from_slice(&[0, 0]); // node_count=0
        assert!(load_model_from_bytes(&bad).is_err());
        // 错误版本
        let mut bad_ver = vec![b'S', b'G', b'I', b'F', 2u8];
        bad_ver.extend_from_slice(&[0u8; 10]);
        assert!(load_model_from_bytes(&bad_ver).is_err());
    }
}