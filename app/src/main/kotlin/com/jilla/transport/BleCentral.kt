package com.jilla.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.jilla.transport.BleMeshTransport.Companion.CCCD_UUID
import com.jilla.transport.BleMeshTransport.Companion.RX_UUID
import com.jilla.transport.BleMeshTransport.Companion.SERVICE_UUID
import com.jilla.transport.BleMeshTransport.Companion.TX_UUID

/**
 * Central role of the BLE mesh: scan for phones advertising the Jilla service, connect,
 * subscribe to their TX notifications (packets they send/relay) and write our packets to
 * their RX characteristic. Together with the peripheral role in [BleMeshTransport], every
 * phone both serves and connects, so alerts hop phone-to-phone with no internet.
 *
 * Android GATT allows one outstanding operation per connection, so each link runs its
 * operations (MTU, discovery, CCCD write, chunk writes) through a strict queue on one thread.
 */
@SuppressLint("MissingPermission") // BleMeshTransport checks permissions before starting us
internal class BleCentral(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val onNeighbour: (String) -> Unit,
) {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("ble-central").apply { start() }
    private val h = Handler(thread.looper)
    @Volatile private var running = false

    private inner class Link(val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var ready = false
        var mtu = 23
        var busy = false
        val ops = ArrayDeque<() -> Unit>()
        val reasm = ChunkFraming.Reassembler()
    }
    private val links = HashMap<String, Link>()               // touched only on [h]
    private val backoffUntil = HashMap<String, Long>()

    fun start() = h.post {
        if (running) return@post
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return@post log("ble-central: no scanner")
        running = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCb) }
            .onSuccess { log("ble-central: scanning for nearby Jilla phones") }
            .onFailure { log("ble-central: scan failed to start: ${it.message}") }
    }

    fun stop() = h.post {
        running = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        links.values.forEach { runCatching { it.gatt?.close() } }
        links.clear()
    }

    fun isLinked() = links.values.any { it.ready }

    /** Write a packet to every phone we're connected to as central. */
    fun broadcast(packet: ByteArray) = h.post {
        val ready = links.values.filter { it.ready }
        for (l in ready) {
            for (chunk in ChunkFraming.split(packet, chunkSize(l.mtu))) enqueue(l) { write(l, chunk) }
        }
        if (ready.isNotEmpty()) log("ble-central: writing ${packet.size}B to ${ready.size} peer(s)")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post { maybeConnect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("ble-central: scan failed ($errorCode)")
        }
    }

    private fun maybeConnect(d: BluetoothDevice) {
        if (!running || links.containsKey(d.address) || links.size >= MAX_LINKS) return
        if ((backoffUntil[d.address] ?: 0) > System.currentTimeMillis()) return
        val l = Link(d)
        links[d.address] = l
        log("ble-central: found Jilla phone ${d.address}, connecting")
        l.gatt = if (Build.VERSION.SDK_INT >= 26) {
            d.connectGatt(ctx, false, gattCb(l), BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)
        } else {
            d.connectGatt(ctx, false, gattCb(l), BluetoothDevice.TRANSPORT_LE)
        }
    }

    private fun drop(l: Link, why: String) {
        log("ble-central: ${l.device.address} $why")
        runCatching { l.gatt?.close() }
        links.remove(l.device.address)
        backoffUntil[l.device.address] = System.currentTimeMillis() + 5_000
    }

    private fun enqueue(l: Link, op: () -> Unit) {
        l.ops.addLast(op)
        pump(l)
    }

    private fun pump(l: Link) {
        if (l.busy) return
        val op = l.ops.removeFirstOrNull() ?: return
        l.busy = true
        op()
    }

    private fun done(l: Link) {
        l.busy = false
        pump(l)
    }

    private fun write(l: Link, chunk: ByteArray) {
        val g = l.gatt ?: return done(l)
        val c = l.rx ?: return done(l)
        val ok = runCatching { writeRaw(g, c, chunk) }.getOrElse { log("ble-central: write error ${it.message}"); false }
        if (!ok) { l.ops.clear(); done(l) }
    }

    private fun writeRaw(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    private fun gattCb(l: Link) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    log("ble-central: connected to ${l.device.address}")
                    enqueue(l) { if (!g.requestMtu(DESIRED_MTU)) { g.discoverServices(); } }
                } else {
                    drop(l, "disconnected (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            h.post {
                if (status == BluetoothGatt.GATT_SUCCESS) l.mtu = mtu
                l.busy = false
                enqueue(l) { if (!g.discoverServices()) drop(l, "service discovery failed") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                l.busy = false
                val svc = g.getService(SERVICE_UUID) ?: return@post drop(l, "has no Jilla service")
                val tx = svc.getCharacteristic(TX_UUID)
                l.rx = svc.getCharacteristic(RX_UUID)
                if (tx == null || l.rx == null) return@post drop(l, "Jilla service incomplete")
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID) ?: return@post drop(l, "no CCCD")
                enqueue(l) {
                    val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, v) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = v
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    if (!ok) drop(l, "subscribe failed")
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post {
                if (status != BluetoothGatt.GATT_SUCCESS) return@post drop(l, "subscribe rejected ($status)")
                l.ready = true
                log("ble-central: linked with ${l.device.address} (mtu ${l.mtu}), mesh peer up")
                // New peer in range: store-carry-forward re-sends what we carry to it.
                onNeighbour("ble-peer:${l.device.address}")
                done(l)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
                if (status != BluetoothGatt.GATT_SUCCESS) l.ops.clear()
                done(l)
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX_UUID) h.post { l.reasm.feed(value)?.let(onPacket) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            if (Build.VERSION.SDK_INT < 33 && c.uuid == TX_UUID) h.post { l.reasm.feed(v)?.let(onPacket) }
        }
    }

    companion object {
        /** ATT payload is MTU-3, but Android also caps any attribute value at 512 bytes. */
        fun chunkSize(mtu: Int) = minOf(mtu - 3, 512)
        const val MAX_LINKS = 4
        const val DESIRED_MTU = 247
    }
}
