package `in`.yashd.bitchat.mesh

import android.content.Context
import android.util.Log
import `in`.yashd.bitchat.bluetooth.BleTransport
import `in`.yashd.bitchat.bluetooth.BluetoothReadinessChecker
import `in`.yashd.bitchat.bluetooth.TransportPacket
import `in`.yashd.bitchat.identity.IdentityManager
import `in`.yashd.bitchat.identity.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object MeshRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, _ -> addError("Nearby operation failed; existing data was preserved. Retry after checking Bluetooth and local storage.") })
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

    fun initialize(context: Context) = scope.launch {
        if (::appContext.isInitialized) return@launch
        appContext = context.applicationContext
        identityManager = IdentityManager(appContext)
        readinessChecker = BluetoothReadinessChecker(appContext)
        peerManager = PeerManager()
        messageStore = MessageStore(appContext)
        if (messageStore.storageError) addError("Nearby history is unavailable; existing data was preserved.")
        scope.launch {
            peerManager.peers.collect { publishState() }
        }
        scope.launch {
            messageStore.conversation.collect { publishState() }
        }
        refreshReadiness()
        runCatching { loadIdentity("nearby") }.onFailure { addError("Nearby identity is unavailable; existing data was preserved.") }
        if (identityManager.migratedFromPeerId != null) addError("Your Nearby ID has changed. Share your new ID with your friends.")
    }

    fun refreshReadiness() = scope.launch {
        if (!::readinessChecker.isInitialized) return@launch
        publishState(readiness = readinessChecker.check())
    }

    fun start(displayName: String = "nearby") = scope.launch {
        if (!::appContext.isInitialized) return@launch
        runCatching { loadIdentity(displayName) }.getOrElse {
            addError("Nearby identity is unavailable; existing data was preserved.")
            return@launch
        }
        refreshReadiness()
        val readiness = _state.value.bluetoothReadiness
        if (!readiness.ready) {
            addError("Turn on Bluetooth and allow Nearby permissions to connect.")
            return@launch
        }
        if (transport != null) return@launch
        transport = BleTransport(
            context = appContext,
            onPacket = { packet -> scope.launch { processTransportPacket(packet) } },
            onLinkReady = { scope.launch { sendAnnouncement() } },
            onError = ::addError,
            onLinkLost = { peerManager.linkLost(it) }
        ).also { it.start() }
        publishState(serviceRunning = true)
        sendAnnouncement()
        announceJob?.cancel()
        announceJob = scope.launch {
            while (true) {
                delay(30_000)
                peerManager.expire()
                sendAnnouncement()
            }
        }
    }

    fun stop() = scope.launch {
        announceJob?.cancel()
        announceJob = null
        transport?.stop()
        transport = null
        if (::peerManager.isInitialized) peerManager.disconnectAll()
        publishState(serviceRunning = false)
    }

    fun renameSelf(name: String) = scope.launch {
        localIdentity = runCatching { identityManager.updateDisplayName(name) }.getOrElse {
            addError("Nearby identity could not be updated; existing data was preserved."); return@launch
        }
        publishState()
        if (_state.value.serviceRunning) sendAnnouncement()
    }

    fun rotateIdentity() = scope.launch {
        val currentName = localIdentity?.displayName ?: "nearby"
        stop()
        localIdentity = runCatching { identityManager.rotateIdentity(currentName) }.getOrElse {
            addError("Nearby identity could not be rotated; existing data was preserved."); return@launch
        }
        publishState()
    }

    fun sendMessage(text: String, peerId: String?) = scope.launch {
        val body = text.trim()
        if (body.isBlank()) return@launch
        if (body.length > PacketCodec.MAX_PAYLOAD_BYTES || body.encodeToByteArray().size > PacketCodec.MAX_PAYLOAD_BYTES) {
            addError("Message is too long. Try a shorter message.")
            return@launch
        }
        val identity = localIdentity ?: return@launch
        val peer = peerId?.let { peerManager.get(it) }
        if (peerId != null && peer == null) {
            addError("Unknown Nearby recipient; message was not sent.")
            return@launch
        }
        val privateMessage = peer != null
        val message = runCatching { messageStore.createOutbound(identity.peerId, peerId, body, privateMessage) }
            .getOrElse { addError("Nearby history is unavailable; existing data was preserved."); return@launch }
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
                addError("Couldn't send this private message. Reconnect and try again.")
                return@launch
            }
        } else {
            payload
        }
        val packet = runCatching {
            PacketCodec.createSigned(type, identity.peerId, peerId, finalPayload, identity.signingPrivateKey)
        }.getOrElse {
            messageStore.updateStatus(message.messageId, null, MessageStatus.Failed)
            addError("Couldn't send this message. Try a shorter message or reconnect.")
            return@launch
        }
        val accepted = runCatching { transport?.send(PacketCodec.encode(packet)) == true }
            .onFailure { addError("Nearby send failed.") }.getOrDefault(false)
        messageStore.updateStatus(
            message.messageId,
            packet.packetId.toHex(),
            if (accepted) MessageStatus.SentToTransport else MessageStatus.Failed
        )
        if (!accepted) addError("No Nearby link accepted the message. Turn on Nearby and retry.")
    }

    fun deleteMessage(messageId: String) = scope.launch {
        if (!::messageStore.isInitialized) return@launch
        runCatching { messageStore.deleteMessage(messageId) }
            .onFailure { addError("Couldn't delete the message; existing data was preserved.") }
    }

    /** [peerId] null means the public Nearby group. */
    fun deleteConversation(peerId: String?, onDeleted: () -> Unit = {}) = scope.launch {
        if (!::messageStore.isInitialized) return@launch
        runCatching { messageStore.deleteConversation(peerId) }
            .onSuccess { onDeleted() }
            .onFailure { addError("Couldn't delete the chat; existing data was preserved.") }
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
            .onFailure { Log.d("RelayMesh", "Ignoring invalid Nearby packet") }
            .getOrNull() ?: return
        if (decoded.senderId == localPeerId) return
        val now = System.currentTimeMillis()
        if (decoded.timestampMs !in (now - 10 * 60_000)..(now + 10 * 60_000)) return
        val announcedIdentity = if (decoded.type == PacketType.ANNOUNCE) {
            runCatching { AnnouncementCodec.decode(decoded) }.getOrNull() ?: return
        } else {
            val sender = peerManager.get(decoded.senderId) ?: return
            if (!PacketCodec.verify(decoded, sender.identity.signingPublicKey)) return
            null
        }
        val packetIdHex = decoded.packetId.toHex()
        if (rememberSeen(packetIdHex)) return
        if (decoded.recipientId != null && decoded.recipientId != localPeerId) {
            relay(decoded)
            return
        }
        when (decoded.type) {
            PacketType.ANNOUNCE -> peerManager.upsertAnnouncement(announcedIdentity!!, packet.linkId, null, decoded.ttl == 5)
            PacketType.MESSAGE -> handleMessage(decoded, encrypted = false)
            PacketType.ENCRYPTED -> handleMessage(decoded, encrypted = true)
            PacketType.ACK -> handleAck(decoded)
            PacketType.PING -> sendSimple(PacketType.PONG, decoded.senderId, ByteArray(0))
            else -> Unit
        }
        if (decoded.recipientId == null) relay(decoded)
    }

    private fun handleMessage(decoded: MeshPacket, encrypted: Boolean) {
        val local = localIdentity ?: return
        if (decoded.recipientId != null && decoded.recipientId != local.peerId) return
        val sender = peerManager.get(decoded.senderId)
        val payload = if (encrypted) {
            val peer = sender ?: return
            runCatching {
                cryptoBox.decrypt(local.encryptionPrivateKey, peer.identity.encryptionPublicKey, decoded.payload)
            }.getOrElse {
                Log.d("RelayMesh", "Ignoring unreadable Nearby message")
                return
            }
        } else {
            decoded.payload
        }
        val json = runCatching { JSONObject(payload.decodeToString()) }.getOrNull() ?: return
        runCatching { messageStore.addInbound(
            packetIdHex = decoded.packetId.toHex(),
            senderId = decoded.senderId,
            recipientId = decoded.recipientId,
            body = json.optString("body"),
            isPrivate = encrypted
        ) }.getOrElse { addError("Nearby history is unavailable; existing data was preserved."); return }
        sendAck(decoded.senderId, decoded.packetId.toHex())
    }

    private fun handleAck(decoded: MeshPacket) {
        val json = runCatching { JSONObject(decoded.payload.decodeToString()) }.getOrNull() ?: return
        if (decoded.recipientId != localIdentity?.peerId) return
        runCatching { messageStore.markPacketDelivered(json.optString("ackedPacketId"), decoded.senderId) }
            .onFailure { addError("Nearby history is unavailable; existing data was preserved.") }
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
