package com.jilla.crypto

/**
 * HSalsa20 core (libsodium `crypto_core_hsalsa20`), needed to derive the
 * crypto_box shared key exactly as libsodium/PyNaCl do. BouncyCastle uses
 * HSalsa20 inside XSalsa20Engine but does not expose it.
 */
internal object HSalsa20 {
    private const val C0 = 0x61707865
    private const val C1 = 0x3320646e
    private const val C2 = 0x79622d32
    private const val C3 = 0x6b206574

    fun derive(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce16.size == 16) { "nonce must be 16 bytes" }

        var x0 = C0
        var x1 = le32(key, 0); var x2 = le32(key, 4); var x3 = le32(key, 8); var x4 = le32(key, 12)
        var x5 = C1
        var x6 = le32(nonce16, 0); var x7 = le32(nonce16, 4); var x8 = le32(nonce16, 8); var x9 = le32(nonce16, 12)
        var x10 = C2
        var x11 = le32(key, 16); var x12 = le32(key, 20); var x13 = le32(key, 24); var x14 = le32(key, 28)
        var x15 = C3

        repeat(10) {
            // column round
            x4 = x4 xor (x0 + x12).rotateLeft(7);  x8 = x8 xor (x4 + x0).rotateLeft(9)
            x12 = x12 xor (x8 + x4).rotateLeft(13); x0 = x0 xor (x12 + x8).rotateLeft(18)
            x9 = x9 xor (x5 + x1).rotateLeft(7);   x13 = x13 xor (x9 + x5).rotateLeft(9)
            x1 = x1 xor (x13 + x9).rotateLeft(13); x5 = x5 xor (x1 + x13).rotateLeft(18)
            x14 = x14 xor (x10 + x6).rotateLeft(7); x2 = x2 xor (x14 + x10).rotateLeft(9)
            x6 = x6 xor (x2 + x14).rotateLeft(13); x10 = x10 xor (x6 + x2).rotateLeft(18)
            x3 = x3 xor (x15 + x11).rotateLeft(7); x7 = x7 xor (x3 + x15).rotateLeft(9)
            x11 = x11 xor (x7 + x3).rotateLeft(13); x15 = x15 xor (x11 + x7).rotateLeft(18)
            // row round
            x1 = x1 xor (x0 + x3).rotateLeft(7);   x2 = x2 xor (x1 + x0).rotateLeft(9)
            x3 = x3 xor (x2 + x1).rotateLeft(13);  x0 = x0 xor (x3 + x2).rotateLeft(18)
            x6 = x6 xor (x5 + x4).rotateLeft(7);   x7 = x7 xor (x6 + x5).rotateLeft(9)
            x4 = x4 xor (x7 + x6).rotateLeft(13);  x5 = x5 xor (x4 + x7).rotateLeft(18)
            x11 = x11 xor (x10 + x9).rotateLeft(7); x8 = x8 xor (x11 + x10).rotateLeft(9)
            x9 = x9 xor (x8 + x11).rotateLeft(13); x10 = x10 xor (x9 + x8).rotateLeft(18)
            x12 = x12 xor (x15 + x14).rotateLeft(7); x13 = x13 xor (x12 + x15).rotateLeft(9)
            x14 = x14 xor (x13 + x12).rotateLeft(13); x15 = x15 xor (x14 + x13).rotateLeft(18)
        }

        val out = ByteArray(32)
        intArrayOf(x0, x5, x10, x15, x6, x7, x8, x9).forEachIndexed { i, v -> putLe32(out, i * 4, v) }
        return out
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
}
