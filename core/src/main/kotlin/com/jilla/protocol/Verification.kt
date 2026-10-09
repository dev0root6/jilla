package com.jilla.protocol

import com.jilla.crypto.Sodium
import com.jilla.crypto.authorityKeyId
import com.jilla.crypto.bindingMessage

/**
 * Freshness + replay window, keyed by (alert_id, incident_pub).
 *
 * Accepts an update only if its counter is strictly greater than the highest
 * counter already accepted for that alert and its timestamp is inside
 * [now - MAX_AGE, now + MAX_FUTURE_SKEW].
 *
 * The key includes incident_pub on purpose: relays cannot check the binding
 * signature, so if they keyed on alert_id alone an attacker who sniffed a
 * victim's alert_id could inject a copy signed with their own key first and
 * make relays drop the victim's genuine updates. Keyed this way, the forgery
 * is just another (unregistered, backend-rejected) alert and the genuine one
 * still flows. Bounded LRU so a flood of fresh alerts cannot exhaust memory.
 */
class ReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 4096,
) {
    enum class Verdict { ACCEPT, REPLAY, STALE, FUTURE }

    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > capacity
    }

    @Synchronized
    fun check(h: PublicHeader): Verdict {
        val now = clock()
        if (h.timestampMs < now - Protocol.MAX_AGE_MS) return Verdict.STALE
        if (h.timestampMs > now + Protocol.MAX_FUTURE_SKEW_MS) return Verdict.FUTURE
        val key = h.streamKey
        val last = seen[key]
        if (last != null && h.counter <= last) return Verdict.REPLAY
        seen[key] = h.counter
        return Verdict.ACCEPT
    }
}

/** Result of an authority (backend/pole) opening a packet. */
sealed class OpenResult {
    data class Ok(val details: SealedDetails) : OpenResult()
    data class Rejected(val reason: String) : OpenResult()
}

/**
 * Authority-side processing of the sealed block. Assumes the outer signature
 * was already checked (it is checked again here for defence in depth).
 *
 * @param isRegistered whether a real identity public key belongs to a registered user
 */
class AuthorityOpener(
    private val authorityKey: Sodium.BoxKeyPair,
    private val isRegistered: (ByteArray) -> Boolean,
) {
    private val kid = authorityKeyId(authorityKey.publicKey)

    fun open(p: SosPacket): OpenResult {
        if (!p.verifySignature()) return OpenResult.Rejected("bad incident signature")
        val entry = p.sealed.firstOrNull { it.keyId.contentEquals(kid) }
            ?: return OpenResult.Rejected("no sealed copy for this authority")
        val plain = Sodium.boxSealOpen(entry.box, authorityKey)
            ?: return OpenResult.Rejected("sealed box authentication failed")
        val d = try {
            SealedDetails.decode(plain)
        } catch (e: MalformedPacketException) {
            return OpenResult.Rejected("sealed details malformed: ${e.message}")
        }
        if (!d.alertId.contentEquals(p.header.alertId)) return OpenResult.Rejected("sealed alert_id mismatch")
        if (!Sodium.verify(d.bindingSig, bindingMessage(p.header.incidentPub), d.realIdentityPub)) {
            return OpenResult.Rejected("binding signature invalid")
        }
        if (!isRegistered(d.realIdentityPub)) return OpenResult.Rejected("identity not registered")
        return OpenResult.Ok(d)
    }
}
