package com.yuexiao12.sensorguard.crypto

/**
 * W7 (文档 §8.2):DEK 生命周期状态。
 *
 * active(可加密+解密) → retired(只解密) → destroyed(90 天后 KEK 侧包裹密钥删除,数据不可再读)。
 */
enum class DekStatus { ACTIVE, RETIRED, DESTROYED }

/**
 * W7 (文档 §8.2):keychain 条目 —— 与 Room 表 `keychain(id, wrapped_dek, created_at, retired_at, status)`
 * 一一对应,同时兼作 Room 实体。
 */
data class KeychainEntry(
    /** key_id,2B 存储(1..65535)。文档 §8.2 record 头 `key_id(2B)`。*/
    val id: Int,
    /** KEK 包裹后的 DEK(AES-256-GCM iv(12B)||ciphertext||tag),KEK 永不出芯片。*/
    val wrappedDek: ByteArray,
    val createdAtMs: Long,
    val retiredAtMs: Long? = null,
    val status: DekStatus = DekStatus.ACTIVE,
) {
    fun retire(atMs: Long = System.currentTimeMillis()) =
        copy(retiredAtMs = atMs, status = DekStatus.RETIRED)

    /** destroyed = 删除 KEK 侧包裹密钥;条目本身可留痕迹(数据不可再读)。*/
    fun markDestroyed() = copy(status = DekStatus.DESTROYED)
}

/**
 * W7 (文档 §8.2):keychain 存储 —— DEK 包裹密钥的持久化容器。
 *
 * 生产实现为 Room(见 db/KeychainDao);纯 JVM 接口便于单测(提供内存实现)。
 */
interface KeychainStore {
    fun upsert(entry: KeychainEntry)

    fun get(id: Int): KeychainEntry?

    /** 全部条目(按 id 升序)。*/
    fun all(): List<KeychainEntry>

    fun delete(id: Int)

    /** 遗忘权(§8.2):销毁全部 DEK 包裹密钥,密文瞬间变随机字节。*/
    fun wipeAll()

    /** 当前未 destroy 的 active DEK(无则 null)。*/
    fun active(): KeychainEntry? = all().firstOrNull { it.status == DekStatus.ACTIVE }
}

/** JVM 单测用内存实现,语义与 Room 一致。*/
class MemoryKeychainStore : KeychainStore {
    private val map = LinkedHashMap<Int, KeychainEntry>()

    @Synchronized
    override fun upsert(entry: KeychainEntry) {
        map[entry.id] = entry
    }

    @Synchronized
    override fun get(id: Int): KeychainEntry? = map[id]

    @Synchronized
    override fun all(): List<KeychainEntry> = map.values.toList()

    @Synchronized
    override fun delete(id: Int) {
        map.remove(id)
    }

    @Synchronized
    override fun wipeAll() {
        map.clear()
    }
}