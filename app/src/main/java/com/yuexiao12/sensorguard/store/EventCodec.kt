package com.yuexiao12.sensorguard.store

import com.yuexiao12.sensorguard.probe.ProbeEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * W7 (文档 §8.2):ProbeEvent 加密前载荷的固定版本化二进制序列化。
 *
 * 布局(TLV,DataOutputStream 大端;长度全部显式,杜绝越界):
 *   version(1B)=1 | tsNs(8B) | uid(4B) | pkgHashLen(1B) | pkgHash(12B)
 *   | op(4B) | phase(4B) | tier(4B)
 *   | pkgNameFlag(1B: 0=null 1=有值) | [pkgNameLen(2B) | utf8]
 *   | sourceLen(2B) | source utf8
 *
 * 载荷格式与 §8.1 FlatBuffers schema 版本独立(加密本地存储自持版本号),未来追加字段
 * 只增号不改型,保持旧记录可读(§8.1 向前兼容最近 2 个大版本)。
 */
object EventCodec {
    const val PAYLOAD_VERSION = 1

    fun encode(ev: ProbeEvent): ByteArray {
        val bos = ByteArrayOutputStream(64)
        DataOutputStream(bos).use { out ->
            out.writeByte(PAYLOAD_VERSION)
            out.writeLong(ev.tsNs)
            out.writeInt(ev.uid)
            out.writeByte(ev.pkgHash.size)
            out.write(ev.pkgHash)
            out.writeInt(ev.op)
            out.writeInt(ev.phase)
            out.writeInt(ev.tier)
            val pkg = ev.pkgName
            if (pkg == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                val b = pkg.toByteArray(Charsets.UTF_8)
                out.writeShort(b.size)
                out.write(b)
            }
            val src = ev.source.toByteArray(Charsets.UTF_8)
            out.writeShort(src.size)
            out.write(src)
        }
        return bos.toByteArray()
    }

    /** 解码失败(版本不符 / 截断 / 长度越界)统一抛 IllegalArgumentException。*/
    fun decode(bytes: ByteArray): ProbeEvent = try {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        val version = inp.readUnsignedByte()
        if (version != PAYLOAD_VERSION) {
            throw IllegalArgumentException("unsupported payload version $version")
        }
        val tsNs = inp.readLong()
        val uid = inp.readInt()
        val pkgHashLen = inp.readUnsignedByte()
        if (pkgHashLen != 12) throw IllegalArgumentException("pkgHash must be 12B, got $pkgHashLen")
        val pkgHash = ByteArray(pkgHashLen).also { inp.readFully(it) }
        val op = inp.readInt()
        val phase = inp.readInt()
        val tier = inp.readInt()
        val pkgName = when (inp.readUnsignedByte()) {
            0 -> null
            1 -> {
                val len = inp.readUnsignedShort()
                ByteArray(len).also { inp.readFully(it) }.toString(Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException("bad pkgName flag")
        }
        val srcLen = inp.readUnsignedShort()
        val source = ByteArray(srcLen).also { inp.readFully(it) }.toString(Charsets.UTF_8)
        ProbeEvent(tsNs, uid, pkgName, pkgHash, op, phase, tier, source)
    } catch (e: java.io.IOException) {
        throw IllegalArgumentException("event payload truncated", e)
    }
}