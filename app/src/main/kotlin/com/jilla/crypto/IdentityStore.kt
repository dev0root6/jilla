package com.jilla.crypto

import android.content.Context
import com.jilla.protocol.hexToBytes
import com.jilla.protocol.toHex
import java.util.UUID

/**
 * Long-term Ed25519 identity + random device UUID (no IMEI).
 *
 * Prototype limitation: the seed sits in app-private SharedPreferences. A
 * production build should wrap it with an Android Keystore key.
 */
class IdentityStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("jilla_identity", Context.MODE_PRIVATE)

    val identity: Sodium.SignKeyPair = prefs.getString("seed", null)
        ?.let { Sodium.SignKeyPair.fromSeed(it.hexToBytes()) }
        ?: Sodium.SignKeyPair.generate().also { prefs.edit().putString("seed", it.seed.toHex()).apply() }

    val deviceUuid: String = prefs.getString("device_uuid", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_uuid", it).apply() }

    var registered: Boolean
        get() = prefs.getBoolean("registered", false)
        set(v) { prefs.edit().putBoolean("registered", v).apply() }
}
