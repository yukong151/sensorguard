package com.sensorguard.app.probe

import com.sensorguard.app.jni.SgEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SensorServiceParser 单测 —— 用真机实测的 dumpsys sensorservice 输出片段验证解析。
 * 覆盖:精确 uid 提取、包名映射、采样周期、敏感 op 映射、非敏感传感器过滤。
 */
class SensorServiceParserTest {

    /** 真机(Moto XT2153_1, Android 13)实测输出片段。*/
    private val REAL_OUTPUT = """
        > 7 active connections
          Connection Number: 0
          	Operating Mode: NORMAL
        > 21:49:26 + 0x010000bf pid=29050 uid=10346 package=com.tencent.mobileqq.msf.core.stepcount.g.b samplingPeriod=0us batchingPeriod=360000000us
        > 21:49:26 - 0x010000bf pid=29050 uid=10346 package=com.tencent.mobileqq.msf.core.stepcount.g.b
        > 21:34:43 + 0x01000065 pid=16761 uid=10213 package=com.google.ccc.abuse.droidguard.events.b samplingPeriod=20000us batchingPeriod=0us
        > 21:34:43 - 0x01000065 pid=16761 uid=10213 package=com.google.ccc.abuse.droidguard.events.b
        > 21:22:00 + 0x0100000b pid= 1824 uid= 1000 package=android.view.OrientationEventListener samplingPeriod=200000us batchingPeriod=0us
        > 21:22:00 - 0x0101000c pid=14130 uid=10209 package=i7.m
        > 21:22:00 + 0x01010020 pid= 3410 uid=10203 package=p9.c samplingPeriod=200000us batchingPeriod=0us
        > 21:22:00 - 0x01010020 pid= 3410 uid=10203 package=p9.c
        0x0100000b) lsm6dso Accelerometer Non-wakeup | STMicro | ver: 142854 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000980
        0x0100000c) lsm6dso Accelerometer Wakeup | STMicro | ver: 142854 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000981
        0x01000015) mmc56x3x Magnetometer Non-wakeup | memsic | ver: 20420568 | type: android.sensor.magnetic_field(2) | perm: n/a
        0x010000bf) lsm6dso Gyroscope Non-wakeup | STMicro | ver: 142854 | type: android.sensor.gyroscope(4) | perm: n/a
        0x01000065) lsm6dso Gyroscope Wakeup | STMicro | ver: 142854 | type: android.sensor.gyroscope(4) | perm: n/a
        0x01010020) lsm6dso Accelerometer | STMicro | ver: 142854 | type: android.sensor.accelerometer(1) | perm: n/a
        0x010001c0) lsm6dso Ambient Light | STMicro | ver: 142854 | type: android.sensor.light(5) | perm: n/a
        0x010001d0) lsm6dso Pressure | STMicro | ver: 142854 | type: android.sensor.pressure(6) | perm: n/a
        0x010001e0) lsm6dso Proximity | STMicro | ver: 142854 | type: android.sensor.proximity(8) | perm: n/a
        0x010001f0) lsm6dso Step Counter | STMicro | ver: 142854 | type: android.sensor.step_counter(19) | perm: n/a
        0x01000200) lsm6dso Significant Motion | STMicro | ver: 142854 | type: android.sensor.significant_motion(17) | perm: n/a
    """.trimIndent()

    @Test
    fun `parses real device output with exact uid attribution`() {
        val clients = SensorServiceParser.parse(REAL_OUTPUT)

        // 3 条活跃连接全部解析出来(0x010000bf, 0x01000065, 0x0100000b, 0x01010020)
        // 其中 0x010000bf → gyroscope(4) → OP_GYRO, uid=10346
        val gyro = clients.find { it.op == SgEnum.OP_GYRO }
        assertEquals("gyro 应精确归因到 uid=10346", 10346, gyro?.uid)
        assertEquals("com.tencent.mobileqq.msf.core.stepcount.g.b", gyro?.packageName)
        assertEquals(0L, gyro?.samplingPeriodUs)

        // 0x01000065 → gyroscope(4) → uid=10213
        val gyro2 = clients.find { it.uid == 10213 }
        assertEquals(SgEnum.OP_GYRO, gyro2?.op)
        assertEquals(20000L, gyro2?.samplingPeriodUs)

        // 0x0100000b → accelerometer(1) → OP_ACCEL, uid=1000(系统)
        val accel = clients.find { it.packageName == "android.view.OrientationEventListener" }
        assertEquals(1000, accel?.uid)
        assertEquals(SgEnum.OP_ACCEL, accel?.op)
        assertEquals(200000L, accel?.samplingPeriodUs)

        // 0x01010020 → accelerometer(1) → uid=10203
        val accel2 = clients.find { it.uid == 10203 }
        assertEquals(SgEnum.OP_ACCEL, accel2?.op)
    }

    @Test
    fun `filters non-active and non-sensitive sensors`() {
        val clients = SensorServiceParser.parse(REAL_OUTPUT)

        // 停止行(- 前缀)不产生连接
        assertTrue("应无重复的停止连接", clients.size <= 4)

        // step counter / significant motion(非敏感传感器 type 19/17)不应出现在结果
        // (注:包名含 step 但与传感器类型无关,按 op 值过滤)
        assertTrue(clients.all { it.op in SgEnum.OP_ACCEL..SgEnum.OP_PROX })
        assertTrue(clients.size <= 4)
    }

    @Test
    fun `empty output returns empty list`() {
        assertTrue(SensorServiceParser.parse("").isEmpty())
        assertTrue(SensorServiceParser.parse("no connections").isEmpty())
    }

    @Test
    fun `maps sensor types to ops correctly`() {
        val input = """
            > 1 active connections
            > 21:00:00 + 0x01000015 pid=1 uid=111 package=com.mag samplingPeriod=1000us batchingPeriod=0us
            > 21:00:00 + 0x010001c0 pid=1 uid=222 package=com.light samplingPeriod=500us batchingPeriod=0us
            > 21:00:00 + 0x010001d0 pid=1 uid=333 package=com.baro samplingPeriod=2000us batchingPeriod=0us
            > 21:00:00 + 0x010001e0 pid=1 uid=444 package=com.prox samplingPeriod=100us batchingPeriod=0us
            0x01000015) Magnetometer | type: android.sensor.magnetic_field(2) | perm: n/a
            0x010001c0) Light | type: android.sensor.light(5) | perm: n/a
            0x010001d0) Pressure | type: android.sensor.pressure(6) | perm: n/a
            0x010001e0) Proximity | type: android.sensor.proximity(8) | perm: n/a
        """.trimIndent()
        val clients = SensorServiceParser.parse(input)
        assertEquals(SgEnum.OP_MAG, clients.find { it.uid == 111 }?.op)
        assertEquals(SgEnum.OP_LIGHT, clients.find { it.uid == 222 }?.op)
        assertEquals(SgEnum.OP_BARO, clients.find { it.uid == 333 }?.op)
        assertEquals(SgEnum.OP_PROX, clients.find { it.uid == 444 }?.op)
    }
}