package com.yuexiao12.sensorguard.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yuexiao12.sensorguard.crypto.KeychainEntry
import com.yuexiao12.sensorguard.crypto.KeychainStore
import com.yuexiao12.sensorguard.store.EventRow
import com.yuexiao12.sensorguard.store.EventSink

/**
 * W7 (文档 §8.1):Room 数据库。
 *
 * - version=2,exportSchema=true: schema JSON 写入 app/schemas/ 提交入库(ksp 配置),
 *   PR 变表必须提供 migration(文档 §8.1 + CI 门禁)。
 * - 单例:App 为单进程常驻服务(GuardService),无需多进程访问。
 *
 * P2-6: version 1→2 新增 attribution 表(pkg_hash_hex, pkg_name, uid, first_seen_ms),
 * 用于持久化 uid→包名映射,使设备重启后仍可解析告警/事件时间线上的包指纹归属。
 */
@Database(
    entities = [EventEntity::class, KeychainEntity::class, AttributionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SgDb : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun keychainDao(): KeychainDao
    abstract fun attributionDao(): AttributionDao

    companion object {
        @Volatile private var instance: SgDb? = null

        /** P2-6: v1→v2 migration —— 新建 attribution 表。*/
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `attribution` (
                        `pkgHashHex` TEXT NOT NULL,
                        `pkgName` TEXT NOT NULL,
                        `uid` INTEGER NOT NULL,
                        `firstSeenMs` INTEGER NOT NULL,
                        PRIMARY KEY(`pkgHashHex`)
                    )""".trimIndent()
                )
            }
        }

        fun get(context: Context): SgDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SgDb::class.java, "sg.db")
                // 文档 §8.1:变表必须 migration;默认(无 fallback)即禁止破坏性回退
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * 生产 KeychainStore:Room 后端(crypto 接口 → db 实体转换)。
 * 保证 DEK 包裹密钥跨进程存活(重启后 KEK 解裹继续可用)。
 * 同步实现:DAO 为阻塞式,仅后台线程调用(sg-tick 线程),与 DekManager 同步路径一致。
 */
class RoomKeychainStore(private val dao: KeychainDao) : KeychainStore {
    override fun upsert(entry: KeychainEntry) {
        dao.upsert(entry.toEntity())
    }

    override fun get(id: Int): KeychainEntry? = dao.get(id)?.toEntry()

    override fun all(): List<KeychainEntry> = dao.all().map { it.toEntry() }

    override fun delete(id: Int) = dao.delete(id)

    override fun wipeAll() = dao.wipeAll()

    override fun active(): KeychainEntry? = dao.active()?.toEntry()
}

/**
 * 生产 EventSink:Room 后端(db.EventEntity ↔ store.EventRow 转换)。
 * 同步实现:DAO 为阻塞式,仅后台线程调用(sg-tick 线程),与加密落库同步路径一致。
 */
class RoomEventSink(private val dao: EventDao) : EventSink {
    override fun insert(tsNs: Long, keyId: Int, record: ByteArray): Long =
        dao.insert(EventEntity(tsNs = tsNs, keyId = keyId, record = record))

    override fun recent(limit: Int): List<EventRow> =
        dao.recent(limit).map { EventRow(it.tsNs, it.keyId, it.record) }

    override fun before(beforeTsNs: Long, limit: Int): List<EventRow> =
        dao.before(beforeTsNs, limit).map { EventRow(it.tsNs, it.keyId, it.record) }

    override fun clearAll() = dao.clearAll()

    override fun count(): Long = dao.count()
}

private fun KeychainEntry.toEntity() = KeychainEntity(
    id = id, wrappedDek = wrappedDek, createdAtMs = createdAtMs,
    retiredAtMs = retiredAtMs, status = when (status) {
        com.yuexiao12.sensorguard.crypto.DekStatus.ACTIVE -> 0
        com.yuexiao12.sensorguard.crypto.DekStatus.RETIRED -> 1
        com.yuexiao12.sensorguard.crypto.DekStatus.DESTROYED -> 2
    },
)

private fun KeychainEntity.toEntry() = KeychainEntry(
    id = id, wrappedDek = wrappedDek, createdAtMs = createdAtMs,
    retiredAtMs = retiredAtMs, status = when (status) {
        0 -> com.yuexiao12.sensorguard.crypto.DekStatus.ACTIVE
        1 -> com.yuexiao12.sensorguard.crypto.DekStatus.RETIRED
        else -> com.yuexiao12.sensorguard.crypto.DekStatus.DESTROYED
    },
)