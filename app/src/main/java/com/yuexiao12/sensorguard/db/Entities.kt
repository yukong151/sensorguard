package com.yuexiao12.sensorguard.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * W7 (文档 §8.2):加密事件记录落库。
 *
 * 最小明文元数据:仅 `tsNs`(时间线排序/分页)与 `keyId`(定位 DEK)明文;
 * 事件全部字段(uid/pkgHash/op/phase/...)只在 `record` 密文内,杜绝明文泄露。
 * record 布局见 CryptoEngine: version(1B) || key_id(2B) || iv(12B) || ciphertext || tag(16B)。
 */
@Entity(
    tableName = "event",
    indices = [Index(value = ["tsNs"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** wall-clock ns,与 GuardService.batchTick 同基。明文仅为时间线查询。*/
    val tsNs: Long,
    /** 加密所用 DEK 的 key_id(record 头部同值,冗余便于按 key 过滤)。*/
    val keyId: Int,
    /** AES-256-GCM 密文记录(CryptoEngine.encryptRecord 输出)。*/
    val record: ByteArray,
)

/**
 * W7 (文档 §8.2):keychain 表 —— `keychain(id, wrapped_dek, created_at, retired_at, status)`。
 *
 * wrappedDek: KEK 包裹后的 DEK(iv(12B)||ciphertext||tag);KEK 本身永不出 AndroidKeyStore。
 * status: 0=ACTIVE 1=RETIRED 2=DESTROYED(见 crypto.DekStatus)。
 */
@Entity(tableName = "keychain")
data class KeychainEntity(
    @PrimaryKey val id: Int,
    val wrappedDek: ByteArray,
    val createdAtMs: Long,
    val retiredAtMs: Long? = null,
    val status: Int = 0,
)

/**
 * P2-6: 归因映射表 —— `attribution(pkg_hash_hex, pkg_name, uid)`。
 *
 * 持久化 uid→包名映射,使设备重启后仍可解析告警/事件时间线上的包指纹归属。
 * 原先仅内存 ConcurrentHashMap(会话级),重启即丢失;现同步至 Room(v2 migration)。
 *
 * 注意:pkgHashHex 为 12 字节包指纹的 hex 编码(24 字符),作主键;
 * pkgName 可能为空串(T0 事件如 MIC/CAMERA 无法归因 uid);
 * uid 为 -1 表示 T0 无归因事件。
 */
@Entity(tableName = "attribution")
data class AttributionEntity(
    @PrimaryKey val pkgHashHex: String,
    val pkgName: String,
    val uid: Int,
    /** 首次观测时间(wall-clock ms),用于排查与审计。*/
    val firstSeenMs: Long,
)