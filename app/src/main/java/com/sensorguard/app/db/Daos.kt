package com.sensorguard.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * W7 事件加密落库 DAO。
 *
 * 全部采用阻塞式(非 suspend):加密落库是 GuardService 同步热路径(§9 预算 ≤20/s,
 * 单次 ≤500μs),调用线程为后台线程(sg-tick / 探针 binder 回调),阻塞 Room 安全。
 * 禁止在主线程调用(UI 读取走内存缓冲 recentEvents(),不触库)。
 */
@Dao
interface EventDao {
    @Insert
    fun insert(entity: EventEntity): Long

    /** 按时间倒序取最近 limit 条(时间线 UI / 解密回读)。*/
    @Query("SELECT * FROM event ORDER BY tsNs DESC LIMIT :limit")
    fun recent(limit: Int): List<EventEntity>

    /** 遗忘权(§8.2):密文本身也可删除。*/
    @Query("DELETE FROM event")
    fun clearAll()

    @Query("SELECT COUNT(*) FROM event")
    fun count(): Long
}

/**
 * W7 keychain DAO —— `keychain(id, wrapped_dek, created_at, retired_at, status)`。
 * 同 EventDao:阻塞式,仅供后台线程调用。
 */
@Dao
interface KeychainDao {
    @Insert
    fun upsert(entity: KeychainEntity)

    @Update
    fun update(entity: KeychainEntity)

    @Query("SELECT * FROM keychain WHERE id = :id")
    fun get(id: Int): KeychainEntity?

    @Query("SELECT * FROM keychain ORDER BY id ASC")
    fun all(): List<KeychainEntity>

    @Query("SELECT * FROM keychain WHERE status = 0 ORDER BY id ASC LIMIT 1")
    fun active(): KeychainEntity?

    @Query("DELETE FROM keychain WHERE id = :id")
    fun delete(id: Int)

    /** 遗忘权(§8.2):销毁全部 DEK 包裹密钥,密文瞬间变随机字节。*/
    @Query("DELETE FROM keychain")
    fun wipeAll()
}