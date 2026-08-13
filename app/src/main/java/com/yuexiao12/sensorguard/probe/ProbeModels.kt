package com.yuexiao12.sensorguard.probe

import com.yuexiao12.sensorguard.jni.SgEnum

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
 */
object CtxProbe {

    private var keyguard: android.app.KeyguardManager? = null
    private var power: android.os.PowerManager? = null

    fun attach(context: android.content.Context) {
        if (keyguard == null) {
            keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            power = context.getSystemService(android.os.PowerManager::class.java)
        }
    }

    fun snapshot(uid: Int, pkgName: String?): com.yuexiao12.sensorguard.jni.CtxTagData {
        val userPresent = keyguard?.isKeyguardLocked == false
        val powerState = power?.isInteractive == true
        // W12/P0-1:systemProxy 反映"调用方是否为系统/核心预装组件",供 R112 等规则
        // 对系统组件豁免(OBSERVE 级侧信道规则不对其触发,消除系统自身误报)。
        val isSystem = isSystemComponent(uid, pkgName)
        // fg_state:0=FG。W4 无 UsageStats 权限,无法区分其他 App 前后台,统一 FG(文档 §11 偏差)。
        return com.yuexiao12.sensorguard.jni.CtxTagData(
            fgState = 0, userPresent = userPresent, intentHint = false,
            declPurpose = 0, systemProxy = isSystem, audioFocus = false,
            powerState = powerState, netEgressAnomaly = false,
        )
    }

    /**
     * 判定调用方是否为系统/核心组件(uid<10000 的 AID 系统进程,或包名属
     * android./com.android./com.google.android. 体系)。
     * 供 R112 系统豁免(规则引擎)与"查看事件时间线"筛选(UI)共用同一口径,
     * 避免两处判定漂移。注意:厂商预装 App(如 Moto Launcher)不属此判定,
     * 按用户约定一律纳入监测(不豁免、不隐藏)。
     */
    fun isSystemComponent(uid: Int, pkgName: String?): Boolean {
        return uid < 10000 || pkgName != null && (
                pkgName.startsWith("android.") ||
                pkgName.startsWith("com.android.") ||
                pkgName.startsWith("com.google.android."))
    }
}