package com.sensorguard.app.jni

object SgNative {
    init { System.loadLibrary("sensorguard") }

    external fun sgInit(cfg: ByteArray?): Int
    external fun sgPushSensor(tsNs: Long, kind: Byte, x: Float, y: Float, z: Float): Int
    external fun sgPushOp(buf: ByteArray): Int
    /** W5 (文档 §5.2):返回传感器基线健康信号(sg_sensor_health 二进制 ABI)。
     *  out 容量需 ≥ 256 字节;返回值为有效长度(<0 为错误码,SgErrors 同义)。*/
    external fun sgSensorHealth(out: ByteArray): Int
    external fun sgTick(input: ByteArray, out: ByteArray): Int   // 返回 >=0 为 out 有效长度, <0 为错误码
    external fun sgSnapshot(out: ByteArray): Int
    external fun sgShutdown(): Int

    fun init() {
        val rc = runCatching { sgInit(null) }.getOrElse { SgErrors.E_PANIC }
        SgErrors.check("sg_init", rc)
    }
}
