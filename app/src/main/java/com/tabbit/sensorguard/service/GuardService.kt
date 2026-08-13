package com.tabbit.sensorguard.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tabbit.sensorguard.BuildConfig
import com.tabbit.sensorguard.R
import com.tabbit.sensorguard.crypto.CounterGuard
import com.tabbit.sensorguard.crypto.DekManager
import com.tabbit.sensorguard.crypto.EspCounterStore
import com.tabbit.sensorguard.crypto.KekProvider
import com.tabbit.sensorguard.crypto.SafeModeException
import com.tabbit.sensorguard.db.RoomEventSink
import com.tabbit.sensorguard.db.RoomKeychainStore
import com.tabbit.sensorguard.db.SgDb
import com.tabbit.sensorguard.jni.ActivePairData
import com.tabbit.sensorguard.jni.FbSerde
import com.tabbit.sensorguard.jni.SgEnum
import com.tabbit.sensorguard.jni.SgErrors
import com.tabbit.sensorguard.jni.SgNative
import com.tabbit.sensorguard.jni.SensorHealthReader
import com.tabbit.sensorguard.jni.VerdictBatchData
import com.tabbit.sensorguard.jni.VerdictEntryData
import com.tabbit.sensorguard.jni.OpEventData
import com.tabbit.sensorguard.jni.VerdictReader
import com.tabbit.sensorguard.logic.ActionRouter
import com.tabbit.sensorguard.logic.HealthLevel
import com.tabbit.sensorguard.logic.SystemHealth
import com.tabbit.sensorguard.probe.CameraProbe
import com.tabbit.sensorguard.probe.CtxProbe
import com.tabbit.sensorguard.probe.MicProbe
import com.tabbit.sensorguard.probe.ProbeEvent
import com.tabbit.sensorguard.probe.ProbeSink
import com.tabbit.sensorguard.probe.ShizukuProbe
import com.tabbit.sensorguard.probe.SensorBaselineProbe
import com.tabbit.sensorguard.probe.SensorServiceParser
import com.tabbit.sensorguard.store.EncryptedEventStore
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GuardService : Service() {

    private lateinit var scheduler: ScheduledExecutorService
    private val tickBuf = ByteArray(64 * 1024)

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

    // W12 (文档 §4 P4):Shizuku 精确归因探针(T2 增强,可选独立插件)
    private var shizukuProbe: ShizukuProbe? = null

    /** W5 (文档 §5.2):传感器基线探针(accel/gyro/mag/light/prox 自采 ~50Hz → sg_push_sensor)。*/
    private var sensorBaselineProbe: SensorBaselineProbe? = null

    /** W12/T2:上次 Shizuku 轮询快照(uid:op -> client),用于 diff 推 START/STOP/TICK。*/
    private val shizukuLast = ConcurrentHashMap<String, SensorServiceParser.SensorClient>()

    /** W12/T2: 包指纹(hex) -> (内层包名, uid) 反向映射,供 UI 在告警/事件上做归属展示(仅内存,会话级)。*/
    private val pkgHashInfo = ConcurrentHashMap<String, Pair<String, Int>>()

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

        // W7 (文档 §8.2):加密事件存储 —— KEK(AndroidKeyStore,StrongBox 优先)+ DEK 编排 +
        // keychain(Room)+ counter(EncryptedSharedPreferences)+ 唯一性守卫。
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

        // W4: 启动探针(公开 API 等效替代 AppOps 监听,见各探针注释)
        instance = this
        // W8 (文档 §10): 接线 Health 状态机 —— SgErrors.check 上报错误累计
        SgErrors.health = health
        CtxProbe.attach(this)
        micProbe = MicProbe(this).also { it.start(probeSink) }
        cameraProbe = CameraProbe(this).also { it.start(probeSink) }
        // W12 (文档 §4 P4):Shizuku 精确归因 —— 启用以 ADB 权限读取 dumpsys sensorservice,
        // 获取精确 uid+采样率,覆盖 AppOps 探针无法归因的 IMU 类传感器盲区。
        // start() 内部注册权限/binder 监听器并请求授权: 晚授权(用户在 Shizuku 内手动授予)
        // 或 Shizuku 重启时,监听器回调会自动激活探针(Step 3 打磨)。
        shizukuProbe = ShizukuProbe { onShizukuClients(it) }.also { it.start() }
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
        // W12/T2: 维护 包指纹 -> (内层包名, uid) 反向映射,供 UI 归因展示(仅内存)。
        pkgHashInfo[pkgHex(ev.pkgHash)] = Pair(ev.pkgName ?: "", ev.uid)
        val opEv = OpEventData(
            ev.tsNs, ev.uid, ev.pkgHash, ev.op, ev.phase,
            CtxProbe.snapshot(ev.uid, ev.pkgName), ev.samplingPeriodUs,
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
        if (persist) persistEncrypted(ev)
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
            val upgraded = upgradeAbuseVerdict(v)
            pushAlert(upgraded)
            if (upgraded.severity >= SEVERITY_CRITICAL) {
                notifyCritical(upgraded)
            }
        }
    }

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

    /** W12/T2: 供 UI 查询某包指纹对应的(内层包名, uid),用于告警/事件归因展示;查不到返回 null。*/
    fun attributionFor(hex: String): Pair<String, Int>? = pkgHashInfo[hex]

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
     */
    fun wipeAllLogs() {
        runCatching { eventStore.wipeAll() }
            .onFailure { Log.e("SG", "wipeAllLogs failed", it) }
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
        shizukuProbe?.stop()
        sensorBaselineProbe?.stop()
        micProbe = null
        cameraProbe = null
        shizukuProbe = null
        SgErrors.health = null
        instance = null
        scheduler.shutdownNow()
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