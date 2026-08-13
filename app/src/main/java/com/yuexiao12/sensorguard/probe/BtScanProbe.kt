package com.yuexiao12.sensorguard.probe

import android.content.Context
import android.util.Log
import com.yuexiao12.sensorguard.jni.SgEnum

/**
 * 蓝牙扫描威胁面探针(P3, 文档 §2 威胁面:蓝牙/Wi-Fi 扫描定位)。
 *
 * 威胁:高频 `BluetoothAdapter.startDiscovery` / `WifiManager.startScan` 可被用于
 * 定位追踪(§2 应对:频次阈值告警)。沙箱下无法直接监听他 App 的 startDiscovery,
 * 但可经 Shizuku 读取 `dumpsys bluetooth_manager` 的扫描计数/发现状态做频次统计。
 *
 * 能力边界(诚实标注):
 * - 无 Shizuku:退化——不产生扫描频次数据,静默跳过(不假装检测)。
 * - 有 Shizuku:每 60s 读一次 dumpsys,统计 discovery 状态翻转次数,
 *   高频(≥ SCAN_RATE_THRESHOLD 次/窗口)时由 GuardService 回调产生 OBSERVE 告警。
 * - 蓝牙扫描主体归因:依赖 Shizuku dumpsys 输出的 uid/包名(与 ShizukuProbe 共用通道)。
 */
class BtScanProbe(
    private val context: Context,
    private val onHighFreq: (Int) -> Unit, // 高频扫描回调(次数)
) : Probe {

    @Volatile private var sink: ProbeSink? = null
    private var started = false
    private var lastDiscoveryState = false
    private var transitions = 0

    override fun start(sink: ProbeSink) {
        this.sink = sink
        if (started) return
        started = true
        // 探针本身不轮询;高频判定由 GuardService 的 batchTick / Shizuku 通道驱动。
        Log.i(TAG, "蓝牙扫描探针就绪(依赖 Shizuku 通道)")
    }

    override fun stop() {
        started = false
        sink = null
    }

    /**
     * 由 Shizuku dumpsys 通道喂入原始 bluetooth_manager 输出,解析 discovery 状态。
     * 每 60s 调用一次;discovery 状态翻转计为一次扫描。
     */
    fun feedDumpsys(dumpsys: String?) {
        val sinkNow = sink ?: return
        if (dumpsys.isNullOrBlank()) return
        val discovering = dumpsys.contains("isDiscovering: true") ||
            dumpsys.contains("mDiscoveryState: Discovering") ||
            dumpsys.contains("Discovery state: 1")
        if (discovering && !lastDiscoveryState) transitions++
        lastDiscoveryState = discovering
        // 窗口结束由调用方复位 transitions
    }

    /** 窗口结算:返回本窗口扫描次数并复位。*/
    fun resetWindow(): Int = transitions.also { transitions = 0 }.let { c ->
        // 高频:阈值=5 次/窗口(≈每 12s 一次 discovery)
        if (c >= SCAN_RATE_THRESHOLD) {
            onHighFreq(c)
        }
        c
    }

    private companion object {
        const val TAG = "SgBtProbe"
        /** 高频阈值:窗口内 discovery 翻转 ≥5 次判高频扫描。 */
        const val SCAN_RATE_THRESHOLD = 5
    }
}