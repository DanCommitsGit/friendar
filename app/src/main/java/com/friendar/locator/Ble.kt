package com.friendar.locator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.util.UUID

private val SERVICE_UUID: UUID = UUID.fromString("5f1c0a7e-8a3b-4b8e-9f64-53f0e2a1c001")
private val CHAR_UUID: UUID = UUID.fromString("5f1c0a7e-8a3b-4b8e-9f64-53f0e2a1c002")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val COMPANY_ID = 0xFFFF
private const val SETUP_TIMEOUT_MS = 5_000L

private fun nonceBytes(nonce: Int): ByteArray = ByteBuffer.allocate(4).putInt(nonce).array()

/** Callbacks arrive on arbitrary Bluetooth threads. */
interface LinkListener {
    /** The other phone is connected and can receive messages; [asHost] is true when it connected to us. */
    fun onReady(asHost: Boolean)
    fun onMessage(bytes: ByteArray)
    fun onRssi(dbm: Int)
    fun onDisconnected()
    fun onError(message: String)
    fun onLog(text: String)
}

/**
 * Advertises a GATT service, with [nonce] so the other phone can decide which of the two
 * connects, and accepts a single peer.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleHost(private val context: Context, private val id: Int, private val listener: LinkListener) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var peer: BluetoothDevice? = null
    private var peerReady = false
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var extendedCallback: AdvertisingSetCallback? = null

    // A link that survived the friend's restart connects but never sets up notifications.
    private val dropSilentPeer = Runnable {
        val device = peer ?: return@Runnable
        if (peerReady) return@Runnable
        listener.onLog("Bluetooth: friend's link never set up, dropping it")
        server?.cancelConnection(device)
        peer = null
        startAdvertising()
    }

    fun start() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Turn Bluetooth on")
            return
        }
        val ch = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        characteristic = ch
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(ch)
        val gattServer = manager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            listener.onError("Bluetooth unavailable")
            return
        }
        server = gattServer
        // A connection left over from an earlier session would otherwise be taken as the friend.
        manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).forEach { gattServer.cancelConnection(it) }
        gattServer.addService(service)
    }

    fun send(bytes: ByteArray) {
        val device = peer ?: return
        val ch = characteristic ?: return
        val gattServer = server ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            gattServer.notifyCharacteristicChanged(device, ch, false, bytes)
        } else {
            ch.value = bytes
            gattServer.notifyCharacteristicChanged(device, ch, false)
        }
    }

    fun stop() {
        handler.removeCallbacks(dropSilentPeer)
        stopAdvertising()
        server?.close()
        server = null
        peer = null
        peerReady = false
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onError("Bluetooth advertising unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(COMPANY_ID, nonceBytes(id))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                listener.onLog("Bluetooth: advertising")
            }

            override fun onStartFailure(errorCode: Int) {
                listener.onError("Couldn't start advertising ($errorCode)")
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
        startLongRangeAdvertising()
    }

    /** A second, coded-PHY advertisement for devices that can hear it; the legacy one above stays. */
    private fun startLongRangeAdvertising() {
        val adapter = manager?.adapter ?: return
        if (!adapter.isLeCodedPhySupported || !adapter.isLeExtendedAdvertisingSupported) {
            listener.onLog("Bluetooth: long range not supported on this phone")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(COMPANY_ID, nonceBytes(id))
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                listener.onLog(
                    if (status == ADVERTISE_SUCCESS) {
                        "Bluetooth: long range advertising"
                    } else {
                        "Bluetooth: long range advertising failed ($status)"
                    },
                )
            }
        }
        extendedCallback = callback
        runCatching { advertiser.startAdvertisingSet(parameters, data, null, null, null, callback) }
            .onFailure { listener.onLog("Bluetooth: long range advertising failed (${it.javaClass.simpleName})") }
    }

    private fun stopAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        extendedCallback?.let { runCatching { advertiser?.stopAdvertisingSet(it) } }
        extendedCallback = null
        val callback = advertiseCallback ?: return
        advertiser?.stopAdvertising(callback)
        advertiseCallback = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising()
            } else {
                listener.onError("Couldn't start Bluetooth service ($status)")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (device != peer) {
                    peer = device
                    peerReady = false
                    stopAdvertising()
                    listener.onLog("Bluetooth: friend connected")
                    handler.removeCallbacks(dropSilentPeer)
                    handler.postDelayed(dropSilentPeer, SETUP_TIMEOUT_MS)
                }
            } else if (device == peer) {
                handler.removeCallbacks(dropSilentPeer)
                peer = null
                peerReady = false
                listener.onDisconnected()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (device != peer) peer = device
            peerReady = true
            handler.removeCallbacks(dropSilentPeer)
            listener.onReady(asHost = true)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (device == peer) listener.onMessage(value)
        }
    }
}

/**
 * Scans for the other phone and connects when its nonce is higher than [id],
 * then reports signal strength while connected.
 * GATT permits one outstanding operation, so every write and read goes through [ops].
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleJoiner(private val context: Context, private val id: Int, private val listener: LinkListener) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var longRangeScanCallback: ScanCallback? = null
    private var linkCoded = false
    private var stopped = false
    private var connectAttempts = 0
    private var established = false

    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private val opTimeout = Runnable {
        busy = false
        nextOp()
    }

    fun start() {
        stopped = false
        scan()
    }

    fun send(bytes: ByteArray) {
        val ch = characteristic ?: return
        enqueue {
            val g = gatt ?: return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        ops.clear()
        stopScan()
        gatt?.close()
        gatt = null
    }

    private fun scan() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Turn Bluetooth on")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Bluetooth scanning unavailable")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = consider(result)

            override fun onScanFailed(errorCode: Int) {
                listener.onError("Couldn't scan ($errorCode)")
            }
        }
        scanCallback = callback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        listener.onLog("Bluetooth: scanning")
        startLongRangeScan(adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported, filter)
    }

    /** A second scan that also hears extended and coded-PHY advertisements; the legacy scan above stays. */
    private fun startLongRangeScan(supported: Boolean, filter: ScanFilter) {
        if (!supported) return
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = consider(result)

            override fun onScanFailed(errorCode: Int) {
                listener.onLog("Bluetooth: long range scan failed ($errorCode)")
            }
        }
        longRangeScanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { longRangeScanCallback = null }
    }

    private fun consider(result: ScanResult) {
        if (gatt != null) return
        val peerId = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            ?.takeIf { it.size >= 4 }
            ?.let { ByteBuffer.wrap(it).int }
            ?: return
        if (peerId <= id) return
        stopScan()
        linkCoded = result.primaryPhy == BluetoothDevice.PHY_LE_CODED ||
            result.secondaryPhy == BluetoothDevice.PHY_LE_CODED
        listener.onLog("Bluetooth: found friend${if (linkCoded) " (long range)" else ""}, connecting")
        gatt = if (result.isLegacy) {
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
            )
        }
    }

    private fun stopScan() {
        val scanner = manager?.adapter?.bluetoothLeScanner
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        scanCallback = null
        longRangeScanCallback?.let { runCatching { scanner?.stopScan(it) } }
        longRangeScanCallback = null
    }

    private fun retryConnect(status: Int) {
        gatt?.close()
        gatt = null
        characteristic = null
        ops.clear()
        busy = false
        connectAttempts++
        listener.onLog("Bluetooth: connect failed ($status), retrying")
        handler.postDelayed({ if (!stopped) scan() }, minOf(connectAttempts, MAX_RETRY_DELAY_S) * 1000L)
    }

    private fun enqueue(op: () -> Boolean) {
        handler.post {
            ops.addLast(op)
            nextOp()
        }
    }

    private fun nextOp() {
        if (busy || stopped) return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
        if (!op()) {
            handler.removeCallbacks(opTimeout)
            busy = false
            nextOp()
        }
    }

    private fun opDone() {
        handler.post {
            handler.removeCallbacks(opTimeout)
            busy = false
            nextOp()
        }
    }

    private fun pollRssi() {
        if (stopped) return
        if (ops.isEmpty() && !busy) enqueue { gatt?.readRemoteRssi() ?: false }
        handler.postDelayed(::pollRssi, RSSI_INTERVAL_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (stopped) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        listener.onLog("Bluetooth: connected")
                        g.discoverServices()
                    }

                    !established && (status != BluetoothGatt.GATT_SUCCESS ||
                        newState == BluetoothProfile.STATE_DISCONNECTED) -> retryConnect(status)

                    newState == BluetoothProfile.STATE_DISCONNECTED -> listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                val cccd = ch?.getDescriptor(CCCD_UUID)
                if (ch == null || cccd == null) {
                    retryConnect(status)
                    return@post
                }
                characteristic = ch
                if (linkCoded) {
                    runCatching {
                        g.setPreferredPhy(
                            BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_OPTION_S8,
                        )
                    }
                }
                g.setCharacteristicNotification(ch, true)
                enqueue {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opDone()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { retryConnect(status) }
                return
            }
            handler.post {
                established = true
                listener.onLog("Bluetooth: link ready")
                listener.onReady(asHost = false)
                handler.postDelayed(::pollRssi, RSSI_INTERVAL_MS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            listener.onMessage(ch.value ?: return)
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            listener.onLog("Bluetooth: PHY tx=$txPhy rx=$rxPhy (3 = long range)")
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            opDone()
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(rssi)
        }
    }

    private companion object {
        const val MAX_RETRY_DELAY_S = 5
        const val OP_TIMEOUT_MS = 5000L
        const val RSSI_INTERVAL_MS = 1000L
    }
}
