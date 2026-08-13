package com.yuexiao12.sensorguard.probe

import com.yuexiao12.sensorguard.enums.SgEnum

/** 统一 wall-clock 时间基(ns),与 GuardService.batchTick 的 nowNs 一致(Rust 侧 24h 窗口切片)。*/
fun wallClockNs(): Long = System.currentTimeMillis() * 1_000_000L

/**
 * 探针产出的原始事件(尚未编码为 FlatBuffers),供 GuardService 消费与时间线 UI 展示。
 *
 * source: 事件来源("MIC" / "CAMERA"),用于 UI 展示与调试。
 * tier: 证据等级(SgEnum.TIER_*);文档 §11 前提经核对不成立(见 MicProbe 注释),
 * W4 公共 API 下麦克风/摄像头均无法归因 uid,统一 T0(uid=-1、pkgHash 全 0)。
 */
data class ProbeEvent(
    val tsNs: Long,
    val uid: Int,
    val pkgName: String?,      // 可解析时填充;CameraProbe(T0) 为 null
    val pkgHash: ByteArray,    // 12B PkgHash
    val op: Int,               // SgEnum.OP_*
    val phase: Int,            // SgEnum.PHASE_*
    val tier: Int,             // SgEnum.TIER_*
    val source: String,
    // W12/T2 (文档 §4 P4):精确采样周期(微秒),由 Shizuku 探针从 dumpsys sensorservice 解析。
    // 0 = 未知(非 Shizuku 来源如 MIC/CAMERA 填 0)。透传至 OpEventData → Rust 引擎。
    val samplingPeriodUs: Long = 0L,
    // 事件时间线增强:具体传感器显示名(如 "lsm6dso Accelerometer"),由 Shizuku 探针从
    // dumpsys 的 Sensor List 解析。仅用于时间线 UI 展示(不落加密库);非 Shizuku 来源为空串,
    // UI 回退到 op 类别名(MIC/CAM/ACCEL...)。
    val sensorName: String = "",
) {
    val pkgHashHex: String get() = pkgHash.joinToString("") { "%02X".format(it) }
    val isStart: Boolean get() = phase == SgEnum.PHASE_START
}

/** 探针事件消费方(由 GuardService 实现)。*/
interface ProbeSink {
    fun onProbeEvent(ev: ProbeEvent)
}

/** 通用探针接口。start() 后探针通过 sink 推送事件;stop() 必须释放系统回调。*/
interface Probe {
    fun start(sink: ProbeSink)
    fun stop()
}

/**
 * 事件时刻的上下文快照(CtxTag, schema sensorguard.fbs)。
 * W4 诚实边界:不申请 UsageStats/无障碍权限,故 fg_state 无法精确区分其他 App 前后台,
 * 统一按 FG(0) 上报并在注释标注;user_present / power_state 为真实设备状态。
 *
 * P2-2: intentHint / audioFocus / netEgressAnomaly 三字段原先硬编码 false,现补全:
 * - intentHint: 基于 decl_purpose 与 op 的语义一致性(相机类 App 用 CAMERA → true)。
 *   与 Rust 侧 purpose_matches() 同口径,供 R104 等规则谓词 IntentHintEquals 使用,
 *   同时在 S_ctx 计算中贡献 W3=0.25 权重(原先硬编码 0.0 已启用为实际值)。
 * - audioFocus: 设备级音频活跃信号(AudioManager.mode != NORMAL 或 isMusicActive)。
 *   无系统 API 可查他 App audio focus,按诚实边界做设备级判定(与 fg_state 同策略)。
 * - netEgressAnomaly: 读取 NetProbe.lastSuspicious(上一次 60s tick 的可疑出端标记)。
 *   v1.0 仅审计标记,不直接参与告警;为后续 v1.1 侧信道+回传关联分析铺垫。
 */
object CtxProbe {

    private var keyguard: android.app.KeyguardManager? = null
    private var power: android.os.PowerManager? = null
    private var audioManager: android.media.AudioManager? = null
    // P3: context 供 DeclPurposeClassifier 离线抽取用途标签
    private var appContext: android.content.Context? = null
    // P2-2: NetProbe 引用,读取 lastSuspicious;延迟注入(GuardService 创建 NetProbe 后 set)
    @Volatile
    private var netProbe: NetProbe? = null

    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
        if (keyguard == null) {
            keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            power = context.getSystemService(android.os.PowerManager::class.java)
            audioManager = context.getSystemService(android.media.AudioManager::class.java)
        }
    }

    /** P2-2: 注入 NetProbe 引用,供 snapshot 读取 netEgressAnomaly。*/
    fun setNetProbe(probe: NetProbe?) {
        netProbe = probe
    }

    /**
     * 生成事件时刻的上下文快照。
     *
     * @param uid 调用方 uid(T0 事件为 -1)
     * @param pkgName 调用方包名(T0 事件为 null)
     * @param op 传感器操作类型(SgEnum.OP_*),P2-2 新增:用于 intentHint 语义一致性判定
     */
    fun snapshot(uid: Int, pkgName: String?, op: Int = 0): com.yuexiao12.sensorguard.jni.CtxTagData {
        val userPresent = keyguard?.isKeyguardLocked == false
        val powerState = power?.isInteractive == true
        // W12/P0-1:systemProxy 反映"调用方是否为系统/核心预装组件",供 R112 等规则
        // 对系统组件豁免(OBSERVE 级侧信道规则不对其触发,消除系统自身误报)。
        val isSystem = isSystemComponent(uid, pkgName)
        // P3 (文档 §7):decl_purpose 离线抽取 —— App 用途先验(健身/导航/相机/输入法)
        val purpose = appContext?.let { DeclPurposeClassifier.classify(it, pkgName) } ?: 0
        // P2-2: intentHint —— decl_purpose 与 op 的语义一致性(与 Rust purpose_matches 同口径)。
        // 相机类 App 用 CAMERA、健身类用 ACCEL/GYRO/MAG、导航类用 LOCATION、输入法用 MIC → true。
        // 其他组合(如手电筒 App 偷开相机)→ false,供 R104 IntentHintEquals(false) 命中。
        val intentHint = purposeMatches(purpose, op)
        // P2-2: audioFocus —— 设备级音频活跃信号。无系统 API 查他 App audio focus,
        // 按 W4 诚实边界用 AudioManager.mode + isMusicActive 做设备级判定。
        // mode != NORMAL 表示通话/响铃/通信中;isMusicActive 表示媒体播放中。
        val am = audioManager
        val audioFocus = am != null && (
            am.mode != android.media.AudioManager.MODE_NORMAL || am.isMusicActive
        )
        // P2-2: netEgressAnomaly —— 读取 NetProbe 上一次 tick 的可疑出端标记。
        // v1.0 仅审计标记,不直接参与告警;为 v1.1 侧信道+回传关联分析铺垫。
        val netEgressAnomaly = netProbe?.lastSuspicious ?: false
        // fg_state:0=FG。W4 无 UsageStats 权限,无法区分其他 App 前后台,统一 FG(文档 §11 偏差)。
        return com.yuexiao12.sensorguard.jni.CtxTagData(
            fgState = 0, userPresent = userPresent, intentHint = intentHint,
            declPurpose = purpose, systemProxy = isSystem, audioFocus = audioFocus,
            powerState = powerState, netEgressAnomaly = netEgressAnomaly,
        )
    }

    /**
     * P2-2:用途与操作的语义一致性判定(与 Rust 侧 purpose_matches 同口径)。
     * purpose: 1=相机,2=健身,3=导航,4=输入法
     * op: 0=MIC,1=CAM,2=LOC,10=ACCEL,11=GYRO,12=MAG
     */
    private fun purposeMatches(purpose: Int, op: Int): Boolean {
        return when (purpose) {
            DeclPurposeClassifier.CAMERA -> op == SgEnum.OP_CAMERA
            DeclPurposeClassifier.FITNESS -> op in SgEnum.OP_ACCEL..SgEnum.OP_MAG
            DeclPurposeClassifier.NAVIGATION -> op == SgEnum.OP_FINE_LOCATION
            DeclPurposeClassifier.IME -> op == SgEnum.OP_RECORD_AUDIO
            else -> false
        }
    }

    /**
     * 判定调用方是否为系统/核心组件(uid<10000 的 AID 系统进程,或包名属
     * android./com.android./com.google.android. 体系)。
     * 供 R112 系统豁免(规则引擎)与"查看事件时间线"筛选(UI)共用同一口径,
     * 避免两处判定漂移。注意:厂商预装 App(如 Moto Launcher)不属此判定,
     * 按用户约定一律纳入监测(不豁免、不隐藏)。
     *
     * P0-2 增强:额外覆盖已知系统传感器消费方包名(不属 AID 系统进程但由系统调度),
     * 如 GMS 的子包(com.google.android.gms.* 子组件经独立进程运行时 uid 可能 ≥10000)。
     */
    fun isSystemComponent(uid: Int, pkgName: String?): Boolean {
        if (uid < 10000) return true
        if (pkgName == null) return false
        // AOSP / Google 核心包前缀
        if (pkgName.startsWith("android.") ||
            pkgName.startsWith("com.android.") ||
            pkgName.startsWith("com.google.android.")) return true
        // P0-2:已知系统传感器消费方(经真机 dumpsys 验证会激活 ACCEL/GYRO 的系统服务子包)
        return SYSTEM_SENSOR_CONSUMERS.contains(pkgName)
    }

    /**
     * P0-2:已知会消费 ACCEL/GYRO 但不属 AID 系统进程的系统服务包名集合。
     * 这些包由系统调度激活传感器(如抬起唤醒、手势识别),非用户主动行为,
     * 不应触发 R112 OBSERVE。真机验证后逐步补充。
     */
    private val SYSTEM_SENSOR_CONSUMERS = setOf(
        // Google Play Services 子组件(独立进程,uid 可能 ≥10000)
        "com.google.android.gms",
        "com.google.android.gms.ui",
        "com.google.android.gms.location",
        // Android System Intelligence(原 Device Personalization Services)
        "com.google.android.as",
        // 厂商系统服务(真机 dumpsys 验证后补充)
        // 留空:厂商预装 App 按用户约定一律纳入监测,不在此豁免。
    )
}