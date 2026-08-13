package com.sensorguard.app.crypto

import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** 一条已加密记录的产出:(key_id, record 字节)。*/
data class EncryptedRecord(val keyId: Int, val record: ByteArray)

/**
 * W7 (文档 §8.2):DEK 编排器。
 *
 * - DEK 随机 256-bit,每 30 天或累计 2^32 条自动轮换,新旧 DEK 均由 KEK 包裹存 keychain。
 * - IV counter 按 key_id 独立单调(CounterStore,"dek:<id>"),加密前经 CounterGuard 断言
 *   (key_id, counter) 唯一,违反立即 Safe Mode。
 * - 生命周期: active(可加密/解密) → retired(只解密) → destroyed(90 天后包裹密钥删除)。
 * - 遗忘权 wipeAll: 销毁全部 DEK 包裹密钥,所有密文不可再读。
 *
 * 纯 JVM(无 Android 依赖):KEK 由调用方注入(设备端 KekProvider.getOrCreate()),可单测。
 */
class DekManager(
    private val kek: SecretKey,
    private val keychain: KeychainStore,
    private val counters: CounterStore,
    private val guard: CounterGuard,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private var activeId: Int? = null

    /** 加密一条载荷,返回 (key_id, record)。轮换到期/无 active 时先轮换。*/
    @Synchronized
    fun encrypt(payload: ByteArray): EncryptedRecord {
        val id = ensureActive()
        val counter = counters.next(counterName(id))
        if (counter >= MAX_RECORDS) {
            // 累计 2^32 条轮换:退役当前 DEK,新 DEK 计数器从 1 重新开始。
            retire(id)
            val fresh = ensureActive()
            val c2 = counters.next(counterName(fresh))
            guard.check(fresh, c2)
            return EncryptedRecord(fresh, CryptoEngine.encryptRecord(dekOf(fresh), fresh, c2, payload))
        }
        guard.check(id, counter)
        return EncryptedRecord(id, CryptoEngine.encryptRecord(dekOf(id), id, counter, payload))
    }

    /** 用 key_id 对应的 DEK(active 或 retired)解密。未知/已销毁 key_id → Safe Mode。*/
    @Synchronized
    fun decrypt(keyId: Int, record: ByteArray): ByteArray {
        val entry = keychain.get(keyId)
            ?: throw SafeModeException("key_id=$keyId 未知或已销毁,数据不可再读(§8.2)")
        if (entry.status == DekStatus.DESTROYED) {
            throw SafeModeException("key_id=$keyId 已 destroyed,数据不可再读(§8.2)")
        }
        return CryptoEngine.decryptRecord(dekOf(keyId), record)
    }

    /** 遗忘权(§8.2):销毁全部 DEK 包裹密钥,密文瞬间变随机字节。*/
    @Synchronized
    fun wipeAll() {
        activeId = null
        guard.reset()
        keychain.wipeAll()
    }

    /**
     * 生命周期清理(§8.2):retired 超过 90 天 → destroyed(删除 KEK 侧包裹密钥)。
     * 由 GuardService 定期(如每日)调用。
     */
    @Synchronized
    fun destroyExpired(thresholdMs: Long = nowMs() - RETIRED_RETENTION_MS) {
        for (e in keychain.all()) {
            if (e.status == DekStatus.RETIRED && (e.retiredAtMs ?: 0L) < thresholdMs) {
                keychain.delete(e.id)
                if (activeId == e.id) activeId = null
            }
        }
    }

    /** 取当前 active DEK,无则创建;超过 30 天则轮换。*/
    private fun ensureActive(): Int {
        val cur = activeId?.let { keychain.get(it) }?.takeIf { it.status == DekStatus.ACTIVE }
            ?: keychain.active()
        activeId = when {
            cur == null -> createDek()
            nowMs() - cur.createdAtMs >= DEK_ROTATION_MS -> {
                retire(cur.id)
                createDek()
            }
            else -> cur.id
        }
        return activeId!!
    }

    /** 退役 active DEK(只解密),由 KEY+id 后续轮换创建新 DEK。retiredAt 用注入时钟,保证可测且语义一致。*/
    private fun retire(id: Int) {
        val e = keychain.get(id) ?: return
        keychain.upsert(e.retire(nowMs()))
    }

    /** 生成随机 256-bit DEK,KEK 包裹后写入 keychain,返回其 key_id。*/
    private fun createDek(): Int {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val dek = SecretKeySpec(raw, "AES")
        val wrapped = CryptoEngine.wrapDek(kek, dek)
        val id = nextKeyId()
        keychain.upsert(
            KeychainEntry(id = id, wrappedDek = wrapped, createdAtMs = nowMs(), status = DekStatus.ACTIVE)
        )
        return id
    }

    /** key_id 分配:1 起递增,跳过已占用(2B 上限 65535)。*/
    private fun nextKeyId(): Int {
        val used = keychain.all().map { it.id }.toSet()
        var id = 1
        while (id in used) {
            id++
            if (id > 0xFFFF) {
                // 65535 个 DEK 全部占用:强制遗忘后重建(理论需 2^32 条记录才可能,防御性处理)。
                keychain.wipeAll()
                id = 1
            }
        }
        return id
    }

    private fun dekOf(id: Int): SecretKey {
        val entry = keychain.get(id)
            ?: throw SafeModeException("keychain 缺失 key_id=$id")
        return CryptoEngine.unwrapDek(kek, entry.wrappedDek)
    }

    private fun counterName(keyId: Int) = "dek:$keyId"

    companion object {
        /** 每 30 天轮换(§8.2)。*/
        const val DEK_ROTATION_MS = 30L * 24 * 3600 * 1000
        /** 累计 2^32 条记录轮换(§8.2)。*/
        const val MAX_RECORDS = 1L shl 32
        /** retired 保留 90 天后 destroyed(§8.2)。*/
        const val RETIRED_RETENTION_MS = 90L * 24 * 3600 * 1000
    }
}