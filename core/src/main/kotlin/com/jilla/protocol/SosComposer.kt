package com.jilla.protocol

import com.jilla.crypto.Sodium
import com.jilla.crypto.authorityKeyId
import com.jilla.crypto.bindingMessage

/** What the victim's phone knows about the victim. Goes only into the sealed block. */
data class VictimInfo(
    val name: String,
    val deviceUuid: String,
    val contacts: List<String> = emptyList(),
)

/**
 * One SOS incident: fresh ephemeral Ed25519 key + random alert_id. Every update
 * (e.g. new location) re-uses both and bumps the counter, so receivers can
 * de-duplicate and reject replays of older updates.
 */
class Incident internal constructor(
    val alertId: ByteArray,
    val key: Sodium.SignKeyPair,
    val bindingSig: ByteArray,
) {
    var counter: Long = 0
        internal set
}

/**
 * Builds signed, sealed SOS packets.
 *
 * @param identity    long-term Ed25519 identity, registered with the backend
 * @param authorities X25519 public keys of backend + poles (bundled in app)
 */
class SosComposer(
    private val identity: Sodium.SignKeyPair,
    private val authorities: List<ByteArray>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(authorities.isNotEmpty() && authorities.size <= Protocol.MAX_RECIPIENTS)
    }

    fun startIncident(): Incident {
        val key = Sodium.SignKeyPair.generate()
        return Incident(
            alertId = Sodium.randomBytes(Protocol.ALERT_ID_BYTES),
            key = key,
            bindingSig = Sodium.sign(bindingMessage(key.publicKey), identity.seed),
        )
    }

    fun compose(
        incident: Incident,
        victim: VictimInfo,
        lat: Double,
        lng: Double,
        note: String = "",
        ttl: Int = Protocol.DEFAULT_TTL,
    ): SosPacket {
        incident.counter += 1
        val header = PublicHeader(
            version = Protocol.VERSION,
            alertId = incident.alertId,
            incidentPub = incident.key.publicKey,
            timestampMs = clock(),
            counter = incident.counter,
            coarseGeohash = Geohash.encode(lat, lng),
        )
        val details = SealedDetails(
            realIdentityPub = identity.publicKey,
            bindingSig = incident.bindingSig,
            alertId = incident.alertId,
            name = victim.name,
            lat = lat,
            lng = lng,
            deviceUuid = victim.deviceUuid,
            note = note,
            contacts = victim.contacts,
        ).encode()
        val sealed = SosPacket.encodeSealed(
            authorities.map { SealedEntry(authorityKeyId(it), Sodium.boxSeal(details, it)) }
        )
        val headerBytes = header.encode()
        val sig = Sodium.sign(headerBytes + sealed, incident.key.seed)
        return SosPacket(ttl, headerBytes, sealed, sig)
    }
}
