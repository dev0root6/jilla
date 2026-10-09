package com.jilla.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * Bluetooth LE link, peripheral role: this phone runs a GATT server and advertises the
 * Jilla service UUID. Any central that connects and subscribes (another phone, or the
 * authority laptop's browser via Web Bluetooth) receives every packet this node sends
 * or relays as chunked notifications on TX; centrals push packets to us by writing RX.
 *
 * The central role (scan + connect to other phones) runs alongside in [BleCentral], so
 * every phone both serves and connects and alerts hop phone-to-phone without internet.
 */
@SuppressLint("MissingPermission") // checked in hasPermissions()
class BleMeshTransport(private val ctx: Context, private val log: (String) -> Unit) : MeshTransport {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var advertising = false
    private var central: BleCentral? = null

    private var packetHandler: (ByteArray) -> Unit = {}
    private var neighbourHandler: (String) -> Unit = {}

    private class Link(val device: BluetoothDevice) {
        var mtu = 23
        var subscribed = false
        var inFlight = false
        val queue = ArrayDeque<ByteArray>()
        val rx = ChunkFraming.Reassembler()
    }
    private val links = HashMap<String, Link>()

    override fun onPacket(handler: (ByteArray) -> Unit) { packetHandler = handler }
    override fun onNeighbourUp(handler: (String) -> Unit) { neighbourHandler = handler }
    override fun isLinked() = advertising || central?.isLinked() == true

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun start() {
        val adapter = manager?.adapter
        if (adapter == null) return log("ble: no Bluetooth adapter")
        if (!hasPermissions()) return log("ble: nearby-devices permission not granted")
        if (!adapter.isEnabled) return log("ble: Bluetooth is off")
        central = BleCentral(ctx, log, { packetHandler(it) }, { neighbourHandler(it) }).also { it.start() }
        if (adapter.bluetoothLeAdvertiser == null) return log("ble: this phone can't advertise; running as scanner only")

        val txChar = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        val rxChar = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(txChar)
            addCharacteristic(rxChar)
        }
        tx = txChar
        server = manager.openGattServer(ctx, callback)?.also { it.addService(service) }
        if (server == null) log("ble: could not open GATT server")
    }

    private fun startAdvertising() {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 128-bit UUID (18 B) in the advert so centrals can filter on it; name in scan response.
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            log("ble: advertising Jilla service $SERVICE_UUID")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            log("ble: advertising failed (code $errorCode)")
        }
    }

    override fun stop() {
        central?.stop()
        central = null
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        advertising = false
        synchronized(links) { links.clear() }
    }

    override fun broadcast(packet: ByteArray) {
        central?.broadcast(packet)
        val targets = synchronized(links) { links.values.filter { it.subscribed } }
        if (targets.isEmpty()) return
        for (l in targets) {
            synchronized(links) { l.queue.addAll(ChunkFraming.split(packet, BleCentral.chunkSize(l.mtu))) }
            pump(l)
        }
        log("ble: queued ${packet.size}B to ${targets.size} subscriber(s)")
    }

    /** GATT allows one outstanding notification per link: send next chunk after onNotificationSent. */
    private fun pump(l: Link) {
        val chunk = synchronized(links) {
            if (l.inFlight || l.queue.isEmpty()) return
            l.inFlight = true
            l.queue.removeFirst()
        }
        val s = server ?: return
        val c = tx ?: return
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(l.device, c, false, chunk) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(l.device, c, false)
            }
        }.getOrElse { log("ble: notify error ${it.message}"); false }
        if (!ok) {
            synchronized(links) { l.inFlight = false; l.queue.clear() }
            log("ble: notify to ${l.device.address} failed; dropping its queue")
        }
    }

    private fun link(d: BluetoothDevice) = synchronized(links) { links.getOrPut(d.address) { Link(d) } }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else log("ble: addService failed $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                link(device)
                log("ble: central connected ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(links) { links.remove(device.address) }
                log("ble: central disconnected ${device.address}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            link(device).mtu = mtu
            log("ble: mtu ${device.address} = $mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val on = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                link(device).subscribed = on
                log("ble: ${device.address} ${if (on) "subscribed" else "unsubscribed"} to alerts")
                // New listener in range: store-carry-forward re-sends everything we carry.
                if (on) neighbourHandler("ble:${device.address}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (characteristic.uuid == RX_UUID && value != null) {
                link(device).rx.feed(value)?.let { packetHandler(it) }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val l = synchronized(links) { links[device.address] } ?: return
            synchronized(links) { l.inFlight = false }
            pump(l)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6a1b5f00-7c3e-4a0e-9d5b-0a11a5050001")
        val TX_UUID: UUID = UUID.fromString("6a1b5f00-7c3e-4a0e-9d5b-0a11a5050002")
        val RX_UUID: UUID = UUID.fromString("6a1b5f00-7c3e-4a0e-9d5b-0a11a5050003")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
