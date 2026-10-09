package com.jilla

import com.jilla.crypto.Sodium
import com.jilla.protocol.hexToBytes
import com.jilla.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vectors produced by PyNaCl 1.6 (libsodium). Pins byte-level interop with the backend. */
class SodiumInteropTest {
    private val rsk = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()
    private val rpk = "8f40c5adb68f25624ae5b214ea767a6ec94d829d3d7b5e1ad1ba6f3e2138285f"
    private val pynaclSealed =
        "1a9885f1c509e58f7cc639b3d27b5903c62e9189ddf564c81d9889e8ec266c77fbdd48f7ec6c5602bce1b56f116bac12" +
            "5c20720f99c03536c50ac29ea9d1b54e9b7d5f8416344d810eb3083a2100dacd54dffb36dbc4531b"

    @Test
    fun x25519PublicKeyMatchesLibsodium() {
        assertEquals(rpk, Sodium.BoxKeyPair.fromSecret(rsk).publicKey.toHex())
    }

    @Test
    fun opensSealedBoxProducedByPyNaCl() {
        val plain = Sodium.boxSealOpen(pynaclSealed.hexToBytes(), Sodium.BoxKeyPair.fromSecret(rsk))
        assertEquals("SOS interop vector: libsodium sealed box", plain?.decodeToString())
    }

    @Test
    fun ed25519MatchesLibsodium() {
        val kp = Sodium.SignKeyPair.fromSeed("6465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f80818283".hexToBytes())
        assertEquals("0bbc346a57667c380120bd9c7fd7e51d2c5fdfea37cd2f5bf405b2c6bf6f2d78", kp.publicKey.toHex())
        val sig = Sodium.sign("binding".toByteArray(), kp.seed)
        assertEquals(
            "6b2103fd5ca3c8ebe44c21f132f0381a5ba4bb9a5ca8475a5befe01f6476455692308777482772d676879821646e3ba40cb8955312a8f4867e847eb31e8e9c01",
            sig.toHex(),
        )
        assertTrue(Sodium.verify(sig, "binding".toByteArray(), kp.publicKey))
    }

    @Test
    fun sealOpenRoundTrip() {
        val kp = Sodium.BoxKeyPair.generate()
        val msg = Sodium.randomBytes(300)
        val sealed = Sodium.boxSeal(msg, kp.publicKey)
        assertEquals(msg.size + Sodium.SEAL_OVERHEAD, sealed.size)
        assertContentEquals(msg, Sodium.boxSealOpen(sealed, kp))
    }

    @Test
    fun sealedBoxRejectsWrongKeyAndTamper() {
        val kp = Sodium.BoxKeyPair.generate()
        val sealed = Sodium.boxSeal("secret".toByteArray(), kp.publicKey)
        assertNull(Sodium.boxSealOpen(sealed, Sodium.BoxKeyPair.generate()))
        for (i in sealed.indices) {
            val t = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(Sodium.boxSealOpen(t, kp), "tamper at byte $i accepted")
        }
        assertNull(Sodium.boxSealOpen(sealed.copyOf(40), kp))
    }
}
