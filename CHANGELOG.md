# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-08-17

### 新增

- **实时仪表盘**:系统健康度、加密存储状态、今日事件统计
- **事件时间线**:麦克风/摄像头/位置/IMU 调用记录,支持 Room 历史分页加载与完整包名归因
- **异常检测引擎**:
  - 20 条硬规则(含 R112 侧信道滥用检测,带系统 uid 白名单)
  - 3 项统计检验:KS 检验、Burst 熵、昼夜 KL 散度
  - Lomb-Scargle 节律一致性、Isolation Forest 评分(v1.1 启用)
- **精确归因**:经 Shizuku 读取 `dumpsys sensorservice` / `dumpsys media.camera`,归因到真实 App 包名;无 Shizuku 时静默降级为"未知来源"
- **一键干预**:麦克风/摄像头异常提供系统隐私设置深链引导
- **加密日志**:AES-256-GCM 加密存储,DEK 由 Android Keystore(StrongBox 优先)保护,支持密钥轮换与过期销毁,一键擦除
- **完全离线**:主进程零网络权限,推理全部在端侧 Rust 完成
- **多模块架构**:Kotlin 应用层 + Rust 核心(core-rust),flatbuffers 序列化经 JNI 桥接

### 修复

- R112 规则将系统 uid(1000-9999)与 GMS(10213)误判为侧信道滥用的问题,新增 `UidGte`/`UidNotIn` 谓词并支持 OTA 数据驱动更新
- Android 14 前台服务类型(FGS specialUse)未在 manifest 声明导致的真机闪退
- 传感器基线环形缓冲满时覆盖最旧数据,消除 `E_RESOURCE` 刷屏
- 系统调用开关失效与事件点击无详情的时间线回归
- Shizuku 精确归因探针回调主线程 Room 崩溃

### 已知限制

- **内存占用**:RSS 稳态约 150-167MB(v1.0 以功能完整优先,未针对 40MB 预算优化);54h 连续压测无崩溃、无明显线性泄漏,优化排入 v1.1
- **Shizuku 依赖**:精确归因需要 Shizuku 授权;未授权时传感器/相机事件显示"未知来源"
- **零网络**:社区版完全离线,不采集、不联网,因此不具备云端规则更新能力(规则通过内置数据文件 OTA 式替换)
- **设备覆盖**:L1 统计检验为设备无关实现;T1 精确归因依赖 Shizuku 支持的设备
- **截屏防护 / root 检测**:未内置(传感器监控类工具,INFO 级建议,非安全关键)

## [0.x] - 开发里程碑

- `c8cbc1e` v1.0.0 GA:W1-W12 全部里程碑完成
- `0e93a6e` P0/P1/P2 开发阶段完成
- `96289e7`/`efe50e0` 包名迁移 `com.tabbit.sensorguard` → `com.yuexiao12.sensorguard`