# T2 链路实现记录 —— Step 1 + Step 2

> 对应 `T2代码审查与打磨清单.md` 的 P0-1 / P0-2。
> 状态：代码已落地；Rust 侧 68 个单测全绿、fbs golden 已重生成；Kotlin 侧因本机缺 Android SDK 未编译，需在用户侧 build 后真机回归。

## 根因（已修复）
- **P0-1**：原 `ShizukuProbe` 回调只调 `addSharedActivePair`，从不推 `OpEvent`。Rust `sg_tick` 仅对 `changed()`（需收到过 OpEvent）的窗口判定 → T2 发现的 IMU 对永远 `changed()==false`，零判定（"活着的死分支"）。
- **P0-2**：`samplingPeriodUs` 在 `GuardService` 回调处直接丢弃；`ActivePair`/`TickInput` 全链路无 rate 字段；引擎 `sample_rate_hz` 由事件间隔反推（Shizuku 轮询 60s 一次 → 误得 ~0.017 Hz）。

## 改动清单

### Step 1 — Shizuku 推 START/STOP/TICK 事件（修复 P0-1 + 停止客户端只增不删）
- `ShizukuProbe.kt`
  - 新增 `running` / `isRunning` 守卫，避免 `start()` 重复 `scheduleAtFixedRate`。
- `GuardService.kt`
  - 新增 `shizukuLast: ConcurrentHashMap<String, SensorClient>` 快照。
  - 新增 `onShizukuClients()`：每轮轮询与上次快照 diff ——
    - 消失的客户端 → 推 **STOP**（同时 `removeSharedActivePair`，修复只增不删）。
    - 新增 → 推 **START**（注册活跃组合 + 打 `changed`）。
    - 持续在列 → 推 **TICK**（刷新采样率 + 维持 `changed`，使每轮 batchTick 重新评估）。
  - 新增 `pushShizukuEvent()`：构造 `source="SHIZUKU"`、`tier=T2_ENHANCED` 的 `ProbeEvent` 并 `pushProbeEvent`，其中 **TICK 不落加密审计库**（仅刷新引擎，避免 60s 一次刷屏日志）。
  - `pushProbeEvent(ev, persist=true)`：新增 `persist` 参数；`when(phase){ START→add; STOP→remove; TICK→不动注册表 }`（原 `if(isStart) add else remove` 会让 TICK 误删活跃组合）。
  - 激活兜底：未授权时 `requestPermission()` 并以 5s/15s 轻量重试探活 `start()`（完整 Shizuku 权限监听器见 Step 3）。

### Step 2 — fbs / FFI / 窗口携带 `sampling_period_us`（修复 P0-2）
- `schemas/sensorguard.fbs`
  - `OpEvent` 表新增 `sampling_period_us: long;`（位于 `ctx` 之后，slot 6；0 = 未知）。
  - `build.rs` 经 `tools/flatc-25.12.19/flatc.exe` 重生成 `sensorguard_generated.rs`。
- `core-rust/src/event_window.rs`
  - `PairWindow` 新增 `sampling_period_us: i64` 字段与 `set_sampling_period_us()` / `sampling_period_us()`。
  - `sample_rate_hz()` 优先用物理值 `1e6 / sampling_period_us`，未知时回退原间隔反推。
  - `size_bytes()` 由 `+44` 调为 `+52`（内存预算仍满足 `MAX_PAIRS>=40`）。
- `core-rust/src/ffi/mod.rs`
  - `sg_push_op` 读取 `event.sampling_period_us()` 并写入窗口（>0 才写，非 Shizuku 来源不受影响）。
  - `make_op_event_full` 测试夹具设置 `20000`，用于重新生成 golden。
- Kotlin 契约同步：
  - `ProbeModels.kt`：`ProbeEvent` 加 `val samplingPeriodUs: Long = 0L`（默认 0，既有调用点免改）。
  - `FbSerde.kt`：`OpEventData` 加同名字段；`encodeOpEvent` 增 `SLOT_SAMPLING_PERIOD_US=6`、`startTable(7)`、`addScalarI64(...)`。
  - `GuardService.pushProbeEvent`：`OpEventData(..., ev.samplingPeriodUs)` 透传。
  - `FbSerdeTest.kt`：`FB_FIXTURE_OP_EVENT_FULL` 更新为重新生成字节（80 B），两处 `OpEventData` 构造加 `samplingPeriodUs=20000L` 并补 slot-6 解码断言。

## 验证
- Rust（host 构建，已通过）：`cargo test` → **68 passed**（含 `event_window` 内存预算、`ffi` 全部用例、`rules_loader` 20 条规则）。
- golden 重生成：`dump_fb_fixtures_hex` 输出新 `FB_FIXTURE_OP_EVENT_FULL = 1C00...2A36FE9C9717`（逐字符已比对一致）。
- Kotlin：**本环境缺 Android SDK，未能 `./gradlew` 编译/跑单测**。改动与 Rust golden 字节级对称（仅新增 slot 6，沿用既有 6 字段已验证机制），需在用户侧 `assembleDebug` 后回归。

## 真机回归清单（Moto XT2153 / Shizuku 已授权）
1. 安装并启动 App，确认 Shizuku 已对本 App 授权（adb 看 `logcat -s SG`：`ShizukuProbe started (T2 enhanced)`）。
2. 打开一个会持续采样 IMU 的应用（如指南针/计步），观察时间线应出现 `source=SHIZUKU` 的 START/TICK 事件，且 `evidenceTier` 升至 T2。
3. 停止该应用后，时间线应出现对应 STOP，`sharedPairs` 中该 (uid,op) 被移除（不再误报）。
4. 用 `SensorServiceParserTest` 已覆盖的真实 `dumpsys` 格式断言解析（gyro2=20000us, accel=200000us）确保采样率正确注入引擎。
5. 若 Shizuku 晚于 App 启动才授权，应在 5s/15s 重试后自动激活（见日志）。

## 待办（Step 3，未含在本次）
- Shizuku 完整权限/binder 监听器（替代 5s/15s 轮询探活）。
- `execDumpsys` 增加读取超时（当前 `readText()` 无超时，单线程挂死风险）。
- `build.gradle.kts` 硬依赖 `dev.rikka.shizuku:api:13.1.5` 与纯反射实现冲突（违背 <4MB 目标）：二选一——删除硬依赖或改正式 API。
- `DetailActivity` 把 T2 误标 T0 的 `opName` 展示修正。
