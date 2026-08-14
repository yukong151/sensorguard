# SensorGuard — 传感器隐私守护

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

轻量级 **Android 传感器隐私监测** 工具。实时监控 App 对麦克风、摄像头、IMU 等传感器的调用,识别异常采样模式,引导系统隐私设置。**全部本地处理,零网络权限**。

## 功能

- **实时仪表盘**:系统健康度、加密存储状态、今日事件统计
- **事件时间线**:按时间顺序展示 App 对麦克风、摄像头、位置、IMU 的调用记录(内测版含精确 App 归属)
- **异常检测引擎**:20 条硬规则 + 3 项统计检验(KS 检验、Burst 熵、昼夜 KL 散度)+ Lomb-Scargle 节律一致性 + Isolation Forest(v1.1)
- **精确归因**(内测版):经 Shizuku 读取 `dumpsys sensorservice` / `dumpsys media.camera`,将传感器/相机调用精确到真实 App 包名
- **一键干预**:对麦克风/摄像头异常提供系统隐私设置深链引导
- **加密日志**:AES-256-GCM 加密存储,密钥由 Android Keystore 保护,一键擦除
- **完全离线**:主进程零网络权限,所有推理在端侧 Rust 完成

## 架构

```
┌─────────────────────────────────────────────┐
│ Android (Kotlin)                            │
│  Mic/Camera/Location 探针 + Shizuku 归因     │
│  Timeline / Detail UI / 加密存储 (Room)      │
└───────────────┬─────────────────────────────┘
                │ JNI (flatbuffers)
┌───────────────▼─────────────────────────────┐
│ Rust Core (core-rust)                       │
│  24h 滑动窗口 · KS/Burst熵/KL/Lomb · 规则引擎 │
│  事件环形缓冲 (SPSC ring) · Verdict 判定      │
└─────────────────────────────────────────────┘
```

- **core-rust/**:Rust 核心(异常检测引擎、环形缓冲、规则引擎、Isolation Forest)
- **app/**:Android 应用层(Kotlin,探针、UI、加密存储)
- **schemas/**:flatbuffers 事件/告警 schema

## 构建

```bash
# Rust 核心(arm64)
cd core-rust && cargo build --release --target aarch64-linux-android

# Android APK(两个变体)
./gradlew :app:assembleInternalDebug   # 内测版:显示 App 归属
./gradlew :app:assembleStoreDebug      # 商店版:隐藏 App 身份标识
```

环境要求:Android SDK、Android Studio JBR(Java 17)、Rust nightly + android target。

## 内测版精确归因(Shizuku)

传感器/相机事件精确到 App 包名依赖 Shizuku。激活与授权步骤见 [docs/SHIZUKU_WIRELESS_SETUP.md](docs/SHIZUKU_WIRELESS_SETUP.md)(无线调试方式,每次开机需重新激活)。

无 Shizuku 时静默降级:传感器/相机事件显示"未知来源",不影响其他功能。

## 开发文档

- `sensorguard_边缘计算及传感器安全的轻量级APP总开发开发文档 (3).md`:完整开发设计文档(威胁模型、算法、性能预算、合规)
- `docs/`:商店提审清单、隐私影响评估、开发进度回顾等

## 许可与致谢

- **许可证**:[Apache License 2.0](LICENSE)
- **算法原创性声明与参考致谢**:见 [NOTICE](NOTICE)

核心算法(KS 检验、Lomb-Scargle、Isolation Forest、KL/熵)均为基于公开数学公式的**原创实现**,不链接第三方统计库。设计灵感来自 Spearphone (NDSS'20)、AccelEve (SenSys'19)、EarSpy (2022) 等学术研究,以及 Access Dots、PilferShush Jammer、TrackerControl 等开源项目的工程思路(无代码复用)。

部分设计文档由 LLM 辅助生成,实现代码经人工编写与审核。

## 隐私与免责

SensorGuard 的告警/观察记录**仅供参考,不构成对任何应用的指控**。所有数据本地处理,不采集个人信息,不联网。
