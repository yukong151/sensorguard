package com.tabbit.sensorguard.jni

/** Verdict (table) 解码结果,字段名与 schemas/sensorguard.fbs 中 Verdict 表一致。*/
data class VerdictEntryData(
    val kind: Int,                    // VerdictKind
    val category: Int,                // ViolationCat
    val severity: Int,                // 0..255
    val sCtx: Float,
    val ruleId: Int,
    val top3: List<FeatureContributionData>,
    val windowStartNs: Long,
    val windowEndNs: Long,
    val evidenceTier: Int,            // EvidenceTier
    val pkgHash: ByteArray,           // PkgHash,12 字节
    val op: Int,                      // OpKind
    val degraded: Boolean,
)

/** FeatureContrib (struct) 解码结果。*/
data class FeatureContributionData(
    val featureId: Int,
    val value: Float,
    val contribution: Float,
)

/** VerdictBatch (root table) 解码结果。*/
data class VerdictBatchData(
    val verdicts: List<VerdictEntryData>,
    val tickId: Long,
    val wallStartNs: Long,
    val wallEndNs: Long,
    val schemaVersion: Int,
)

/**
 * 生产级 FlatBuffers 解码器,用于解析 sgTick 输出的 VerdictBatch。
 * 与 src/test/.../FbReader.kt 使用相同的最小读取原语(通过 golden 夹具验证),
 * 但带 schema 字段映射,可进入 release 编译。
 *
 * 关键约定(与 schema 一致):
 *  - table 字段: vtable 中未设置(offset=0)的字段读取方回退 schema 默认值。
 *  - offset 字段: 字段位置存 u32 偏移,目标 = 字段位置 + 偏移。
 *  - struct 字段 (PkgHash): 内联存储,字段位置即结构体数据,无偏移跳转。
 *  - struct vector (top3/active_pairs): 元素内联,固定步长。
 *  - table vector (verdicts): 元素为 u32 偏移,先解引用再读表。
 */
object VerdictReader {

    private fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF
    fun u16(b: ByteArray, p: Int): Int = u8(b, p) or (u8(b, p + 1) shl 8)
    fun i32(b: ByteArray, p: Int): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or u8(b, p + i)
        return r
    }
    fun i64(b: ByteArray, p: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or u8(b, p + i).toLong()
        return r
    }
    private fun f32(b: ByteArray, p: Int): Float = Float.fromBits(i32(b, p))

    fun rootTable(b: ByteArray): Int = i32(b, 0)

    /** 返回字段绝对位置;vtable 未设置(0)返回 null,读取方回退 schema 默认值。*/
    fun fieldPos(b: ByteArray, tablePos: Int, slot: Int): Int? {
        val soffset = i32(b, tablePos)
        val vtablePos = tablePos - soffset
        val vtableLen = u16(b, vtablePos)
        val slotEntryPos = 4 + slot * 2
        if (slotEntryPos >= vtableLen) return null
        val fieldOffset = u16(b, vtablePos + slotEntryPos)
        return if (fieldOffset == 0) null else tablePos + fieldOffset
    }

    fun offsetFieldTarget(b: ByteArray, fieldPos: Int): Int = fieldPos + i32(b, fieldPos)

    /** 解析 sgTick 输出的完整 VerdictBatch 缓冲区。非法输入抛 IllegalArgumentException。*/
    fun decode(buf: ByteArray): VerdictBatchData {
        if (buf.size < 8) throw IllegalArgumentException("buffer too small: ${buf.size}")
        val root = rootTable(buf)
        val verdicts = readVerdictVector(buf, root, SLOT_VERDICTS)
        val tickId = fieldPos(buf, root, SLOT_TICK_ID)?.let { i64(buf, it) } ?: 0L
        val wallStart = fieldPos(buf, root, SLOT_WALL_START)?.let { i64(buf, it) } ?: 0L
        val wallEnd = fieldPos(buf, root, SLOT_WALL_END)?.let { i64(buf, it) } ?: 0L
        val schemaVersion = fieldPos(buf, root, SLOT_SCHEMA_VERSION)?.let { u16(buf, it) } ?: 1
        return VerdictBatchData(verdicts, tickId, wallStart, wallEnd, schemaVersion)
    }

    // VerdictBatch 字段 slot(.fbs 声明序 0-based)
    private const val SLOT_VERDICTS = 0
    private const val SLOT_TICK_ID = 1
    private const val SLOT_WALL_START = 2
    private const val SLOT_WALL_END = 3
    private const val SLOT_SCHEMA_VERSION = 4

    // Verdict 字段 slot(.fbs 声明序 0-based)
    private const val V_SLOT_KIND = 0
    private const val V_SLOT_CATEGORY = 1
    private const val V_SLOT_SEVERITY = 2
    private const val V_SLOT_S_CTX = 3
    private const val V_SLOT_RULE_ID = 4
    private const val V_SLOT_TOP3 = 5
    private const val V_SLOT_WINDOW_START = 6
    private const val V_SLOT_WINDOW_END = 7
    private const val V_SLOT_EVIDENCE_TIER = 8
    private const val V_SLOT_PKG_HASH = 9
    private const val V_SLOT_OP = 10
    private const val V_SLOT_DEGRADED = 11

    /** FeatureContrib struct 大小: feature_id(1B)+pad(3B)+value(4B)+contrib(4B)=12B,align 4。*/
    private const val FEATURE_CONTRIB_SIZE = 12

    /** 解析 verdicts table vector: 元素为 u32 偏移,指向 Verdict table。*/
    private fun readVerdictVector(buf: ByteArray, tablePos: Int, slot: Int): List<VerdictEntryData> {
        val fieldPos = fieldPos(buf, tablePos, slot) ?: return emptyList()
        val vecPos = offsetFieldTarget(buf, fieldPos)
        val count = i32(buf, vecPos)
        if (count < 0 || count > 1024) throw IllegalArgumentException("invalid verdict count: $count")
        val base = vecPos + 4
        val result = ArrayList<VerdictEntryData>(count)
        for (i in 0 until count) {
            val elemPos = base + i * 4
            val verdictPos = elemPos + i32(buf, elemPos)
            result.add(readVerdict(buf, verdictPos))
        }
        return result
    }

    private fun readVerdict(buf: ByteArray, tablePos: Int): VerdictEntryData {
        val kind = fieldPos(buf, tablePos, V_SLOT_KIND)?.let { u8(buf, it) } ?: SgEnum.VERDICT_LEGIT
        val category = fieldPos(buf, tablePos, V_SLOT_CATEGORY)?.let { u8(buf, it) } ?: SgEnum.CAT_NONE
        val severity = fieldPos(buf, tablePos, V_SLOT_SEVERITY)?.let { u8(buf, it) } ?: 0
        val sCtx = fieldPos(buf, tablePos, V_SLOT_S_CTX)?.let { f32(buf, it) } ?: 0f
        val ruleId = fieldPos(buf, tablePos, V_SLOT_RULE_ID)?.let { u16(buf, it) } ?: 0
        val top3 = readTop3(buf, tablePos)
        val windowStart = fieldPos(buf, tablePos, V_SLOT_WINDOW_START)?.let { i64(buf, it) } ?: 0L
        val windowEnd = fieldPos(buf, tablePos, V_SLOT_WINDOW_END)?.let { i64(buf, it) } ?: 0L
        val evidenceTier = fieldPos(buf, tablePos, V_SLOT_EVIDENCE_TIER)?.let { u8(buf, it) } ?: SgEnum.TIER_T0_BASIC
        val pkgHash = readPkgHash(buf, tablePos)
        val op = fieldPos(buf, tablePos, V_SLOT_OP)?.let { u8(buf, it) } ?: SgEnum.OP_RECORD_AUDIO
        val degraded = fieldPos(buf, tablePos, V_SLOT_DEGRADED)?.let { u8(buf, it) != 0 } ?: false
        return VerdictEntryData(
            kind, category, severity, sCtx, ruleId, top3,
            windowStart, windowEnd, evidenceTier, pkgHash, op, degraded,
        )
    }

    /** pkg_hash struct 字段: 内联存储,字段位置即 12 字节结构体。*/
    private fun readPkgHash(buf: ByteArray, tablePos: Int): ByteArray {
        val pos = fieldPos(buf, tablePos, V_SLOT_PKG_HASH)
            ?: return ByteArray(12)
        val out = ByteArray(12)
        for (i in 0 until 12) out[i] = buf[pos + i]
        return out
    }

    /** top3 struct vector: 元素内联固定 12B。*/
    private fun readTop3(buf: ByteArray, tablePos: Int): List<FeatureContributionData> {
        val fieldPos = fieldPos(buf, tablePos, V_SLOT_TOP3) ?: return emptyList()
        val vecPos = offsetFieldTarget(buf, fieldPos)
        val count = i32(buf, vecPos)
        if (count < 0 || count > 64) throw IllegalArgumentException("invalid feature count: $count")
        val base = vecPos + 4
        val result = ArrayList<FeatureContributionData>(count)
        for (i in 0 until count) {
            val e = base + i * FEATURE_CONTRIB_SIZE
            result.add(FeatureContributionData(u8(buf, e), f32(buf, e + 4), f32(buf, e + 8)))
        }
        return result
    }
}