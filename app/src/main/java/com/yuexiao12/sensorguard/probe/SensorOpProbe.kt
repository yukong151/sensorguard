package com.yuexiao12.sensorguard.probe

import android.app.AppOpsManager
import android.content.Context
import android.util.Log
import com.yuexiao12.sensorguard.enums.SgEnum
import java.security.MessageDigest

/**
 * 内测版精确归因探针(AppOps T1)。
 *
 * 需求:内测版需要"精确知道是哪个 App 在调用",而非 T0 的"未知来源"。
 * Android 10+ 的 `AppOpsManager.startWatchingActive` 对第三方开放且**无需特殊权限**,
 * 回调携带精确的 uid + 包名(LocationProbe 已验证同机制),对以下 op 生效:
 *
 *   - `OPSTR_RECORD_AUDIO`(android:record_audio)→ 麦克风,MicProbe(T0)盲区
 *   - `OPSTR_CAMERA`(android:camera)→ 摄像头,CameraProbe(T0)盲区
 *
 * 不监听 IMU 类传感器 op:android-34 公共 stub 未导出对应 OPSTR 常量(IMU op 无字符串名),
 * 且 startWatchingActive 对传感器 op 是否触发回调在厂商 ROM 上不可靠;传感器精确归因由
 * Shizuku T2(dumpsys sensorservice, 精确 uid+采样率)承担,无 Shizuku 时保留 T0 兜底。
 *
 * 与各 T0 探针的关系:本探针是**归因增强**,不替代事件检测。T0 探针仍负责
 * "是否有调用"的即时性(MicProbe 用录音会话、CameraProbe 用 availability),
 * 本探针在回调到达时发出**精确归因事件**(T1),时间线据此显示真实包名。
 *
 * 厂商 ROM 若 startWatchingActive 回调不可靠(部分 MIUI/EMUI),优雅降级:
 * 仅记启动故障日志,不影响其他探针与前台服务。
 */
class SensorOpProbe(private val context: Context) : Probe {

    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val executor = context.mainExecutor

    /** 监听 op → SgEnum op。麦克风/相机是 T0 探针的归因盲区,由本探针升级为精确归因。*/
    private val watched = arrayOf(
        AppOpsManager.OPSTR_RECORD_AUDIO,
        AppOpsManager.OPSTR_CAMERA,
    )

    @Volatile private var sink: ProbeSink? = null
    private var started = false
    private val activeKeys = HashSet<String>() // 去重: "op:uid"

    /** AppOps 使用状态变化回调(主线程执行器)。*/
    private val callback = object : AppOpsManager.OnOpActiveChangedListener {
        override fun onOpActiveChanged(op: String, uid: Int, packageName: String, active: Boolean) {
            val kind = kindOf(op) ?: return
            // 只上报非本 App 的调用(本 App 自采基线由 SensorBaselineProbe 负责,不应误报为第三方)
            if (packageName == context.packageName) return
            val key = "$op:$uid"
            val sinkNow = sink ?: return
            if (active) {
                if (!activeKeys.add(key)) return // 已上报 START,去重
                sinkNow.onProbeEvent(
                    ProbeEvent(
                        tsNs = wallClockNs(), uid = uid, pkgName = packageName,
                        pkgHash = pkgHashOf(packageName, uid),
                        op = kind, phase = SgEnum.PHASE_START,
                        tier = SgEnum.TIER_T1_STANDARD, source = "APPOPS",
                    )
                )
            } else {
                if (!activeKeys.remove(key)) return // 未上报过 START,忽略
                sinkNow.onProbeEvent(
                    ProbeEvent(
                        tsNs = wallClockNs(), uid = uid, pkgName = packageName,
                        pkgHash = pkgHashOf(packageName, uid),
                        op = kind, phase = SgEnum.PHASE_STOP,
                        tier = SgEnum.TIER_T1_STANDARD, source = "APPOPS",
                    )
                )
            }
        }
    }

    private fun kindOf(op: String): Int? = when (op) {
        AppOpsManager.OPSTR_RECORD_AUDIO -> SgEnum.OP_RECORD_AUDIO
        AppOpsManager.OPSTR_CAMERA -> SgEnum.OP_CAMERA
        else -> null
    }

    override fun start(sink: ProbeSink) {
        this.sink = sink
        if (started) return
        started = true
        runCatching {
            appOps.startWatchingActive(watched, executor, callback)
        }.onFailure { Log.w(TAG, "appops 精确归因探针注册失败(厂商 ROM 限制),降级 T0", it) }
    }

    override fun stop() {
        started = false
        sink = null
        runCatching { appOps.stopWatchingActive(callback) }
        activeKeys.clear()
    }

    /** 12B 包指纹(与 GuardService.pkgHashFromName / LocationProbe 同构:HMAC-SHA256 前 12 字节近似)。*/
    private fun pkgHashOf(pkg: String, uid: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pkg.toByteArray())
        md.update(uid.toString().toByteArray())
        return md.digest().copyOf(12)
    }

    private companion object {
        const val TAG = "SgOpProbe"
    }
}