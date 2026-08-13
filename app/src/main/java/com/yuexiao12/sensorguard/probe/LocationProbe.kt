package com.yuexiao12.sensorguard.probe

import android.app.AppOpsManager
import android.content.Context
import android.util.Log
import com.yuexiao12.sensorguard.enums.SgEnum

/**
 * 位置探针(P3, 文档 §5.1 AppOps Probe)。
 *
 * 使用 `AppOpsManager.startWatchingActive` 监听 `OPSTR_FINE_LOCATION` 与
 * `OPSTR_COARSE_LOCATION`。这是文档 §5.1 指定的核心 API —— Android 10 起
 * 对第三方 App 开放且**无需特殊权限**,回调携带精确的 uid + 包名,可直接归因(T1)。
 *
 * Ps:部分厂商 ROM(如部分 MIUI/EMUI)startWatchingActive 回调可能不可靠,
 * 失败时优雅降级:仅记录启动故障日志,不影响其他探针与前台服务。
 */
class LocationProbe(private val context: Context) : Probe {

    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val executor = context.mainExecutor
    private val watched = arrayOf(
        AppOpsManager.OPSTR_FINE_LOCATION,
        AppOpsManager.OPSTR_COARSE_LOCATION,
        AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION,
    )

    @Volatile private var sink: ProbeSink? = null
    private var started = false
    private val activeKeys = HashSet<String>() // 去重: "op:uid"

    /** AppOps 使用状态变化回调(线程安全,回调在主线程执行器)。*/
    private val callback = object : AppOpsManager.OnOpActiveChangedListener {
        override fun onOpActiveChanged(op: String, uid: Int, packageName: String, active: Boolean) {
            val kind = when (op) {
                AppOpsManager.OPSTR_FINE_LOCATION,
                AppOpsManager.OPSTR_COARSE_LOCATION,
                AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION -> SgEnum.OP_FINE_LOCATION
                else -> return
            }
            val key = "$op:$uid"
            val sinkNow = sink ?: return
            if (active) {
                if (!activeKeys.add(key)) return // 已上报 START,去重
                sinkNow.onProbeEvent(
                    ProbeEvent(
                        tsNs = wallClockNs(), uid = uid, pkgName = packageName,
                        pkgHash = pkgHashOf(packageName, uid),
                        op = kind, phase = SgEnum.PHASE_START,
                        tier = SgEnum.TIER_T1_STANDARD, source = "LOCATION",
                    )
                )
            } else {
                if (!activeKeys.remove(key)) return // 未上报过 START,忽略
                sinkNow.onProbeEvent(
                    ProbeEvent(
                        tsNs = wallClockNs(), uid = uid, pkgName = packageName,
                        pkgHash = pkgHashOf(packageName, uid),
                        op = kind, phase = SgEnum.PHASE_STOP,
                        tier = SgEnum.TIER_T1_STANDARD, source = "LOCATION",
                    )
                )
            }
        }
    }

    override fun start(sink: ProbeSink) {
        this.sink = sink
        if (started) return
        started = true
        runCatching {
            appOps.startWatchingActive(watched, executor, callback)
        }.onFailure { Log.w(TAG, "location probe 注册失败(厂商 ROM 限制),降级", it) }
    }

    override fun stop() {
        started = false
        sink = null
        runCatching { appOps.stopWatchingActive(callback) }
        activeKeys.clear()
    }

    /** 12B 包指纹(与 GuardService.pkgHashFromName 同构:HMAC-SHA256 前 12 字节近似)。*/
    private fun pkgHashOf(pkg: String, uid: Int): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(pkg.toByteArray())
        md.update(uid.toString().toByteArray())
        return md.digest().copyOf(12)
    }

    private companion object {
        const val TAG = "SgLocProbe"
    }
}