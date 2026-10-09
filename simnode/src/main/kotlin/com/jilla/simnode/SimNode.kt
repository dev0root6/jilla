package com.jilla.simnode

import com.jilla.crypto.Sodium
import com.jilla.mesh.HttpGateway
import com.jilla.mesh.MeshEngine
import com.jilla.protocol.SosComposer
import com.jilla.protocol.VictimInfo
import com.jilla.protocol.hexToBytes
import com.jilla.protocol.toHex
import com.jilla.transport.TcpMeshTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime

/**
 * Headless mesh node on the JVM: same core engine as the app, TCP transport.
 * Lets mesh-sim graphs have more nodes than the host can run emulators.
 *
 *   simnode --name B [--hub 127.0.0.1:7777] [--gateway http://127.0.0.1:8000]
 *           [--sos "note" --lat 12.97 --lng 77.59 --victim Asha --register]
 *           [--repeat N --interval-ms 1500]
 */
fun main(args: Array<String>) {
    val opts = parse(args)
    val name = opts["name"] ?: error("--name required")
    val (host, port) = (opts["hub"] ?: "127.0.0.1:7777").split(":").let { it[0] to it[1].toInt() }
    val log = { m: String -> println("${LocalTime.now()} [$name] $m") }

    val transport = TcpMeshTransport(host, port, name, log)
    val gateway = opts["gateway"]?.let { HttpGateway(it, log) }
    val engine = MeshEngine(transport, gateway, log = log, retryIntervalMs = 3000)
    engine.start()

    opts["sos"]?.let { note ->
        val backend = opts["backend"] ?: opts["gateway"] ?: "http://127.0.0.1:8000"
        val keyFile = File(opts["authority-key"] ?: "backend/keys/authority.pub")
        val authority = keyFile.readText().trim().hexToBytes()
        val identity = Sodium.SignKeyPair.generate()
        if ("register" in opts) register(backend, identity, opts["victim"] ?: name, opts["phone"] ?: "", "sim-$name", log)
        val composer = SosComposer(identity, listOf(authority))
        val incident = composer.startIncident()
        Thread.sleep(opts["delay-ms"]?.toLong() ?: 1500) // let the TCP link come up
        repeat(opts["repeat"]?.toInt() ?: 1) {
            val p = composer.compose(
                incident,
                VictimInfo(opts["victim"] ?: name, "sim-$name"),
                opts["lat"]?.toDouble() ?: 12.97161,
                opts["lng"]?.toDouble() ?: 77.59456,
                note,
                opts["ttl"]?.toInt() ?: 7,
            )
            log("SOS originate alert=${p.header.alertIdHex.take(8)} ctr=${p.header.counter} ttl=${p.ttl}")
            engine.originate(p)
            Thread.sleep(opts["interval-ms"]?.toLong() ?: 1500)
        }
    }
    Thread.currentThread().join()
}

/** Same signed registration as the app (see RegistrationClient.kt / backend registration_message). */
private fun register(backend: String, id: Sodium.SignKeyPair, name: String, phone: String, deviceId: String, log: (String) -> Unit) {
    val ts = System.currentTimeMillis()
    val msg = "JILLA-REG-v2".toByteArray() + listOf(ts.toString(), phone, deviceId, name).joinToString("\u001f").toByteArray() +
        byteArrayOf(0x1f) + id.publicKey
    val sig = Sodium.sign(msg, id.seed).toHex()
    val c = URL("$backend/register").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use {
        it.write(
            """{"identity_pub":"${id.publicKey.toHex()}","name":"$name","phone":"$phone","device_id":"$deviceId","ts_ms":$ts,"signature":"$sig"}"""
                .toByteArray()
        )
    }
    log("register -> ${c.responseCode}")
}

private fun parse(args: Array<String>): Map<String, String> {
    val m = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) { m[k] = args[i + 1]; i += 2 } else { m[k] = ""; i++ }
    }
    return m
}
