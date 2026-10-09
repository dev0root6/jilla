package com.jilla.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dev/test transport: connects to the mesh-sim hub (devtools/mesh-sim), which
 * delivers each broadcast only to the neighbours in its adjacency graph.
 *
 * Frame: u32 big-endian length, then 1 type byte + body.
 *   client -> hub : HELLO(0x00, node name utf8) | PACKET(0x01, bytes)
 *   hub -> client : PACKET(0x01, bytes) | NEIGHBOUR_UP(0x02, name utf8)
 *
 * Emulators reach the host at 10.0.2.2. Reconnects automatically.
 */
class TcpMeshTransport(
    private val host: String,
    private val port: Int,
    private val nodeName: String,
    private val log: (String) -> Unit = {},
) : MeshTransport {
    private val running = AtomicBoolean(false)
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var socket: Socket? = null
    private var packetHandler: (ByteArray) -> Unit = {}
    private var neighbourHandler: (String) -> Unit = {}
    private var thread: Thread? = null

    override fun onPacket(handler: (ByteArray) -> Unit) { packetHandler = handler }
    override fun onNeighbourUp(handler: (String) -> Unit) { neighbourHandler = handler }
    override fun isLinked() = out != null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "tcp-mesh-$nodeName").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: IOException) {}
    }

    override fun broadcast(packet: ByteArray) {
        val o = out ?: run { log("tcp: not connected, broadcast dropped"); return }
        try {
            synchronized(o) { writeFrame(o, TYPE_PACKET, packet) }
        } catch (e: IOException) {
            log("tcp: send failed: ${e.message}")
        }
    }

    private fun loop() {
        while (running.get()) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.tcpNoDelay = true
                    socket = s
                    val o = DataOutputStream(s.getOutputStream().buffered())
                    synchronized(o) { writeFrame(o, TYPE_HELLO, nodeName.toByteArray()) }
                    out = o
                    log("tcp: connected to mesh-sim $host:$port as $nodeName")
                    val input = DataInputStream(s.getInputStream().buffered())
                    while (running.get()) {
                        val len = input.readInt()
                        if (len < 1 || len > MAX_FRAME) throw IOException("bad frame length $len")
                        val type = input.readUnsignedByte()
                        val body = ByteArray(len - 1).also { input.readFully(it) }
                        when (type) {
                            TYPE_PACKET -> packetHandler(body)
                            TYPE_NEIGHBOUR_UP -> neighbourHandler(body.decodeToString())
                        }
                    }
                }
            } catch (e: IOException) {
                if (running.get()) log("tcp: link down (${e.message}), retrying")
            } finally {
                out = null
            }
            if (running.get()) Thread.sleep(2000)
        }
    }

    private fun writeFrame(o: DataOutputStream, type: Int, body: ByteArray) {
        o.writeInt(body.size + 1)
        o.writeByte(type)
        o.write(body)
        o.flush()
    }

    companion object {
        const val TYPE_HELLO = 0x00
        const val TYPE_PACKET = 0x01
        const val TYPE_NEIGHBOUR_UP = 0x02
        const val MAX_FRAME = 64 * 1024
    }
}
