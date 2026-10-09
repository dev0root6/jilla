package com.jilla.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jilla.JillaApp
import com.jilla.service.logi

/**
 * Debug-build-only adb hooks (use -p com.jilla; implicit broadcasts to manifest receivers are blocked):
 *   am broadcast -p com.jilla -a com.jilla.SIMULATE_SOS [--es note "test"]
 *   am broadcast -p com.jilla -a com.jilla.CANCEL_SOS
 *   am broadcast -p com.jilla -a com.jilla.CONFIGURE [--es node_name A] [--es server_host 192.168.1.5]
 *        [--ez tcp_mesh true] [--ez bluetooth true] [--ez gateway true] [--ez sms false] [--ei relay_min 2]
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val node = (ctx.applicationContext as JillaApp).node
        when (intent.action) {
            "com.jilla.SIMULATE_SOS" -> {
                logi("debug: SIMULATE_SOS")
                node.triggerSos(intent.getStringExtra("note") ?: "adb test")
            }
            "com.jilla.CANCEL_SOS" -> node.cancelSos()
            "com.jilla.CONFIGURE" -> {
                val c = node.config
                intent.getStringExtra("node_name")?.let { c.nodeName = it }
                intent.getStringExtra("server_host")?.let { c.serverHost = it }
                if (intent.hasExtra("tcp_mesh")) c.tcpMesh = intent.getBooleanExtra("tcp_mesh", true)
                if (intent.hasExtra("bluetooth")) c.bluetooth = intent.getBooleanExtra("bluetooth", true)
                if (intent.hasExtra("gateway")) c.gateway = intent.getBooleanExtra("gateway", true)
                if (intent.hasExtra("sms")) c.smsContacts = intent.getBooleanExtra("sms", true)
                if (intent.hasExtra("relay_min")) c.relayWindowMin = intent.getIntExtra("relay_min", 2)
                logi("debug: CONFIGURE -> $c")
                node.restart()
            }
        }
    }
}
