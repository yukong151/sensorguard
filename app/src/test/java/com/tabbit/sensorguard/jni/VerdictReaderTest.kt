package com.tabbit.sensorguard.jni

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictReaderTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    // golden 夹具字节(core-rust ffi::tests::dump_fb_fixtures_hex 输出)
    private val FB_FIXTURE_VERDICT_BATCH =
        "14000000000000000C002400200014000C0004000C00000000002A36FE9C9717" +
        "0000A0D8855734162A000000000000000000000004000000010000001C000000" +
        "180034003300320031002C002A002400180010000000040018000000" +
        "BBBBBBBBBBBBBBBBBBBBBBBB00002A36FE9C97170000A0D88557341600000000" +
        "10000000000067000000003F0055020202000000000000000000003E0000403F" +
        "01000000333353400000803F"

    @Test
    fun `decode VerdictBatch matches golden fixture field-for-field`() {
        val buf = unhex(FB_FIXTURE_VERDICT_BATCH)
        val batch = VerdictReader.decode(buf)

        // VerdictBatch 根表字段
        assertEquals(42L, batch.tickId)
        assertEquals(1_600_000_000_000_000_000L, batch.wallStartNs)
        assertEquals(1_700_000_000_000_000_000L, batch.wallEndNs)
        assertEquals(1, batch.schemaVersion)

        // 单条 Verdict
        assertEquals(1, batch.verdicts.size)
        val v = batch.verdicts[0]
        assertEquals(SgEnum.VERDICT_ALERT, v.kind)
        assertEquals(SgEnum.CAT_STEALTH_HOURS, v.category)
        assertEquals(85, v.severity)
        assertEquals(0.5f, v.sCtx, 1e-6f)
        assertEquals(103, v.ruleId)
        assertEquals(1_600_000_000_000_000_000L, v.windowStartNs)
        assertEquals(1_700_000_000_000_000_000L, v.windowEndNs)
        assertEquals(SgEnum.TIER_T0_BASIC, v.evidenceTier)
        assertArrayEquals(ByteArray(12) { 0xBB.toByte() }, v.pkgHash)
        assertEquals(SgEnum.OP_RECORD_AUDIO, v.op)
        assertFalse(v.degraded)

        // top3 struct vector
        assertEquals(2, v.top3.size)
        assertEquals(0, v.top3[0].featureId)
        assertEquals(0.125f, v.top3[0].value, 1e-6f)
        assertEquals(0.75f, v.top3[0].contribution, 1e-6f)
        assertEquals(1, v.top3[1].featureId)
        assertEquals(3.3f, v.top3[1].value, 1e-6f)
        assertEquals(1.0f, v.top3[1].contribution, 1e-6f)
    }

    @Test
    fun `round-trip encode OpEvent then decode via VerdictReader primitives`() {
        // 复用 FbSerde 编码器 + VerdictReader 底层原语,验证 i32/i64/f32 读取一致性
        val buf = FbSerde.encodeTickInput(tickId = 7L, nowNs = 8L, tier = 0, pairs = emptyList())
        // now_ns 字段 slot 1 的绝对位置由 fieldPos 计算
        val tablePos = VerdictReader.rootTable(buf)
        val nowPos = VerdictReader.fieldPos(buf, tablePos, 1)!!
        assertEquals(8L, VerdictReader.i64(buf, nowPos))
        val tickIdPos = VerdictReader.fieldPos(buf, tablePos, 0)!!
        assertEquals(7L, VerdictReader.i64(buf, tickIdPos))
    }

    @Test
    fun `rejects truncated buffer`() {
        val buf = ByteArray(4)
        try {
            VerdictReader.decode(buf)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}