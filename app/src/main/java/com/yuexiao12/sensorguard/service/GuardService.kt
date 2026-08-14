package com.yuexiao12.sensorguard.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yuexiao12.sensorguard.BuildConfig
import com.yuexiao12.sensorguard.R
import com.yuexiao12.sensorguard.crypto.CounterGuard
import com.yuexiao12.sensorguard.crypto.DekManager
import com.yuexiao12.sensorguard.crypto.EspCounterStore
import com.yuexiao12.sensorguard.crypto.KekProvider
import com.yuexiao12.sensorguard.crypto.SafeModeException
import com.yuexiao12.sensorguard.db.RoomEventSink
import com.yuexiao12.sensorguard.db.RoomKeychainStore
import com.yuexiao12.sensorguard.db.SgDb
import com.yuexiao12.sensorguard.jni.ActivePairData
import com.yuexiao12.sensorguard.jni.FbSerde
import com.yuexiao12.sensorguard.enums.SgEnum
import com.yuexiao12.sensorguard.jni.SgErrors
import com.yuexiao12.sensorguard.jni.SgNative
import com.yuexiao12.sensorguard.jni.SensorHealthReader
import com.yuexiao12.sensorguard.jni.VerdictBatchData
import com.yuexiao12.sensorguard.jni.VerdictEntryData
import com.yuexiao12.sensorguard.jni.OpEventData
import com.yuexiao12.sensorguard.jni.VerdictReader
import com.yuexiao12.sensorguard.logic.ActionRouter
import com.yuexiao12.sensorguard.logic.HealthLevel
import com.yuexiao12.sensorguard.logic.SystemHealth
import com.yuexiao12.sensorguard.probe.CameraServiceParser
import com.yuexiao12.sensorguard.probe.CameraProbe
import com.yuexiao12.sensorguard.probe.LocationProbe
import com.yuexiao12.sensorguard.probe.NetProbe
import com.yuexiao12.sensorguard.probe.BtScanProbe
import com.yuexiao12.sensorguard.probe.CtxProbe
import com.yuexiao12.sensorguard.probe.MicProbe
import com.yuexiao12.sensorguard.probe.ProbeEvent
import com.yuexiao12.sensorguard.probe.ProbeSink
import com.yuexiao12.sensorguard.probe.ShizukuProbe
import com.yuexiao12.sensorguard.probe.SensorBaselineProbe
import com.yuexiao12.sensorguard.probe.SensorServiceParser
import com.yuexiao12.sensorguard.probe.SensorOpProbe
import com.yuexiao12.sensorguard.store.EncryptedEventStore
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GuardService : Service() {

    private lateinit var scheduler: ScheduledExecutorService
    private val tickBuf = ByteArray(64 * 1024)

    /** Room 历史分页加载线程(仅读解密,与 batchTick 写路径隔离,不阻塞 sg-tick)。*/
    private lateinit var historyExecutor: ExecutorService

    /** 告警环形日志(内存,W8 UI 读取;W7 起迁移 Room 加密落库)。*/
    private val alertLog: ArrayDeque<VerdictEntryData> = ArrayDeque()

    /** W4 探针原始事件时间线缓冲(内存,MainActivity 每 2s 轮询读取)。*/
    private val eventLog: ArrayDeque<ProbeEvent> = ArrayDeque()

    /**
     * W7 (文档 §8.2):加密事件存储(Room + AES-256-GCM + DEK/KEK)。
     * onCreate 初始化;探针事件先入内存缓冲(UI),再经唯一性断言加密落库。
     */
    private lateinit var eventStore: EncryptedEventStore

    /** W7 (文档 §10):系统健康度 SAFE_MODE —— 唯一性断言违反/密文篡改后置位,停用加密落库。*/
    @Volatile private var safeMode = false

    /**
     * W8 (文档 §10):系统健康度状态机 —— JNI 错误同错累计 → DEGRADED / SAFE_MODE / DEAD。
     * SgErrors.check 上报错误;batchTick 按等级决定是否判定(SAFE_MODE 只透传落库不判定)。
     */
    private val health = SystemHealth()

    /** W8 (文档 §6):每日 09:00 聚合摘要已发送日期(防重复,格式 yyyy-MM-dd)。*/
    @Volatile private var lastSummaryDate: String? = null

    private var micProbe: MicProbe? = null
    private var cameraProbe: CameraProbe? = null
    // 内测版精确归因: AppOps startWatchingActive 监听 record_audio/camera op,
    // 回调携带精确 uid+包名,将 Mic/Camera 的 T0"未知来源"升级为 T1 精确归因。
    private var sensorOpProbe: SensorOpProbe? = null
    // P3 (文档 §5.1):位置探针 —— AppOps startWatchingActive 监听 FINE/COARSE_LOCATION
    private var locationProbe: LocationProbe? = null
    // P3 (文档 §2):蓝牙扫描威胁面探针 —— 经 Shizuku dumpsys 统计 discovery 频次
    private var btScanProbe: BtScanProbe? = null
    // P4-8 (文档 §2/§4 C4):网络流量统计探针(v1.0 仅统计,不出端)
    private var netProbe: NetProbe? = null

    // W12 (文档 §4 P4):Shizuku 精确归因探针(T2 增强,可选独立插件)
    private var shizukuProbe: ShizukuProbe? = null

    /** W5 (文档 §5.2):传感器基线探针(accel/gyro/mag/light/prox 自采 ~50Hz → sg_push_sensor)。*/
    private var sensorBaselineProbe: SensorBaselineProbe? = null

    /** W12/T2:上次 Shizuku 轮询快照(uid:op -> client),用于 diff 推 START/STOP/TICK。*/
    private val shizukuLast = ConcurrentHashMap<String, SensorServiceParser.SensorClient>()

    /** 内测版相机归因:上次 Shizuku 相机轮询快照(pkg:pid -> client),用于 diff 推 START/STOP。*/
    private val shizukuCameraLast = ConcurrentHashMap<String, CameraServiceParser.CameraClient>()

    /**
     * W12/T2: 包指纹(hex) -> (内层包名, uid) 反向映射,供 UI 在告警/事件上做归属展示。
     * P2-6: 启动时从 Room attribution 表加载,运行时新映射同步写入 Room(跨重启持久化)。
     */
    private val pkgHashInfo = ConcurrentHashMap<String, Pair<String, Int>>()

    /** P2-6: 归因映射 DAO,持久化 uid→包名映射到 Room。*/
    private var attributionDao: com.yuexiao12.sensorguard.db.AttributionDao? = null

    private val probeSink = object : ProbeSink {
        override fun onProbeEvent(ev: ProbeEvent) = pushProbeEvent(ev)
    }

    // W5 (文档 §6 告警投递策略)
    private val tickSeq = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentText(getString(R.string.notif_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        // foregroundServiceType 按 API 版本传参:API 34+ 用 SPECIAL_USE(manifest 不静态声明,
        // 避免 Android 10-13 安装解析失败);Lint ForegroundServiceType 为已知偏差,代码级抑制。
        @SuppressLint("ForegroundServiceType")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sg-tick").apply { isDaemon = true }
        }
        scheduler.scheduleWithFixedDelay(::batchTick, 5, 60, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay(::destroyExpiredDek, 1, 24, TimeUnit.HOURS)
        historyExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sg-history").apply { isDaemon = true }
        }

        // W7 (文档 §8.2):加密事件存储 —— KEK(AndroidKeyStore,StrongBox 优先)+ DEK 编排 +
        // keychain(Room)+ counter(AndroidKeyStore+AES-GCM)+ 唯一性守卫。
        val db = SgDb.get(this)
        eventStore = EncryptedEventStore(
            dekManager = DekManager(
                kek = KekProvider().getOrCreate(),
                keychain = RoomKeychainStore(db.keychainDao()),
                counters = EspCounterStore(this),
                guard = CounterGuard(),
            ),
            sink = RoomEventSink(db.eventDao()),
        )

        // P2-6: 初始化归因 DAO,从 Room 加载持久化的 uid→包名映射到内存。
        // 在探针启动前完成,确保首批事件即可命中已有映射(UI 归因不闪烁)。
        // Room 读走 sg-history 后台线程,避免主线程禁入警告。
        attributionDao = db.attributionDao()
        historyExecutor.execute {
            try {
                var n = 0
                for (row in attributionDao!!.all()) {
                    pkgHashInfo[row.pkgHashHex] = Pair(row.pkgName, row.uid); n++
                }
                if (n > 0) Log.i("SG", "attribution: loaded $n entries from Room")
            } catch (e: Exception) {
                Log.w("SG", "attribution: load from Room failed", e)
            }
        }

        // W4: 启动探针(公开 API 等效替代 AppOps 监听,见各探针注释)
        instance = this
        // W8 (文档 §10): 接线 Health 状态机 —— SgErrors.check 上报错误累计
        SgErrors.health = health
        CtxProbe.attach(this)
        micProbe = MicProbe(this).also { it.start(probeSink) }
        cameraProbe = CameraProbe(this).also { it.start(probeSink) }
        // 内测版精确归因: 麦克风/相机 op 的精确 uid+包名(回调自带 packageName),
        // 将 Mic/Camera 探针的 T0"未知来源"升级为 T1 精确归因。
        sensorOpProbe = SensorOpProbe(this).also { it.start(probeSink) }
        // P3 (文档 §5.1):位置探针(OPSTR_FINE/COARSE_LOCATION, T1 uid归因)
        locationProbe = LocationProbe(this).also { it.start(probeSink) }
        // P3 (文档 §2):蓝牙扫描探针,高频时推 OBSERVE 告警
        btScanProbe = BtScanProbe(this) { count -> pushBtScanAlert(count) }
            .also { it.start(probeSink) }
        // P4-8: 网络流量统计(仅日志审计,不告警)
        netProbe = NetProbe(this).also { it.start(probeSink) }
        // P2-2: 注入 NetProbe 引用,供 CtxProbe.snapshot 读取 netEgressAnomaly
        CtxProbe.setNetProbe(netProbe)
        // W12 (文档 §4 P4):Shizuku 精确归因 —— 启用以 ADB 权限读取 dumpsys sensorservice,
        // 获取精确 uid+采样率,覆盖 AppOps 探针无法归因的 IMU 类传感器盲区。
        // start() 内部注册权限/binder 监听器并请求授权: 晚授权(用户在 Shizuku 内手动授予)
        // 或 Shizuku 重启时,监听器回调会自动激活探针(Step 3 打磨)。
        // P1-4:先检测 Shizuku App 是否安装,未安装时跳过探针创建(零开销降级)。
if (ShizukuProbe.isShizukuInstalled(this)) {
            shizukuProbe = ShizukuProbe(
                { onShizukuClients(it) },
                { onShizukuCameraClients(it) },
            ).also { it.start() }
        } else {
            Log.i("SG", "Shizuku not installed, T2 probe disabled (graceful degradation)")
        }
        // W5 (文档 §5.2):传感器基线探针 —— accel/gyro/mag/light/prox 自采 ~50Hz,
        // 经 sg_push_sensor 注入 Rust RING,由 Batch Tick(sg_tick)消费做 HAL 竞争 KS 推断。
        sensorBaselineProbe = SensorBaselineProbe(this).also { it.start(probeSink) }
    }

    /**
     * W12/T2 (文档 §4 P4):Shizuku 精确归因 diff 处理 —— 将每次轮询的活跃传感器连接与上次
     * 快照对比,推 START(新增)/ STOP(消失)/ TICK(持续,刷新采样率并维持 changed 标记)。
     * 修复此前只 addSharedActivePair 不推 OpEvent → IMU 对永不 changed() → 零判定的死分支,
     * 以及停止客户端只增不删的误报源。仅 START/STOP 落加密审计库,TICK 仅刷新引擎(不膨胀日志)。
     */
    private fun onShizukuClients(clients: List<SensorServiceParser.SensorClient>) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val curr = clients.associateBy { "${it.uid}:${it.op}" }
        // STOP:上次有、本次没有
        for ((k, c) in shizukuLast) {
            if (k !in curr) pushShizukuEvent(c, SgEnum.PHASE_STOP, nowNs)
        }
        // START(新增)/ TICK(持续)
        for ((k, c) in curr) {
            val phase = if (shizukuLast.containsKey(k)) SgEnum.PHASE_TICK else SgEnum.PHASE_START
            pushShizukuEvent(c, phase, nowNs)
        }
        shizukuLast.clear()
        shizukuLast.putAll(curr)
    }

    private fun pushShizukuEvent(
        c: SensorServiceParser.SensorClient,
        phase: Int,
        nowNs: Long,
    ) {
        val ev = ProbeEvent(
            tsNs = nowNs,
            uid = c.uid,
            pkgName = c.packageName,
            pkgHash = pkgHashFromName(c.packageName),
            op = c.op,
            phase = phase,
            tier = SgEnum.TIER_T2_ENHANCED,
            source = "SHIZUKU",
            samplingPeriodUs = c.samplingPeriodUs,
            sensorName = c.sensorName,
        )
        // TICK 只刷新引擎窗口(采样率/changed),不落加密审计库
        pushProbeEvent(ev, persist = phase != SgEnum.PHASE_TICK)
    }

    /**
     * 内测版相机精确归因 diff 处理(Shizuku T2 通道)。
     * 每次轮询的 `dumpsys media.camera` Active Camera Clients 与上次对比,
     * 新增即推 START(精确包名)、消失即推 STOP,解决 CameraProbe(T0) 无法归因的盲区。
     * 相机 uid 无法直接获得(media.camera 只给包名+PID),uid 用 -1 保持与 T0 一致,
     * 但 pkgName 精确 -> UI 归因显示真实包名。
     */
    private fun onShizukuCameraClients(clients: List<CameraServiceParser.CameraClient>) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val curr = clients.associateBy { "${it.packageName}:${it.pid}" }
        // STOP:上次有、本次没有
        for ((k, c) in shizukuCameraLast) {
            if (k !in curr) {
                pushProbeEvent(
                    ProbeEvent(
                        tsNs = nowNs,
                        uid = -1,
                        pkgName = c.packageName,
                        pkgHash = pkgHashFromName(c.packageName),
                        op = SgEnum.OP_CAMERA,
                        phase = SgEnum.PHASE_STOP,
                        tier = SgEnum.TIER_T2_ENHANCED,
                        source = "CAMERA-SHIZUKU",
                    )
                )
            }
        }
        // START:新增
        for ((k, c) in curr) {
            if (!shizukuCameraLast.containsKey(k)) {
                pushProbeEvent(
                    ProbeEvent(
                        tsNs = nowNs,
                        uid = -1,
                        pkgName = c.packageName,
                        pkgHash = pkgHashFromName(c.packageName),
                        op = SgEnum.OP_CAMERA,
                        phase = SgEnum.PHASE_START,
                        tier = SgEnum.TIER_T2_ENHANCED,
                        source = "CAMERA-SHIZUKU",
                    )
                )
            }
        }
        shizukuCameraLast.clear()
        shizukuCameraLast.putAll(curr)
    }

    /**
     * W4: 探针事件 -> 时间线缓冲 + FlatBuffers OpEvent -> sg_push_op + 活跃组合注册表。
     * 时间基为 wall-clock ns(与 batchTick 同一时钟源);START 注册组合、STOP 注销,
     * 保证 TickInput.active_pairs 反映当前真实活跃状态。CtxTag 为 CtxProbe 诚实快照
     * (W4 无 UsageStats 权限,fg_state 统一 FG,见 CtxProbe 注释)。
     */
    private fun pushProbeEvent(ev: ProbeEvent, persist: Boolean = true) {
        synchronized(eventLog) {
            eventLog.addLast(ev)
            while (eventLog.size > EVENT_LOG_CAP) eventLog.removeFirst()
        }
        // W12/T2: 维护 包指纹 -> (内层包名, uid) 反向映射,供 UI 归因展示。
        // P2-6: 新映射同步写入 Room(跨重启持久化);已存在则 IGNORE 跳过。
        val hex = pkgHex(ev.pkgHash)
        // 首次出现才需写归因表;内存映射同步更新(UI 即时可用),Room 落库在后台执行。
        val needAttrInsert = !pkgHashInfo.containsKey(hex)
        if (needAttrInsert) pkgHashInfo[hex] = Pair(ev.pkgName ?: "", ev.uid)
        val opEv = OpEventData(
            ev.tsNs, ev.uid, ev.pkgHash, ev.op, ev.phase,
            CtxProbe.snapshot(ev.uid, ev.pkgName, ev.op), ev.samplingPeriodUs,
        )
        val rc = runCatching { SgNative.sgPushOp(FbSerde.encodeOpEvent(opEv)) }
            .getOrDefault(SgErrors.E_PANIC)
        // W8 (文档 §10): JNI 失败经 SgErrors.check 上报 Health 状态机(同错累计 / panic 直接 SAFE_MODE)
        if (rc < 0) SgErrors.check("sgPushOp", rc)
        // W12/T2:START 注册活跃组合、STOP 注销;TICK(持续采样)仅刷新引擎窗口,不改动组合注册表
        when (ev.phase) {
            SgEnum.PHASE_START -> addSharedActivePair(ev.uid, ev.op, ev.pkgHash)
            SgEnum.PHASE_STOP -> removeSharedActivePair(ev.uid, ev.op)
        }
        if (persist) {
            // Room 访问(归因持久化 + 加密落库)统一走 sg-history 单线程,避免探针回调
            // (可能位于主线程)触碰 Room 主线程禁入而 crash。单线程执行器保证落库顺序。
            val attrHex = hex
            val attrPkg = ev.pkgName
            val attrUid = ev.uid
            val mustInsertAttr = needAttrInsert
            historyExecutor.execute {
                if (mustInsertAttr) {
                    try {
                        attributionDao?.insertIfAbsent(
                            com.yuexiao12.sensorguard.db.AttributionEntity(
                                pkgHashHex = attrHex,
                                pkgName = attrPkg ?: "",
                                uid = attrUid,
                                firstSeenMs = System.currentTimeMillis(),
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("SG", "attribution: persist failed for $attrHex", e)
                    }
                }
                persistEncrypted(ev)
            }
        }
    }

    /**
     * W7 (文档 §8.2):探针事件加密落库 (key_id,counter) 唯一断言 + AES-256-GCM。
     * 断言违反/密文异常 → SafeMode(文档 §10),此后停止加密落库(内存时间线不受影响)。
     */
    private fun persistEncrypted(ev: ProbeEvent) {
        if (safeMode) return
        try {
            eventStore.saveEvent(ev)
        } catch (e: SafeModeException) {
            safeMode = true
            Log.e("SG", "SafeMode: 加密落库中止(${e.message})")
        }
    }

    /** W7 (文档 §8.2):DEK 生命周期清理 —— retired 超 90 天的包裹密钥销毁。*/
    private fun destroyExpiredDek() {
        try {
            eventStore.destroyExpired()
        } catch (e: Exception) {
            Log.w("SG", "destroyExpiredDek failed", e)
        }
    }

    /**
     * W5: L3 闭环 — 组装 TickInput -> sgTick -> 解析 VerdictBatch -> 告警/日志。
     * 性能预算(文档 §9):Batch Tick L3 ≤ 4ms / 次;编码+解码+解析均应远低于该值。
     * 时间基: wall-clock ns(System.currentTimeMillis*1e6),与 Rust 侧 24h 窗口切片使用
     * 同一时钟源(传感器 ts_ns 由 W4 探针按同一基注入)。
     *
     * W8 (文档 §10):HEALTH 门控 —— SAFE_MODE/DEAD 只透传落库不判定(返回前仍走
     * 摘要侧支),并仅在自愈窗口执行一次探针判定;DEGRADED 仅标记 degraded 不断判定。
     */
    private fun batchTick() {
        maybeDailySummary()
        // P3 (文档 §2):蓝牙扫描频次检测 —— 经 Shizuku dumpsys bluetooth_manager
        checkBtScan()
        // P4-8: 网络流量增量统计(v1.0 仅审计日志)
        netProbe?.tick()
        val level = health.level()
        if (level == HealthLevel.SAFE_MODE || level == HealthLevel.DEAD) {
            maybeSelfHeal()
            return // 只透传落库不判定(探针事件落库由 pushProbeEvent 独立完成)
        }
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val tickId = tickSeq.incrementAndGet()

        val pairs = sharedPairs.values.toList()
        val input = try {
            FbSerde.encodeTickInput(tickId, nowNs, evidenceTier(), pairs)
        } catch (e: IllegalArgumentException) {
            Log.w("SG", "encodeTickInput failed tick=$tickId", e)
            return
        }

        val rc = runCatching { SgNative.sgTick(input, tickBuf) }
            .getOrDefault(SgErrors.E_PANIC)
        if (rc < 0) {
            // W8 (文档 §10): JNI 失败经 SgErrors.check 上报 Health 状态机
            SgErrors.check("sgTick", rc)
            return
        }
        if (rc == 0) {
            health.onSuccess()
            // W5 (文档 §5.2):即使无 verdict,仍需消费 RING 防止溢出并更新基线
            checkSensorBaseline()
            return
        }

        val batch = runCatching { VerdictReader.decode(tickBuf.copyOf(rc)) }
            .getOrNull()
        if (batch == null) {
            Log.w("SG", "VerdictBatch decode failed @ tick=$tickId")
            return
        }
        health.onSuccess()
        Log.i("SG", "tick=$tickId rc=$rc verdicts=${batch.verdicts.size} " +
            "wall=[${batch.wallStartNs}..${batch.wallEndNs}]")
        handleVerdicts(batch)
        // W5 (文档 §5.2):读取传感器基线健康信号,异常上升沿推 OBSERVE 进时间线。
        checkSensorBaseline()
    }

    /**
     * W5 (文档 §5.2):读取传感器基线健康信号(sg_sensor_health)。
     * 仅对 accel/gyro 的 anomaly 上升沿推一条 OBSERVE(FINGERPRINT, T0)进入时间线,
     * 表示"检测到第三方高频 IMU 采样(未知来源)"。每 60s 仅上升沿推送,避免重复刷屏。
     * 无 Shizuku 时只能标"存在未知采样方",归因到具体 uid 由 Shizuku 探针负责(§4 P4)。
     */
    private val sensorAnomalyKinds = ConcurrentHashMap<Int, Boolean>()
    private fun checkSensorBaseline() {
        val buf = ByteArray(256)
        val rc = runCatching { SgNative.sgSensorHealth(buf) }.getOrDefault(SgErrors.E_PANIC)
        if (rc < 0) {
            SgErrors.check("sgSensorHealth", rc)
            return
        }
        val items = runCatching { SensorHealthReader.decode(buf, rc) }.getOrNull() ?: return
        for (h in items) {
            val wasAnom = sensorAnomalyKinds[h.kind] ?: false
            // 上升沿:IMU 类(accel/gyro)推 OBSERVE 进入时间线;light/prox 仅记录不刷屏
            if (h.anomaly && !wasAnom &&
                (h.kind == SgEnum.OP_ACCEL || h.kind == SgEnum.OP_GYRO)
            ) {
                pushBaselineAlert(h)
            }
            sensorAnomalyKinds[h.kind] = h.anomaly
        }
    }

    /** P3 (文档 §2):蓝牙扫描频次检测 —— Shizuku 可用时读 bluetooth_manager dump,
     *  由 BtScanProbe 统计窗口内 discovery 翻转次数;高频时回调推 OBSERVE 告警。
     *  无 Shizuku 时静默降级(不假装检测)。 */
    private fun checkBtScan() {
        val probe = btScanProbe ?: return
        val sp = shizukuProbe ?: return
        val dump = sp.execBluetoothManager()
        probe.feedDumpsys(dump.ifBlank { null })
        // 窗口结算(每 60s 一次):高频时 onHighFreq 回调
        probe.resetWindow()
    }

    /** P3 (文档 §2/§5.5):蓝牙扫描高频告警 —— OBSERVE 级,含卸载引导入口(需包名)。*/
    private fun pushBtScanAlert(count: Int) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val v = VerdictEntryData(
            kind = SgEnum.VERDICT_OBSERVE,
            category = SgEnum.CAT_SIDE_CHANNEL,
            severity = 55,
            sCtx = 0f,
            ruleId = 140, // P3 蓝牙扫描高频(本窗口 discovery 翻转次数)
            top3 = emptyList(),
            windowStartNs = nowNs - 60_000_000_000L,
            windowEndNs = nowNs,
            evidenceTier = SgEnum.TIER_T1_STANDARD,
            pkgHash = ByteArray(12), // 蓝牙扫描无 pkgHash 归因(OBSERVE 级,UI 提供卸载引导)
            op = SgEnum.OP_BT_SCAN,
            degraded = false,
        )
        pushAlert(v)
        Log.i("SG", "bt scan high-freq: $count discoveries in window (OBSERVE)")
    }

    /** W5 (文档 §5.2):第三方高频 IMU 采样(T0 未知来源)的 OBSERVE 告警,进入时间线 UI。*/
    private fun pushBaselineAlert(h: SensorHealthReader.SensorHealthData) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val v = VerdictEntryData(
            kind = SgEnum.VERDICT_OBSERVE,
            category = SgEnum.CAT_FINGERPRINT,
            severity = 70,
            sCtx = 0f,
            ruleId = 130, // W5 自采基线 HAL 竞争推断(第三方高频 IMU 采样,T0 未知来源)
            top3 = emptyList(),
            windowStartNs = nowNs - 60_000_000_000L,
            windowEndNs = nowNs,
            evidenceTier = SgEnum.TIER_T0_BASIC,
            pkgHash = ByteArray(12), // 未知来源(无 Shizuku 无法归因 uid)
            op = h.kind,
            degraded = false,
        )
        pushAlert(v)
        Log.i("SG", "sensor baseline anomaly kind=${h.kind} ks_d=${h.ksD} " +
            "hz=${h.sampleHz} (T0 未知采样方)")
    }

    /**
     * W8 (文档 §10):SAFE_MODE 自愈 —— 仅当 [SystemHealth.shouldAttemptHeal] 指示
     * 到点(10min→30min→2h 退避)时,执行一次空 tick 探针判定;成功即恢复 OK。
     * 累计 [SystemHealth.MAX_HEAL_FAILURES] 次失败由状态机置 DEAD(等用户重启)。
     */
    private fun maybeSelfHeal() {
        if (!health.shouldAttemptHeal()) return
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val input = try {
            FbSerde.encodeTickInput(tickSeq.incrementAndGet(), nowNs, evidenceTier(), emptyList())
        } catch (e: IllegalArgumentException) {
            health.reportHealResult(false)
            return
        }
        val rc = runCatching { SgNative.sgTick(input, tickBuf) }
            .getOrDefault(SgErrors.E_PANIC)
        health.reportHealResult(rc >= 0)
        if (rc < 0) Log.w("SG", "self-heal probe rc=$rc")
    }

    /**
     * W8 (文档 §6):每日 09:00 一条聚合摘要通知(IMPORTANCE_MIN 渠道)。
     * 由 batchTick(60s 周期)驱动;仅当日 09:00~09:01 窗口触发一次(防重复),
     * 统计今日探针事件 / 告警 / CRITICAL(内存缓冲,诚实的会话内聚合)。
     */
    private fun maybeDailySummary() {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) != SUMMARY_HOUR) return
        if (cal.get(java.util.Calendar.MINUTE) > 1) return
        val today = String.format(java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
        if (today == lastSummaryDate) return
        lastSummaryDate = today
        val startOfDayNs = run {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis * 1_000_000L
        }
        val events = synchronized(eventLog) { eventLog.count { it.tsNs >= startOfDayNs } }
        val alerts = synchronized(alertLog) { alertLog.count { it.windowEndNs >= startOfDayNs } }
        val critical = synchronized(alertLog) {
            alertLog.count { it.windowEndNs >= startOfDayNs && it.severity >= SEVERITY_CRITICAL }
        }
        val text = getString(R.string.notif_summary_text, events, alerts, critical)
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.notif_summary_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_SUMMARY_ID, notif)
        Log.i("SG", "daily summary: events=$events alerts=$alerts critical=$critical")
    }

    // P0-1:已知"摇一摇/开屏摇 + 广告 SDK"包名子串模式。命中且为 ACCEL/GYRO 时,
    // 把 Rust 引擎的 OBSERVE(侧信道提示)升级为 ALERT,让真滥用在 UI 显式告警。
    private val ABUSE_SDK_PATTERNS = listOf(
        "com.zhihu.android.launch.view.shake", // 知乎摇一摇
        "com.youku.xadsdk.ui.shake",           // 优酷开屏摇一摇
        "com.pgl.ssdk",                        // Pangle 广告 SDK
        "com.facebook.ads",                    // Meta Audience Network
        "com.applovin",                        // AppLovin
        "com.bytedance.sdk.openadsdk",         // 穿山甲
        "com.qq.e.ads",                        // 优量汇
    )

    private fun handleVerdicts(batch: VerdictBatchData) {
        for (v in batch.verdicts) {
            // P0-2:Kotlin 侧二次过滤 —— 即使 Rust 引擎已通过 decl_purpose_not_in 谓词
            // 排除了 FITNESS/NAVIGATION,此处再做一层防御性检查:
            // 若 OBSERVE 级别且目标 App 声明了合法传感器用途,则抑制(不进告警日志)。
            if (v.kind == SgEnum.VERDICT_OBSERVE && shouldSuppressObserve(v)) {
                Log.d("SG", "suppressed OBSERVE rule=${v.ruleId} op=${v.op} " +
                    "pkg=${pkgHex(v.pkgHash).take(8)} (legitimate purpose)")
                continue
            }
            val upgraded = upgradeAbuseVerdict(v)
            pushAlert(upgraded)
            if (upgraded.severity >= SEVERITY_CRITICAL) {
                notifyCritical(upgraded)
            }
        }
    }

    /**
     * P0-2:判定 OBSERVE 级别告警是否应被抑制。
     *
     * 防御性二次过滤:Rust 引擎已通过 R112 的 decl_purpose_not_in 谓词排除
     * FITNESS(2)/NAVIGATION(3) 用途的 App。但若 DeclPurposeClassifier
     * 分类失败(返回 UNKNOWN=0,如 App 无 declared permissions 或 PackageManager
     * 查询失败),Rust 侧仍可能触发 OBSERVE。此处通过包名模式匹配做兜底。
     */
    private fun shouldSuppressObserve(v: VerdictEntryData): Boolean {
        // 仅对 ACCEL/GYRO 的侧信道规则抑制(R112)
        if (v.op != SgEnum.OP_ACCEL && v.op != SgEnum.OP_GYRO) return false
        if (v.category != SgEnum.CAT_SIDE_CHANNEL) return false
        val info = attributionFor(pkgHex(v.pkgHash)) ?: return false
        val pkg = info.first
        // 已知合法运动/健康/导航 App 包名模式(DeclPurposeClassifier 兜底)
        return LEGIT_SENSOR_APP_PATTERNS.any { pkg.contains(it) }
    }

    /** P0-2:已知合法使用 ACCEL/GYRO 的 App 包名子串模式(DeclPurposeClassifier 分类失败时的兜底)。*/
    private val LEGIT_SENSOR_APP_PATTERNS = listOf(
        // 运动/健康
        "com.strava",                           // Strava
        "com.nike.plus",                        // Nike Run Club
        "com.mapmyrun",                         // MapMyRun
        "com.fitbit",                           // Fitbit
        "com.xiaomi.hm.health",                 // 小米运动健康
        "com.ct.client",                        // 悦跑圈
        "com.keep",                             // Keep
        // 导航
        "com.autonavi.amapauto",                // 高德地图
        "com.baidu.BaiduMap",                   // 百度地图
        "com.google.android.apps.maps",         // Google Maps
    )

    /** P0-1:摇一摇/广告 SDK 滥用升级——ACCEL/GYRO 的 OBSERVE 提升为 ALERT(SIDE_CHANNEL)。*/
    private fun upgradeAbuseVerdict(v: VerdictEntryData): VerdictEntryData {
        if (v.kind == SgEnum.VERDICT_ALERT) return v
        if (v.op != SgEnum.OP_ACCEL && v.op != SgEnum.OP_GYRO) return v
        val info = attributionFor(pkgHex(v.pkgHash)) ?: return v
        val pkg = info.first
        if (ABUSE_SDK_PATTERNS.none { pkg.contains(it) }) return v
        return v.copy(
            kind = SgEnum.VERDICT_ALERT,
            category = SgEnum.CAT_SIDE_CHANNEL,
            severity = if (v.severity >= 80) v.severity else 80,
            ruleId = 112,
        )
    }

    private fun pushAlert(v: VerdictEntryData) {
        synchronized(alertLog) {
            alertLog.addLast(v)
            while (alertLog.size > ALERT_LOG_CAP) alertLog.removeFirst()
        }
        Log.w("SG", "verdict kind=${v.kind} cat=${v.category} sev=${v.severity} " +
            "rule=${v.ruleId} op=${v.op} degraded=${v.degraded} " +
            "pkg=${pkgHex(v.pkgHash)} window=[${v.windowStartNs}..${v.windowEndNs}]")
    }

    /** 文档 §6: severity ≥ 90 的 CRITICAL 实时弹通知,其余只落日志。*/
    private fun notifyCritical(v: VerdictEntryData) {
        val manager = getSystemService(NotificationManager::class.java)
        val title = getString(R.string.notif_critical_title)
        val text = "rule=${v.ruleId} severity=${v.severity} op=${v.op} " +
            "pkg=${pkgHex(v.pkgHash).take(8)}"
        val notif = NotificationCompat.Builder(this, CRIT_CH_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_CRIT_BASE + v.ruleId, notif)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_MIN)
            )
        }
        if (nm.getNotificationChannel(CRIT_CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CRIT_CH_ID, getString(R.string.notif_critical_channel),
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /**
     * W4 探针(AppOps/Camera Probe)通过 companion 注册/注销活跃 (uid, op) 组合,
     * 作为 TickInput.active_pairs 输入(线程安全,key = "uid:op")。
     */
    fun recentAlerts(): List<VerdictEntryData> = synchronized(alertLog) {
        alertLog.reversed().toList()
    }

    /** W4: 时间线 UI 读取探针事件(新→旧)。*/
    fun recentEvents(): List<ProbeEvent> = synchronized(eventLog) {
        eventLog.reversed().toList()
    }

    /**
     * 时间线滚动加载:从 Room 加密历史分页解密回读 tsNs < beforeTsNs 的更早事件。
     * 在后台线程执行(历史 executor),结果经 onResult 回调回主线程;避免阻塞 UI/写路径。
     * 内存 512 条(eventLog)之上追加的是 Room 全量历史,解决"512 条后数据停更"。
     */
    fun loadMoreEvents(beforeTsNs: Long, onResult: (List<ProbeEvent>) -> Unit) {
        historyExecutor.execute {
            val events = try {
                eventStore.loadBefore(beforeTsNs, HISTORY_PAGE)
            } catch (e: Exception) {
                Log.w("SG", "loadMoreEvents failed: ${e.message}")
                emptyList()
            }
            // 解密读在后台线程;回调回主线程,避免 UI 线程触碰 Room
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(events) }
        }
    }

    /** 时间线滚动加载:最早可见事件的 tsNs(用于分页游标),无则返回 0。*/
    fun earliestEventTs(): Long = synchronized(eventLog) {
        eventLog.firstOrNull()?.tsNs ?: 0L
    }

    /** W12/T2: 供 UI 查询某包指纹对应的(内层包名, uid),用于告警/事件归因展示;查不到返回 null。*/
    fun attributionFor(hex: String): Pair<String, Int>? {
        pkgHashInfo[hex]?.let { return it }
        // 兜底:内存映射缺失时查 Room(wipe 后重载 / 偶发未建映射),避免告警行丢失应用名
        val row = try { attributionDao?.get(hex) } catch (_: Exception) { null }
        return row?.let { Pair(it.pkgName, it.uid) }
    }

    /**
     * W8 (Debug 演示):注入一条演示告警(仅 debug 构建可调用,release 下 BuildConfig.DEBUG=false 直接 no-op)。
     * 用于人工验证 A2 时间线告警行 → A3 详情/引导页的完整链路。走与真实判定相同的
     * pushAlert + notifyCritical(severity≥90)路径,不 bypass 告警投递逻辑。
     */
    fun injectDemoAlert() {
        if (!BuildConfig.DEBUG) return
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val demo = VerdictEntryData(
            kind = SgEnum.VERDICT_ALERT,
            category = SgEnum.CAT_STEALTH_HOURS,
            severity = 92,
            sCtx = 0f,
            ruleId = 3,
            top3 = emptyList(),
            windowStartNs = nowNs - 3_600_000_000_000L, // 1h 窗口
            windowEndNs = nowNs,
            evidenceTier = SgEnum.TIER_T1_STANDARD,
            pkgHash = DEMO_PKG_HASH,
            op = SgEnum.OP_RECORD_AUDIO,
            degraded = false,
        )
        pushAlert(demo)
        notifyCritical(demo)
        Log.i("SG", "demo alert injected (debug only)")
    }

    /** W9 (文档 §4 P4):证据分级 —— Shizuku 活跃=T2 增强;Android 12+=T1;Android 10~11=T0。
     * 打包进 TickInput.tier 供 Rust 规则引擎按 min_tier 过滤。*/
    private fun evidenceTier(): Int = when {
        shizukuProbe?.isAvailable == true -> SgEnum.TIER_T2_ENHANCED
        Build.VERSION.SDK_INT >= 31 -> SgEnum.TIER_T1_STANDARD
        else -> SgEnum.TIER_T0_BASIC
    }

    /** W12 (文档 §4 P4):Shizuku 精确归因 —— 将包名哈希为 12B 指纹(与探针 pkgHash 同构)。*/
    private fun pkgHashFromName(pkg: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(pkg.toByteArray()).copyOf(12)
    }

    /** W7 (文档 §8.2):遗忘权 —— 销毁全部 DEK 包裹密钥 + 清空密文,历史记录不可再读。
     * 由 UI("擦除全部日志")在后台线程调用。Safe Mode 下同样可执行(DekManager.wipeAll 幂等)。
     * P2-6: 同时清空 attribution 归因映射(内存 + Room)。
     */
    fun wipeAllLogs() {
        runCatching { eventStore.wipeAll() }
            .onFailure { Log.e("SG", "wipeAllLogs failed", it) }
        // P2-6: 清空归因映射
        pkgHashInfo.clear()
        runCatching { attributionDao?.clearAll() }
            .onFailure { Log.w("SG", "attribution clearAll failed", it) }
    }

    /** W7 (文档 §10):系统健康度 SAFE_MODE 查询(UI 展示)。*/
    fun inSafeMode(): Boolean = safeMode

    /** W8 (文档 §10):系统健康度等级查询(UI 仪表盘展示)。*/
    fun healthLevel(): HealthLevel = health.level()

    /** W12/T2: 由 MainActivity(Activity 上下文)调用,向 Shizuku 发起授权请求。
     *  必须传入 Activity,否则系统授权框弹不出来(后台 Service 调用会被静默丢弃)。*/
    fun requestShizukuPermission(activity: android.app.Activity) = shizukuProbe?.requestPermission(activity)

    /** W8 (文档 §6):干预路由 —— op → 深链方案(UI 风险详情/引导页使用)。*/
    fun interventionFor(v: VerdictEntryData): ActionRouter.Intervention? =
        ActionRouter.resolve(v.op)

    override fun onDestroy() {
        micProbe?.stop()
        cameraProbe?.stop()
        sensorOpProbe?.stop()
        locationProbe?.stop()
        btScanProbe?.stop()
        netProbe?.stop()
        shizukuProbe?.stop()
        sensorBaselineProbe?.stop()
        micProbe = null
        cameraProbe = null
        sensorOpProbe = null
        shizukuProbe = null
        SgErrors.health = null
        instance = null
        scheduler.shutdownNow()
        historyExecutor.shutdownNow()
        SgNative.sgShutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH_ID = "sg_privacy_min"
        private const val CRIT_CH_ID = "sg_critical"
        private const val NOTIF_ID = 1001
        private const val NOTIF_CRIT_BASE = 2000
        private const val SEVERITY_CRITICAL = 90
        private const val ALERT_LOG_CAP = 512
        private const val EVENT_LOG_CAP = 512

        /** 时间线滚动加载分页:一次加载条数(Room 解密成本 ~500µs/条,单线程分批)。*/
        private const val HISTORY_PAGE = 200

        /** W8 (文档 §6):每日聚合摘要通知 id 与触发小时。*/
        private const val NOTIF_SUMMARY_ID = 3010
        private const val SUMMARY_HOUR = 9

        /** W8 (Debug 演示):演示告警的固定 12B 包指纹(虚构,仅 UI 链路验证用)。*/
        private val DEMO_PKG_HASH: ByteArray =
            byteArrayOf(0x44.toByte(), 0x45.toByte(), 0x4D.toByte(), 0x4F.toByte(),
                0x31.toByte(), 0x32.toByte(), 0x33.toByte(), 0x34.toByte(),
                0x35.toByte(), 0x36.toByte(), 0x37.toByte(), 0x38.toByte())

        /** UI 访问服务状态/事件缓冲(仅主线程读取)。*/
        @Volatile var instance: GuardService? = null

        /** App 内共享的活跃组合注册表(key = "uid:op")。*/
        private val sharedPairs = ConcurrentHashMap<String, ActivePairData>()

        fun addSharedActivePair(uid: Int, op: Int, pkgHash: ByteArray) {
            sharedPairs["$uid:$op"] = ActivePairData(uid, op, pkgHash)
        }

        fun removeSharedActivePair(uid: Int, op: Int) {
            sharedPairs.remove("$uid:$op")
        }
    }

    private fun pkgHex(pkg: ByteArray): String =
        pkg.joinToString("") { "%02X".format(it) }
}