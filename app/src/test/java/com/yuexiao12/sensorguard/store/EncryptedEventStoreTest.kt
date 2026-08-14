package com.yuexiao12.sensorguard.store

import com.yuexiao12.sensorguard.crypto.CounterGuard
import com.yuexiao12.sensorguard.crypto.CryptoEngine
import com.yuexiao12.sensorguard.crypto.DekManager
import com.yuexiao12.sensorguard.crypto.MemoryCounterStore
import com.yuexiao12.sensorguard.crypto.MemoryKeychainStore
import com.yuexiao12.sensorguard.crypto.SafeModeException
import com.yuexiao12.sensorguard.enums.SgEnum
import com.yuexiao12.sensorguard.probe.ProbeEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * W7 (文档 §8.2):EncryptedEventStore 端到端 —— 探针事件加密落库 / 解密回读 / 遗忘权 / 篡改。
 * 纯 JVM:MemoryKeychain + MemoryCounter + MemoryEventSink。
 */
class EncryptedEventStoreTest {

    private fun store(): Pair<EncryptedEventStore, MemoryEventSink> {
        val kek: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val dekManager = DekManager(kek, MemoryKeychainStore(), MemoryCounterStore(), CounterGuard())
        val sink = MemoryEventSink()
        return EncryptedEventStore(dekManager, sink) to sink
    }

    private fun event(n: Long): ProbeEvent = ProbeEvent(
        tsNs = 1_700_000_000_000_000_000L + n, uid = (1000 + n).toInt(),
        pkgName = "com.example.app", pkgHash = ByteArray(12) { (it + n).toByte() },
        op = SgEnum.OP_CAMERA, phase = SgEnum.PHASE_START, tier = 0, source = "CAMERA",
    )

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
    fun `save and load round-trip preserves event`() {
        val (s, _) = store()
        val ev = event(1)
        s.saveEvent(ev)
        assertEquals(1, s.count())
        val loaded = s.loadEvents(10)
        assertEquals(1, loaded.size)
        assertEventEquals(ev, loaded[0])
    }

    @Test
    fun `loadEvents returns newest first`() {
        val (s, _) = store()
        s.saveEvent(event(1))
        s.saveEvent(event(2))
        s.saveEvent(event(3))
        val loaded = s.loadEvents(10)
        assertEquals(3, loaded.size)
        assertEquals(1_700_000_000_000_000_003L, loaded[0].tsNs) // 最新在前
        assertEquals(1_700_000_000_000_000_001L, loaded[2].tsNs)
    }

    @Test
    fun `loadEvents respects limit`() {
        val (s, _) = store()
        repeat(5) { s.saveEvent(event(it.toLong())) }
        assertEquals(2, s.loadEvents(2).size)
    }

    @Test
    fun `event records are encrypted not plaintext`() {
        val (s, sink) = store()
        s.saveEvent(event(1))
        val row = sink.recent(1).first()
        // 落库行只有明文元数据(tsNs/keyId)+ 密文;密文不可含明文载荷特征(版本头=1 是格式,可接受)
        assertEquals(1_700_000_000_000_000_001L, row.tsNs)
        assertTrue(row.keyId in 1..0xFFFF)
        // 密文不是明文 JSON/字节流(记录含 GCM tag,长度=头+明文+tag)
        val payload = EventCodec.encode(event(1))
        assertEquals(CryptoEngine.HEADER_BYTES + payload.size + CryptoEngine.TAG_BYTES, row.record.size)
        // 篡改密文 -> 解密抛 SafeModeException(§8.2 篡改立即 Safe Mode)
        row.record[row.record.size - 1] = (row.record.last().toInt() xor 0x01).toByte()
        assertThrows(SafeModeException::class.java) { s.loadEvents(10) }
    }

    @Test
    fun `wipeAll clears keychain and events`() {
        val (s, sink) = store()
        s.saveEvent(event(1)); s.saveEvent(event(2))
        s.wipeAll()
        assertEquals(0, s.count())
        assertTrue(sink.recent(10).isEmpty())
        // 遗忘权后仍可写入(新 DEK)
        s.saveEvent(event(3))
        assertEquals(1, s.count())
        assertEventEquals(event(3), s.loadEvents(10).first())
    }

    @Test
    fun `save events under rotation both eras readable`() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        val kek: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val dekManager = DekManager(
            kek, MemoryKeychainStore(), MemoryCounterStore(), CounterGuard(), { now.get() },
        )
        val sink = MemoryEventSink()
        val s = EncryptedEventStore(dekManager, sink)
        s.saveEvent(event(1))
        now.addAndGet(DekManager.DEK_ROTATION_MS) // 跨 30 天,触发轮换
        s.saveEvent(event(2))
        val loaded = s.loadEvents(10)
        assertEquals(2, loaded.size)
        assertTrue(loaded.any { it.tsNs == 1_700_000_000_000_000_001L })
        assertTrue(loaded.any { it.tsNs == 1_700_000_000_000_000_002L })
    }

    @Test
    fun `loadBefore returns events strictly older than cursor, newest first`() {
        val (s, _) = store()
        repeat(5) { s.saveEvent(event(it.toLong())) } // tsNs = base+0..4
        // 游标 = base+2:应只返回 base+0、base+1(更早),不含 base+2 本身
        val loaded = s.loadBefore(1_700_000_000_000_000_002L, 10)
        assertEquals(2, loaded.size)
        assertEquals(1_700_000_000_000_000_001L, loaded[0].tsNs) // 最新在前
        assertEquals(1_700_000_000_000_000_000L, loaded[1].tsNs)
    }

    @Test
    fun `loadBefore pagination advances across pages`() {
        val (s, _) = store()
        repeat(5) { s.saveEvent(event(it.toLong())) }
        // 第一页:取 < base+5 的最近 2 条 -> base+4、base+3
        val p1 = s.loadBefore(1_700_000_000_000_000_005L, 2)
        assertEquals(listOf(4L, 3L), p1.map { it.tsNs - 1_700_000_000_000_000_000L })
        // 第二页:以上一页最旧为游标 -> base+2、base+1
        val p2 = s.loadBefore(p1.last().tsNs, 2)
        assertEquals(listOf(2L, 1L), p2.map { it.tsNs - 1_700_000_000_000_000_000L })
        // 第三页 -> 只剩 base+0
        val p3 = s.loadBefore(p2.last().tsNs, 2)
        assertEquals(listOf(0L), p3.map { it.tsNs - 1_700_000_000_000_000_000L })
        // 已到底:空页
        assertEquals(0, s.loadBefore(p3.last().tsNs, 2).size)
    }
}