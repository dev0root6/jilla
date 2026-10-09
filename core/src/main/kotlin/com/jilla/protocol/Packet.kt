package com.jilla.protocol

import com.jilla.crypto.Sodium

class MalformedPacketException(msg: String) : Exception(msg)

/**
 * Cleartext, signed header. Everyone (including bystander relays) can read it.
 * Wire form: CBOR map {v, aid, ipk, ts, ctr, gh} in that order.
 */
data class PublicHeader(
    val version: Int,
    val alertId: ByteArray,
    val incidentPub: ByteArray,
    val timestampMs: Long,
    val counter: Long,
    val coarseGeohash: String,
) {
    fun encode(): ByteArray = Cbor.encode(
        linkedMapOf(
            "v" to version,
            "aid" to alertId,
            "ipk" to incidentPub,
            "ts" to timestampMs,
            "ctr" to counter,
            "gh" to coarseGeohash,
        )
    )

    val alertIdHex: String get() = alertId.toHex()

    /** One incident's update stream. alert_id alone is not trusted (see ReplayGuard). */
    val streamKey: String get() = alertIdHex + incidentPub.toHex()

    companion object {
        fun decode(bytes: ByteArray): PublicHeader {
            val m = decodeMap(bytes)
            val h = PublicHeader(
                version = m.int("v"),
                alertId = m.bytes("aid"),
                incidentPub = m.bytes("ipk"),
                timestampMs = m.long("ts"),
                counter = m.long("ctr"),
                coarseGeohash = m.str("gh"),
            )
            if (h.version != Protocol.VERSION) throw MalformedPacketException("unsupported version ${h.version}")
            if (h.alertId.size != Protocol.ALERT_ID_BYTES) throw MalformedPacketException("bad alert_id length")
            if (h.incidentPub.size != Sodium.SIGN_PUBLIC_BYTES) throw MalformedPacketException("bad incident_pub length")
            if (h.timestampMs < 0) throw MalformedPacketException("bad timestamp")
            if (h.counter !in 0..0xffffffffL) throw MalformedPacketException("counter out of u32 range")
            if (!Geohash.isValidCoarse(h.coarseGeohash)) throw MalformedPacketException("bad coarse geohash")
            return h
        }
    }

    override fun equals(other: Any?) = other is PublicHeader && encode().contentEquals(other.encode())
    override fun hashCode() = encode().contentHashCode()
}

/** One sealed copy of the detail block, addressed to one authority/pole key. */
class SealedEntry(val keyId: ByteArray, val box: ByteArray)

/**
 * Wire packet: CBOR array [ttl, header_bytes, sealed_bytes, signature].
 *
 * signature = Ed25519_incident(header_bytes || sealed_bytes)
 *
 * ttl is deliberately OUTSIDE the signature (like IP TTL): relays must
 * decrement it without holding the incident key. A malicious relay can only
 * raise it to MAX_TTL, and floods remain bounded by the per-node dedup cache.
 */
class SosPacket(
    val ttl: Int,
    val headerBytes: ByteArray,
    val sealedBytes: ByteArray,
    val signature: ByteArray,
) {
    val header: PublicHeader = PublicHeader.decode(headerBytes)
    val sealed: List<SealedEntry> = decodeSealed(sealedBytes)

    init {
        if (ttl !in 0..Protocol.MAX_TTL) throw MalformedPacketException("ttl out of range")
        if (signature.size != Sodium.SIGNATURE_BYTES) throw MalformedPacketException("bad signature length")
    }

    fun signedMessage(): ByteArray = headerBytes + sealedBytes

    fun verifySignature(): Boolean = Sodium.verify(signature, signedMessage(), header.incidentPub)

    fun withTtl(newTtl: Int) = SosPacket(newTtl, headerBytes, sealedBytes, signature)

    fun encode(): ByteArray = Cbor.encode(listOf(ttl, headerBytes, sealedBytes, signature))

    /** Identity of this exact alert update, used for dedup. Ignores ttl. */
    val dedupKey: String get() = "${header.streamKey}:${header.counter}"

    companion object {
        fun decode(bytes: ByteArray): SosPacket {
            if (bytes.size > Protocol.MAX_PACKET_BYTES) throw MalformedPacketException("packet too large")
            val arr = try {
                Cbor.decode(bytes) as? List<*>
            } catch (e: Cbor.DecodeException) {
                throw MalformedPacketException("cbor: ${e.message}")
            } ?: throw MalformedPacketException("not an array")
            if (arr.size != 4) throw MalformedPacketException("expected 4 elements")
            val ttl = (arr[0] as? Long)?.takeIf { it <= Protocol.MAX_TTL }?.toInt()
                ?: throw MalformedPacketException("bad ttl")
            return SosPacket(
                ttl,
                arr[1] as? ByteArray ?: throw MalformedPacketException("bad header"),
                arr[2] as? ByteArray ?: throw MalformedPacketException("bad sealed"),
                arr[3] as? ByteArray ?: throw MalformedPacketException("bad signature"),
            )
        }

        fun encodeSealed(entries: List<SealedEntry>): ByteArray =
            Cbor.encode(entries.map { listOf(it.keyId, it.box) })

        private fun decodeSealed(bytes: ByteArray): List<SealedEntry> {
            val arr = try {
                Cbor.decode(bytes) as? List<*>
            } catch (e: Cbor.DecodeException) {
                throw MalformedPacketException("sealed cbor: ${e.message}")
            } ?: throw MalformedPacketException("sealed not an array")
            if (arr.isEmpty() || arr.size > Protocol.MAX_RECIPIENTS) throw MalformedPacketException("bad recipient count")
            return arr.map {
                val e = it as? List<*> ?: throw MalformedPacketException("bad sealed entry")
                if (e.size != 2) throw MalformedPacketException("bad sealed entry")
                val kid = e[0] as? ByteArray ?: throw MalformedPacketException("bad kid")
                val box = e[1] as? ByteArray ?: throw MalformedPacketException("bad box")
                if (kid.size != Protocol.KEY_ID_BYTES || box.size < Sodium.SEAL_OVERHEAD) {
                    throw MalformedPacketException("bad sealed entry sizes")
                }
                SealedEntry(kid, box)
            }
        }
    }
}

/** Plaintext inside each sealed box. Only authority/pole keys can read it. */
data class SealedDetails(
    val realIdentityPub: ByteArray,
    val bindingSig: ByteArray,
    val alertId: ByteArray,
    val name: String,
    val lat: Double,
    val lng: Double,
    val deviceUuid: String,
    val note: String,
    val contacts: List<String>,
) {
    fun encode(): ByteArray = Cbor.encode(
        linkedMapOf(
            "rid" to realIdentityPub,
            "bsig" to bindingSig,
            "aid" to alertId,
            "name" to name,
            "lat" to lat,
            "lng" to lng,
            "dev" to deviceUuid,
            "note" to note,
            "contacts" to contacts,
        )
    )

    companion object {
        fun decode(bytes: ByteArray): SealedDetails {
            val m = decodeMap(bytes)
            return SealedDetails(
                realIdentityPub = m.bytes("rid"),
                bindingSig = m.bytes("bsig"),
                alertId = m.bytes("aid"),
                name = m.str("name"),
                lat = m.double("lat"),
                lng = m.double("lng"),
                deviceUuid = m.str("dev"),
                note = m.str("note"),
                contacts = (m["contacts"] as? List<*>)?.map { it as? String ?: throw MalformedPacketException("bad contact") }
                    ?: throw MalformedPacketException("missing contacts"),
            )
        }
    }
}

// ---- helpers ---------------------------------------------------------------

private fun decodeMap(bytes: ByteArray): Map<String, Any?> = try {
    @Suppress("UNCHECKED_CAST")
    Cbor.decode(bytes) as? Map<String, Any?> ?: throw MalformedPacketException("not a map")
} catch (e: Cbor.DecodeException) {
    throw MalformedPacketException("cbor: ${e.message}")
}

private fun Map<String, Any?>.long(k: String) = this[k] as? Long ?: throw MalformedPacketException("missing/invalid $k")
private fun Map<String, Any?>.int(k: String) = long(k).toInt()
private fun Map<String, Any?>.bytes(k: String) = this[k] as? ByteArray ?: throw MalformedPacketException("missing/invalid $k")
private fun Map<String, Any?>.str(k: String) = this[k] as? String ?: throw MalformedPacketException("missing/invalid $k")
private fun Map<String, Any?>.double(k: String) = this[k] as? Double ?: throw MalformedPacketException("missing/invalid $k")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
