package com.jilla.transport

/** Runs several link layers at once (e.g. dev TCP mesh + Bluetooth). Dedup in the engine absorbs echoes. */
class CompositeTransport(private val parts: List<MeshTransport>) : MeshTransport {
    override fun start() = parts.forEach { it.start() }
    override fun stop() = parts.forEach { it.stop() }
    override fun broadcast(packet: ByteArray) = parts.forEach { it.broadcast(packet) }
    override fun onPacket(handler: (ByteArray) -> Unit) = parts.forEach { it.onPacket(handler) }
    override fun onNeighbourUp(handler: (String) -> Unit) = parts.forEach { it.onNeighbourUp(handler) }
    override fun isLinked() = parts.any { it.isLinked() }
}
