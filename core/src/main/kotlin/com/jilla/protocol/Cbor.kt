package com.jilla.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal CBOR (RFC 8949) subset: unsigned/negative ints, byte/text strings,
 * arrays, maps (text keys), float64, bool, null. Definite lengths only.
 *
 * Encoding is deterministic for a given value: maps keep insertion order, ints
 * use the shortest form. Signatures are always computed over the exact encoded
 * bytes, so decoders never need to re-encode.
 *
 * The decoder treats all input as hostile: bounded nesting depth, lengths
 * checked against remaining input, trailing bytes rejected.
 */
object Cbor {
    class DecodeException(msg: String) : Exception(msg)

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    fun decode(bytes: ByteArray, maxDepth: Int = 8): Any? {
        val r = Reader(bytes, maxDepth)
        val v = r.read(0)
        if (r.pos != bytes.size) throw DecodeException("trailing bytes")
        return v
    }

    private fun writeHead(out: ByteArrayOutputStream, major: Int, n: Long) {
        val mt = major shl 5
        when {
            n < 24 -> out.write(mt or n.toInt())
            n <= 0xff -> { out.write(mt or 24); out.write(n.toInt()) }
            n <= 0xffff -> { out.write(mt or 25); writeBe(out, n, 2) }
            n <= 0xffffffffL -> { out.write(mt or 26); writeBe(out, n, 4) }
            else -> { out.write(mt or 27); writeBe(out, n, 8) }
        }
    }

    private fun writeBe(out: ByteArrayOutputStream, n: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((n ushr (8 * i)) and 0xff).toInt())
    }

    @Suppress("UNCHECKED_CAST")
    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0xf6)
            is Boolean -> out.write(if (v) 0xf5 else 0xf4)
            is Int -> write(out, v.toLong())
            is Long -> if (v >= 0) writeHead(out, 0, v) else writeHead(out, 1, -1 - v)
            is ByteArray -> { writeHead(out, 2, v.size.toLong()); out.write(v) }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                writeHead(out, 3, b.size.toLong()); out.write(b)
            }
            is Double -> { out.write(0xfb); writeBe(out, java.lang.Double.doubleToLongBits(v), 8) }
            is List<*> -> { writeHead(out, 4, v.size.toLong()); v.forEach { write(out, it) } }
            is Map<*, *> -> {
                writeHead(out, 5, v.size.toLong())
                for ((k, value) in v) {
                    require(k is String) { "only text map keys supported" }
                    write(out, k); write(out, value)
                }
            }
            else -> throw IllegalArgumentException("unsupported CBOR type ${v::class}")
        }
    }

    private class Reader(val b: ByteArray, val maxDepth: Int) {
        var pos = 0

        private fun u8(): Int {
            if (pos >= b.size) throw DecodeException("truncated")
            return b[pos++].toInt() and 0xff
        }

        private fun be(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        private fun arg(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> be(1)
            info == 25 -> be(2)
            info == 26 -> be(4)
            info == 27 -> be(8).also { if (it < 0) throw DecodeException("length overflow") }
            else -> throw DecodeException("indefinite/reserved length not supported")
        }

        private fun len(info: Int): Int {
            val n = arg(info)
            if (n > b.size - pos) throw DecodeException("length exceeds input")
            return n.toInt()
        }

        fun read(depth: Int): Any? {
            if (depth > maxDepth) throw DecodeException("nesting too deep")
            val ib = u8()
            val major = ib ushr 5
            val info = ib and 0x1f
            return when (major) {
                0 -> arg(info)
                1 -> -1 - arg(info)
                2 -> { val n = len(info); b.copyOfRange(pos, pos + n).also { pos += n } }
                3 -> { val n = len(info); String(b, pos, n, Charsets.UTF_8).also { pos += n } }
                4 -> { val n = len(info); List(n) { read(depth + 1) } }
                5 -> {
                    val n = len(info)
                    val m = LinkedHashMap<String, Any?>()
                    repeat(n) {
                        val k = read(depth + 1) as? String ?: throw DecodeException("non-text map key")
                        if (m.containsKey(k)) throw DecodeException("duplicate map key $k")
                        m[k] = read(depth + 1)
                    }
                    m
                }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    27 -> java.lang.Double.longBitsToDouble(be(8))
                    else -> throw DecodeException("unsupported simple/float $info")
                }
                else -> throw DecodeException("unsupported major type $major")
            }
        }
    }
}
