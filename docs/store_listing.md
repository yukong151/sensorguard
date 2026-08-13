# SensorGuard — 商店提审清单 (W11)

## 基本信息
- **应用名称**: SensorGuard — 传感器隐私守护
- **包名**: com.tabbit.sensorguard
- **分类**: 工具 → 安全/隐私
- **标签**: 隐私监测、传感器、反窃听、权限管理、安全
- **最低版本**: Android 10 (API 29)
- **目标版本**: Android 14 (API 34)
- **架构**: arm64-v8a
- **体积**: ~3.5 MB (v1.0.0)
- **构建变体**(W8/Final 新增):
  - `internal` 内测版:显示 App 归属/包名(仅内部测试,不发布商店)
  - `store` 商店版:隐藏一切包名/应用身份标识(上架唯一变体)

## 商店描述

### 简短描述(80 字)
传感器隐私监测工具。实时监控 App 对麦克风、摄像头、IMU 等传感器的调用,识别异常采样模式,引导系统隐私设置。全部本地处理,零网络权限。

### 详细描述(中文)
**SensorGuard 是一款轻量级传感器隐私监测工具,帮您发现哪些 App 在后台悄悄调用传感器。**

**核心功能:**
- **实时仪表盘**:一目了然查看系统健康度、加密存储状态、今日事件统计
- **事件时间线**:按时间顺序展示 App 对麦克风、摄像头、位置、IMU 的调用记录
- **异常检测引擎**:20 条硬规则 + 3 项统计检验(KS 检验、Burst 熵、昼夜 KL 散度),识别越界采样、隐蔽时段、旁路推断、指纹追踪四类违规
- **一键干预**:对麦克风/摄像头异常提供系统隐私设置深链引导
- **加密日志**:AES-256-GCM 加密存储,密钥由 Android Keystore 保护,一键擦除
- **完全离线**:主进程零网络权限,所有推理在端侧完成

**隐私承诺:**
- 不采集个人信息,不联网,不追踪
- 不经用户授权不记录任何数据
- 开源可审计,无广告、无统计 SDK

### Google Play 审核话术
> **Use case description**: Sensor & permission usage transparency tool. All processing on-device, no network.
> **Foreground service use case**: privacy_monitoring — continuous monitoring of sensor and permission usage for privacy protection.
> **Data collected**: None. All data processing is on-device. No personal data collected, transmitted, or shared.
> **Foreground service type**: Declared at runtime (API 34+ specialUse; no static declaration for Android 10-13 compatibility).

### 华为 AppGallery 审核话术
> **应用场景**: 传感器与权限使用透明度工具。所有处理在本地完成,无网络通信。
> **常驻服务说明**: 前台服务用途为"隐私监测"(privacy_monitoring),仅用于持续监控传感器调用,无其他用途。
> **数据收集声明**: 不收集任何个人信息,数据仅存于本地,不上传云端。
> **权限说明**: 仅申请前台服务、通知、高频传感器采样、开机自启;不申请窥探类权限(QUERY_ALL_PACKAGES 等)。

## 分类
| 项目 | 选择 |
|---|---|
| 分类 | 工具 → 安全与隐私 |
| 内容分级 | PEGI 3 / ESRB E |
| 年龄分级 | 3+ |
| 广告 | 无 |
| 应用内购买 | 无 |

## 截图素材建议
1. 仪表盘页(显示健康度绿色、事件统计)
2. 事件时间线(显示告警行)
3. 风险详情页(显示 MIC 干预引导)
4. 加密存储说明页(可选)

## 隐私政策 URL
- 建议托管: GitHub Pages 或应用内 WebView 展示
- 文件位置: `docs/privacy_policy.html`