package com.jilla

import com.jilla.crypto.Sodium
import com.jilla.transport.ChunkFraming
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class ChunkFramingTest {
    @Test
    fun roundTripAcrossMtus() {
        for (mtuPayload in listOf(20, 23, 182, 244, 509)) {
            for (size in listOf(1, 17, 20, 500, 717, 4096)) {
                val p = Sodium.randomBytes(size)
                val r = ChunkFraming.Reassembler()
                val chunks = ChunkFraming.split(p, mtuPayload)
                chunks.dropLast(1).forEach { assertNull(r.feed(it)) }
                assertContentEquals(p, r.feed(chunks.last()), "mtu=$mtuPayload size=$size")
            }
        }
    }

    @Test
    fun garbageAndOrphanChunksIgnored() {
        val r = ChunkFraming.Reassembler()
        assertNull(r.feed(byteArrayOf(0x00, 1, 2)))          // continuation without a start
        assertNull(r.feed(byteArrayOf(0x07, 1, 2)))          // unknown flag
        assertNull(r.feed(byteArrayOf(0x01, 0x7f, 0x7f, 1))) // declared length too big
        val p = Sodium.randomBytes(100)
        val c = ChunkFraming.split(p, 20)
        c.forEach { r.feed(it) }
        assertContentEquals(p, ChunkFraming.Reassembler().let { rr -> c.map { rr.feed(it) }.last() })
    }
}
