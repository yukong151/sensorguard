package com.yuexiao12.sensorguard.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * W7 (文档 §8.2):IV counter 单调递增源。"counter 由 EncryptedSharedPreferences 原子递增 +
 * fsync,禁止纯随机 IV"。实现必须保证:持久化后才返回、崩溃恢复后不重放(继续 +1)。
 *
 * P2-7 迁移:用 AndroidKeyStore + AES-256-GCM 直接加密存储,移除已废弃的
 * security-crypto 依赖。功能等价:KeyStore 保护 KEK,每个 counter 独立 GCM 加密,
 * synchronized + commit() 保证原子性和持久化。
 */
interface CounterStore {
    /** 原子取下一个值(从 1 开始)。同一 name 下严格单调。*/
    fun next(name: String): Long
}

/**
 * AndroidKeyStore 实现:KEK 由硬件安全模块保护,AES-256-GCM 加密每个 counter 值,
 * 加密 blob 以 base64(iv:密文) 形式存入普通 SharedPreferences。
 * commit() 同步写盘保证崩溃恢复不重放(继续 +1)。
 *
 * 线程安全:不同 name 用独立锁,同 name 下 synchronized 保证原子性。
 * 与旧 EspCounterStore 完全兼容,仅存储后端不同。
 */
class EspCounterStore(context: Context) : CounterStore {
    private val KEY_ALIAS = "sg_counter_key_v2"
    private val PREFS_NAME = "sg_counters_v2"

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val key: SecretKey = loadOrGenerateKey()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun next(name: String): Long {
        val lock = locks.computeIfAbsent(name) { Any() }
        synchronized(lock) {
            val current = decrypt(prefs.getString(name, null))
            val nxt = (current ?: 0L) + 1
            prefs.edit().putString(name, encrypt(nxt)).commit()
            return nxt
        }
    }

    // ---- 加密 / 解密 ----

    private fun encrypt(value: Long): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(ct)
    }

    private fun decrypt(encoded: String?): Long? {
        if (encoded == null) return null
        val sep = encoded.indexOf(':')
        if (sep < 0) return null
        val iv = Base64.getDecoder().decode(encoded.substring(0, sep))
        val ct = Base64.getDecoder().decode(encoded.substring(sep + 1))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8).toLong()
    }

    private fun loadOrGenerateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        kg.init(spec)
        kg.generateKey()
        // 重新加载以获取 key
        ks.load(null)
        return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}

/** JVM 单测用内存实现,与 ESP 相同单调语义。*/
class MemoryCounterStore : CounterStore {
    private val map = ConcurrentHashMap<String, Long>()
    override fun next(name: String): Long =
        map.compute(name) { _, cur -> (cur ?: 0L) + 1 }!!
}

/**
 * (key_id, counter) 唯一性守卫(文档 §8.2):加密前断言唯一,非单调即立即 Safe Mode。
 * 纯 JVM 可测;生产实现由 EncryptedEventStore 持有(进程内单线程加密路径)。
 */
class CounterGuard {
    private val last = ConcurrentHashMap<Int, Long>()

    /** 校验并记录。counter 必须严格大于该 key_id 的上一次值。*/
    fun check(keyId: Int, counter: Long) {
        val prev = last[keyId] ?: 0L
        if (counter <= prev) {
            throw SafeModeException("(key_id=$keyId, counter=$counter) 非单调,违反唯一性断言")
        }
        last[keyId] = counter
    }

    fun reset() = last.clear()
}