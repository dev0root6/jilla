package com.jilla.transport

import java.io.ByteArrayOutputStream

/**
 * Splits packets into BLE-sized chunks and reassembles them. A GATT notification or
 * write carries at most (ATT MTU - 3) bytes, far less than a ~500-byte SOS packet.
 *
 *   first chunk:        0x01 | len_hi | len_lo | data...
 *   continuation chunk: 0x00 | data...
 *
 * Same format is implemented in backend/dashboard.html (Web Bluetooth receiver).
 */
object ChunkFraming {
    const val FIRST = 0x01
    const val CONT = 0x00
    const val MAX_PACKET = 4096

    fun split(packet: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk >= 4) { "chunk too small" }
        require(packet.size in 1..MAX_PACKET) { "bad packet size" }
        val out = ArrayList<ByteArray>()
        val firstData = minOf(packet.size, maxChunk - 3)
        out += byteArrayOf(FIRST.toByte(), (packet.size ushr 8).toByte(), packet.size.toByte()) + packet.copyOfRange(0, firstData)
        var off = firstData
        while (off < packet.size) {
            val n = minOf(packet.size - off, maxChunk - 1)
            out += byteArrayOf(CONT.toByte()) + packet.copyOfRange(off, off + n)
            off += n
        }
        return out
    }

    /** Per-link reassembler. Returns a full packet when the last chunk arrives, else null. */
    class Reassembler {
        private var buf: ByteArrayOutputStream? = null
        private var expected = 0

        fun feed(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null
            when (chunk[0].toInt()) {
                FIRST -> {
                    if (chunk.size < 3) return reset()
                    expected = ((chunk[1].toInt() and 0xff) shl 8) or (chunk[2].toInt() and 0xff)
                    if (expected !in 1..MAX_PACKET) return reset()
                    buf = ByteArrayOutputStream().apply { write(chunk, 3, chunk.size - 3) }
                }
                CONT -> (buf ?: return null).write(chunk, 1, chunk.size - 1)
                else -> return reset()
            }
            val b = buf!!
            if (b.size() > expected) return reset()
            if (b.size() == expected) { buf = null; return b.toByteArray() }
            return null
        }

        private fun reset(): ByteArray? { buf = null; expected = 0; return null }
    }
}
