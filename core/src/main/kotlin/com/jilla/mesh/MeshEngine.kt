package com.jilla.mesh

import com.jilla.protocol.MalformedPacketException
import com.jilla.protocol.Protocol
import com.jilla.protocol.PublicHeader
import com.jilla.protocol.ReplayGuard
import com.jilla.protocol.SosPacket
import com.jilla.transport.MeshTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a node did with a packet, for logs/UI/tests. */
enum class Action { ORIGINATED, RELAYED, DELIVERED, REJECTED_BY_SERVER, TTL_EXHAUSTED, DROPPED_MALFORMED, DROPPED_BAD_SIG, DROPPED_REPLAY, DROPPED_STALE, DROPPED_RATE_LIMIT }

/** Bystander view of an alert: only the public header. */
data class AlertEvent(val header: PublicHeader?, val ttl: Int, val action: Action, val detail: String = "")

/**
 * Transport-agnostic relay engine:
 *   receive -> decode -> verify signature -> replay/dedup -> rate limit
 *           -> store (carry) -> relay with ttl-1 -> try gateway upload
 *
 * Store-carry-forward: every accepted packet stays in [store] until a gateway
 * upload from this node succeeds (or it ages out). Pending packets are
 * re-broadcast whenever a new neighbour appears and retried on the gateway
 * periodically, so alerts cross mesh gaps as people move.
 */
class MeshEngine(
    private val transport: MeshTransport,
    private val gateway: Gateway?,
    private val store: PacketStore = MemoryPacketStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val minUpdateIntervalMs: Long = 1000,
    private val retryIntervalMs: Long = 15_000,
    /** How long a node keeps re-broadcasting an alert it carries (prod: hours; dev: minutes). */
    private val carryWindowMs: Long = Protocol.MAX_AGE_MS,
    /** Re-broadcast period while carrying, so late arrivals in range still get the alert. */
    private val rebroadcastIntervalMs: Long = 10_000,
) {
    private val guard = ReplayGuard(clock)
    private val lastAcceptedAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 4096
    }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mesh-engine").apply { isDaemon = true }
    }
    private val uplink: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mesh-uplink").apply { isDaemon = true }
    }
    private val listeners = mutableListOf<(AlertEvent) -> Unit>()

    fun addListener(l: (AlertEvent) -> Unit) { synchronized(listeners) { listeners += l } }

    fun start() {
        transport.onPacket { bytes -> worker.execute { handleInbound(bytes) } }
        transport.onNeighbourUp { name -> worker.execute { onNeighbourUp(name) } }
        transport.start()
        worker.scheduleWithFixedDelay({ retryUploads() }, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS)
        worker.scheduleWithFixedDelay({ rebroadcastCarried() }, rebroadcastIntervalMs, rebroadcastIntervalMs, TimeUnit.MILLISECONDS)
        log("engine: started (gateway=${gateway != null})")
    }

    fun stop() {
        transport.stop()
        worker.shutdownNow()
        uplink.shutdownNow()
    }

    /** Locally triggered SOS: record in replay guard so our own echo is ignored, then send. */
    fun originate(p: SosPacket) {
        worker.execute {
            guard.check(p.header)
            lastAcceptedAt[p.header.streamKey] = clock()
            store.put(p.dedupKey, p.encode(), clock())
            emit(AlertEvent(p.header, p.ttl, Action.ORIGINATED))
            transport.broadcast(p.encode())
            tryUpload(p.dedupKey, p.encode())
        }
    }

    /** Re-broadcast and retry everything we are carrying (e.g. connectivity came back). */
    fun flushPending() = worker.execute { onNeighbourUp("flush") }

    /** Blocks until queued engine work has run. For tests. */
    fun drain() {
        worker.submit {}.get(5, TimeUnit.SECONDS)
        uplink.submit {}.get(15, TimeUnit.SECONDS)
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun handleInbound(bytes: ByteArray) {
        val p = try {
            SosPacket.decode(bytes)
        } catch (e: MalformedPacketException) {
            return emit(AlertEvent(null, -1, Action.DROPPED_MALFORMED, e.message ?: ""))
        }
        if (!p.verifySignature()) return emit(AlertEvent(p.header, p.ttl, Action.DROPPED_BAD_SIG))

        when (val v = guard.check(p.header)) {
            ReplayGuard.Verdict.ACCEPT -> {}
            ReplayGuard.Verdict.STALE, ReplayGuard.Verdict.FUTURE ->
                return emit(AlertEvent(p.header, p.ttl, Action.DROPPED_STALE, v.name))
            else -> return emit(AlertEvent(p.header, p.ttl, Action.DROPPED_REPLAY, v.name))
        }

        // Rate limit per alert_id: a holder of an incident key cannot spam counters.
        val now = clock()
        val last = lastAcceptedAt[p.header.streamKey]
        if (last != null && now - last < minUpdateIntervalMs) {
            return emit(AlertEvent(p.header, p.ttl, Action.DROPPED_RATE_LIMIT))
        }
        lastAcceptedAt[p.header.streamKey] = now

        if (p.ttl > 0) {
            val fwd = p.withTtl(p.ttl - 1).encode()
            store.put(p.dedupKey, fwd, now)
            transport.broadcast(fwd)
            emit(AlertEvent(p.header, p.ttl, Action.RELAYED, "fwd ttl=${p.ttl - 1}"))
        } else {
            // Hop budget spent: carry for our own gateway, but never re-broadcast.
            store.put(p.dedupKey, bytes, now)
            emit(AlertEvent(p.header, p.ttl, Action.TTL_EXHAUSTED))
        }
        tryUpload(p.dedupKey, bytes)
    }

    private fun onNeighbourUp(name: String) {
        store.expire(clock() - carryWindowMs)
        val carried = store.carried()
        if (carried.isEmpty()) return
        log("engine: neighbour up ($name), re-sending ${carried.size} carried packet(s)")
        for (e in carried) {
            if (SosPacket.decode(e.packet).ttl > 0) transport.broadcast(e.packet)
            if (!e.delivered) tryUpload(e.key, e.packet)
        }
    }

    /** Periodic re-broadcast while inside the carry window (delivered or not). */
    private fun rebroadcastCarried() {
        store.expire(clock() - carryWindowMs)
        val carried = store.carried().filter { SosPacket.decode(it.packet).ttl > 0 }
        if (carried.isEmpty()) return
        log("engine: carrying ${carried.size} packet(s), re-broadcasting")
        carried.forEach { transport.broadcast(it.packet) }
    }

    private fun retryUploads() {
        if (gateway == null) return
        store.expire(clock() - carryWindowMs)
        for (e in store.pending()) tryUpload(e.key, e.packet)
    }

    private fun tryUpload(key: String, packet: ByteArray) {
        val g = gateway ?: return
        uplink.execute {
            if (!store.isPending(key)) return@execute
            when (val r = g.upload(packet)) {
                UploadResult.Accepted -> {
                    store.markDelivered(key)
                    val p = SosPacket.decode(packet)
                    worker.execute { emit(AlertEvent(p.header, p.ttl, Action.DELIVERED, "via this node's gateway")) }
                }
                is UploadResult.Rejected -> {
                    // Final answer from the authority: stop retrying, but say so loudly.
                    store.markDelivered(key)
                    val p = SosPacket.decode(packet)
                    worker.execute { emit(AlertEvent(p.header, p.ttl, Action.REJECTED_BY_SERVER, r.reason)) }
                }
                UploadResult.Offline -> {}
            }
        }
    }

    private fun emit(e: AlertEvent) {
        val h = e.header
        val who = if (h != null) "alert=${h.alertIdHex.take(8)} ctr=${h.counter} area=${h.coarseGeohash}" else "?"
        log("engine: ${e.action} $who ttl=${e.ttl} ${e.detail}".trimEnd())
        synchronized(listeners) { listeners.toList() }.forEach { it(e) }
    }
}
