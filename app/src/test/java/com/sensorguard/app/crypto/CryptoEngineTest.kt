package com.sensorguard.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CryptoEngineTest {

    private fun aes256(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    // ---------- 记录布局(§8.2) ----------

    @Test
    fun `record layout version keyId iv body tag`() {
        val key = aes256()
        val payload = "hello".toByteArray()
        val rec = CryptoEngine.encryptRecord(key, keyId = 7, counter = 42L, payload = payload)
        assertEquals(0x01, rec[0].toInt() and 0xFF) // version(1B)=1
        assertEquals(0, rec[1].toInt() and 0xFF)    // keyId BE
        assertEquals(7, rec[2].toInt() and 0xFF)
        // iv(12B) + ciphertext + tag(16B)
        assertEquals(CryptoEngine.HEADER_BYTES + payload.size + CryptoEngine.TAG_BYTES, rec.size)
        assertEquals(7, CryptoEngine.keyIdOf(rec))
        assertArrayEquals(payload, CryptoEngine.decryptRecord(key, rec))
    }

    @Test
    fun `keyId fits 2 bytes and rejects out of range`() {
        val key = aes256()
        val rec = CryptoEngine.encryptRecord(key, keyId = 0xFFFF, counter = 1, payload = byteArrayOf(1))
        assertEquals(0xFFFF, CryptoEngine.keyIdOf(rec))
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.encryptRecord(key, keyId = 0, counter = 1, payload = byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoEngine.encryptRecord(key, keyId = 0x10000, counter = 1, payload = byteArrayOf(1))
        }
    }

    @Test
    fun `IV embeds counter LE64 and a random 32bit tail`() {
        val a = CryptoEngine.ivFor(0x0102030405060708L)
        assertEquals(CryptoEngine.IV_BYTES, a.size)
        // counter 部分按小端:0x08,0x07,0x06,0x05,0x04,0x03,0x02,0x01
        val expected = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)
        for (i in 0 until 8) assertEquals("iv[$i]", expected[i].toInt(), a[i].toInt() and 0xFF)
        val b = CryptoEngine.ivFor(0x0102030405060708L)
        // 同 counter 的随机尾不同 -> 不同 IV(但仍然唯一,因 counter 部分相同)
        assertNotEquals(hex(a.copyOfRange(8, 12)), hex(b.copyOfRange(8, 12)))
        // 不同 counter -> IV 整体不同
        assertNotEquals(hex(a), hex(CryptoEngine.ivFor(0x0102030405060709L)))
    }

    @Test
    fun `same counter and key never produces identical records`() {
        val key = aes256()
        val a = CryptoEngine.encryptRecord(key, 1, 5, byteArrayOf(1, 2, 3))
        val b = CryptoEngine.encryptRecord(key, 1, 5, byteArrayOf(1, 2, 3))
        assertNotEquals(hex(a), hex(b)) // random IV tail 介入
    }

    // ---------- 篡改检测 ----------

    @Test
    fun `tampered record fails GCM`() {
        val key = aes256()
        val rec = CryptoEngine.encryptRecord(key, 3, 9, "secret".toByteArray())
        rec[rec.size - 1] = (rec[rec.size - 1].toInt() xor 0x01).toByte() // 翻转 tag 尾字节
        assertThrows(SafeModeException::class.java) { CryptoEngine.decryptRecord(key, rec) }
    }

    @Test
    fun `unsupported version and short record rejected`() {
        val key = aes256()
        assertThrows(SafeModeException::class.java) {
            CryptoEngine.decryptRecord(key, byteArrayOf(0x02) + ByteArray(30))
        }
        assertThrows(SafeModeException::class.java) {
            CryptoEngine.decryptRecord(key, ByteArray(10))
        }
    }

    // ---------- KEK 包裹 DEK(§8.2) ----------

    @Test
    fun `wrap and unwrap DEK round-trip`() {
        val kek = aes256()
        val dek = aes256()
        val wrapped = CryptoEngine.wrapDek(kek, dek)
        val unwrapped = CryptoEngine.unwrapDek(kek, wrapped)
        assertArrayEquals(dek.encoded, unwrapped.encoded)
    }

    @Test
    fun `unwrap with wrong kek or tampered blob fails`() {
        val kek1 = aes256(); val kek2 = aes256()
        val dek = aes256()
        val wrapped = CryptoEngine.wrapDek(kek1, dek)
        assertThrows(SafeModeException::class.java) { CryptoEngine.unwrapDek(kek2, wrapped) }
        wrapped[0] = (wrapped[0].toInt() xor 0x01).toByte()
        assertThrows(SafeModeException::class.java) { CryptoEngine.unwrapDek(kek1, wrapped) }
    }
}