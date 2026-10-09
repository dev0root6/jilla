package com.jilla.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.jilla.BuildConfig
import com.jilla.account.AccountStore
import com.jilla.account.RegistrationClient
import com.jilla.crypto.IdentityStore
import com.jilla.mesh.Action
import com.jilla.mesh.AlertEvent
import com.jilla.mesh.HttpGateway
import com.jilla.mesh.MeshEngine
import com.jilla.mesh.MemoryPacketStore
import com.jilla.protocol.Geohash
import com.jilla.protocol.Incident
import com.jilla.protocol.SosComposer
import com.jilla.protocol.VictimInfo
import com.jilla.protocol.hexToBytes
import com.jilla.protocol.toHex
import com.jilla.transport.CompositeTransport
import com.jilla.transport.MeshTransport
import com.jilla.transport.TcpMeshTransport
import com.jilla.ui.simPhoneNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val TAG = "SOSMesh"

fun logi(msg: String) { Log.i(TAG, msg) }

/** Live SOS state shown on the home screen. */
data class SosState(
    val active: Boolean = false,
    val alertShort: String = "",
    val updatesSent: Long = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val deliveredCount: Int = 0,
    val smsSent: Int = 0,
    val locating: Boolean = false,
    val warning: String? = null,
)

/** Someone else's alert heard over the mesh. Only public-header data: rough area, never identity. */
data class NearbyAlert(
    val streamKey: String,
    val alertShort: String,
    val geohash: String,
    val areaLat: Double,
    val areaLng: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val updates: Long,
    val forwarded: Boolean,
    /** Set when the server refused it (e.g. sender not registered). */
    val rejectedReason: String? = null,
)

data class FeedItem(val atMs: Long, val title: String, val detail: String, val mine: Boolean)

data class NodeState(
    val running: Boolean = false,
    val linked: Boolean = false,
    val transports: String = "",
    val gateway: Boolean = false,
    val relayedCount: Int = 0,
)

/** Result of talking to the configured authority server. */
data class ServerState(
    val checking: Boolean = false,
    val reachable: Boolean? = null,
    val keyMatches: Boolean? = null,
    val registered: Boolean = false,
    val identifiedBy: String = "",
    val message: String = "",
)

/**
 * Process-wide mesh node: identity, engine, transports, gateway, live SOS.
 * The foreground [MeshService] keeps the process alive; UI and debug receiver call in here.
 */
class MeshNode(private val ctx: Context) {
    val config = NodeConfig(ctx)
    val ids = IdentityStore(ctx)
    val account = AccountStore(ctx)
    private val authorities = BuildConfig.AUTHORITY_KEYS.split(",").map { it.hexToBytes() }
    private val composer = SosComposer(ids.identity, authorities)
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val notifier = AlertNotifier(ctx)

    private var engine: MeshEngine? = null
    private var transport: MeshTransport? = null
    private var incident: Incident? = null
    private var liveUpdates: ScheduledFuture<*>? = null

    private val _sos = MutableStateFlow(SosState())
    val sos: StateFlow<SosState> = _sos.asStateFlow()
    private val _node = MutableStateFlow(NodeState())
    val node: StateFlow<NodeState> = _node.asStateFlow()
    private val _feed = MutableStateFlow<List<FeedItem>>(emptyList())
    val feed: StateFlow<List<FeedItem>> = _feed.asStateFlow()
    private val _nearby = MutableStateFlow<List<NearbyAlert>>(emptyList())
    val nearby: StateFlow<List<NearbyAlert>> = _nearby.asStateFlow()
    private val _server = MutableStateFlow(ServerState(registered = account.registered))
    val server: StateFlow<ServerState> = _server.asStateFlow()

    init {
        io.scheduleWithFixedDelay({
            _node.update { it.copy(linked = transport?.isLinked() == true && engine != null) }
            // nearby alerts disappear once their relay window is over
            val cutoff = System.currentTimeMillis() - config.relayWindowMin * 60_000L
            _nearby.update { l -> l.filter { it.lastSeenMs >= cutoff } }
        }, 1, 2, TimeUnit.SECONDS)
    }

    @Synchronized
    fun start() {
        if (engine != null) return
        logi("node: starting $config identity=${ids.identity.publicKey.toHex().take(16)} authorities=${authorities.size}")
        val parts = buildList {
            if (config.tcpMesh) add(TcpMeshTransport(config.meshHost, config.meshPort, config.nodeName, ::logi))
            if (config.bluetooth) add(com.jilla.transport.BleMeshTransport(ctx, ::logi))
        }
        val t = CompositeTransport(parts)
        val gateway = if (config.gateway) HttpGateway(config.backendUrl, ::logi) else null
        val e = MeshEngine(
            t, gateway, MemoryPacketStore(), log = ::logi,
            carryWindowMs = config.relayWindowMin * 60_000L,
            rebroadcastIntervalMs = 10_000,
        )
        e.addListener(::onEvent)
        e.start()
        engine = e
        transport = t
        val names = listOfNotNull("TCP".takeIf { config.tcpMesh }, "BLE".takeIf { config.bluetooth }).joinToString("+")
        _node.update { it.copy(running = true, transports = names.ifEmpty { "none" }, gateway = config.gateway) }
        registerConnectivityCallback()
        if (account.onboarded) ensureRegistered()
    }

    @Synchronized
    fun stop() {
        engine?.stop()
        engine = null
        transport = null
        _node.update { it.copy(running = false, linked = false) }
    }

    fun restart() { stop(); start() }

    // ---- server / registration --------------------------------------------

    /** The number the user registers with: active SIM number, else empty (server then uses device id). */
    fun detectPhone(): String = ctx.simPhoneNumber()?.let(RegistrationClient::normalize) ?: ""

    /** Check reachability + key pinning, then register if needed. Safe to call repeatedly. */
    fun ensureRegistered(force: Boolean = false) = net.execute {
        _server.update { it.copy(checking = true, message = "") }
        val client = RegistrationClient(config.backendUrl, ids.identity)
        try {
            val serverKey = client.authorityKey()
            val matches = serverKey in BuildConfig.AUTHORITY_KEYS.split(",")
            if (!matches) {
                _server.value = ServerState(
                    reachable = true, keyMatches = false, registered = account.registered,
                    message = "Server key doesn't match the key built into this app. Alerts sent there can't be opened.",
                )
                logi("server: authority key MISMATCH ($serverKey)")
                return@execute
            }
            // Older builds stored numbers like "91xxxxxxxxxx": normalise and re-register once.
            val fixed = RegistrationClient.normalize(account.phone)
            val needsFix = fixed != account.phone
            if (needsFix) account.phone = fixed
            // Register only once we know who the user is (name is set in onboarding's profile step).
            if ((!account.registered || force || needsFix) && account.name.isNotBlank()) {
                if (account.phone.isBlank()) account.phone = detectPhone()
                val by = client.register(account.name.ifBlank { "Unnamed user" }, account.phone, ids.deviceUuid)
                account.registered = true
                account.identifiedBy = by
                logi("register: ok, identified by $by")
            }
            _server.value = ServerState(
                reachable = true, keyMatches = true, registered = account.registered, identifiedBy = account.identifiedBy,
                message = "Connected",
            )
        } catch (e: RegistrationClient.RegistrationException) {
            _server.value = ServerState(reachable = false, registered = account.registered, message = e.message ?: "error")
            logi("server: ${e.message}")
        }
    }

    fun setServer(host: String) {
        val changed = NodeConfig.cleanServer(host) != config.serverHost
        config.serverHost = host
        if (changed) {
            account.registered = false // different server: register there too
            restart()
        }
        ensureRegistered()
    }

    // ---- mesh events -------------------------------------------------------

    private fun onEvent(e: AlertEvent) {
        val h = e.header ?: return
        val mine = incident?.alertId?.contentEquals(h.alertId) == true
        val short = h.alertIdHex.take(6)
        when (e.action) {
            Action.DELIVERED -> {
                if (mine) _sos.update { it.copy(deliveredCount = it.deliveredCount + 1) }
                else _nearby.update { l -> l.map { if (it.streamKey == h.streamKey) it.copy(forwarded = true) else it } }
            }
            Action.REJECTED_BY_SERVER -> {
                if (mine) _sos.update { it.copy(warning = "Server rejected your SOS: ${e.detail}") }
                else _nearby.update { l -> l.map { if (it.streamKey == h.streamKey) it.copy(rejectedReason = e.detail) else it } }
            }
            Action.RELAYED, Action.TTL_EXHAUSTED -> if (!mine) onNearby(h)
            else -> {}
        }
        if (e.action == Action.RELAYED) _node.update { it.copy(relayedCount = it.relayedCount + 1) }
        val (title, detail) = when (e.action) {
            Action.ORIGINATED -> "Your SOS sent" to "update #${h.counter} broadcast to mesh"
            Action.DELIVERED -> (if (mine) "Your SOS reached the server" else "Forwarded alert $short to the server") to
                "update #${h.counter} accepted"
            Action.RELAYED -> "Someone nearby needs help" to "area ${h.coarseGeohash}; relayed alert $short (identity sealed)"
            Action.TTL_EXHAUSTED -> "Someone nearby needs help" to "area ${h.coarseGeohash}; hop limit reached"
            Action.REJECTED_BY_SERVER -> (if (mine) "Server rejected your SOS" else "Server rejected alert $short") to
                "update #${h.counter}: ${e.detail}"
            Action.DROPPED_BAD_SIG -> "Rejected forged packet" to "signature check failed"
            Action.DROPPED_STALE -> "Rejected stale packet" to e.detail
            else -> return
        }
        _feed.update { (listOf(FeedItem(System.currentTimeMillis(), title, detail, mine)) + it).take(100) }
    }

    private fun onNearby(h: com.jilla.protocol.PublicHeader) {
        val now = System.currentTimeMillis()
        val existing = _nearby.value.firstOrNull { it.streamKey == h.streamKey }
        if (existing == null) {
            val cell = runCatching { Geohash.decode(h.coarseGeohash) }.getOrNull()
            val a = NearbyAlert(
                h.streamKey, h.alertIdHex.take(6), h.coarseGeohash,
                cell?.lat ?: 0.0, cell?.lng ?: 0.0, now, now, h.counter, false,
            )
            _nearby.update { listOf(a) + it }
            notifier.nearby(a)
            logi("nearby: new alert ${a.alertShort} in area ${a.geohash} -> notification shown")
        } else {
            _nearby.update { l -> l.map { if (it.streamKey == h.streamKey) it.copy(lastSeenMs = now, updates = h.counter) else it } }
        }
    }

    // ---- SOS ---------------------------------------------------------------

    /** Start an SOS (or push an update if one is active). Coordinates are always attached. */
    fun triggerSos(note: String = "") {
        if (incident == null) {
            _sos.value = SosState(active = true, locating = true)
            liveUpdates?.cancel(false)
            liveUpdates = io.scheduleWithFixedDelay(
                { if (incident != null) withLocation { lat, lng, acc, warn -> send(lat, lng, acc, "live location update", warn) } },
                LIVE_UPDATE_S, LIVE_UPDATE_S, TimeUnit.SECONDS,
            )
        }
        withLocation { lat, lng, acc, warn -> send(lat, lng, acc, note, warn) }
    }

    fun cancelSos() {
        val wasActive = incident != null
        liveUpdates?.cancel(false)
        liveUpdates = null
        incident = null
        _sos.value = SosState()
        logi("sos: incident closed by user")
        if (wasActive && config.smsContacts) io.execute { SmsAlerter.safe(ctx, account.contacts, account.name.ifBlank { "Your contact" }) }
    }

    /**
     * Gets a fresh high-accuracy fix (10 s budget), falling back to the last known
     * fix. Never silently sends 0,0: if no fix exists the packet carries a warning.
     */
    @SuppressLint("MissingPermission")
    private fun withLocation(cb: (Double, Double, Float?, String?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) logi("sos: location services are OFF")
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        io.schedule({ cts.cancel() }, 10, TimeUnit.SECONDS)
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnCompleteListener { t ->
                    val loc = if (t.isSuccessful) t.result else null
                    if (loc != null) return@addOnCompleteListener cb(loc.latitude, loc.longitude, loc.accuracy, null)
                    client.lastLocation.addOnCompleteListener { last ->
                        val l = if (last.isSuccessful) last.result else null
                        if (l != null) cb(l.latitude, l.longitude, l.accuracy, "using last known location")
                        else cb(0.0, 0.0, null, "NO GPS FIX - location unavailable")
                    }
                }
        } catch (e: SecurityException) {
            cb(0.0, 0.0, null, "NO LOCATION PERMISSION")
        }
    }

    private fun send(lat: Double, lng: Double, acc: Float?, note: String, warning: String?) = io.execute {
        val eng = engine ?: run { logi("sos: node not running"); return@execute }
        val isNew = incident == null
        val inc = incident ?: composer.startIncident().also {
            incident = it
            logi("sos: new incident alert=${it.alertId.toHex().take(8)} incident_pub=${it.key.publicKey.toHex().take(16)}")
        }
        val victim = VictimInfo(
            name = account.name.ifBlank { "Unnamed user" },
            deviceUuid = ids.deviceUuid,
            contacts = account.contacts.map { it.sealedForm() },
        )
        val fullNote = listOfNotNull(note.ifBlank { null }, warning, acc?.let { "accuracy ${it.toInt()} m" }).joinToString("; ")
        val p = composer.compose(inc, victim, lat, lng, fullNote)
        logi("sos: composed ctr=${p.header.counter} size=${p.encode().size}B lat=$lat lng=$lng acc=$acc area=${p.header.coarseGeohash}")
        _sos.update {
            it.copy(
                active = true, alertShort = inc.alertId.toHex().take(6), updatesSent = inc.counter,
                lat = lat, lng = lng, accuracyM = acc, locating = false, warning = warning,
            )
        }
        eng.originate(p)
        // First fix of a new incident: text the emergency contacts from this phone's number.
        if (isNew && config.smsContacts) {
            val n = SmsAlerter.sos(ctx, account.contacts, account.name.ifBlank { "Your contact" }, lat, lng, acc)
            _sos.update { it.copy(smsSent = n) }
        }
    }

    // ---- connectivity ------------------------------------------------------

    private var netCallbackRegistered = false

    private fun registerConnectivityCallback() {
        if (netCallbackRegistered) return
        netCallbackRegistered = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logi("net: connectivity available -> flush carried packets")
                engine?.flushPending()
                if (account.onboarded && !account.registered) ensureRegistered()
            }
        })
    }

    companion object {
        const val LIVE_UPDATE_S = 30L
    }
}
