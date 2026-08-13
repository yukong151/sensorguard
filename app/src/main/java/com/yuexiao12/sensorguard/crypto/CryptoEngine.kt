package com.yuexiao12.sensorguard.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 加密唯一性断言违反 / 密文篡改 / 密钥生命周期违规时抛出,调用方进入 Safe Mode(文档 §10)。*/
class SafeModeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * W7 (文档 §8.2):AES-256-GCM 记录加解密 + DEK 包裹/解裹,纯 JVM(无 Android 依赖,可单测)。
 *
 * 每条记录布局(§8.2):
 *   record = version(1B,=1) || key_id(2B BE) || iv(12B) || ciphertext || tag(16B)
 * IV 生成(§8.2,禁止纯随机 IV):
 *   iv = counter(64-bit LE) || random(32-bit)
 *   counter 由 CounterStore 原子递增 + 持久化;加密前由 CounterGuard 断言 (key_id, counter) 唯一。
 *
 * GCM doFinal 输出天然为 ciphertext||tag(16B),与 §8.2 布局一致。
 */
object CryptoEngine {
    const val TRANSFORM = "AES/GCM/NoPadding"
    const val RECORD_VERSION = 1
    const val KEY_ID_BYTES = 2
    const val IV_BYTES = 12 // counter 8B LE + random 4B
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 1 + KEY_ID_BYTES + IV_BYTES // 15
    private const val TAG_BITS = TAG_BYTES * 8

    private val random = SecureRandom()

    /** 加密一条记录。keyId 需在 1..65535。*/
    fun encryptRecord(dek: SecretKey, keyId: Int, counter: Long, payload: ByteArray): ByteArray {
        require(keyId in 1..0xFFFF) { "key_id must fit 2 bytes, got $keyId" }
        val iv = ivFor(counter)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(payload) // ciphertext || tag(16B)
        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = RECORD_VERSION.toByte()
        out[1] = ((keyId shr 8) and 0xFF).toByte()
        out[2] = (keyId and 0xFF).toByte()
        System.arraycopy(iv, 0, out, 3, IV_BYTES)
        System.arraycopy(body, 0, out, HEADER_BYTES, body.size)
        return out
    }

    /** 解密记录。版本不符 / 长度非法 / GCM 标签不匹配(篡改)一律抛 SafeModeException。*/
    fun decryptRecord(dek: SecretKey, record: ByteArray): ByteArray {
        if (record.size < HEADER_BYTES + TAG_BYTES) throw SafeModeException("record too short")
        val version = record[0].toInt() and 0xFF
        if (version != RECORD_VERSION) throw SafeModeException("unsupported record version $version")
        val iv = record.copyOfRange(3, 3 + IV_BYTES)
        val body = record.copyOfRange(HEADER_BYTES, record.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (e: Exception) {
            throw SafeModeException("decrypt failed (tampered?)", e)
        }
    }

    /** 记录头中的 key_id(BE,2B)。*/
    fun keyIdOf(record: ByteArray): Int {
        if (record.size < HEADER_BYTES) throw SafeModeException("record too short")
        return ((record[1].toInt() and 0xFF) shl 8) or (record[2].toInt() and 0xFF)
    }

    /** IV = counter(64-bit LE) || random(32-bit),文档 §8.2。*/
    fun ivFor(counter: Long): ByteArray {
        val iv = ByteArray(IV_BYTES)
        var v = counter
        for (i in 0 until 8) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        // Android 34 stub 无 SecureRandom.nextBytes(byte[],off,len)(Java 9+),手动填充随机尾。
        val tail = ByteArray(4).also { random.nextBytes(it) }
        System.arraycopy(tail, 0, iv, 8, 4)
        return iv
    }

    /**
     * KEK 包裹 DEK(§8.2:新旧 DEK 均由 KEK 包裹存 keychain),输出 iv(12B)||GCM(ciphertext||tag)。
     *
     * 注意:KEK 为 AndroidKeyStore 密钥,默认 randomizedEncryptionRequired=true,**禁止调用方自带 IV**。
     * 因此此处 init 不传 GCMParameterSpec,由密钥库自动生成 IV 后经 getIV() 取出并前置存储;
     * 解密端 unwrapDek 按此约定从包裹首 12B 读回 IV。布局与 §8.2 一致,无需改动。
     */
    fun wrapDek(kek: SecretKey, dek: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek) // 不传 IV → AndroidKeyStore 自动生成
        val iv = cipher.getIV() // 12B,系统生成(SunJCE 亦自动生成,单测可用)
        val body = cipher.doFinal(dek.encoded)
        return iv + body
    }

    /** 解裹 DEK;包裹被篡改抛 SafeModeException。*/
    fun unwrapDek(kek: SecretKey, wrapped: ByteArray): SecretKey {
        if (wrapped.size < IV_BYTES + TAG_BYTES) throw SafeModeException("wrapped DEK too short")
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val body = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
            SecretKeySpec(cipher.doFinal(body), "AES")
        } catch (e: Exception) {
            throw SafeModeException("unwrap DEK failed", e)
        }
    }
}
