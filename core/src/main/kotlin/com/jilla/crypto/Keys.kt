package com.jilla.crypto

import com.jilla.protocol.Protocol
import org.bouncycastle.crypto.digests.Blake2bDigest

/** Short identifier for an authority X25519 key: BLAKE2b-64(pk). */
fun authorityKeyId(pk: ByteArray): ByteArray {
    val d = Blake2bDigest(Protocol.KEY_ID_BYTES * 8)
    d.update(pk, 0, pk.size)
    return ByteArray(Protocol.KEY_ID_BYTES).also { d.doFinal(it, 0) }
}

/** Message the real identity key signs to vouch for an incident key. */
fun bindingMessage(incidentPub: ByteArray): ByteArray = Protocol.BINDING_TAG + incidentPub
