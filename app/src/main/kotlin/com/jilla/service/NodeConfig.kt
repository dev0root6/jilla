package com.jilla.service

import android.content.Context
import com.jilla.BuildConfig

/** Runtime-tunable node settings (Server card, developer panel, or debug `CONFIGURE` broadcast). */
class NodeConfig(ctx: Context) {
    private val p = ctx.getSharedPreferences("jilla_config", Context.MODE_PRIVATE)

    var nodeName: String
        get() = p.getString("node_name", null) ?: "node-${android.os.Build.MODEL.replace(' ', '_')}"
        set(v) = p.edit().putString("node_name", v).apply()

    /**
     * Authority server as entered by the user:
     *  - bare IP/host[:port]  e.g. 192.168.0.103 -> http://192.168.0.103:8000
     *  - full URL             e.g. https://xyz.trycloudflare.com (tunnels: HTTPS on 443, no :8000)
     * 127.0.0.1 works over USB with `adb reverse tcp:8000 tcp:8000`.
     */
    var serverHost: String
        get() = cleanServer(p.getString("server_host", BuildConfig.DEFAULT_MESH_HOST)!!)
        set(v) = p.edit().putString("server_host", cleanServer(v)).apply()

    val backendUrl: String
        get() = serverHost.let {
            when {
                "://" in it -> it
                ':' in it -> "http://$it"
                else -> "http://$it:8000"
            }
        }

    /** mesh-sim hub lives on the same host as the server in dev (not reachable through HTTP tunnels). */
    val meshHost: String
        get() = serverHost.substringAfter("://").substringBefore('/').substringBefore(':')

    var meshPort: Int
        get() = p.getInt("mesh_port", 7777)
        set(v) = p.edit().putInt("mesh_port", v).apply()

    /** Wi-Fi/USB dev mesh via mesh-sim (TCP). Bluetooth runs alongside when permitted. */
    var tcpMesh: Boolean
        get() = p.getBoolean("tcp_mesh", true)
        set(v) = p.edit().putBoolean("tcp_mesh", v).apply()

    var bluetooth: Boolean
        get() = p.getBoolean("bluetooth", true)
        set(v) = p.edit().putBoolean("bluetooth", v).apply()

    /** Whether this node uploads to the server when it has internet. */
    var gateway: Boolean
        get() = p.getBoolean("gateway", true)
        set(v) = p.edit().putBoolean("gateway", v).apply()

    /** How long carried alerts keep being re-broadcast. Dev default 2 min; real deployments > 1 h. */
    var relayWindowMin: Int
        get() = p.getInt("relay_window_min", 2)
        set(v) = p.edit().putInt("relay_window_min", v.coerceIn(1, 24 * 60)).apply()

    /** Text emergency contacts from this phone's own number when an SOS starts / ends. */
    var smsContacts: Boolean
        get() = p.getBoolean("sms_contacts", true)
        set(v) = p.edit().putBoolean("sms_contacts", v).apply()

    companion object {
        /**
         * Keeps only scheme://host[:port] (or host[:port]). People paste the dashboard link,
         * e.g. https://x.trycloudflare.com/?token=..., but phones only need the origin:
         * the token guards the dashboard, not the phone endpoints.
         */
        fun cleanServer(raw: String): String {
            val t = raw.trim()
            val scheme = if ("://" in t) t.substringBefore("://") + "://" else ""
            val authority = t.removePrefix(scheme).substringBefore('/').substringBefore('?').substringBefore('#')
            return scheme + authority
        }
    }

    override fun toString() =
        "name=$nodeName server=$backendUrl mesh=${if (tcpMesh) "$meshHost:$meshPort" else "off"} ble=$bluetooth " +
            "gateway=$gateway relay=${relayWindowMin}min sms=$smsContacts"
}
