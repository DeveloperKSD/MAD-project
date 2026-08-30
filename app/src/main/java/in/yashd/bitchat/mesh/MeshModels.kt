package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.PeerIdentity

enum class PeerConnectionState {
    Discovered,
    Connecting,
    Connected,
    Authenticated,
    Disconnected,
    Failed
}

data class PeerState(
    val identity: PeerIdentity,
    val state: PeerConnectionState,
    val directDeviceAddress: String?,
    val rssi: Int?,
    val lastConnectedMs: Long?,
    val lastDisconnectedMs: Long?,
    val failureReason: String?
)

enum class MessageStatus {
    Pending,
    SentToTransport,
    Delivered,
    Read,
    Failed
}

enum class MessageDirection {
    Inbound,
    Outbound
}

data class ChatMessage(
    val messageId: String,
    val packetIdHex: String?,
    val senderId: String,
    val recipientId: String?,
    val body: String,
    val createdAtMs: Long,
    val receivedAtMs: Long?,
    val isPrivate: Boolean,
    val status: MessageStatus,
    val direction: MessageDirection
)

data class BluetoothReadiness(
    val hasBle: Boolean,
    val hasPermissions: Boolean,
    val bluetoothEnabled: Boolean,
    val canAdvertise: Boolean,
    val canScan: Boolean,
    val canGattServer: Boolean
) {
    val ready: Boolean
        get() = hasBle && hasPermissions && bluetoothEnabled && canAdvertise && canScan && canGattServer
}

data class MeshUiState(
    val localPeerId: String = "",
    val displayName: String = "",
    val bluetoothReadiness: BluetoothReadiness = BluetoothReadiness(
        hasBle = false,
        hasPermissions = false,
        bluetoothEnabled = false,
        canAdvertise = false,
        canScan = false,
        canGattServer = false
    ),
    val serviceRunning: Boolean = false,
    val peers: List<PeerState> = emptyList(),
    val currentConversation: List<ChatMessage> = emptyList(),
    val errors: List<String> = emptyList()
)

enum class PacketType(val value: Int) {
    ANNOUNCE(0x01),
    MESSAGE(0x02),
    LEAVE(0x03),
    HANDSHAKE(0x10),
    ENCRYPTED(0x11),
    ACK(0x12),
    FRAGMENT(0x20),
    PING(0x30),
    PONG(0x31);

    companion object {
        fun fromValue(value: Int): PacketType? = entries.firstOrNull { it.value == value }
    }
}

data class MeshPacket(
    val version: Int,
    val type: PacketType,
    val ttl: Int,
    val flags: Int,
    val timestampMs: Long,
    val packetId: ByteArray,
    val senderId: String,
    val recipientId: String?,
    val payload: ByteArray,
    val signature: ByteArray?
)

data class RoutedPacket(
    val bytes: ByteArray,
    val ingressLinkId: String?,
    val transportPeerHint: String?
)
