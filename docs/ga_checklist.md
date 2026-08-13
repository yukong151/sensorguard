# SensorGuard v1.0 GA 发布检查清单 (W12)

## 前置条件
- [x] W1~W11 全部交付验证
- [x] Rust 64/64 单测全绿,`cargo bench` 全部预算达标
- [x] Android 59/59 单测全绿,`assembleRelease` BUILD SUCCESSFUL(R8 混淆通过)
- [x] 真机验证:安装、启动、前台服务、tick 循环、三屏 UI、深链跳转均正常
- [x] ProGuard 规则:JNI/Room/枚举/FB 序列化保留
- [x] Semgrep 安全规则:10 条,零违规
- [x] 隐私政策:个保法/GDPR 合规,零网络/端侧加密声明
- [x] 商店提审素材:描述/分类/截图建议/审核话术

## 发布流程

### 1. 版本锁定
- [ ] 确认 `schemas/sensorguard.fbs` schema_version = 1
- [ ] 确认 `rules.v1.json` 冻结
- [ ] 确认 `thresholds.v1.json` 冻结
- [ ] 确认 `app/build.gradle.kts` 中 versionCode = 1, versionName = "1.0.0"

### 2. 代码冻结
- [ ] 初始化 git 仓库: `git init && git add -A && git commit -m "v1.0.0 GA"`
- [ ] 打标签: `git tag v1.0.0`

### 3. 最终构建
- [ ] `cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release`
- [ ] `./gradlew :app:assembleRelease` → 产出 `app/build/outputs/apk/release/app-release.apk`
- [ ] APK 签名验证: `jarsigner -verify -verbose -certs app-release.apk`
- [ ] APK 体积验证: ≤ 3.5 MB

### 4. 灰度发布 (1% × 3 天)
- [ ] Google Play Console: 发布到 Internal Testing Track (1% 用户)
- [ ] 监控 3 天:Crash-free ≥ 99.95%,误报率 ≤ 2 次/设备/周
- [ ] 无阻塞问题 → 升级到 Production Track

### 5. 全量发布
- [ ] Google Play Console: Production Track 100%
- [ ] 华为 AppGallery: 同步发布
- [ ] GitHub Releases: 发布 APK + 源码 tag

## 运维 Runbook

### Safe Mode 排查
1. 仪表盘显示"安全模式"红色 → 系统健康度进入 SAFE_MODE
2. 检查 `logcat -s SG:*` 中最近一行 `tick rc=...`
3. 排查方向:
   - `E_PANIC(-6)`: Rust panic → 检查最近 JNI 调用参数
   - `E_INTERNAL(-4)`: 逻辑错误 → 检查日志上下文
   - `E_RESOURCE(-5)`: 资源不足 → 检查内存使用
4. 尝试:停止监测 → 重启应用 → 重新启动监测
5. 3 次失败后进入 DEAD → 需手动重启应用

### 告警误报申诉
1. 用户点击"这是误报" → 记录 (uid, op, category) 组合
2. 该组合 30 天静音,日志仍保留

### 版本回滚
1. Google Play Console: 回滚到上一版本
2. 灰度异常:暂停灰度,回滚到稳定版本
3. 紧急修复:Hotfix 分支,跳过灰度直接全量