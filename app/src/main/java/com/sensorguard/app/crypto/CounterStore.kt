package com.sensorguard.app.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * W7 (文档 §8.2):IV counter 单调递增源。"counter 由 EncryptedSharedPreferences 原子递增 +
 * fsync,禁止纯随机 IV"。实现必须保证:持久化后才返回、崩溃恢复后不重放(继续 +1)。
 */
interface CounterStore {
    /** 原子取下一个值(从 1 开始)。同一 name 下严格单调。*/
    fun next(name: String): Long
}

/**
 * EncryptedSharedPreferences 实现:同步 commit() 落盘(近似 fsync);同进程内
 * synchronized 保证原子性。App 为单进程常驻服务场景,满足文档要求。
 * 注:security-crypto 已被 Google 标记 deprecated(供应链锁版本,文档 §14),API 稳定。
 */
class EspCounterStore(context: Context) : CounterStore {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sg_counters",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val locks = ConcurrentHashMap<String, Any>()

    override fun next(name: String): Long {
        val lock = locks.computeIfAbsent(name) { Any() }
        synchronized(lock) {
            val nxt = prefs.getLong(name, 0L) + 1
            prefs.edit().putLong(name, nxt).commit() // 同步写
            return nxt
        }
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
