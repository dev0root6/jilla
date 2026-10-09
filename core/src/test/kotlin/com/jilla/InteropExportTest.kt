package com.jilla

import com.jilla.crypto.Sodium
import com.jilla.protocol.SosComposer
import com.jilla.protocol.VictimInfo
import com.jilla.protocol.toHex
import java.io.File
import kotlin.test.Test

/**
 * Writes a Kotlin-built packet + keys to build/interop/ so backend/tests can
 * prove PyNaCl verifies and opens what the app produces.
 */
class InteropExportTest {
    @Test
    fun exportPacketForPython() {
        val identity = Sodium.SignKeyPair.generate()
        val authority = Sodium.BoxKeyPair.generate()
        val composer = SosComposer(identity, listOf(authority.publicKey))
        val p = composer.compose(composer.startIncident(), VictimInfo("Interop", "dev-x"), 12.97, 77.59, "hi")
        val dir = File("build/interop").apply { mkdirs() }
        File(dir, "packet.cbor").writeBytes(p.encode())
        File(dir, "keys.json").writeText(
            """{"authority_sk":"${authority.secretKey.toHex()}","identity_pk":"${identity.publicKey.toHex()}"}"""
        )
    }
}
