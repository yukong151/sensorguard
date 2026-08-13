//! W2~W3 (文档 §5.3):L3 四项统计检验的纯函数实现。
//! 全部无状态、无 IO,阈值由调用方传入(§7 阈值治理:阈值文件由 calibrate.py 产出,不可手改)。
//! 子模块:ks(两样本 KS 检验)、entropy(Burst 采样间隔 Shannon 熵)、kl(昼夜 KL 散度)、
//! lomb(Lomb-Scargle 周期图,节律一致性检验)。

pub mod entropy;
pub mod kl;
pub mod ks;
pub mod lomb;
