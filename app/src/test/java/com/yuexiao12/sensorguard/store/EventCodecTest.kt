package com.yuexiao12.sensorguard.store

import com.yuexiao12.sensorguard.enums.SgEnum
import com.yuexiao12.sensorguard.probe.ProbeEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EventCodecTest {

    private fun event(
        tsNs: Long = 1_700_000_000_000_000_000L,
        uid: Int = 12_345,
        pkgName: String? = "com.example.app",
        pkgHash: ByteArray = ByteArray(12) { 0xAA.toByte() },
        op: Int = SgEnum.OP_CAMERA,
        phase: Int = SgEnum.PHASE_START,
        tier: Int = 0,
        source: String = "CAMERA",
    ) = ProbeEvent(tsNs, uid, pkgName, pkgHash, op, phase, tier, source)

    /** ProbeEvent.pkgHash 是 ByteArray,data class equals 按引用比较;逐字段内容断言。*/
    private fun assertEventEquals(expected: ProbeEvent, actual: ProbeEvent) {
        assertEquals(expected.tsNs, actual.tsNs)
        assertEquals(expected.uid, actual.uid)
        assertEquals(expected.pkgName, actual.pkgName)
        assertArrayEquals(expected.pkgHash, actual.pkgHash)
        assertEquals(expected.op, actual.op)
        assertEquals(expected.phase, actual.phase)
        assertEquals(expected.tier, actual.tier)
        assertEquals(expected.source, actual.source)
    }

    @Test
    fun `round-trip with full fields`() {
        val ev = event()
        assertEventEquals(ev, EventCodec.decode(EventCodec.encode(ev)))
    }

    @Test
    fun `round-trip with null pkgName and CJK source`() {
        val ev = event(pkgName = null, source = "麦克风")
        val decoded = EventCodec.decode(EventCodec.encode(ev))
        assertEventEquals(ev, decoded)
        assertNull(decoded.pkgName)
        assertEquals("麦克风", decoded.source)
    }

    @Test
    fun `unsupported version rejected`() {
        val bytes = EventCodec.encode(event())
        bytes[0] = 0x02
        assertThrows(IllegalArgumentException::class.java) { EventCodec.decode(bytes) }
    }

    @Test
    fun `truncated payload rejected`() {
        val bytes = EventCodec.encode(event())
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decode(bytes.copyOf(bytes.size / 2))
        }
    }

    @Test
    fun `pkgHash wrong length rejected`() {
        val bytes = EventCodec.encode(event())
        bytes[13] = 11
        assertThrows(IllegalArgumentException::class.java) { EventCodec.decode(bytes) }
    }
}