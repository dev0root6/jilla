package com.jilla

import com.jilla.crypto.Sodium
import com.jilla.crypto.bindingMessage
import com.jilla.protocol.AuthorityOpener
import com.jilla.protocol.Cbor
import com.jilla.protocol.Geohash
import com.jilla.protocol.MalformedPacketException
import com.jilla.protocol.OpenResult
import com.jilla.protocol.Protocol
import com.jilla.protocol.ReplayGuard
import com.jilla.protocol.SealedDetails
import com.jilla.protocol.SosComposer
import com.jilla.protocol.SosPacket
import com.jilla.protocol.VictimInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PacketTest {
    private var now = 1_760_000_000_000L
    private val clock = { now }
    private val identity = Sodium.SignKeyPair.generate()
    private val backend = Sodium.BoxKeyPair.generate()
    private val pole = Sodium.BoxKeyPair.generate()
    private val registry = mutableSetOf(identity.publicKey.toList())
    private val composer = SosComposer(identity, listOf(backend.publicKey, pole.publicKey), clock)
    private val victim = VictimInfo("Asha", "dev-uuid-1", listOf("+911234567890"))
    private fun opener(key: Sodium.BoxKeyPair = backend) = AuthorityOpener(key) { it.toList() in registry }

    private fun freshPacket(note: String = "help") =
        composer.compose(composer.startIncident(), victim, 12.97161, 77.59456, note)

    @Test
    fun wireRoundTripPreservesEverything() {
        val p = freshPacket()
        val q = SosPacket.decode(p.encode())
        assertEquals(p.header, q.header)
        assertContentEquals(p.encode(), q.encode())
        assertEquals(Protocol.DEFAULT_TTL, q.ttl)
        assertEquals("tdr1v", q.header.coarseGeohash)
        assertTrue(q.verifySignature())
        println("packet size = ${p.encode().size} bytes (2 recipients)")
    }

    @Test
    fun authoritiesOpenSealedBlockAndVerifyBinding() {
        val p = SosPacket.decode(freshPacket().encode())
        for (key in listOf(backend, pole)) {
            val r = opener(key).open(p)
            assertIs<OpenResult.Ok>(r)
            assertEquals("Asha", r.details.name)
            assertEquals(12.97161, r.details.lat)
            assertEquals(77.59456, r.details.lng)
            assertEquals("dev-uuid-1", r.details.deviceUuid)
            assertContentEquals(identity.publicKey, r.details.realIdentityPub)
            assertTrue(Sodium.verify(r.details.bindingSig, bindingMessage(p.header.incidentPub), identity.publicKey))
        }
    }

    @Test
    fun bystanderWithoutAuthorityKeyCannotOpen() {
        val r = opener(Sodium.BoxKeyPair.generate()).open(freshPacket())
        assertIs<OpenResult.Rejected>(r)
        // bystander sees only coarse area; precise coords and name are not in cleartext
        val wire = freshPacket().encode()
        assertFalse(String(wire, Charsets.ISO_8859_1).contains("Asha"))
        assertFalse(String(wire, Charsets.ISO_8859_1).contains("dev-uuid-1"))
    }

    @Test
    fun validSignatureAcceptedTamperedByteRejected() {
        val p = freshPacket()
        assertTrue(p.verifySignature())
        val signed = p.signedMessage()
        var rejected = 0
        for (i in signed.indices) {
            val hdrLen = p.headerBytes.size
            val h = p.headerBytes.copyOf()
            val s = p.sealedBytes.copyOf()
            if (i < hdrLen) h[i] = (h[i].toInt() xor 0x01).toByte() else s[i - hdrLen] = (s[i - hdrLen].toInt() xor 0x01).toByte()
            val ok = try {
                SosPacket(p.ttl, h, s, p.signature).verifySignature()
            } catch (e: MalformedPacketException) {
                false
            }
            if (!ok) rejected++
        }
        assertEquals(signed.size, rejected, "every single-bit flip in header/sealed must be rejected")
        for (i in p.signature.indices) {
            val sig = p.signature.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFalse(SosPacket(p.ttl, p.headerBytes, p.sealedBytes, sig).verifySignature())
        }
    }

    @Test
    fun ttlIsMutableWithoutBreakingSignature() {
        val p = freshPacket()
        assertTrue(p.withTtl(3).verifySignature())
    }

    @Test
    fun forgedSignatureByOtherKeyRejected() {
        val p = freshPacket()
        val attacker = Sodium.SignKeyPair.generate()
        val forged = SosPacket(p.ttl, p.headerBytes, p.sealedBytes, Sodium.sign(p.signedMessage(), attacker.seed))
        assertFalse(forged.verifySignature())
        assertIs<OpenResult.Rejected>(opener().open(forged))
    }

    @Test
    fun replayedCounterRejected() {
        val guard = ReplayGuard(clock)
        val inc = composer.startIncident()
        val p1 = composer.compose(inc, victim, 1.0, 2.0)
        val p2 = composer.compose(inc, victim, 1.1, 2.1)
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(p1.header))
        assertEquals(ReplayGuard.Verdict.REPLAY, guard.check(p1.header))
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(p2.header))
        assertEquals(ReplayGuard.Verdict.REPLAY, guard.check(p1.header), "older counter after newer")
        assertEquals(ReplayGuard.Verdict.REPLAY, guard.check(p2.header))
    }

    @Test
    fun staleAndFutureTimestampsRejected() {
        val guard = ReplayGuard(clock)
        val p = freshPacket()
        now += Protocol.MAX_AGE_MS + 1
        assertEquals(ReplayGuard.Verdict.STALE, guard.check(p.header))
        now -= 2 * Protocol.MAX_AGE_MS
        assertEquals(ReplayGuard.Verdict.FUTURE, guard.check(p.header))
    }

    @Test
    fun alertIdHijackCannotBlockGenuineUpdates() {
        val guard = ReplayGuard(clock)
        val inc = composer.startIncident()
        val p = composer.compose(inc, victim, 1.0, 2.0)
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(p.header))
        // attacker re-uses the alert_id with their own incident key and a higher counter
        val evil = composer.startIncident()
        val f = evil.javaClass.getDeclaredField("alertId").apply { isAccessible = true }
        f.set(evil, inc.alertId)
        repeat(5) { composer.compose(evil, victim, 0.0, 0.0) }
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(composer.compose(evil, victim, 0.0, 0.0).header))
        // the forged high-counter copy must not block the victim's genuine next update
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(composer.compose(inc, victim, 1.0, 2.0).header))
        // and the forgery fails at the authority: its identity is not the victim's / not registered
        val rogue = SosComposer(Sodium.SignKeyPair.generate(), listOf(backend.publicKey), clock)
        val rogueInc = rogue.startIncident()
        f.set(rogueInc, inc.alertId)
        assertEquals(OpenResult.Rejected("identity not registered"), opener().open(rogue.compose(rogueInc, victim, 0.0, 0.0)))
    }

    @Test
    fun bindingSigFromUnregisteredOrWrongIdentityRejected() {
        // attacker with their own identity key: binding verifies, but identity not registered
        val rogue = SosComposer(Sodium.SignKeyPair.generate(), listOf(backend.publicKey), clock)
        val r1 = opener().open(rogue.compose(rogue.startIncident(), victim, 0.0, 0.0))
        assertEquals(OpenResult.Rejected("identity not registered"), r1)

        // attacker claims a registered identity but cannot produce its binding signature
        val inc = composer.startIncident()
        val good = composer.compose(inc, victim, 0.0, 0.0)
        val ok = opener().open(good) as OpenResult.Ok
        val fakeBinding = Sodium.sign(bindingMessage(inc.key.publicKey), Sodium.SignKeyPair.generate().seed)
        val evilDetails = ok.details.copy(bindingSig = fakeBinding).encode()
        val sealed = SosPacket.encodeSealed(
            listOf(com.jilla.protocol.SealedEntry(
                com.jilla.crypto.authorityKeyId(backend.publicKey),
                Sodium.boxSeal(evilDetails, backend.publicKey),
            ))
        )
        val p = SosPacket(7, good.headerBytes, sealed, Sodium.sign(good.headerBytes + sealed, inc.key.seed))
        assertEquals(OpenResult.Rejected("binding signature invalid"), opener().open(p))
    }

    @Test
    fun incidentKeysAreUnlinkableAcrossIncidents() {
        val a = freshPacket().header
        val b = freshPacket().header
        assertFalse(a.incidentPub.contentEquals(b.incidentPub))
        assertFalse(a.alertId.contentEquals(b.alertId))
        assertFalse(a.incidentPub.contentEquals(identity.publicKey))
    }

    @Test
    fun malformedInputRejectedCleanly() {
        val p = freshPacket().encode()
        assertFailsWith<MalformedPacketException> { SosPacket.decode(p.copyOf(p.size - 1)) }
        assertFailsWith<MalformedPacketException> { SosPacket.decode(byteArrayOf()) }
        assertFailsWith<MalformedPacketException> { SosPacket.decode(ByteArray(Protocol.MAX_PACKET_BYTES + 1)) }
        assertFailsWith<MalformedPacketException> { SosPacket.decode(Cbor.encode(listOf(99, ByteArray(1), ByteArray(1), ByteArray(64)))) }
        // random garbage never crashes with anything other than MalformedPacketException
        repeat(2000) {
            val junk = Sodium.randomBytes(1 + Sodium.random.nextInt(200))
            try { SosPacket.decode(junk) } catch (e: MalformedPacketException) { }
        }
    }

    @Test
    fun geohashKnownValues() {
        assertEquals("u4pru", Geohash.encode(57.64911, 10.40744))
        assertEquals("tdr1v", Geohash.encode(12.97161, 77.59456))
    }

    @Test
    fun sealedDetailsRoundTrip() {
        val d = SealedDetails(ByteArray(32), ByteArray(64), ByteArray(16), "n", 1.5, -2.5, "u", "", listOf("a", "b"))
        assertEquals(d.encode().toList(), SealedDetails.decode(d.encode()).encode().toList())
    }
}
