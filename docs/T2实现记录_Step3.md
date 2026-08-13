# T2 链路打磨 · Step 3 实现记录

> 前置：Step 1+2 已让 Shizuku 推 START/STOP/TICK 事件并贯通精确采样率（`docs/T2实现记录_Step1+2.md`）。
> 本步聚焦「真机激活时序 + 健壮性」：权限/服务状态监听器、`dumpsys` 超时、T2 等级 UI 渲染、依赖取舍注释。

## 改动清单

### 1. `app/.../probe/ShizukuProbe.kt`（重写）
- **`isAvailable` 修正为"binder 存活 且 已授权"**：
  原实现仅 `pingBinder`，导致"Shizuku 已装但未授权"时 `evidenceTier()` 误判 T2。
  新增 `hasPermission()`（反射 `Shizuku.checkSelfPermission()` == 0）与 `isBinderAlive`。
- **反射注册三类监听器（晚授权/服务重启自动激活）**：
  - `BinderReceivedListener.onBinderReceived` → `maybeStart()`（Shizuku 启动/授权后自动拉起轮询）
  - `BinderDeadListener.onBinderDead` → `running=false`（Shizuku 停止时暂停探针）
  - `RequestPermissionResultListener.onRequestPermissionResult` → 匹配 `SHIZUKU_REQUEST_CODE` 且 `grant==0` 时 `maybeStart()`
  监听器用 `Proxy.newProxyInstance` 动态代理实现 Shizuku 内部接口，注册/反注册均 try-catch 非致命。
- **`execDumpsys` 加 15s 超时**：独立 `ioExecutor` 线程 `submit(Callable{ readText() })`，`future.get(15s)` 超时则 `destroy()` 进程并取消读取，抛 `IOException` 由 `refresh()` 捕获日志——避免 Shizuku 进程挂死卡住 scheduler 单线程。
- `start()` 现 = 注册监听器 + `maybeStart()`；`stop()` 反注册并关闭两个 executor。

### 2. `app/.../service/GuardService.kt`
- 创建 `shizukuProbe` 由原先 `if (probe.isAvailable) probe.start() else { 请求权限 + 5s/15s 重试 }`
  简化为 `ShizukuProbe { onShizukuClients(it) }.also { it.start() }`。
  晚激活完全交给探针内部监听器，删除脆弱的手工重试调度。

### 3. `app/.../ui/DetailActivity.kt`
- `formatVerdict` 的"等级"字段原只渲染 T1/T0，补充 `tierName()`：
  `TIER_T2_ENHANCED` → "T2(Shizuku)"，`TIER_T1_STANDARD` → "T1"，其余 → "T0"。
  修复 T2 告警在详情页被误标成 T0 的问题。

### 4. `app/build.gradle.kts`
- 为 `dev.rikka.shizuku:api:13.1.5` 依赖补充注释，说明"硬依赖 vs 文档 <4MB 可选插件目标"的取舍：
  纯 `compileOnly` 会导致运行时 `Class.forName` 失败、探针永不激活；彻底解法是拆 dynamic-feature 模块，留 Step 4 决策。

## 验证状态
- ⚠️ **Kotlin 未编译**：本环境缺 Android SDK，无法 `assembleDebug` / 跑单测。改动均为纯反射与线程安全改造，与既有 Step 1+2 字节级契约独立、互不冲突。
- ✅ **Rust 侧**：Step 1+2 已 `cargo test` 全绿（68 passed），本步未触碰 Rust，无回归。
- ✅ 静态核查：旧手工重试块已删除；`TIER_T2_ENHANCED=2` 常量存在；三类监听器接口名/签名与 Shizuku API 对齐。

## 需要你做的（真机回归）
1. 本地 `assembleDebug` 构建。
2. Moto XT2153 上：
   - **晚授权路径**：先启动 SensorGuard（Shizuku 未授权）→ 打开 Shizuku App 对 SensorGuard 授权 → 日志应出现 `ShizukuProbe started (T2 enhanced)`，无需重启 App。
   - **服务死亡路径**：运行中杀掉/停用 Shizuku → 日志 `Shizuku binder dead, T2 probe paused`；重新启用 Shizuku → 探针自动恢复。
   - **超时保护**：若 `dumpsys sensorservice` 异常挂起，15s 后应出现 `Shizuku dumpsys failed` 且调度线程不卡死（后续轮询仍正常）。
   - **T2 展示**：打开指南针/计步类应用 → 时间线出现 `source=SHIZUKU` 事件；点开告警详情页"等级"应显示 `T2(Shizuku)`。

## 未含（Step 4 建议）
- build.gradle 的"可选插件"真正落地 = dynamic-feature 模块拆分（架构决策，需评估体积/安装流程）。
- 其余已在 Step 1+2 完成；T2 链路打磨基本到位，建议下一步做真机全链路回归确认后转交付。
