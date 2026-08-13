package com.sensorguard.app.jni

/**
 * W5 (文档 §5.2):解码 [SgNative.sgSensorHealth] 输出的稳定二进制 ABI(小端)。
 *
 * 布局(与 core-rust/src/sensor_baseline.rs `health_bytes` 严格对齐):
 *   [0]      = count N (u8)
 *   每条 10B: kind(u8) | ks_d(f32 LE) | anomaly(u8) | sample_hz(f32 LE)
 *
 * 该信号是 §5.2 HAL 竞争推断结果:第三方以更高频率激活同一物理传感器时,我方自采
 * 抖动分布偏离基线,Rust 用两样本 KS 检验(D_KS > τ=0.18)标记 anomaly。无 Shizuku 时
 * 只能标"存在未知采样方"(T0),归因到具体 uid 由 Shizuku 探针负责。
 */
object SensorHealthReader {

    /** 单 kind 传感器健康信号。*/
    data class SensorHealthData(
        val kind: Int,
        val ksD: Float,
        val anomaly: Boolean,
        val sampleHz: Float,
    )

    /** 解码 [buf] 的前 [len] 字节为健康信号列表。越界/损坏静默截断。*/
    fun decode(buf: ByteArray, len: Int): List<SensorHealthData> {
        if (len < 1) return emptyList()
        val count = buf[0].toInt() and 0xFF
        val out = ArrayList<SensorHealthData>(count)
        var p = 1
        for (i in 0 until count) {
            if (p + 10 > len) break
            val kind = buf[p].toInt() and 0xFF
            val ksD = Float.fromBits(readI32(buf, p + 1))
            val anomaly = (buf[p + 5].toInt() and 0xFF) != 0
            val sampleHz = Float.fromBits(readI32(buf, p + 6))
            out.add(SensorHealthData(kind, ksD, anomaly, sampleHz))
            p += 10
        }
        return out
    }

    private fun readI32(b: ByteArray, p: Int): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or (b[p + i].toInt() and 0xFF)
        return r
    }
}
