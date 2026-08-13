package com.yuexiao12.sensorguard.jni

/** 与 schemas/sensorguard.fbs 中 OpKind/Phase 枚举值保持一致,勿单独修改。*/
object SgEnum {
    const val OP_RECORD_AUDIO = 0
    const val OP_CAMERA = 1
    const val OP_FINE_LOCATION = 2
    const val OP_ACCEL = 10
    const val OP_GYRO = 11
    const val OP_MAG = 12
    const val OP_BARO = 13
    const val OP_LIGHT = 14
    const val OP_PROX = 15

    const val PHASE_START = 0
    const val PHASE_STOP = 1
    const val PHASE_TICK = 2

    const val VERDICT_LEGIT = 0
    const val VERDICT_OBSERVE = 1
    const val VERDICT_ALERT = 2

    const val CAT_NONE = 0
    const val CAT_OUT_OF_SCOPE = 1
    const val CAT_STEALTH_HOURS = 2
    const val CAT_SIDE_CHANNEL = 3
    const val CAT_FINGERPRINT = 4

    const val TIER_T0_BASIC = 0
    const val TIER_T1_STANDARD = 1
    const val TIER_T2_ENHANCED = 2
}

data class CtxTagData(
    val fgState: Int, val userPresent: Boolean, val intentHint: Boolean,
    val declPurpose: Int, val systemProxy: Boolean, val audioFocus: Boolean,
    val powerState: Boolean, val netEgressAnomaly: Boolean,
)

data class OpEventData(
    val tsNs: Long, val uid: Int, val pkgHash: ByteArray, // 长度必须为 12
    val op: Int, val phase: Int, val ctx: CtxTagData,
    val samplingPeriodUs: Long = 0L, // W12/T2:物理采样周期(us),0=未知
) {
    init { require(pkgHash.size == 12) { "pkgHash must be exactly 12 bytes" } }
}

data class ActivePairData(val uid: Int, val op: Int, val pkgHash: ByteArray) {
    init { require(pkgHash.size == 12) { "pkgHash must be exactly 12 bytes" } }
}

/**
 * FlatBuffers 编码器,严格对应 schemas/sensorguard.fbs 的字段声明顺序与 struct 布局。
 * 修改 schema 后必须同步修改本文件的 slot 常量与字节布局,否则会产生无法被 Rust 侧解析的数据。
 *
 * 字段写入顺序 = .fbs 声明顺序(与 core-rust 的 golden 夹具 `make_op_event_full` /
 * `make_tick_input` 的 `add_*` 调用顺序一致)。flatbuffers 缓冲区自尾部向下生长,
 * 首个写入的字段落在最高地址,因此必须按声明顺序写入才能与 Rust 侧逐字节对齐。
 */
object FbSerde {

    // OpEvent 字段 slot(必须等于 .fbs 中声明顺序的 0-based 下标)
    private const val SLOT_TS_NS = 0
    private const val SLOT_UID = 1
    private const val SLOT_PKG_HASH = 2
    private const val SLOT_OP = 3
    private const val SLOT_PHASE = 4
    private const val SLOT_CTX = 5
    private const val SLOT_SAMPLING_PERIOD_US = 6 // W12/T2:物理采样周期(us)

    // TickInput 字段 slot
    private const val SLOT_TICK_ID = 0
    private const val SLOT_NOW_NS = 1
    private const val SLOT_ACTIVE_PAIRS = 2
    private const val SLOT_TIER = 3

    /** CtxTag struct:8 个 1 字节字段,按声明顺序内联写入,总大小 8,align 1。*/
    private fun encodeCtxTag(b: FbBuilder, ctx: CtxTagData): Int {
        b.prepStruct(align = 1, totalSize = 8)
        // 反序写入(缓冲区向低地址增长),最终内存中按声明顺序正向排列
        b.writeRawU8(if (ctx.netEgressAnomaly) 1 else 0)
        b.writeRawU8(if (ctx.powerState) 1 else 0)
        b.writeRawU8(if (ctx.audioFocus) 1 else 0)
        b.writeRawU8(if (ctx.systemProxy) 1 else 0)
        b.writeRawU8(ctx.declPurpose)
        b.writeRawU8(if (ctx.intentHint) 1 else 0)
        b.writeRawU8(if (ctx.userPresent) 1 else 0)
        b.writeRawU8(ctx.fgState)
        return b.offset()
    }

    /** PkgHash struct:12 个 1 字节字段,总大小 12,align 1。*/
    private fun encodePkgHash(b: FbBuilder, bytes: ByteArray): Int {
        b.prepStruct(align = 1, totalSize = 12)
        for (i in 11 downTo 0) b.writeRawU8(bytes[i].toInt() and 0xFF)
        return b.offset()
    }

    /**
     * ActivePair struct 的原始内联写入(仅供 vector 元素调用,不单独 prep,
     * 因为容量与对齐已由外层 startVector() 一次性预留)。
     * 布局: uid(4B,offset0) + op(1B,offset4) + pkg_hash(12B,offset5..16) + pad(3B) = 20B,align 4。
     */
    private fun writeActivePairRaw(b: FbBuilder, p: ActivePairData) {
        b.writeRawPad(3)
        for (i in 11 downTo 0) b.writeRawU8(p.pkgHash[i].toInt() and 0xFF)
        b.writeRawU8(p.op)
        b.writeRawI32(p.uid)
    }

    /** 编码单条 OpEvent,返回可直接传给 sg_push_op 的 FlatBuffers 字节数组。*/
    fun encodeOpEvent(ev: OpEventData): ByteArray {
        val b = FbBuilder(128)
        b.startTable(7)

        // 字段按 .fbs 声明顺序写入(首个字段落最高地址),与 golden make_op_event_full 一致
        b.addScalarI64(SLOT_TS_NS, ev.tsNs, 0L)
        b.addScalarI32(SLOT_UID, ev.uid, 0)

        val pkgOff = encodePkgHash(b, ev.pkgHash)     // slot 2
        b.addStructField(SLOT_PKG_HASH, pkgOff)

        b.addScalarU8(SLOT_OP, ev.op, SgEnum.OP_RECORD_AUDIO)
        b.addScalarU8(SLOT_PHASE, ev.phase, SgEnum.PHASE_START)

        // W12/T2:物理采样周期(us);0 表示未知,走默认不写入(与 Rust 侧 golden 对齐)
        b.addScalarI64(SLOT_SAMPLING_PERIOD_US, ev.samplingPeriodUs, 0L)

        val ctxOff = encodeCtxTag(b, ev.ctx)          // slot 5,最后内联写入
        b.addStructField(SLOT_CTX, ctxOff)

        val tableOff = b.endTable()
        return b.finish(tableOff)
    }

    /** 编码 TickInput,返回可直接传给 sg_tick 的 FlatBuffers 字节数组。*/
    fun encodeTickInput(tickId: Long, nowNs: Long, tier: Int, pairs: List<ActivePairData>): ByteArray {
        val b = FbBuilder(256 + pairs.size * 24)

        // vector 必须在 startTable() 之前完整构建好(offset 类引用规则)
        b.startVector(elemSize = 20, numElems = pairs.size, alignment = 4)
        for (i in pairs.indices.reversed()) writeActivePairRaw(b, pairs[i])
        val vecOff = b.endVector()

        b.startTable(4)
        // 按 .fbs 声明顺序写入 tick_id 首选(产生最高地址),与 golden make_tick_input 一致
        b.addScalarU64(SLOT_TICK_ID, tickId, 0L)
        b.addScalarI64(SLOT_NOW_NS, nowNs, 0L)
        b.addOffsetField(SLOT_ACTIVE_PAIRS, vecOff, 0)
        // W9 (文档 §4 P4):证据分级,0=T0_BASIC,1=T1_STANDARD
        b.addScalarU8(SLOT_TIER, tier, 0)

        val tableOff = b.endTable()
        return b.finish(tableOff)
    }
}