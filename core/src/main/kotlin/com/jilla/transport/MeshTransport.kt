package com.jilla.transport

/**
 * Link layer for the mesh. The engine is agnostic to whether packets travel
 * over TCP (emulator/dev) or BLE (real devices).
 */
interface MeshTransport {
    fun start()
    fun stop()

    /** Send to all current neighbours. */
    fun broadcast(packet: ByteArray)

    /** Inbound packets from any neighbour. */
    fun onPacket(handler: (ByteArray) -> Unit)

    /** A neighbour came into range. Drives store-carry-forward re-sends. */
    fun onNeighbourUp(handler: (String) -> Unit) {}

    /** Whether the link layer is currently up (connected to hub / radio active). */
    fun isLinked(): Boolean = true
}
