SensorGuard
版权所有 2026 yukong121 / yukong151

本产品包含 SensorGuard 项目团队开发的原创软件,基于 Apache License 2.0 许可。

-------------------------------------------------------------------------
算法原创性及归属声明
-------------------------------------------------------------------------

Rust 核心(core-rust/)中实现的统计与机器学习算法,均是基于公开数学公式的**原创实现**。
本项目未链接任何第三方统计/机器学习库。

以下为公开的标准方法,其在此处的实现是原创代码,并非对任何特定库或项目的复制:

  * Kolmogorov-Smirnov 两样本检验(core-rust/src/stats/ks.rs)
      - 经典非参数检验(Kolmogorov 1933; Smirnov 1939)。
  * Lomb-Scargle 周期图(core-rust/src/stats/lomb.rs)
      - 针对非均匀采样数据的经典谱分析方法(Lomb 1976; Scargle 1982)。
  * Kullback-Leibler 散度(core-rust/src/stats/kl.rs)
      - 信息论散度(Kullback & Leibler 1951)。
  * Shannon 熵(core-rust/src/stats/entropy.rs)
      - 经典信息熵(Shannon 1948)。
  * Isolation Forest(core-rust/src/iforest.rs)
      - 基于隔离树的异常检测(Liu, Ting, Zhou, "Isolation Forest", ICDM 2008)。
        推理引擎(扁平节点数组、INT8 量化权重、无递归遍历)为原创实现。

设计灵感与研究背景(在此致谢):

  * 关于基于加速度计/IMU 隐私攻击的学术论文,启发了威胁模型与检测思路:
      - Spearphone (NDSS 2020)
      - AccelEve (SenSys 2019)
      - EarSpy (2022)
  * 开源工程参考(商店文案与后台服务策略,未复制任何代码):
      - Access Dots
      - PilferShush Jammer
      - TrackerControl

本仓库未从上述项目中复制任何代码。

-------------------------------------------------------------------------
AI 生成内容声明
-------------------------------------------------------------------------

本仓库下部分设计文档由 LLM 工具作为研究助手起草,仅供参考,供审阅。
实现代码由人工编写与审核。详见仓库文档。
