# KTV / SensorGuard 项目长期笔记

## 文档基准约定
- **开发文档以 `tabbit_边缘计算及传感器安全的轻量级APP总开发开发文档 (3).md` 为准**（带"总开发"前缀的根目录总文档，含 §1–§13，含 12 周里程碑）。
- 根目录另有一份 `边缘计算及传感器安全的轻量级APP开发文档 (1).md`，其内容已被 (3) 包含，**不再单独对照 (1)**。
- 进度/排期核对一律以 (3) 的 §13 里程碑为基准。

## 构建与真机环境（硬限制）
- agent 沙箱**无法构建 APK**：跨用户写 `C:\Users\ew\.gradle` 被拒 + Gradle 8.7 native dll 加载被拦 + cmd.exe 被 Bash 安全策略禁。必须用户本机 PowerShell 跑 `tools\build_apk.bat`（bat 绕开 PS 执行策略；脚本已带 `set -eu` 式校验 APK 真生成 + 落盘 `build_apk.log`）。
- 装机：`tools\install_apk.bat` 用 adb 绝对路径 + 设备 `ZY22DDK2FL` + `install -r`，绕过用户机 adb 不在 PATH。agent 自己用 PowerShell 调 adb 可（`input tap`/`screencap`/`pull` 均可用），但 `am start` 启动 `exported="false"` 的内部 Activity 需临时改 Manifest。
- JDK 用 `C:\Users\ew\jdk17`（Temurin 17），Gradle 8.7 已解包在用户 `.gradle`。

## 关键代码事实（避免重复踩坑）
- `GuardService.kt` 写入 map 必须用 `Pair(a, b)`，Kotlin **无** `(a, b)` 元组字面量（曾因此编译失败）。
- `BuildConfig.DEBUG` 门控：DEBUG 时间线/详情页显示「宿主App(包) › SDK内层包」，RELEASE 只显「某应用」（上架合规，绝不泄露身份）。
- `TimelineActivity`/`DetailActivity` 为 `exported="false"` 内部 Activity。
