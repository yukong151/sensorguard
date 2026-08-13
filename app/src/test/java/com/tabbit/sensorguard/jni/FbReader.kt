package com.tabbit.sensorguard.jni

/** 仅用于单元测试的最小 FlatBuffers 读取器,不参与 release 编译。*/
object FbReader {
    private fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xFF
    fun u16(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)
    fun i32(b: ByteArray, p: Int): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or u8(b, p + i)
        return r
    }
    fun i64(b: ByteArray, p: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (u8(b, p + i).toLong())
        return r
    }

    fun rootTable(b: ByteArray): Int = i32(b, 0)

    /** 返回字段绝对位置;vtable 中标记为未设置(0)则返回 null(读取方应回退为 schema 默认值)。*/
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
}