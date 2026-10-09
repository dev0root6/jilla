package com.jilla.mesh

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of handing a packet to the authority. */
sealed class UploadResult {
    /** 2xx: dispatched, updated, or already held (duplicate). */
    data object Accepted : UploadResult()
    /** 4xx: the authority refused it (forged, unregistered, stale...). Retrying won't help. */
    data class Rejected(val reason: String) : UploadResult()
    /** Network failure or 5xx: keep carrying and retry later. */
    data object Offline : UploadResult()
}

/** Internet uplink to the authority. */
interface Gateway {
    fun upload(packet: ByteArray): UploadResult
}

/** POSTs the raw CBOR packet to `<baseUrl>/sos`. Relays never see inside the sealed block. */
class HttpGateway(private val baseUrl: String, private val log: (String) -> Unit = {}) : Gateway {
    override fun upload(packet: ByteArray): UploadResult = try {
        val c = URL("${baseUrl.trimEnd('/')}/sos").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/cbor")
        c.outputStream.use { it.write(packet) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }
        log("gateway: POST /sos -> $code $body")
        when (code) {
            in 200..299 -> UploadResult.Accepted
            in 400..499 -> UploadResult.Rejected(REASON.find(body ?: "")?.groupValues?.get(1) ?: "HTTP $code")
            else -> UploadResult.Offline
        }
    } catch (e: IOException) {
        log("gateway: offline (${e.message})")
        UploadResult.Offline
    }

    private companion object {
        val REASON = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")
    }
}
