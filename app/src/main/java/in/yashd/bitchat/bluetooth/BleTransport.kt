package `in`.yashd.bitchat.bluetooth

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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger

data class TransportPacket(
    val bytes: ByteArray,
    val linkId: String?,
    val peerHint: String?
)

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val onPacket: (TransportPacket) -> Unit,
    private val onLinkReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private val scanner = adapter?.bluetoothLeScanner
    private val connectedGatts = linkedMapOf<String, BluetoothGatt>()
    private val readyClients = mutableSetOf<String>()
    private val subscribedServerClients = linkedMapOf<String, BluetoothDevice>()
    private val negotiatedMtus = mutableMapOf<String, Int>()
    private val writeQueue = LinkedList<Pair<String, ByteArray>>()
    private val notificationQueue = LinkedList<Pair<BluetoothDevice, ByteArray>>()
    private val nextMessageId = AtomicInteger()
    private val reassembler = BleFrameCodec.Reassembler()
    private var activeWrite = false
    private var activeNotification = false
    private var gattServer: BluetoothGattServer? = null
    private var packetCharacteristic: BluetoothGattCharacteristic? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onError("advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
            if (ParcelUuid(BleUuids.SERVICE_UUID) !in serviceUuids) return
            val address = result.device.address ?: return
            if (!connectedGatts.containsKey(address)) {
                runCatching {
                    result.device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                }.onSuccess { gatt ->
                    connectedGatts[address] = gatt
                }.onFailure {
                    onError("connect failed: ${it.message ?: "unknown"}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onError("scan failed: $errorCode")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGatts[gatt.device.address] = gatt
                if (!gatt.requestMtu(512)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectedGatts.remove(gatt.device.address)
                readyClients.remove(gatt.device.address)
                negotiatedMtus.remove(gatt.device.address)
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtus[gatt.device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("service discovery failed: $status")
                gatt.disconnect()
                return
            }
            val characteristic = gatt.getService(BleUuids.SERVICE_UUID)
                ?.getCharacteristic(BleUuids.PACKET_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                onError("packet characteristic missing")
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleUuids.CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(descriptor)) {
                    onError("notification setup failed")
                    gatt.disconnect()
                }
            } else {
                markClientReady(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleUuids.CLIENT_CONFIG_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markClientReady(gatt)
            } else {
                onError("notification setup failed: $status")
                gatt.disconnect()
            }
        }

        @Deprecated("Deprecated in platform API")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BleUuids.PACKET_CHARACTERISTIC_UUID) {
                receiveFrame(characteristic.value, gatt.device.address)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) onError("write failed: $status")
            activeWrite = false
            drainWrites()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedServerClients.remove(device.address)
                negotiatedMtus.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtus[device.address] = mtu
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else onError("GATT service registration failed: $status")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = descriptor.uuid == BleUuids.CLIENT_CONFIG_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (valid) subscribedServerClients[device.address] = device
            val status = if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, offset, null)
            if (valid) onLinkReady()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleUuids.PACKET_CHARACTERISTIC_UUID) {
                receiveFrame(value, device.address)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) onError("notification failed: $status")
            activeNotification = false
            drainNotifications()
        }
    }

    fun start() {
        if (!hasRequiredPermissions()) {
            onError("bluetooth permissions missing")
            return
        }
        startGattServer()
        startScanning()
    }

    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        connectedGatts.values.forEach { runCatching { it.close() } }
        connectedGatts.clear()
        readyClients.clear()
        subscribedServerClients.clear()
        negotiatedMtus.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        packetCharacteristic = null
        writeQueue.clear()
        notificationQueue.clear()
        reassembler.clear()
        activeWrite = false
        activeNotification = false
    }

    fun send(bytes: ByteArray): Boolean {
        var queued = false
        readyClients.forEach { address ->
            BleFrameCodec.encode(bytes, valueSize(address), nextMessageId.incrementAndGet()).forEach {
                writeQueue.add(address to it)
            }
            queued = true
        }
        subscribedServerClients.values.forEach { device ->
            BleFrameCodec.encode(bytes, valueSize(device.address), nextMessageId.incrementAndGet()).forEach {
                notificationQueue.add(device to it)
            }
            queued = true
        }
        drainWrites()
        drainNotifications()
        return queued
    }

    private fun startGattServer() {
        val characteristic = BluetoothGattCharacteristic(
            BleUuids.PACKET_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                BleUuids.CLIENT_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val service = BluetoothGattService(BleUuids.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        packetCharacteristic = characteristic
        val server = bluetoothManager?.openGattServer(context, serverCallback)
        if (server == null || !server.addService(service)) {
            onError("GATT server unavailable")
            server?.close()
            return
        }
        gattServer = server
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun drainWrites() {
        if (activeWrite) return
        val (address, bytes) = writeQueue.poll() ?: return
        val gatt = connectedGatts[address] ?: return drainWrites()
        val characteristic = gatt.getService(BleUuids.SERVICE_UUID)
            ?.getCharacteristic(BleUuids.PACKET_CHARACTERISTIC_UUID)
            ?: return drainWrites()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = bytes
        activeWrite = gatt.writeCharacteristic(characteristic)
        if (!activeWrite) {
            onError("write could not start")
            drainWrites()
        }
    }

    private fun drainNotifications() {
        if (activeNotification) return
        val (device, bytes) = notificationQueue.poll() ?: return
        val server = gattServer ?: return
        val characteristic = packetCharacteristic ?: return
        characteristic.value = bytes
        activeNotification = server.notifyCharacteristicChanged(device, characteristic, false)
        if (!activeNotification) {
            onError("notification could not start")
            drainNotifications()
        }
    }

    private fun markClientReady(gatt: BluetoothGatt) {
        if (readyClients.add(gatt.device.address)) onLinkReady()
    }

    private fun receiveFrame(bytes: ByteArray, linkId: String) {
        runCatching { reassembler.accept(linkId, bytes) }
            .onFailure { onError("malformed BLE frame dropped") }
            .getOrNull()
            ?.let { onPacket(TransportPacket(it, linkId, null)) }
    }

    private fun valueSize(address: String): Int = (negotiatedMtus[address] ?: 23) - 3

    private fun hasRequiredPermissions(): Boolean {
        return BluetoothReadinessChecker.requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
