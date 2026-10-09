package com.jilla.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.Arrays as BcArrays
import java.security.SecureRandom

/**
 * libsodium-compatible primitives implemented on BouncyCastle (pure JVM, so the
 * same code runs in JVM unit tests and on Android without JNA/native libs).
 *
 *  - Ed25519 detached signatures       == crypto_sign_detached / verify_detached
 *  - X25519                            == crypto_scalarmult
 *  - Sealed box                        == crypto_box_seal / crypto_box_seal_open
 *      epk || XSalsa20-Poly1305(k, nonce, m)
 *      k     = HSalsa20(X25519(esk, rpk), 0^16)          (crypto_box_beforenm)
 *      nonce = BLAKE2b-192(epk || rpk)
 *
 * Interop with PyNaCl is pinned by test vectors in SodiumInteropTest.
 */
object Sodium {
    const val SIGN_PUBLIC_BYTES = 32
    const val SIGN_SEED_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val BOX_PUBLIC_BYTES = 32
    const val BOX_SECRET_BYTES = 32
    const val MAC_BYTES = 16
    const val SEAL_OVERHEAD = BOX_PUBLIC_BYTES + MAC_BYTES

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---- Ed25519 ------------------------------------------------------------

    /** Ed25519 keypair. [seed] is the 32-byte private seed (libsodium sk = seed || pk). */
    class SignKeyPair(val seed: ByteArray, val publicKey: ByteArray) {
        companion object {
            fun generate(): SignKeyPair = fromSeed(randomBytes(SIGN_SEED_BYTES))

            fun fromSeed(seed: ByteArray): SignKeyPair {
                require(seed.size == SIGN_SEED_BYTES)
                val sk = Ed25519PrivateKeyParameters(seed, 0)
                return SignKeyPair(seed.copyOf(), sk.generatePublicKey().encoded)
            }
        }
    }

    fun sign(message: ByteArray, seed: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES || publicKey.size != SIGN_PUBLIC_BYTES) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            false // malformed public key
        }
    }

    // ---- X25519 / sealed box ------------------------------------------------

    class BoxKeyPair(val secretKey: ByteArray, val publicKey: ByteArray) {
        companion object {
            fun generate(): BoxKeyPair = fromSecret(randomBytes(BOX_SECRET_BYTES))

            fun fromSecret(secret: ByteArray): BoxKeyPair {
                require(secret.size == BOX_SECRET_BYTES)
                val sk = X25519PrivateKeyParameters(secret, 0)
                return BoxKeyPair(secret.copyOf(), sk.generatePublicKey().encoded)
            }
        }
    }

    fun boxSeal(message: ByteArray, recipientPk: ByteArray): ByteArray {
        require(recipientPk.size == BOX_PUBLIC_BYTES)
        val eph = BoxKeyPair.generate()
        val nonce = sealNonce(eph.publicKey, recipientPk)
        val k = beforenm(recipientPk, eph.secretKey)
        val boxed = secretbox(message, nonce, k)
        BcArrays.fill(eph.secretKey, 0)
        BcArrays.fill(k, 0)
        return eph.publicKey + boxed
    }

    /** Returns plaintext, or null if the box is malformed or authentication fails. */
    fun boxSealOpen(sealed: ByteArray, recipient: BoxKeyPair): ByteArray? {
        if (sealed.size < SEAL_OVERHEAD) return null
        val epk = sealed.copyOfRange(0, BOX_PUBLIC_BYTES)
        val nonce = sealNonce(epk, recipient.publicKey)
        val k = try {
            beforenm(epk, recipient.secretKey)
        } catch (e: IllegalStateException) {
            return null // low-order point -> all-zero shared secret
        }
        return secretboxOpen(sealed.copyOfRange(BOX_PUBLIC_BYTES, sealed.size), nonce, k)
            .also { BcArrays.fill(k, 0) }
    }

    private fun sealNonce(epk: ByteArray, rpk: ByteArray): ByteArray {
        val d = Blake2bDigest(24 * 8)
        d.update(epk, 0, epk.size)
        d.update(rpk, 0, rpk.size)
        return ByteArray(24).also { d.doFinal(it, 0) }
    }

    private fun beforenm(pk: ByteArray, sk: ByteArray): ByteArray {
        val shared = ByteArray(32)
        // throws IllegalStateException on all-zero output (libsodium also rejects)
        X25519PrivateKeyParameters(sk, 0).generateSecret(X25519PublicKeyParameters(pk, 0), shared, 0)
        return HSalsa20.derive(shared, ByteArray(16)).also { BcArrays.fill(shared, 0) }
    }

    /** crypto_secretbox_easy: mac(16) || ciphertext. */
    private fun secretbox(m: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val stream = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(32)
        stream.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val c = ByteArray(m.size)
        stream.processBytes(m, 0, m.size, c, 0)
        val mac = poly1305(polyKey, c)
        BcArrays.fill(polyKey, 0)
        return mac + c
    }

    private fun secretboxOpen(boxed: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (boxed.size < MAC_BYTES) return null
        val stream = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(32)
        stream.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val mac = boxed.copyOfRange(0, MAC_BYTES)
        val c = boxed.copyOfRange(MAC_BYTES, boxed.size)
        val expected = poly1305(polyKey, c)
        BcArrays.fill(polyKey, 0)
        if (!BcArrays.constantTimeAreEqual(mac, expected)) return null
        val m = ByteArray(c.size)
        stream.processBytes(c, 0, c.size, m, 0)
        return m
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }
}
