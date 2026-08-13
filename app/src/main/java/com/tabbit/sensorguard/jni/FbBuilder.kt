package com.tabbit.sensorguard.jni

/**
 * 最小依赖的 FlatBuffers 构建器,只实现 sensorguard.fbs 用到的子集:
 * scalar 字段、struct 内联写入、table + vtable、vector<struct>、root finish()。
 * 严格遵循标准 FlatBuffers 二进制布局(小端,缓冲区从尾部向前增长)。
 *
 * 关键不变式(务必保留,勿在后续修改中破坏):
 * 1. offset() 的值是"距离最终缓冲区末尾的字节数",在 growBuffer() 前后保持不变
 *    (growBuffer 把旧数据拷到新数组末尾、并把 space 增加同等增量,这是该不变式成立的原因)。
 * 2. 所有 offset 类引用(root uoffset、table 的 offset 字段)遵循
 *    "目标绝对位置 = 引用位置 + 该位置处存储的整数值"。
 * 3. struct 只能在 table 构建期间(nested=true)内联写入;vector/嵌套 table
 *    必须在 startTable() 之前完整构建好,以 offset 形式被引用。
 */
class FbBuilder(initialCapacity: Int = 256) {

    private var bb = ByteArray(initialCapacity)
    private var space = initialCapacity
    private var minalign = 1
    private var vtable: IntArray? = null
    private var vtableInUse = 0
    private var objectStart = 0
    private var nested = false
    private var vectorNumElems = 0

    // ---------- 缓冲区增长 ----------

    private fun growBuffer() {
        val old = bb
        val newCap = maxOf(old.size * 2, 1)
        val nb = ByteArray(newCap)
        System.arraycopy(old, 0, nb, newCap - old.size, old.size)
        space += newCap - old.size
        bb = nb
    }

    /** 当前写入位置相对缓冲区末尾的偏移量(不变式见类注释)。*/
    fun offset(): Int = bb.size - space

    // ---------- 对齐与容量预留(标准 FlatBuffers Prep 算法) ----------

    private fun prep(size: Int, additionalBytes: Int) {
        if (size > minalign) minalign = size
        val used = bb.size - space
        val alignMask = size - 1
        val alignSize = (size - ((used + additionalBytes) % size)) and alignMask
        while (space < alignSize + size + additionalBytes) growBuffer()
        repeat(alignSize) { writeRawU8(0) }
    }

    /** struct 专用:一次性对齐并预留整块 struct 的容量,内部字段随后用 writeRaw* 无需再次 prep。*/
    fun prepStruct(align: Int, totalSize: Int) {
        prep(align, totalSize - align)
    }

    fun startVector(elemSize: Int, numElems: Int, alignment: Int) {
        check(!nested) { "FbBuilder: cannot start vector while a table is open" }
        prep(4, elemSize * numElems)
        if (alignment > 4) prep(alignment, elemSize * numElems)
        vectorNumElems = numElems
    }

    fun endVector(): Int {
        writeRawI32(vectorNumElems)
        return offset()
    }

    // ---------- 底层无对齐检查的原始写入(struct/vector 元素内部使用) ----------

    fun writeRawU8(v: Int) { space -= 1; bb[space] = (v and 0xFF).toByte() }

    fun writeRawU16(v: Int) {
        space -= 2
        bb[space] = (v and 0xFF).toByte()
        bb[space + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun writeRawI32(v: Int) {
        space -= 4
        var x = v
        for (i in 0 until 4) { bb[space + i] = (x and 0xFF).toByte(); x = x ushr 8 }
    }

    fun writeRawI64(v: Long) {
        space -= 8
        var x = v
        for (i in 0 until 8) { bb[space + i] = (x and 0xFF).toByte(); x = x ushr 8 }
    }

    fun writeRawF32(v: Float) = writeRawI32(java.lang.Float.floatToRawIntBits(v))

    fun writeRawPad(n: Int) { repeat(n) { writeRawU8(0) } }

    // ---------- 带 Prep 的标量写入(table 顶层标量字段使用) ----------

    private fun putU8(v: Int) { prep(1, 0); writeRawU8(v) }
    private fun putU16(v: Int) { prep(2, 0); writeRawU16(v) }
    private fun putI32(v: Int) { prep(4, 0); writeRawI32(v) }
    private fun putI64(v: Long) { prep(8, 0); writeRawI64(v) }

    // ---------- Table 构建 ----------

    fun startTable(numFields: Int) {
        check(!nested) { "FbBuilder: nested table not supported" }
        nested = true
        vtable = IntArray(numFields)
        vtableInUse = numFields
        objectStart = offset()
    }

    /** offset 类字段(vector/嵌套 table 引用),valueOffset==defaultOffset(通常0)时跳过不写。*/
    fun addOffsetField(slot: Int, valueOffset: Int, defaultOffset: Int) {
        if (valueOffset == defaultOffset) return
        addOffset(valueOffset)
        vtable!![slot] = offset()
    }

    /** struct 字段:struct 已在调用前内联写好,这里只登记其偏移供 vtable 使用。*/
    fun addStructField(slot: Int, structOffset: Int) {
        vtable!![slot] = structOffset
    }

    fun addScalarU8(slot: Int, v: Int, default: Int) {
        if (v == default) return
        putU8(v); vtable!![slot] = offset()
    }

    fun addScalarU16(slot: Int, v: Int, default: Int) {
        if (v == default) return
        putU16(v); vtable!![slot] = offset()
    }

    fun addScalarI32(slot: Int, v: Int, default: Int) {
        if (v == default) return
        putI32(v); vtable!![slot] = offset()
    }

    fun addScalarI64(slot: Int, v: Long, default: Long) {
        if (v == default) return
        putI64(v); vtable!![slot] = offset()
    }

    fun addScalarU64(slot: Int, v: Long, default: Long) {
        if (v == default) return
        putI64(v); vtable!![slot] = offset()
    }

    private fun addOffset(off: Int) {
        prep(4, 0)
        writeRawI32(offset() - off + 4)
    }

    /**
     * 结束当前 table:写 soffset 占位、生成并写入 vtable,回填 soffset。
     * v1.0 不做 vtable 去重(去重是性能优化,非正确性必需项,推迟到有性能压力时再做,
     * 避免为了省几十字节引入回滚逻辑的正确性风险)。
     *
     * 与 flatbuffers-rs 严格对齐的三点:
     * 1. soffset 占位(4B uoffset)前必须先 prep(4,0),确保 vtable 偏移按 4 对齐
     *    (golden 夹具 OP/tick 中 table 数据区末尾/字段前的 pad 字节正是由此产生)。
     * 2. vtable 头顺序:标准布局为 [vsz u16][tsz u16],缓冲区自尾向下生长,
     *    因此必须"先写 tsz、后写 vsz",让 vsz 落在最低地址。
     * 3. soffset 值 = vtableOffset - tableOffset(均为反向 used 坐标),
     *    绝对位置满足 table_abs = soffset 所在绝对位置, vtable_abs = table_abs - soffset。
     */
    fun endTable(): Int {
        check(nested) { "FbBuilder: endTable() called without startTable()" }
        prep(4, 0)                       // 修正①: soffset 占位前按 u32 对齐
        writeRawI32(0)                   // soffset 占位,随后回填
        val tableOffset = offset()

        val vt = vtable!!
        for (i in vtableInUse - 1 downTo 0) {
            val fieldOffset = if (vt[i] != 0) tableOffset - vt[i] else 0
            writeRawU16(fieldOffset)
        }
        writeRawU16(tableOffset - objectStart)   // 修正②: 先写 tsz(高地址)
        writeRawU16(4 + vtableInUse * 2)         // 修正②: 后写 vsz(最低地址 → 内存 [vsz][tsz])

        val vtableOffset = offset()
        writeSoffset(tableOffset, vtableOffset)

        nested = false
        vtable = null
        return tableOffset
    }

    private fun writeSoffset(tableOffset: Int, vtableOffset: Int) {
        // soffset 占位写在 tableOffset 对应的绝对位置(bb.size - tableOffset)
        val pos = bb.size - tableOffset
        // 修正③: 反向 used 坐标下 vtable 在更高地址,soffset = vtableOffset - tableOffset(正数)
        val soffset = vtableOffset - tableOffset
        var x = soffset
        for (k in 0 until 4) { bb[pos + k] = (x and 0xFF).toByte(); x = x shr 8 }
    }

    // ---------- Root / Finish ----------

    fun finish(rootTableOffset: Int): ByteArray {
        prep(minalign, 4)
        addOffset(rootTableOffset)
        return bb.copyOfRange(space, bb.size)
    }

    val used: Int get() = bb.size - space
}