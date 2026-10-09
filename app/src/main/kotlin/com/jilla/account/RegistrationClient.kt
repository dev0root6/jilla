package com.jilla.account

import com.jilla.crypto.Sodium
import com.jilla.protocol.toHex
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-step registration with the authority. The user is identified by the device's active
 * SIM number, or by the device id when no number is available.
 *
 * Signature over "JILLA-REG-v2" || ts \x1f phone \x1f device_id \x1f name \x1f identity_pub
 * proves we own the identity key and binds every field (no swapping the number after signing).
 * The timestamp bounds replay of a captured request.
 */
class RegistrationClient(private val baseUrl: String, private val identity: Sodium.SignKeyPair) {
    class RegistrationException(msg: String) : Exception(msg)

    /** Returns how the server identified us: "phone" or "device_id". */
    fun register(name: String, phone: String, deviceId: String): String {
        val ts = System.currentTimeMillis()
        val p = normalize(phone)
        val n = name.trim()
        val msg = REG_TAG + listOf(ts.toString(), p, deviceId, n).joinToString(SEP).toByteArray() +
            SEP.toByteArray() + identity.publicKey
        val r = post(
            "/register",
            JSONObject()
                .put("identity_pub", identity.publicKey.toHex())
                .put("name", n)
                .put("phone", p)
                .put("device_id", deviceId)
                .put("ts_ms", ts)
                .put("signature", Sodium.sign(msg, identity.seed).toHex()),
        )
        return r.optString("identified_by", "phone")
    }

    /** Fetches the server's authority key, to compare against the key bundled in the app. */
    fun authorityKey(): String = get("/authority").getString("x25519_pub")

    private fun get(path: String): JSONObject = request(path, null)
    private fun post(path: String, body: JSONObject): JSONObject = request(path, body)

    private fun request(path: String, body: JSONObject?): JSONObject {
        try {
            val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 6000
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw RegistrationException(json.optString("detail", "server error $code"))
            return json
        } catch (e: IOException) {
            throw RegistrationException("Can't reach the server at $baseUrl")
        }
    }

    companion object {
        private val REG_TAG = "JILLA-REG-v2".toByteArray()
        private const val SEP = "\u001f"

        /**
         * E.164-ish: digits with a leading +. Fixes common SIM/user formats for India:
         * "+91 0xxxxxxxxxx" (trunk 0), "91xxxxxxxxxx" (missing +), "xxxxxxxxxx" (no country code).
         */
        fun normalize(phone: String): String {
            val p = phone.filter { it.isDigit() || it == '+' }
            return when {
                p.isEmpty() -> p
                p.startsWith("+91") && p.length == 14 && p[3] == '0' -> "+91" + p.substring(4)
                p.startsWith("+") -> p
                p.length == 12 && p.startsWith("91") -> "+$p"
                p.length == 11 && p.startsWith("0") -> "+91" + p.substring(1)
                p.length == 10 && p[0] in '6'..'9' -> "+91$p"
                else -> p
            }
        }
    }
}
