# 贡献指南

感谢你对 SensorGuard 的关注!本指南帮助社区贡献者了解如何提交 issue、编写代码并参与维护。

## 社区版定位

- **面向安全研究者与开发者**,不上架应用商店
- 纯本地、零网络权限、无后端
- 告警/观察记录仅供参考,不构成对任何应用的指控

## 报告问题

1. 先搜索 [Issues](https://github.com/yukong151/sensorguard/issues) 是否已有相同报告
2. 使用 Bug 报告模板(见 `.github/ISSUE_TEMPLATE/bug_report.md`),尽量包含:
   - 设备型号、Android 版本
   - 是否启用 Shizuku
   - 复现步骤、期望行为与实际行为
   - 相关日志(`logcat -s SG`)

## 开发环境

- Android Studio(JBR Java 17)
- Rust nightly + `aarch64-linux-android` target
- Android SDK

```bash
# Rust 测试
cd core-rust && cargo test

# Rust 静态检查(CI 门禁)
cargo fmt --check && cargo clippy --all-targets -- -D warnings

# 构建 APK
./gradlew :app:assembleInternalDebug
```

## 提交规范

- 分支名:`fix/`、`feat/`、`docs/`、`chore/` 前缀
- 提交信息遵循 Conventional Commits(如 `fix(rules): ...`)
- **不要在提交中引入密钥、token 或任何敏感信息**
- 中文提交信息优先(与仓库历史一致),英文亦可

## 代码规范

### Rust(core-rust)

- 必须通过 `cargo fmt` 与 `cargo clippy --all-targets -- -D warnings`
- 所有规则/阈值变更必须附带回归测试
- 新增规则遵循现有 `rules.rs` / `rules_loader.rs` 数据驱动模式(规则文件可独立更新)

### Kotlin(app)

- 遵循 Android 官方 Kotlin 风格
- 探针/归因逻辑必须可降级:无 Shizuku 时静默降级,不崩溃
- 日志不得记录密钥、口令、IV 等敏感值

## 提交 PR

1. Fork 仓库并创建功能分支
2. 运行全部测试与静态检查,确保 CI 通过
3. 创建 PR,关联相关 issue,描述改动与验证方式
4. 维护者审核后合并;重大行为变更需讨论后再合并

## 安全漏洞

涉及安全的缺陷请**不要**公开在 issue 中。直接联系维护者(见仓库主页),或使用 GitHub 的私有漏洞披露功能。维护者承诺 48h 内响应重大安全问题。

## 行为准则

参与本项目即视为同意 [Code of Conduct](CODE_OF_CONDUCT.md)。