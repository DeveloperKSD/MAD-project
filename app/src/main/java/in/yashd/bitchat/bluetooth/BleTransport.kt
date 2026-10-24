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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
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
    private val onError: (String) -> Unit,
    private val onLinkLost: (String) -> Unit = {}
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val advertiser get() = adapter?.bluetoothLeAdvertiser
    private val scanner get() = adapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val connectedGatts = linkedMapOf<String, BluetoothGatt>()
    private val readyClients = mutableSetOf<String>()
    private val subscribedServerClients = linkedMapOf<String, BluetoothDevice>()
    private val negotiatedMtus = mutableMapOf<String, Int>()
    private val serverMtus = mutableMapOf<String, Int>()
    private val writeQueue = LinkedList<Pair<String, ByteArray>>()
    private val notificationQueue = LinkedList<Pair<BluetoothDevice, ByteArray>>()
    private val nextMessageId = AtomicInteger()
    private val reassembler = BleFrameCodec.Reassembler()
    private val write = PendingBleOperation<BluetoothGatt>(
        { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }, handler::removeCallbacks
    ) { gatt -> onError("BLE write timed out"); dropClient(gatt) }
    private val notification = PendingBleOperation<BluetoothDevice>(
        { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }, handler::removeCallbacks
    ) { device ->
        onError("BLE notification timed out")
        runCatching { gattServer?.cancelConnection(device) }
        dropServerClient(device)
    }
    private var gattServer: BluetoothGattServer? = null
    private var packetCharacteristic: BluetoothGattCharacteristic? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = onMain {
            onError("advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onMain {
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
            if (ParcelUuid(BleUuids.SERVICE_UUID) !in serviceUuids) return@onMain
            val address = result.device.address ?: return@onMain
            if (!connectedGatts.containsKey(address) && connectedGatts.size < 16) {
                runCatching {
                    result.device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                }.onSuccess { gatt ->
                    connectedGatts[address] = gatt
                }.onFailure {
                    onError("connect failed: ${it.message ?: "unknown"}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) = onMain {
            onError("scan failed: $errorCode")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (connectedGatts[gatt.device.address] !== gatt) return@onMain
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGatts[gatt.device.address] = gatt
                if (!gatt.requestMtu(512)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                dropClient(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = onMain {
            if (connectedGatts[gatt.device.address] !== gatt) return@onMain
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtus[gatt.device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = onMain {
            if (connectedGatts[gatt.device.address] !== gatt) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError("service discovery failed: $status")
                gatt.disconnect()
                return@onMain
            }
            val characteristic = gatt.getService(BleUuids.SERVICE_UUID)
                ?.getCharacteristic(BleUuids.PACKET_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                onError("packet characteristic missing")
                gatt.disconnect()
                return@onMain
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

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain {
            if (connectedGatts[gatt.device.address] !== gatt || descriptor.uuid != BleUuids.CLIENT_CONFIG_UUID) return@onMain
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markClientReady(gatt)
            } else {
                onError("notification setup failed: $status")
                gatt.disconnect()
            }
        }

        @Deprecated("Deprecated in platform API")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.copyOf() ?: return
            onMain {
                if (connectedGatts[gatt.device.address] === gatt && characteristic.uuid == BleUuids.PACKET_CHARACTERISTIC_UUID) {
                    receiveFrame(value, gatt.device.address)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = onMain {
            if (!write.finish(gatt)) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) onError("write failed: $status")
            drainWrites()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = onMain {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dropServerClient(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = onMain {
            serverMtus[device.address] = mtu
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) = onMain {
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
        ) = onMain {
            val valid = descriptor.uuid == BleUuids.CLIENT_CONFIG_UUID &&
                !preparedWrite && offset == 0 &&
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
        ) = onMain {
            val valid = characteristic.uuid == BleUuids.PACKET_CHARACTERISTIC_UUID && !preparedWrite && offset == 0
            if (valid) {
                receiveFrame(value, device.address)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = onMain {
            if (!notification.finish(device)) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) onError("notification failed: $status")
            drainNotifications()
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running) return
        if (!hasRequiredPermissions()) {
            onError("bluetooth permissions missing")
            return
        }
        running = true
        startGattServer()
        startScanning()
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        running = false
        write.clear()
        notification.clear()
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        connectedGatts.values.forEach { runCatching { it.close() } }
        connectedGatts.clear()
        readyClients.clear()
        subscribedServerClients.clear()
        negotiatedMtus.clear()
        serverMtus.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        packetCharacteristic = null
        writeQueue.clear()
        notificationQueue.clear()
        reassembler.clear()
    }

    fun send(bytes: ByteArray): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!running) return false
        var queued = false
        readyClients.forEach { address ->
            val frames = BleFrameCodec.encode(bytes, valueSize(address), nextMessageId.incrementAndGet())
            if (writeQueue.size + frames.size > MAX_QUEUED_FRAMES) return@forEach
            frames.forEach {
                writeQueue.add(address to it)
            }
            queued = true
        }
        subscribedServerClients.values.forEach { device ->
            val frames = BleFrameCodec.encode(bytes, (serverMtus[device.address] ?: 23) - 3, nextMessageId.incrementAndGet())
            if (notificationQueue.size + frames.size > MAX_QUEUED_FRAMES) return@forEach
            frames.forEach {
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
    }

    private fun drainWrites() {
        if (write.owner != null) return
        while (writeQueue.isNotEmpty()) {
            val (address, bytes) = writeQueue.removeFirst()
            val gatt = connectedGatts[address] ?: continue
            val characteristic = gatt.getService(BleUuids.SERVICE_UUID)
                ?.getCharacteristic(BleUuids.PACKET_CHARACTERISTIC_UUID) ?: continue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            if (runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)) {
                write.start(gatt)
                return
            }
            onError("write could not start")
            dropClient(gatt)
            return
        }
    }

    private fun drainNotifications() {
        if (notification.owner != null) return
        while (notificationQueue.isNotEmpty()) {
            val (device, bytes) = notificationQueue.removeFirst()
            if (device.address !in subscribedServerClients) continue
            val server = gattServer ?: return
            val characteristic = packetCharacteristic ?: return
            characteristic.value = bytes
            if (runCatching { server.notifyCharacteristicChanged(device, characteristic, false) }.getOrDefault(false)) {
                notification.start(device)
                return
            }
            onError("notification could not start")
            runCatching { server.cancelConnection(device) }
            dropServerClient(device)
            return
        }
    }

    private fun dropClient(gatt: BluetoothGatt) {
        val address = gatt.device.address
        connectedGatts.remove(address)
        readyClients.remove(address)
        negotiatedMtus.remove(address)
        writeQueue.removeAll { it.first == address }
        write.finish(gatt)
        runCatching { gatt.disconnect(); gatt.close() }
        linkLost(address)
        drainWrites()
    }

    private fun dropServerClient(device: BluetoothDevice) {
        val address = device.address
        subscribedServerClients.remove(address)
        serverMtus.remove(address)
        notificationQueue.removeAll { it.first.address == address }
        notification.finish(device)
        linkLost(address)
        drainNotifications()
    }

    private fun linkLost(address: String) {
        if (address !in readyClients && address !in subscribedServerClients) {
            reassembler.clear(address)
            onLinkLost(address)
        }
    }

    private fun onMain(action: () -> Unit) {
        handler.post {
            if (running) runCatching(action).onFailure { onError("BLE operation failed; check Bluetooth permissions and retry.") }
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

    private companion object {
        const val OPERATION_TIMEOUT_MS = 15_000L
        const val MAX_QUEUED_FRAMES = 4096
    }
}
