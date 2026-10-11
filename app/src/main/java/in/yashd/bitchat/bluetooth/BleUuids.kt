package `in`.yashd.bitchat.bluetooth

import java.util.UUID

object BleUuids {
    val SERVICE_UUID: UUID = UUID.fromString("6f8d2f55-7c8a-4e87-90b7-536f2d03e471")
    val PACKET_CHARACTERISTIC_UUID: UUID = UUID.fromString("2f0a28f7-4d3f-43bb-9622-c6c3f1c06f09")
    val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
