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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import `in`.yashd.bitchat.transfer.*
import `in`.yashd.bitchat.transfer.ble.*

object MeshRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, _ -> addError("Nearby operation failed; existing data was preserved. Retry after checking Bluetooth and local storage.") })
    private val notificationPackets = HashMap<String, Long>()
    private val seenPackets = LinkedHashMap<String, Long>()
    private val cryptoBox = CryptoBox()

    private lateinit var appContext: Context
    private lateinit var identityManager: IdentityManager
    private lateinit var readinessChecker: BluetoothReadinessChecker
    private lateinit var peerManager: PeerManager
    private lateinit var nicknameStore: PeerNicknameStore
    private lateinit var messageStore: MessageStore
    private lateinit var requestsStore: NearbyRequestsStore
    /** Signed control messages (e.g. request_accept) awaiting a route, retried with the queue. */
    private val pendingControls = LinkedHashMap<String, String>()
    private val _pendingRequests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pendingRequests: StateFlow<Map<String, Long>> = _pendingRequests

    @Volatile private var localIdentity: LocalIdentity? = null
    private val fileDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fileScope = CoroutineScope(SupervisorJob() + fileDispatcher +
        CoroutineExceptionHandler { _, _ -> addError("File transfer paused; saved files were preserved.") })
    private val fileInbox = Channel<TransportPacket>(32)
    private var fileController: BleFileController? = null
    private var fileUnavailable = false
    private val fileSeen = linkedMapOf<String, Long>()
    private val fileState = MutableStateFlow<List<BleFileStatus>>(emptyList())
    val fileTransfers: StateFlow<List<BleFileStatus>> = fileState
    private val forwardingFiles = BleFileForwardingGate(
        peer = { id -> if (::peerManager.isInitialized) peerManager.peers.value.firstOrNull { it.identity.peerId == id }?.identity else null },
        drop = { id -> runBlocking(Dispatchers.Main.immediate) { transport?.cancelQueuedFile(id) } })

    init {
        fileScope.launch {
            for (incoming in fileInbox) {
                runCatching { processFilePacket(incoming) }.onFailure { addError("File transfer unavailable; saved files were preserved.") }
                fileController?.let { fileState.value = it.state.value }
            }
        }
        fileScope.launch {
            while (true) {
                delay(1000)
                runCatching { forwardingFiles.busy(System.currentTimeMillis()); fileController?.tick() }.onFailure { addError("File transfer paused; check the link and saved files.") }
                fileController?.let { fileState.value = it.state.value }
            }
        }
    }
    private var receiveStartedAt = Long.MAX_VALUE
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
        nicknameStore = PeerNicknameStore(appContext)
        if (nicknameStore.storageError) addError("Saved nearby names are unavailable; live names will be kept for this session.")
        scope.launch { nicknameStore.names.collect { publishState() } }
        messageStore = MessageStore(appContext)
        if (messageStore.storageError) addError("Nearby history is unavailable; existing data was preserved.")
        requestsStore = NearbyRequestsStore(appContext)
        requestsStore.seedAccepted(messageStore.knownPeerIds())
        // Accepted requests whose signed accept never reached the mesh keep retrying after restarts.
        requestsStore.acceptOutbox.value.forEach { pendingControls["$it:request_accept"] = it }
        scope.launch { requestsStore.pending.collect { _pendingRequests.value = it } }
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
        receiveStartedAt = System.currentTimeMillis()
        transport = BleTransport(
            context = appContext,
            onPacket = { packet -> scope.launch { processTransportPacket(packet) } },
            onLinkReady = { scope.launch { sendAnnouncement(); flushQueued() } },
            onError = ::addError,
            onLinkLost = {
                peerManager.linkLost(it)
                fileScope.launch { fileController?.linkLost(); forwardingFiles.reset(System.currentTimeMillis()) }
            }
        ).also { it.start() }
        publishState(serviceRunning = true)
        sendAnnouncement()
        announceJob?.cancel()
        announceJob = scope.launch {
            while (true) {
                delay(30_000)
                peerManager.expire()
                sendAnnouncement()
                flushQueued()
            }
        }
    }

    fun stop() = scope.launch {
        announceJob?.cancel()
        announceJob = null
        transport?.stop()
        transport = null
        fileScope.launch { fileController?.linkLost(); forwardingFiles.reset(System.currentTimeMillis()) }
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
        fileScope.launch { fileController?.close(); fileController = null; fileUnavailable = false }
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
        // Out-of-range friends are queued, never blocked and never sent to the public group.
        val message = runCatching { messageStore.createOutbound(identity.peerId, peerId, body, peerId != null) }
            .getOrElse { addError("Nearby history is unavailable; existing data was preserved."); return@launch }
        // Starting a chat ourselves means it belongs in our own chat list.
        if (peerId != null && ::requestsStore.isInitialized) requestsStore.accept(peerId)
        dispatchOutbound(message)
    }

    /** Accept an incoming chat request: the chat joins the list and they get a signed accept. */
    fun acceptRequest(peerId: String) = scope.launch {
        if (!::requestsStore.isInitialized) return@launch
        requestsStore.accept(peerId, notifyPeer = true)
        pendingControls["$peerId:request_accept"] = peerId
        sendControl(peerId, "request_accept")
    }

    /** Decline a request: it leaves the list along with its messages on this phone only. */
    fun declineRequest(peerId: String) = scope.launch {
        if (!::requestsStore.isInitialized) return@launch
        requestsStore.decline(peerId)
        deleteConversation(peerId)
    }

    private fun sendControl(peerId: String, kind: String): Boolean {
        val identity = localIdentity ?: return false
        val payload = JSONObject().put("kind", kind).toString().encodeToByteArray()
        val packet = runCatching {
            PacketCodec.createSigned(PacketType.CONTROL, identity.peerId, peerId, payload, identity.signingPrivateKey)
        }.getOrNull() ?: return false
        val accepted = runCatching { transport?.send(PacketCodec.encode(packet)) == true }.getOrDefault(false)
        if (accepted) {
            pendingControls.remove("$peerId:$kind")
            if (kind == "request_accept" && ::requestsStore.isInitialized) requestsStore.acceptDispatched(peerId)
        }
        return accepted
    }

    private fun handleControl(decoded: MeshPacket) {
        val json = runCatching { JSONObject(decoded.payload.decodeToString()) }.getOrNull() ?: return
        if (decoded.recipientId != localIdentity?.peerId) return
        if (json.optString("kind") != "request_accept" || !::requestsStore.isInitialized) return
        val sender = peerManager.get(decoded.senderId)
        if (requestsStore.markAcceptedBy(decoded.senderId)) {
            val local = localIdentity ?: return
            `in`.yashd.bitchat.notifications.MessageNotifications.stored(
                appContext,
                `in`.yashd.bitchat.ui.relay.MutedChatsStore.key("nearby", local.peerId, decoded.senderId),
                `in`.yashd.bitchat.nearbyRoute(decoded.senderId),
                sender?.identity?.displayName.orEmpty(),
                "Accepted your chat request"
            )
        }
    }

    /**
     * Tries one dispatch of a queued message. Returns false (message stays Pending) when no
     * link accepted it or the recipient's keys are not known yet; their next announcement
     * supplies the keys and the flush retries. Runs only on the main scope, so a queued
     * message is never dispatched twice at once.
     */
    private fun dispatchOutbound(message: ChatMessage): Boolean {
        val identity = localIdentity ?: return false
        if (message.direction != MessageDirection.Outbound || message.status != MessageStatus.Pending) return false
        val peer = message.recipientId?.let { peerManager.get(it) }
        if (message.isPrivate && peer == null) return false
        val payload = JSONObject()
            .put("messageId", message.messageId)
            .put("createdAtMs", message.createdAtMs)
            .put("body", message.body)
            .toString()
            .encodeToByteArray()
        val type = if (message.isPrivate) PacketType.ENCRYPTED else PacketType.MESSAGE
        val finalPayload = if (message.isPrivate) {
            runCatching {
                cryptoBox.encrypt(
                    localPrivateKey = identity.encryptionPrivateKey,
                    remotePublicKey = peer!!.identity.encryptionPublicKey,
                    plaintext = payload
                )
            }.getOrElse {
                messageStore.updateStatus(message.messageId, null, MessageStatus.Failed)
                addError("Couldn't send this private message. Reconnect and try again.")
                return false
            }
        } else {
            payload
        }
        val packet = runCatching {
            PacketCodec.createSigned(type, identity.peerId, message.recipientId, finalPayload, identity.signingPrivateKey)
        }.getOrElse {
            messageStore.updateStatus(message.messageId, null, MessageStatus.Failed)
            addError("Couldn't send this message. Try a shorter message or reconnect.")
            return false
        }
        val accepted = runCatching { transport?.send(PacketCodec.encode(packet)) == true }.getOrDefault(false)
        if (accepted) messageStore.updateStatus(message.messageId, packet.packetId.toHex(), MessageStatus.SentToTransport)
        return accepted
    }

    /** Pull-to-refresh: re-check Bluetooth, re-announce and retry queued messages. */
    fun refreshNow() = scope.launch {
        if (!::appContext.isInitialized) return@launch
        refreshReadiness()
        sendAnnouncement()
        flushQueued()
    }

    /** Retries every queued message: on new links, on announcements and on the announce tick. */
    fun flushQueued() = scope.launch {
        if (!::messageStore.isInitialized) return@launch
        messageStore.pendingOutbound().forEach { dispatchOutbound(it) }
        pendingControls.entries.toList().forEach { (key, peerId) ->
            sendControl(peerId, key.substringAfter(':'))
        }
    }

    /** Original Stage 2A service APIs; Stage 2B UI uses the result-bearing APIs below.
     * Caller owns source/output streams and must keep them valid until the returned Job ends.
     */
    fun offerFile(peerId: String, source: InputStream, multiHop: Boolean = false) = fileAction {
        check(!forwardingFiles.busy(System.currentTimeMillis())) { "A forwarded transfer owns this device" }
        it.offer(peerId, source, if (multiHop) TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE)
    }
    fun acceptFile(id: UUID) = fileAction { check(!forwardingFiles.busy(System.currentTimeMillis())); it.accept(id) }
    fun rejectFile(id: UUID) = fileAction { it.reject(id) }
    fun pauseFile(id: UUID) = fileAction { it.pause(id) }
    fun resumeFile(id: UUID) = fileAction { check(!forwardingFiles.busy(System.currentTimeMillis())); it.resume(id) }
    fun cancelFile(id: UUID) = fileAction { it.cancel(id) }
    fun discardFile(id: UUID) = fileAction { it.discard(id) }
    fun copyCompletedFile(id: UUID, output: OutputStream) = fileAction { it.copyCompletedTo(id, output) }

    // Result-bearing UI APIs share the same serialized lane as packets, timers and teardown.
    private suspend fun <T> fileResult(action: (BleFileController) -> T): Result<T> = withContext(fileDispatcher) {
        try { runCatching { action(files()) } }
        finally { fileController?.let { fileState.value = it.state.value } }
    }
    suspend fun reloadFileTransfers(): Result<Unit> = fileResult { Unit }
    suspend fun offerAttachment(peerId: String, source: InputStream, multiHop: Boolean): Result<UUID> = fileResult {
        check(!forwardingFiles.busy(System.currentTimeMillis())) { "Another transfer owns this device" }
        it.offer(peerId, source, if (multiHop) TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE)
    }
    suspend fun copyVerifiedAttachment(id: UUID, output: OutputStream): Result<Unit> = fileResult { it.copyCompletedTo(id, output) }
    suspend fun attachmentAction(id: UUID, action: BleFileAction): Result<Unit> = fileResult {
        when (action) {
            BleFileAction.ACCEPT -> { check(!forwardingFiles.busy(System.currentTimeMillis())); it.accept(id) }
            BleFileAction.REJECT -> it.reject(id)
            BleFileAction.PAUSE -> it.pause(id)
            BleFileAction.RESUME -> { check(!forwardingFiles.busy(System.currentTimeMillis())); it.resume(id) }
            BleFileAction.CANCEL -> it.cancel(id)
            BleFileAction.DISCARD -> it.discard(id)
        }
    }

    private fun fileAction(action: (BleFileController) -> Unit) = fileScope.launch {
        runCatching { action(files()) }.onFailure { addError("File action failed or paused; saved files were preserved. Check consent, size limits, and link availability.") }
        fileController?.let { fileState.value = it.state.value }
    }

    private fun files(): BleFileController {
        check(!fileUnavailable && ::appContext.isInitialized)
        val identity = requireNotNull(localIdentity)
        fileController?.let { return it }
        try {
            val engine = FileTransferEngine(appContext.noBackupFilesDir)
            return try {
                BleFileController(identity, engine, AndroidBleFileBindingStore(appContext),
                    peer = { id -> peerManager.peers.value.firstOrNull { it.identity.peerId == id }?.identity },
                    direct = { id -> peerManager.peers.value.any { it.identity.peerId == id && it.directDeviceAddress != null } },
                    send = { packet, low -> runBlocking(Dispatchers.Main.immediate) {
                        if (localIdentity?.peerId != packet.senderId) false
                        else {
                            val links = if (packet.ttl == 0) peerManager.get(packet.recipientId!!)?.directDeviceAddress?.let { setOf(it) }.orEmpty()
                                else peerManager.routeLinks(packet.recipientId!!)
                            transport?.send(PacketCodec.encode(packet), if (low) BleFileProtocol.header(packet.payload).id else null, links) == true
                        }
                    } }, dropQueued = { id -> runBlocking(Dispatchers.Main.immediate) { transport?.cancelQueuedFile(id) } })
                    .also { fileController = it }
            } catch (e: Exception) { engine.close(); throw e }
        } catch (e: Exception) { fileUnavailable = true; throw e }
    }

    private fun processFilePacket(incoming: TransportPacket) {
        val decoded = PacketCodec.decode(incoming.bytes)
        val identity = localIdentity ?: return
        if (decoded.senderId == identity.peerId || decoded.recipientId == null) return
        val now = System.currentTimeMillis()
        if (decoded.timestampMs !in now - 600_000..now + 60_000) return
        val remote = peerManager.peers.value.firstOrNull { it.identity.peerId == decoded.senderId }?.identity ?: return
        if (!PacketCodec.verify(decoded, remote.signingPublicKey)) return
        val key = decoded.packetId.toHex()
        fileSeen.entries.removeAll { it.value < now - 600_000 }
        if (fileSeen.put(key, now) != null) return
        while (fileSeen.size > 1024) fileSeen.remove(fileSeen.keys.first())
        if (decoded.recipientId == identity.peerId) {
            if (!forwardingFiles.busy(now)) files().receive(decoded)
        } else if (!files().hasActiveTransfer() && forwardingFiles.allow(decoded, now)) runBlocking(Dispatchers.Main.immediate) {
            val links = peerManager.routeLinks(decoded.recipientId).filter { it != incoming.linkId }.toSet()
            transport?.send(PacketCodec.encode(decoded.copy(ttl = decoded.ttl - 1)),
                if (decoded.type == PacketType.FILE_CHUNK) BleFileProtocol.header(decoded.payload).id else null, links)
        }
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
        if (decoded.type == PacketType.FILE_CONTROL || decoded.type == PacketType.FILE_CHUNK) {
            // Bounded mailbox, crypto/file IO off-main; no task per chunk and no chat parser.
            fileInbox.trySend(packet)
            return
        }
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
            PacketType.ANNOUNCE -> {
                peerManager.upsertAnnouncement(announcedIdentity!!, packet.linkId, null, decoded.ttl == 5)
                if (::nicknameStore.isInitialized) nicknameStore.remember(announcedIdentity!!.peerId, announcedIdentity!!.displayName)
                flushQueued()
            }
            PacketType.MESSAGE -> handleMessage(decoded, encrypted = false)
            PacketType.ENCRYPTED -> handleMessage(decoded, encrypted = true)
            PacketType.ACK -> handleAck(decoded)
            PacketType.CONTROL -> handleControl(decoded)
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
        val inserted = runCatching { messageStore.addInbound(
            packetIdHex = decoded.packetId.toHex(),
            senderId = decoded.senderId,
            recipientId = decoded.recipientId,
            body = json.optString("body"),
            isPrivate = encrypted,
            senderMessageId = json.optString("messageId").takeIf { it.isNotBlank() }
        ) }.getOrElse { addError("Nearby history is unavailable; existing data was preserved."); return }
        val notificationNow = System.currentTimeMillis()
        notificationPackets.entries.removeAll { it.value < notificationNow - 10 * 60_000 }
        val firstReceipt = notificationPackets.put(decoded.packetId.toHex(), decoded.timestampMs) == null
        val live = `in`.yashd.bitchat.notifications.NotificationPolicy.isLive(decoded.timestampMs, receiveStartedAt, System.currentTimeMillis())
        // Private messages from someone new wait in Requests instead of alerting as a chat.
        if (encrypted && ::requestsStore.isInitialized && !requestsStore.isAccepted(decoded.senderId)) {
            if (requestsStore.addPending(decoded.senderId, notificationNow) && live) {
                val key = `in`.yashd.bitchat.ui.relay.MutedChatsStore.key("nearby", local.peerId, decoded.senderId)
                `in`.yashd.bitchat.notifications.MessageNotifications.stored(appContext, key, "requests",
                    sender?.identity?.displayName.orEmpty(), "Wants to message you")
            }
        } else if (inserted && firstReceipt && live) {
            val route = `in`.yashd.bitchat.nearbyRoute(if (encrypted) decoded.senderId else null)
            val key = `in`.yashd.bitchat.ui.relay.MutedChatsStore.key("nearby", local.peerId, if (encrypted) decoded.senderId else null)
            `in`.yashd.bitchat.notifications.MessageNotifications.stored(appContext, key, route,
                if (encrypted) sender?.identity?.displayName.orEmpty() else "Nearby group")
        }
        sendAck(decoded.senderId, decoded.packetId.toHex())
    }

    private fun handleAck(decoded: MeshPacket) {
        val json = runCatching { JSONObject(decoded.payload.decodeToString()) }.getOrNull() ?: return
        if (decoded.recipientId != localIdentity?.peerId) return
        runCatching { messageStore.markPacketDelivered(json.optString("ackedPacketId"), decoded.senderId, 5 - decoded.ttl) }
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
        scope.launch {
            val errors = (_state.value.errors + error).takeLast(4)
            _state.value = _state.value.copy(errors = errors)
        }
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
            knownNames = if (::nicknameStore.isInitialized) nicknameStore.names.value else emptyMap(),
            currentConversation = if (::messageStore.isInitialized) messageStore.conversation.value else emptyList(),
            errors = _state.value.errors
        )
    }
}
