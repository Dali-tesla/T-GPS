package com.dali.teslagps.tesla

import java.io.ByteArrayOutputStream

/**
 * 최소 protobuf 인코더/디코더.
 * Tesla BLE 프로토콜에 필요한 wire type 0(varint), 2(length-delimited), 5(fixed32)만 쓴다.
 */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    fun varint(field: Int, value: Long): PbWriter {
        tag(field, 0); rawVarint(value); return this
    }

    fun bytes(field: Int, value: ByteArray): PbWriter {
        tag(field, 2); rawVarint(value.size.toLong()); out.write(value, 0, value.size); return this
    }

    fun fixed32(field: Int, value: Long): PbWriter {
        tag(field, 5)
        for (i in 0..3) out.write(((value shr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun message(field: Int, build: PbWriter.() -> Unit): PbWriter =
        bytes(field, PbWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class PbField(val number: Int, val wire: Int, val value: Long, val data: ByteArray?)

object Pb {
    fun parse(buf: ByteArray): List<PbField> {
        val fields = ArrayList<PbField>()
        var pos = 0

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= buf.size || shift > 63) throw IllegalArgumentException("잘못된 varint")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        while (pos < buf.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (wire) {
                0 -> fields.add(PbField(number, 0, readVarint(), null))
                1 -> {
                    if (pos + 8 > buf.size) throw IllegalArgumentException("fixed64 범위 초과")
                    var v = 0L
                    for (i in 0..7) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    fields.add(PbField(number, 1, v, null))
                }
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > buf.size) throw IllegalArgumentException("length 범위 초과")
                    fields.add(PbField(number, 2, 0, buf.copyOfRange(pos, pos + len)))
                    pos += len
                }
                5 -> {
                    if (pos + 4 > buf.size) throw IllegalArgumentException("fixed32 범위 초과")
                    var v = 0L
                    for (i in 0..3) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 4
                    fields.add(PbField(number, 5, v, null))
                }
                else -> throw IllegalArgumentException("지원하지 않는 wire type $wire")
            }
        }
        return fields
    }
}

fun List<PbField>.bytesOf(n: Int): ByteArray? = lastOrNull { it.number == n && it.wire == 2 }?.data
fun List<PbField>.varintOf(n: Int): Long? = lastOrNull { it.number == n && it.wire == 0 }?.value
fun List<PbField>.fixed32Of(n: Int): Long? = lastOrNull { it.number == n && it.wire == 5 }?.value
fun List<PbField>.floatOf(n: Int): Float? =
    fixed32Of(n)?.let { java.lang.Float.intBitsToFloat(it.toInt()) }
fun List<PbField>.subOf(n: Int): List<PbField>? = bytesOf(n)?.let { Pb.parse(it) }
