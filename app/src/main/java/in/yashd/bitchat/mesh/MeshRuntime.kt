package `in`.yashd.bitchat.mesh

import android.content.Context
import `in`.yashd.bitchat.bluetooth.BleTransport
import `in`.yashd.bitchat.bluetooth.BluetoothReadinessChecker
import `in`.yashd.bitchat.bluetooth.TransportPacket
import `in`.yashd.bitchat.identity.IdentityManager
import `in`.yashd.bitchat.identity.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object MeshRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seenPackets = LinkedHashMap<String, Long>()
    private val cryptoBox = CryptoBox()

    private lateinit var appContext: Context
    private lateinit var identityManager: IdentityManager
    private lateinit var readinessChecker: BluetoothReadinessChecker
    private lateinit var peerManager: PeerManager
    private lateinit var messageStore: MessageStore

    private var localIdentity: LocalIdentity? = null
    private var transport: BleTransport? = null
    private var announceJob: Job? = null

    private val _state = MutableStateFlow(MeshUiState())
    val state: StateFlow<MeshUiState> = _state

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        identityManager = IdentityManager(appContext)
        readinessChecker = BluetoothReadinessChecker(appContext)
        peerManager = PeerManager()
        messageStore = MessageStore(appContext)
        scope.launch {
            peerManager.peers.collect { publishState() }
        }
        scope.launch {
            messageStore.conversation.collect { publishState() }
        }
        refreshReadiness()
        loadIdentity("nearby")
    }

    fun refreshReadiness() {
        if (!::readinessChecker.isInitialized) return
        publishState(readiness = readinessChecker.check())
    }

    fun start(displayName: String = "nearby") {
        if (!::appContext.isInitialized) return
        val identity = loadIdentity(displayName)
        refreshReadiness()
        val readiness = _state.value.bluetoothReadiness
        if (!readiness.ready) {
            addError("bluetooth is not ready")
            return
        }
        if (transport != null) return
        transport = BleTransport(
            context = appContext,
            onPacket = ::processTransportPacket,
            onLinkReady = ::sendAnnouncement,
            onError = ::addError
        ).also { it.start() }
        publishState(serviceRunning = true)
        sendAnnouncement()
        announceJob?.cancel()
        announceJob = scope.launch {
            while (true) {
                delay(30_000)
                sendAnnouncement()
            }
        }
    }

    fun stop() {
        announceJob?.cancel()
        announceJob = null
        transport?.stop()
        transport = null
        publishState(serviceRunning = false)
    }

    fun renameSelf(name: String) {
        localIdentity = identityManager.updateDisplayName(name)
        publishState()
        if (_state.value.serviceRunning) sendAnnouncement()
    }

    fun rotateIdentity() {
        val currentName = localIdentity?.displayName ?: "nearby"
        stop()
        localIdentity = identityManager.rotateIdentity(currentName)
        publishState()
    }

    fun sendMessage(text: String, peerId: String?) {
        val body = text.trim()
        if (body.isBlank()) return
        val identity = localIdentity ?: return
        val peer = peerId?.let { peerManager.get(it) }
        val privateMessage = peer != null
        val message = messageStore.createOutbound(identity.peerId, peerId, body, privateMessage)
        val payload = JSONObject()
            .put("messageId", message.messageId)
            .put("createdAtMs", message.createdAtMs)
            .put("body", body)
            .toString()
            .encodeToByteArray()
        val type = if (privateMessage) PacketType.ENCRYPTED else PacketType.MESSAGE
        val finalPayload = if (privateMessage) {
            runCatching {
                cryptoBox.encrypt(
                    localPrivateKey = identity.encryptionPrivateKey,
                    remotePublicKey = peer!!.identity.encryptionPublicKey,
                    plaintext = payload
                )
            }.getOrElse {
                messageStore.updateStatus(message.messageId, null, MessageStatus.Failed)
                addError("encryption failed")
                return
            }
        } else {
            payload
        }
        val packet = PacketCodec.createSigned(type, identity.peerId, peerId, finalPayload, identity.signingPrivateKey)
        val packetBytes = PacketCodec.encode(packet)
        val accepted = transport?.send(packetBytes) == true
        messageStore.updateStatus(
            message.messageId,
            packet.packetId.toHex(),
            if (accepted) MessageStatus.SentToTransport else MessageStatus.Pending
        )
    }

    private fun loadIdentity(displayName: String): LocalIdentity {
        val identity = identityManager.loadOrCreate { displayName }
        localIdentity = identity
        publishState()
        return identity
    }

    private fun sendAnnouncement() {
        val identity = localIdentity ?: return
        val payload = AnnouncementCodec.encode(identity)
        val packet = PacketCodec.createSigned(
            type = PacketType.ANNOUNCE,
            senderId = identity.peerId,
            recipientId = null,
            payload = payload,
            signingPrivateKey = identity.signingPrivateKey
        )
        transport?.send(PacketCodec.encode(packet))
    }

    private fun processTransportPacket(packet: TransportPacket) {
        val localPeerId = localIdentity?.peerId ?: return
        val decoded = runCatching { PacketCodec.decode(packet.bytes) }
            .onFailure { addError("malformed packet dropped") }
            .getOrNull() ?: return
        if (decoded.senderId == localPeerId) return
        if (kotlin.math.abs(System.currentTimeMillis() - decoded.timestampMs) > 10 * 60_000) return
        val packetIdHex = decoded.packetId.toHex()
        if (rememberSeen(packetIdHex)) return
        if (decoded.recipientId != null && decoded.recipientId != localPeerId) {
            relay(decoded)
            return
        }
        when (decoded.type) {
            PacketType.ANNOUNCE -> handleAnnouncement(decoded, packet)
            PacketType.MESSAGE -> handleMessage(decoded, encrypted = false)
            PacketType.ENCRYPTED -> handleMessage(decoded, encrypted = true)
            PacketType.ACK -> handleAck(decoded)
            PacketType.PING -> sendSimple(PacketType.PONG, decoded.senderId, ByteArray(0))
            else -> Unit
        }
        if (decoded.recipientId == null) relay(decoded)
    }

    private fun handleAnnouncement(decoded: MeshPacket, packet: TransportPacket) {
        val identity = runCatching { AnnouncementCodec.decode(decoded) }
            .onFailure { addError("invalid announcement dropped") }
            .getOrNull() ?: return
        peerManager.upsertAnnouncement(identity, packet.linkId, null)
        sendAnnouncement()
    }

    private fun handleMessage(decoded: MeshPacket, encrypted: Boolean) {
        val local = localIdentity ?: return
        if (decoded.recipientId != null && decoded.recipientId != local.peerId) return
        val sender = peerManager.get(decoded.senderId)
        if (sender != null && !PacketCodec.verify(decoded, sender.identity.signingPublicKey)) {
            addError("invalid message signature")
            return
        }
        val payload = if (encrypted) {
            val peer = sender ?: return addError("encrypted message from unknown peer")
            runCatching {
                cryptoBox.decrypt(local.encryptionPrivateKey, peer.identity.encryptionPublicKey, decoded.payload)
            }.getOrElse {
                addError("decryption failed")
                return
            }
        } else {
            decoded.payload
        }
        val json = runCatching { JSONObject(payload.decodeToString()) }.getOrNull() ?: return
        messageStore.addInbound(
            packetIdHex = decoded.packetId.toHex(),
            senderId = decoded.senderId,
            recipientId = decoded.recipientId,
            body = json.optString("body"),
            isPrivate = encrypted
        )
        sendAck(decoded.senderId, decoded.packetId.toHex())
    }

    private fun handleAck(decoded: MeshPacket) {
        val json = runCatching { JSONObject(decoded.payload.decodeToString()) }.getOrNull() ?: return
        messageStore.markPacketDelivered(json.optString("ackedPacketId"))
    }

    private fun sendAck(recipientId: String, packetIdHex: String) {
        val payload = JSONObject()
            .put("ackedPacketId", packetIdHex)
            .put("status", "received")
            .put("timestampMs", System.currentTimeMillis())
            .toString()
            .encodeToByteArray()
        sendSimple(PacketType.ACK, recipientId, payload)
    }

    private fun sendSimple(type: PacketType, recipientId: String, payload: ByteArray) {
        val identity = localIdentity ?: return
        val packet = PacketCodec.createSigned(type, identity.peerId, recipientId, payload, identity.signingPrivateKey)
        transport?.send(PacketCodec.encode(packet))
    }

    private fun relay(packet: MeshPacket) {
        if (packet.ttl <= 0) return
        transport?.send(PacketCodec.encode(packet.copy(ttl = packet.ttl - 1)))
    }

    private fun rememberSeen(packetIdHex: String): Boolean {
        val now = System.currentTimeMillis()
        val duplicate = seenPackets.containsKey(packetIdHex)
        seenPackets[packetIdHex] = now
        val cutoff = now - 10 * 60_000
        val iterator = seenPackets.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff || seenPackets.size > 5000) iterator.remove()
        }
        return duplicate
    }

    private fun addError(error: String) {
        val errors = (_state.value.errors + error).takeLast(4)
        _state.value = _state.value.copy(errors = errors)
    }

    private fun publishState(
        readiness: BluetoothReadiness = _state.value.bluetoothReadiness,
        serviceRunning: Boolean = _state.value.serviceRunning
    ) {
        val identity = localIdentity
        _state.value = MeshUiState(
            localPeerId = identity?.peerId.orEmpty(),
            displayName = identity?.displayName.orEmpty(),
            bluetoothReadiness = readiness,
            serviceRunning = serviceRunning,
            peers = if (::peerManager.isInitialized) peerManager.peers.value else emptyList(),
            currentConversation = if (::messageStore.isInitialized) messageStore.conversation.value else emptyList(),
            errors = _state.value.errors
        )
    }
}
