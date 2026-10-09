package com.jilla.mesh

/** A packet this node is carrying. [delivered] = this node already got it to the authority. */
class StoredPacket(val key: String, val packet: ByteArray, val storedAtMs: Long, val delivered: Boolean = false)

/**
 * Store-carry-forward queue. Two independent jobs per packet:
 *  - upload: retried until a gateway upload from this node succeeds;
 *  - carry:  re-broadcast to the mesh until the carry window ends, delivered or not,
 *            so people who walk into range later still receive the alert.
 * The app can back this with Room so carried alerts survive process death; the
 * JVM sim node and tests use [MemoryPacketStore].
 */
interface PacketStore {
    /** Insert or replace (a later copy may carry a different ttl). Keeps the delivered flag. */
    fun put(key: String, packet: ByteArray, nowMs: Long)
    /** Everything still inside the carry window. */
    fun carried(): List<StoredPacket>
    /** Not yet uploaded by this node. */
    fun pending(): List<StoredPacket> = carried().filter { !it.delivered }
    fun isPending(key: String): Boolean
    fun markDelivered(key: String)
    fun expire(olderThanMs: Long)
}

class MemoryPacketStore : PacketStore {
    private val entries = LinkedHashMap<String, StoredPacket>()

    @Synchronized override fun put(key: String, packet: ByteArray, nowMs: Long) {
        val old = entries[key]
        entries[key] = StoredPacket(key, packet, old?.storedAtMs ?: nowMs, old?.delivered ?: false)
    }
    @Synchronized override fun carried() = entries.values.toList()
    @Synchronized override fun isPending(key: String) = entries[key]?.delivered == false
    @Synchronized override fun markDelivered(key: String) {
        entries[key]?.let { entries[key] = StoredPacket(it.key, it.packet, it.storedAtMs, true) }
    }
    @Synchronized override fun expire(olderThanMs: Long) { entries.values.removeIf { it.storedAtMs < olderThanMs } }
}
