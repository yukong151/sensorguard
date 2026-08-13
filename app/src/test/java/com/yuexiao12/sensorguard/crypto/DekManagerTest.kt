package com.yuexiao12.sensorguard.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * W7 (文档 §8.2):DEK 生命周期/轮换/遗忘权。
 * 使用内存 keychain+counter 与可控时钟,纯 JVM。
 */
class DekManagerTest {

    private fun kek(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** 可变时钟:模拟 30 天 / 90 天边界。*/
    private fun clock(now: AtomicLong): () -> Long = { now.get() }

    private fun manager(
        kek: SecretKey = kek(),
        keychain: KeychainStore = MemoryKeychainStore(),
        counters: CounterStore = MemoryCounterStore(),
        now: AtomicLong = AtomicLong(1_000_000_000L),
    ) = DekManager(kek, keychain, counters, CounterGuard(), clock(now))

    // ---------- 基础加解密 ----------

    @Test
    fun `encrypt then decrypt round-trip`() {
        val m = manager()
        val payload = ByteArray(64) { it.toByte() }
        val rec = m.encrypt(payload)
        assertTrue(rec.keyId in 1..0xFFFF)
        assertTrue(rec.record.size >= CryptoEngine.HEADER_BYTES + payload.size + CryptoEngine.TAG_BYTES)
        assertEquals(rec.keyId, CryptoEngine.keyIdOf(rec.record))
        assertTrue(m.decrypt(rec.keyId, rec.record).contentEquals(payload))
    }

    @Test
    fun `consecutive encrypts keep same DEK while fresh`() {
        val keychain = MemoryKeychainStore()
        val m = manager(keychain = keychain)
        val a = m.encrypt(ByteArray(1))
        val b = m.encrypt(ByteArray(1))
        // 未到期不轮换
        assertEquals(a.keyId, b.keyId)
        assertEquals(DekStatus.ACTIVE, keychain.active()?.status)
    }

    @Test
    fun `active DEK stored wrapped in keychain`() {
        val keychain = MemoryKeychainStore()
        val m = manager(keychain = keychain)
        m.encrypt(ByteArray(1))
        val entry = keychain.active()
        assertTrue(entry != null && entry.wrappedDek.size >= CryptoEngine.IV_BYTES + CryptoEngine.TAG_BYTES)
    }

    // ---------- 30 天轮换 ----------

    @Test
    fun `rotate after 30 days retires old DEK`() {
        val now = AtomicLong(1_000_000_000L)
        val keychain = MemoryKeychainStore()
        val m = manager(keychain = keychain, now = now)
        val first = m.encrypt(ByteArray(1))
        val oldId = first.keyId
        // 尚未到期 -> 同一 DEK
        now.addAndGet(DekManager.DEK_ROTATION_MS - 1)
        val same = m.encrypt(ByteArray(1))
        assertEquals(oldId, same.keyId)

        // 跨过 30 天 -> 新 DEK,旧 DEK retired(只解密)
        now.addAndGet(2)
        val rotated = m.encrypt(ByteArray(1))
        assertNotEquals(oldId, rotated.keyId)
        val oldEntry = keychain.get(oldId)!!
        assertEquals(DekStatus.RETIRED, oldEntry.status)

        // 旧 DEK 仍可解密旧记录
        assertTrue(m.decrypt(first.keyId, first.record).contentEquals(ByteArray(1)))
        // 新 DEK 解密新记录
        assertTrue(m.decrypt(rotated.keyId, rotated.record).contentEquals(ByteArray(1)))
    }

    // ---------- ### 2^32 条轮换(通过计数器驱动) ----------

    @Test
    fun `retire when counter crosses 2^32`() {
        val keychain = MemoryKeychainStore()
        // 把 DEK#1 的计数器预置到 2^32-2:第 1 条记录 counter=2^32-1(未触发),
        // 第 2 条记录 counter=2^32 -> 触发轮换,DEK#1 retired、新建 DEK#2。
        val counters = SeededCounterStore(mapOf("dek:1" to (DekManager.MAX_RECORDS - 2)))
        val m = manager(keychain = keychain, counters = counters)
        val first = m.encrypt(ByteArray(1))
        assertEquals(1, first.keyId) // 未到边界,仍用 DEK#1
        val second = m.encrypt(ByteArray(1))
        assertNotEquals(first.keyId, second.keyId) // 跨界 -> 轮换,新 DEK
        assertEquals(DekStatus.RETIRED, keychain.get(first.keyId)!!.status)
        // 旧 DEK 只解密,新 DEK 正常加解密
        assertTrue(m.decrypt(first.keyId, first.record).contentEquals(ByteArray(1)))
        assertTrue(m.decrypt(second.keyId, second.record).contentEquals(ByteArray(1)))
    }

    /** 各 name 以 seeds 给定初值起步的单调计数器(模拟 counter 已累计多条的边界状态)。*/
    private class SeededCounterStore(private val seeds: Map<String, Long>) : CounterStore {
        private val values = ConcurrentHashMap(seeds)
        override fun next(name: String): Long {
            val prev = values[name] ?: 0L
            values[name] = prev + 1
            return prev + 1
        }
    }

    // ---------- 遗忘权 / 生命周期销毁 ----------

    @Test
    fun `wipeAll destroys all DEK and data unreadable`() {
        val keychain = MemoryKeychainStore()
        val m = manager(keychain = keychain)
        val rec = m.encrypt(ByteArray(8))
        m.wipeAll()
        assertTrue(keychain.all().isEmpty())
        assertThrows(SafeModeException::class.java) { m.decrypt(rec.keyId, rec.record) }
        // wipeAll 后可重新加密(新 DEK)
        val fresh = m.encrypt(ByteArray(8))
        assertTrue(m.decrypt(fresh.keyId, fresh.record).contentEquals(ByteArray(8)))
    }

    @Test
    fun `destroyExpired removes retired DEK after 90 days`() {
        val now = AtomicLong(1_000_000_000L)
        val keychain = MemoryKeychainStore()
        val m = manager(keychain = keychain, now = now)
        val first = m.encrypt(ByteArray(1))
        // 轮换 -> first 变 retired
        now.addAndGet(DekManager.DEK_ROTATION_MS)
        m.encrypt(ByteArray(1))
        assertEquals(DekStatus.RETIRED, keychain.get(first.keyId)!!.status)

        // 90 天未到 -> 仍可解密
        now.addAndGet(DekManager.RETIRED_RETENTION_MS - 1)
        m.destroyExpired()
        assertTrue(m.decrypt(first.keyId, first.record).contentEquals(ByteArray(1)))

        // 跨过 90 天 -> 包裹密钥销毁,数据不可再读
        now.addAndGet(2)
        m.destroyExpired()
        assertThrows(SafeModeException::class.java) { m.decrypt(first.keyId, first.record) }
    }

    @Test
    fun `decrypt unknown keyId enter SafeMode`() {
        val m = manager()
        assertThrows(SafeModeException::class.java) { m.decrypt(99, ByteArray(40)) }
    }
}