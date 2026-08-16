---
name: Pull Request
about: 提交代码变更
title: ""
labels: ""
assignees: ''
---

**关联 Issue**
`Closes #<issue>`

**改动类型**
- [ ] Bug 修复
- [ ] 新功能
- [ ] 文档
- [ ] 其他

**改动说明**
简要描述改动内容与原因。

**验证**
- [ ] `cargo test`(core-rust)通过
- [ ] `cargo fmt --check` 通过
- [ ] `cargo clippy --all-targets -- -D warnings` 通过
- [ ] Android 编译成功(`assembleInternalDebug`)
- [ ] 无新增 Semgrep/MobSF 高危发现

**测试覆盖**
说明新增/修改的测试用例。

**补充**
其他说明。