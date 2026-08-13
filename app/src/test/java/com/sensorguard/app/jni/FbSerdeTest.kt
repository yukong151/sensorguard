package com.sensorguard.app.jni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FbSerdeTest {

    private fun pkg(vararg b: Int): ByteArray = ByteArray(12) { i -> b.getOrElse(i) { 0 }.toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    // golden 夹具字节(core-rust ffi::tests::dump_fb_fixtures_hex 输出)
    // W12/T2:OpEvent 新增 sampling_period_us 字段(默认 20_000us),vtable 扩展至 7 槽。
    private val FB_FIXTURE_OP_EVENT_FULL =
        "1C000000000000000000120034002C0028001C001B001A0004000C00120000000201010301000100" +
        "204E0000000000000000000000000101AAAAAAAAAAAAAAAAAAAAAAAA3930000000002A36FE9C9717"
private val FB_FIXTURE_TICK_INPUT =
        "100000000C001C0010000800040000000C0000001800000000002A36FE9C9717" +
        "2A0000000000000000000000020000003930000001010101010101010101010101000000" +
        "320901000E010101010101010101010101000000"

    @Test
    fun `encode OpEvent matches golden fixture byte-for-byte`() {
        // 与 Rust make_op_event_full(1_700_000_000_000_000_000, 12_345, 1, 1) 完全一致的入参
        val ev = OpEventData(
            tsNs = 1_700_000_000_000_000_000L, uid = 12_345,
            pkgHash = ByteArray(12) { 0xAA.toByte() },
            op = SgEnum.OP_CAMERA, phase = SgEnum.PHASE_STOP,
            ctx = CtxTagData(
                fgState = 2, userPresent = true, intentHint = true,
                declPurpose = 3, systemProxy = true, audioFocus = false,
                powerState = true, netEgressAnomaly = false,
            ),
            samplingPeriodUs = 20_000L,
        )
        val buf = FbSerde.encodeOpEvent(ev)
        assertEquals(FB_FIXTURE_OP_EVENT_FULL.length / 2, buf.size)
        assertEquals(FB_FIXTURE_OP_EVENT_FULL, hex(buf))
    }

    @Test
    fun `encode TickInput matches golden fixture byte-for-byte`() {
        // 与 Rust make_tick_input(42, 1_700_000_000_000_000_000, &[(12_345,1),(67_890,14)]) 一致
        val pkgOne = ByteArray(12) { 1 }
        val pairs = listOf(
            ActivePairData(uid = 12_345, op = SgEnum.OP_CAMERA, pkgHash = pkgOne),
            ActivePairData(uid = 67_890, op = SgEnum.OP_LIGHT, pkgHash = pkgOne),
        )
        val buf = FbSerde.encodeTickInput(
            tickId = 42L, nowNs = 1_700_000_000_000_000_000L, tier = 0, pairs = pairs,
        )
        assertEquals(88, buf.size)
        assertEquals(FB_FIXTURE_TICK_INPUT, hex(buf))
        // assertEquals(FB_FIXTURE_TICK_INPUT, hex(buf)) // W9: schema 加 tier 字段后 fixture 待更新
    }

    @Test
    fun `encode OpEvent then decode all scalar and struct fields correctly`() {
        val ctx = CtxTagData(
            fgState = 2, userPresent = false, intentHint = false,
            declPurpose = 5, systemProxy = false, audioFocus = true,
            powerState = false, netEgressAnomaly = true,
        )
        val ev = OpEventData(
            tsNs = 1_723_000_000_000L, uid = 10086,
            pkgHash = pkg(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            op = SgEnum.OP_RECORD_AUDIO, phase = SgEnum.PHASE_START, ctx = ctx,
            samplingPeriodUs = 20_000L,
        )

        val buf = FbSerde.encodeOpEvent(ev)
        val tablePos = FbReader.rootTable(buf)

        // ts_ns (slot 0)
        val tsPos = FbReader.fieldPos(buf, tablePos, 0)!!
        assertEquals(ev.tsNs, FbReader.i64(buf, tsPos))

        // uid (slot 1)
        val uidPos = FbReader.fieldPos(buf, tablePos, 1)!!
        assertEquals(ev.uid, FbReader.i32(buf, uidPos))

        // pkg_hash struct (slot 2) — 直接按声明顺序读 12 字节
        val pkgPos = FbReader.fieldPos(buf, tablePos, 2)!!
        for (i in 0 until 12) assertEquals(ev.pkgHash[i], buf[pkgPos + i])

        // phase (slot 4) — 值为 0(PHASE_START)等于默认值,不会被写入,读取应返回 null
        val phasePos = FbReader.fieldPos(buf, tablePos, 4)
        assertNull("PHASE_START 等于 schema 默认值,不应被物理写入", phasePos)

        // ctx struct (slot 5)
        val ctxPos = FbReader.fieldPos(buf, tablePos, 5)!!
        assertEquals(2, buf[ctxPos].toInt())                         // fg_state
        assertEquals(0, buf[ctxPos + 1].toInt())                     // user_present=false
        assertEquals(5, buf[ctxPos + 3].toInt())                     // decl_purpose
        assertEquals(1, buf[ctxPos + 5].toInt())                     // audio_focus=true
        assertEquals(1, buf[ctxPos + 7].toInt())                     // net_egress_anomaly=true

        // sampling_period_us (slot 6) — W12/T2:非默认(20000)应被物理写入
        val spPos = FbReader.fieldPos(buf, tablePos, 6)!!
        assertEquals(20_000L, FbReader.i64(buf, spPos))
    }

    @Test
    fun `encode TickInput with active pairs and decode vector correctly`() {
        val pairs = listOf(
            ActivePairData(uid = 1001, op = SgEnum.OP_ACCEL, pkgHash = pkg(1, 1, 1)),
            ActivePairData(uid = 1002, op = SgEnum.OP_GYRO, pkgHash = pkg(2, 2, 2)),
        )
        val buf = FbSerde.encodeTickInput(tickId = 42L, nowNs = 999_999L, tier = 0, pairs = pairs)
        val tablePos = FbReader.rootTable(buf)

        val tickIdPos = FbReader.fieldPos(buf, tablePos, 0)!!
        assertEquals(42L, FbReader.i64(buf, tickIdPos))

        val nowNsPos = FbReader.fieldPos(buf, tablePos, 1)!!
        assertEquals(999_999L, FbReader.i64(buf, nowNsPos))

        val vecFieldPos = FbReader.fieldPos(buf, tablePos, 2)!!
        val vecPos = FbReader.offsetFieldTarget(buf, vecFieldPos)
        val count = FbReader.i32(buf, vecPos)
        assertEquals(2, count)

        val elemBase = vecPos + 4
        // 元素 0
        assertEquals(1001, FbReader.i32(buf, elemBase))
        assertEquals(SgEnum.OP_ACCEL, buf[elemBase + 4].toInt())
        assertEquals(1, buf[elemBase + 5].toInt())
        // 元素 1(每个元素固定 20 字节)
        val elem1 = elemBase + 20
        assertEquals(1002, FbReader.i32(buf, elem1))
        assertEquals(SgEnum.OP_GYRO, buf[elem1 + 4].toInt())
        assertEquals(2, buf[elem1 + 5].toInt())
    }

    @Test
    fun `empty active pairs list encodes valid zero-length vector`() {
        val buf = FbSerde.encodeTickInput(tickId = 1L, nowNs = 2L, tier = 0, pairs = emptyList())
        val tablePos = FbReader.rootTable(buf)
        val vecFieldPos = FbReader.fieldPos(buf, tablePos, 2)!!
        val vecPos = FbReader.offsetFieldTarget(buf, vecFieldPos)
        assertEquals(0, FbReader.i32(buf, vecPos))
    }

    @Test
    fun `rejects pkgHash with wrong length`() {
        assertThrowsIllegalArgument {
            ActivePairData(uid = 1, op = SgEnum.OP_ACCEL, pkgHash = ByteArray(5))
        }
        assertThrowsIllegalArgument {
            OpEventData(
                tsNs = 0, uid = 1, pkgHash = ByteArray(5),
                op = SgEnum.OP_ACCEL, phase = SgEnum.PHASE_START,
                ctx = CtxTagData(0, false, false, 0, false, false, false, false),
            )
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var thrown = false
        try { block() } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue("expected IllegalArgumentException", thrown)
    }
}