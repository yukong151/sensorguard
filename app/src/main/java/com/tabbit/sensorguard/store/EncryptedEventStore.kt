package com.tabbit.sensorguard.store

import com.tabbit.sensorguard.crypto.DekManager
import com.tabbit.sensorguard.crypto.SafeModeException
import com.tabbit.sensorguard.probe.ProbeEvent

/** 一条已落库加密事件的持久化行(仅明文元数据 + 密文,见 db.EventEntity)。*/
data class EventRow(val tsNs: Long, val keyId: Int, val record: ByteArray)

/**
 * 事件密文的持久化 sink。生产实现为 Room(db.RoomEventSink → EventDao);
 * 纯 JVM 接口使 EncryptedEventStore 可单测(提供内存实现)。
 * 注意:实现方仅供后台线程调用(阻塞 Room)。
 */
interface EventSink {
    fun insert(tsNs: Long, keyId: Int, record: ByteArray): Long
    fun recent(limit: Int): List<EventRow>
    fun clearAll()
    fun count(): Long
}

/**
 * W7 (文档 §8.2):加密事件存储 —— 探针事件的唯一性断言 + 加密落库 + 解密回读。
 *
 * 链路:ProbeEvent → EventCodec.encode(明文载荷) → DekManager.encrypt
 *       [(key_id,counter) 唯一断言,违反立即 SafeMode] → AES-256-GCM 记录 → EventSink 落库。
 *
 * - 落库字段:tsNs(明文,时间线排序)+ keyId(明文,定位 DEK)+ record(密文)。
 * - 解密:按 keyId 取 DEK(active/retired),GCM 标签校验,篡改抛 SafeModeException。
 * - 遗忘权 wipeAll:销毁全部 DEK 包裹密钥 + 清空密文(§8.2)。
 */
class EncryptedEventStore(
    private val dekManager: DekManager,
    private val sink: EventSink,
) {
    /** 加密并落库一条探针事件。违反 (key_id, counter) 唯一断言 → SafeModeException。*/
    fun saveEvent(ev: ProbeEvent) {
        val payload = EventCodec.encode(ev)
        val encrypted = dekManager.encrypt(payload)
        sink.insert(ev.tsNs, encrypted.keyId, encrypted.record)
    }

    /** 按时间倒序解密回读最近 limit 条(时间线恢复/审计)。篡改记录抛 SafeModeException。*/
    fun loadEvents(limit: Int): List<ProbeEvent> {
        val rows = sink.recent(limit)
        return rows.map { row ->
            val payload = dekManager.decrypt(row.keyId, row.record)
            EventCodec.decode(payload)
        }
    }

    /** 遗忘权(§8.2):销毁全部 DEK 包裹密钥,密文瞬间变随机字节;并清空密文行。*/
    fun wipeAll() {
        dekManager.wipeAll()
        sink.clearAll()
    }

    /** 生命周期清理(§8.2):retired 超 90 天的 DEK 包裹密钥销毁(数据不可再读)。*/
    fun destroyExpired() = dekManager.destroyExpired()

    fun count(): Long = sink.count()
}

/** JVM 单测用内存 sink。*/
class MemoryEventSink : EventSink {
    private val rows = ArrayList<EventRow>()
    override fun insert(tsNs: Long, keyId: Int, record: ByteArray): Long {
        rows += EventRow(tsNs, keyId, record)
        return rows.size.toLong()
    }

    override fun recent(limit: Int): List<EventRow> =
        rows.asReversed().take(limit).toList()

    override fun clearAll() = rows.clear()
    override fun count(): Long = rows.size.toLong()
}