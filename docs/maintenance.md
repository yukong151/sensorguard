# 维护与治理

本文件记录 SensorGuard 社区版(始于 v1.0.0-community)的维护机制、治理结构与路线图。

## 维护节奏

| 类别 | 承诺 |
|---|---|
| Issue 响应 | 每月至少响应一次(区分已确认/需讨论/暂缓) |
| 重大安全修复 | **48h 内响应**,优先级最高 |
| 常规 Bug 修复 | 随版本节奏合入(无固定发布周期,按需发 tag) |
| 依赖更新 | 每月检查 `docs/sbom.txt` 与上游 CVE 通告,过期高危依赖优先升级 |

## 开源治理

- **维护者**:`yukong151`(最终决策人,负责 release tag、CHANGELOG 版本、安全披露处理)
- **合并权限**:维护者拥有写权限;社区贡献者通过 PR 合入
- **合并原则**:
  - 必须通过 CI(Rust 测试 + clippy + Android 编译 + Semgrep/MobSF 门禁)
  - 规则/阈值变更必须附带回归测试
  - 探针/归因逻辑必须保持无 Shizuku 时静默降级
  - 不引入网络权限、不采集数据(社区版零网络底线)
- **标签体系**:`bug` / `enhancement` / `question` / `good first issue`

## 发布流程(后续版本)

1. 在 `master` 上完成变更并跑全量测试
2. 更新 `CHANGELOG.md`(Keep a Changelog 格式)
3. 打 tag:`v<major>.<minor>.<patch>`(语义化版本)
4. 推送双远程 + 创建 GitHub Release(附 CHANGELOG 要点)
5. 更新 `docs/sbom.txt` 与 README badges

## v1.1 路线图(公示)

> 方向性规划,不作为交付承诺。社区反馈优先。

### 性能优化

- **内存优化**:当前 RSS ~150-167MB,目标压到 ~40-60MB
  - 特征窗口降采样、环形缓冲复用、稀疏事件结构
  - 关键路径 54h 压测已无线性泄漏,优化重点在常量开销

### 检测能力

- **Isolation Forest 启用**:L4 层已有实现与评分,默认关闭;v1.1 评估 false positive 后启用
- **云端复核(opt-in)**:可选上传脱敏事件样本用于阈值校准(需用户明确授权,不默认开启;社区版仍默认零网络)

### 平台

- **鸿蒙移植**:Rust 核心可经 NAPI 复用(~85%),规划 `hvigor` 工程与权限模型适配(社区版优先)
- **x86 模拟器支持**:补 `x86_64-linux-android` target 便于模拟器开发

## 沟通渠道

- Issues:功能请求、Bug 报告、讨论(见 `.github/ISSUE_TEMPLATE/`)
- 安全漏洞:请勿公开;联系维护者或使用 GitHub 私有漏洞披露,48h 内响应