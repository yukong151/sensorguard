package com.yuexiao12.sensorguard.probe

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * P4-8 (文档 §2/§4 C4):Net Probe —— 网络流量统计(仅统计,不出端)。
 *
 * 威胁面:隐蔽信道回传 —— App 经 DNS/QUIC/推送通道回传"结论"。文档明示:
 * 「v1.0 仅统计,v1.1 引入本地 VPN 环回」。故本探针 v1.0 只做:
 *  - 用 `NetworkStatsManager.querySummaryForDevice` 统计本机/各 uid 的流量增量;
 *  - 每 60s(batchTick 对齐)记录 rx/tx 字节增量审计日志;
 *  - 低频高突发(可疑回传)时打审计标记,不阻断、不告警刷屏。
 *
 * 能力边界(诚实):沙箱下无法按 uid 精确统计他 App 的按 App 流量
 * (需 PACKAGE_USAGE_STATS 特殊权限,文档 §7 明确不申请),故本探针只做**设备级**
 * 流量统计 + 上下文关联。精确归因留待 v1.1 VPN 环回。
 *
 * 注意:querySummaryForDevice 返回自设备启动累计值,需记录上次值做增量。
 */
class NetProbe(private val context: Context) : Probe {

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTs = System.currentTimeMillis()
    private var started = false

    override fun start(sink: ProbeSink) {
        started = true
        // 记录基线(下一次 tick 得增量)
        val snap = snapshot()
        lastRx = snap.first
        lastTx = snap.second
        lastTs = System.currentTimeMillis()
        Log.i(TAG, "NetProbe 基线: rx=${lastRx} tx=${lastTx}")
    }

    override fun stop() {
        started = false
    }

    /** 每 60s 调用:统计流量增量,可疑突发(> 10 MB/min)打审计标记。*/
    fun tick() {
        if (!started) return
        val snap = snapshot()
        val now = System.currentTimeMillis()
        val dRx = (snap.first - lastRx).coerceAtLeast(0)
        val dTx = (snap.second - lastTx).coerceAtLeast(0)
        val dtS = ((now - lastTs) / 1000).coerceAtLeast(1)
        val rate = (dRx + dTx) / dtS // bytes/sec
        lastRx = snap.first
        lastTx = snap.second
        lastTs = now

        val suspicious = rate > SUSPICIOUS_RATE_BPS
        Log.i(TAG, "net delta rx=$dRx tx=$dTx rate=${rate}B/s" +
            if (suspicious) " [suspicious egress, audit only]" else "")
    }

    /** 设备级累计流量(rx, tx)。NetworkStatsManager 需 ACCESS_NETWORK_STATE(已具备)。*/
    private fun snapshot(): Pair<Long, Long> {
        return try {
            val nsm = context.getSystemService(NetworkStatsManager::class.java)
            val bucket = nsm.querySummaryForDevice(
                android.net.ConnectivityManager.TYPE_MOBILE,
                null, 0L, System.currentTimeMillis()
            )
            Pair(bucket?.rxBytes ?: 0L, bucket?.txBytes ?: 0L)
        } catch (_: Exception) {
            Pair(0L, 0L) // 无权限/系统限制,静默降级
        }
    }

    private companion object {
        const val TAG = "SgNetProbe"
        /** 可疑出端速率阈值:10 MB/min ≈ 170 KB/s。仅审计标记,不告警。 */
        const val SUSPICIOUS_RATE_BPS = 170_000L
    }
}