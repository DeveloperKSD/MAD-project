package `in`.yashd.bitchat.ui.relay

import android.util.Log
import `in`.yashd.bitchat.data.internet.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import `in`.yashd.bitchat.nostr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Future

/** Public UI boundary keeps Keystore and blocking transport work off the main thread. */
interface InternetSession : Closeable {
    fun receive(inboxes: List<String>): Future<Unit>
    fun announce(inboxes: List<String>): Future<List<NostrMessagingCoordinator.Publication>>
    fun send(recipient: String, body: String, prepared: (String) -> Unit): Future<NostrMessagingCoordinator.SendResult>
    /** Null when this session cannot send receipts; the runtime then leaves senders at relay-accepted. */
    fun sendReceipt(recipient: String, originalRumorId: String): Future<NostrMessagingCoordinator.SendResult>? = null
}
interface InternetBackend : Closeable {
    fun identity(): NostrIdentity
    fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
             connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession
}
class AndroidInternetBackend(private val identity: NostrIdentityManager,
                             private val mayCreateIdentity: () -> Boolean = { true }) : InternetBackend {
    private val http = OkHttpClient()
    override fun identity() = if (mayCreateIdentity()) identity.loadOrCreate() else
        requireNotNull(identity.loadExisting()) { "Saved identity unavailable" }
    override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                      connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
        val coordinator = NostrMessagingCoordinator(identity, http, discovery,
            onReceived = received,
            onRelayIssue = { url, error ->
                if (error in listOf(NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED, NostrMessagingCoordinator.Issue.SUBSCRIPTION_CLOSED))
                    connection(url, "subscription unavailable")
                val message = when (error) {
                    NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED -> "This server requires a login Relay does not support yet. Choose another server."
                    NostrMessagingCoordinator.Issue.SUBSCRIPTION_CLOSED -> "A server closed your inbox subscription. Retry or choose another inbox server."
                    NostrMessagingCoordinator.Issue.CONNECTION_ERROR -> "A server connection failed. Check your Internet connection and server addresses, then retry."
                    NostrMessagingCoordinator.Issue.INVALID_MESSAGE, NostrMessagingCoordinator.Issue.RECEIVE_QUEUE_FULL -> {
                        Log.d("RelayInternet", "Relay diagnostic: $error")
                        null
                    }
                }
                if (message != null) issue(message)
            },
            onInboxState = { url, state -> connection(url, state.name.lowercase()) })
        return object : InternetSession {
            override fun receive(inboxes: List<String>) = coordinator.startReceiving(inboxes)
            override fun announce(inboxes: List<String>) = coordinator.announce(inboxes)
            override fun send(recipient: String, body: String, prepared: (String) -> Unit) = coordinator.send(recipient, body, beforePublish = prepared)
            override fun close() = coordinator.close()
        }
    }
    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

data class InternetMessage(val id: String, val peer: String, val body: String,
                           val outgoing: Boolean, val status: String, val createdAtMs: Long = 0, val rumorId: String? = null)
data class InternetConversation(val publicKey: String, val npub: String, val displayName: String = "", val lastMessageAtMs: Long? = null)
enum class InboxAnnouncement { NOT_PUBLISHED, PUBLISHING, ACCEPTED, PARTIAL, FAILED, UNKNOWN }
// Discovery publishes to both relays; only nos.lol is a default inbox because relay.damus.io
// requires NIP-42 auth for subscriptions and would always report a failed inbox connection.
internal val DEFAULT_DISCOVERY_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol")
internal val DEFAULT_INBOX_RELAYS = listOf("wss://nos.lol")

data class InternetSetupState(
    val step: Int = 0, val discoveryDraft: String = "", val inboxDraft: String = "",
    val announcement: InboxAnnouncement = InboxAnnouncement.NOT_PUBLISHED,
    val publications: List<NostrMessagingCoordinator.Publication> = emptyList(),
    val error: String? = null, val finished: Boolean = false
)
data class RelayUiState(
    val npub: String = "", val publicKey: String = "", val discovery: List<String> = emptyList(),
    val inboxes: List<String> = emptyList(), val connections: Map<String, String> = emptyMap(),
    val messages: List<InternetMessage> = emptyList(), val busy: Boolean = false,
    val active: Boolean = false, val notice: String? = null, val error: String? = null,
    val storageLoading: Boolean = true, val storageError: String? = null,
    val configurationLoading: Boolean = true, val configurationError: String? = null,
    val conversations: List<InternetConversation> = emptyList(), val setup: InternetSetupState = InternetSetupState(),
    val threadPeer: String? = null, val thread: List<InternetMessage> = emptyList(),
    val threadLoading: Boolean = false, val threadLatest: Boolean = true, val threadHasOlder: Boolean = false
) {
    val inboxesConnected: Boolean get() = active && inboxes.isNotEmpty() && inboxes.all { connections[it] == "connected" }
    val readyToShare: Boolean get() = !storageLoading && storageError == null && inboxesConnected && setup.announcement == InboxAnnouncement.ACCEPTED && npub.isNotEmpty()
}

/** Application-owned Internet state and transport. The lifecycle lock serializes admission,
 * session replacement, and shutdown; historyGate serializes durable writes and deduplication.
 * Activity adapters borrow this instance. Only process foreground transitions call start/stop.
 * Reconfiguration and manual reconnect are rejected while sending or publishing. No operation
 * is automatically replayed, and the transport retains its existing bounded retry policy.
 */
class InternetRuntime(private val backend: InternetBackend,
                     dispatcher: CoroutineDispatcher = Dispatchers.IO,
                     private val history: InternetHistoryRepository,
                     private val configurationStore: InternetConfigurationStore? = null,
                     private val onIncomingStored: (String, String, String) -> Unit = { _, _, _ -> }) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(RelayUiState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private val historyGate = Mutex()
    private var session: InternetSession? = null
    @Volatile private var generation = 0
    private var operation: Job? = null
    private var configurationSaving = false
    private var backgroundJob: Job? = null
    @Volatile private var closed = false
    init {
        scope.launch {
            val identity = try { backend.identity() } catch (_: Exception) {
                mutable.update { it.copy(storageLoading = false, configurationLoading = false,
                    configurationError = "Saved identity is unavailable. Existing data was preserved.") }
                error("Nostr identity could not be loaded. Existing identity data was preserved. Restart to retry.")
                return@launch
            }
            mutable.update { it.copy(npub = identity.npub, publicKey = identity.publicKeyHex) }
            try {
                val saved = configurationStore?.load(identity.publicKeyHex)
                mutable.update { old -> old.copy(configurationLoading = false,
                    discovery = saved?.discovery.orEmpty(), inboxes = saved?.inboxes.orEmpty(),
                    setup = old.setup.copy(discoveryDraft = saved?.discovery?.joinToString("\n").orEmpty(),
                        inboxDraft = saved?.inboxes?.joinToString("\n").orEmpty())) }
            } catch (_: Exception) {
                configurationFailure()
            }
            try {
                historyGate.withLock {
                    history.load(identity.publicKeyHex) // Validate every encrypted field before changing pending state.
                    history.interruptPending(identity.publicKeyHex)
                    showHistory(history.load(identity.publicKeyHex))
                }
                synchronized(lock) { if (state.value.active) connect() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageFailure() }
        }
    }
    private fun configurationFailure() {
        val message = "Saved Internet servers are unavailable. Existing configuration and identity were preserved. Restart Relay to retry."
        mutable.update { it.copy(configurationLoading = false, configurationError = message,
            error = message, setup = it.setup.copy(error = message)) }
    }
    private fun storageFailure() {
        mutable.update { it.copy(storageLoading = false,
            storageError = "Local Internet history is unavailable. Existing data has been preserved. Restart Relay to retry. If this continues, the storage key or data may be damaged; do not clear app data if you need this history.") }
    }
    private fun showHistory(snapshot: HistorySnapshot) {
        mutable.update { it.copy(storageLoading = false,
            conversations = snapshot.contacts.map { c -> InternetConversation(c.peer, publicNpub(c.peer), c.name, c.lastMessageAtMs) },
            messages = snapshot.messages.map { it.toUi() }) }
    }
    private fun SavedMessage.toUi() = InternetMessage(recordId, peer, body, outgoing, status.label(), createdAtMs, rumorId)
    private fun mergeHistory(snapshot: HistorySnapshot) {
        val changed = snapshot.messages.map { it.toUi() }
        fun merge(previous: List<InternetMessage>, updates: List<InternetMessage>, limit: Int) =
            (previous.filter { old -> updates.none { it.id == old.id || it.rumorId != null && it.rumorId == old.rumorId } } + updates)
                .sortedWith(compareBy<InternetMessage> { it.createdAtMs }.thenBy { it.id }).takeLast(limit)
        mutable.update { old -> old.copy(
            conversations = (old.conversations.associateBy { it.publicKey } + snapshot.contacts.associate {
                it.peer to InternetConversation(it.peer, publicNpub(it.peer), it.name,
                    listOfNotNull(it.lastMessageAtMs, old.conversations.firstOrNull { previous -> previous.publicKey == it.peer }?.lastMessageAtMs).maxOrNull()) }).values.toList(),
            messages = merge(old.messages, changed, 200),
            thread = merge(old.thread, changed.filter { it.peer == old.threadPeer &&
                (old.threadLatest || old.thread.any { row -> row.id == it.id }) }, 100)) }
    }
    fun loadConversation(npub: String, older: Boolean = false) {
        val peer = runCatching { recipientKey(npub) }.getOrNull() ?: return
        val snapshot = state.value
        if (snapshot.storageLoading || snapshot.storageError != null || snapshot.threadLoading && snapshot.threadPeer == peer) return
        val before = if (older && snapshot.threadPeer == peer) snapshot.thread.firstOrNull() else null
        mutable.update { if (it.threadPeer == peer) it.copy(threadLoading = true)
            else it.copy(threadPeer = peer, thread = emptyList(), threadLoading = true, threadLatest = true, threadHasOlder = false) }
        scope.launch {
            try {
                historyGate.withLock {
                    val page = history.page(state.value.publicKey, peer, before?.createdAtMs, before?.id.orEmpty())
                    mutable.update { if (it.threadPeer != peer) it else it.copy(threadLoading = false,
                        thread = if (page.isEmpty() && older) it.thread else page.map { row -> row.toUi() },
                        threadLatest = if (page.isEmpty() && older) it.threadLatest else before == null,
                        threadHasOlder = page.size == 100) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(threadLoading = false) }; storageFailure() }
        }
    }
    /** Deletes one Internet message from this phone only. */
    fun deleteMessage(id: String) {
        scope.launch {
            val snapshot = state.value
            if (snapshot.storageLoading || snapshot.storageError != null) return@launch
            try {
                historyGate.withLock {
                    history.deleteMessage(state.value.publicKey, id)
                    mutable.update { old -> old.copy(
                        messages = old.messages.filterNot { it.id == id },
                        thread = old.thread.filterNot { it.id == id }) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageFailure() }
        }
    }
    /** Deletes a whole Internet chat from this phone only. A new message from that person starts it again. */
    fun deleteConversation(npub: String, onDeleted: () -> Unit = {}) {
        val peer = runCatching { recipientKey(npub) }.getOrNull() ?: return
        scope.launch {
            val snapshot = state.value
            if (snapshot.storageLoading || snapshot.storageError != null) return@launch
            try {
                historyGate.withLock {
                    history.deleteConversation(state.value.publicKey, peer)
                    mutable.update { old -> old.copy(
                        conversations = old.conversations.filterNot { it.publicKey == peer },
                        messages = old.messages.filterNot { it.peer == peer },
                        threadPeer = if (old.threadPeer == peer) null else old.threadPeer,
                        thread = if (old.threadPeer == peer) emptyList() else old.thread,
                        threadLoading = if (old.threadPeer == peer) false else old.threadLoading,
                        threadHasOlder = if (old.threadPeer == peer) false else old.threadHasOlder) }
                    onDeleted()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageFailure() }
        }
    }
    private suspend fun store(id: String? = null, peer: String? = null, action: suspend (String) -> Unit): Boolean {
        if (state.value.storageError != null || state.value.storageLoading) return false
        return try {
            historyGate.withLock {
                if (state.value.storageError != null) return false
                val owner = state.value.publicKey
                action(owner)
                val changes = history.changes(owner, id, peer)
                mergeHistory(changes)
                val threadPeer = state.value.threadPeer
                if (threadPeer != null && !state.value.threadLatest && changes.messages.any { it.peer == threadPeer && it.outgoing && it.status == HistoryStatus.QUEUED }) {
                    val latest = history.page(owner, threadPeer)
                    mutable.update { if (it.threadPeer != threadPeer) it else it.copy(thread = latest.map { row -> row.toUi() }, threadLatest = true, threadHasOlder = latest.size == 100) }
                }
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { storageFailure(); false }
    }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun start() = synchronized(lock) {
        if (closed) return@synchronized
        backgroundJob?.cancel(BackgroundCatchUpPreempted())
        mutable.update { it.copy(active = true) }
        connect()
    }
    fun stop() = synchronized(lock) {
        if (!state.value.active && session == null) return@synchronized
        generation++
        operation?.cancel(); operation = null
        session?.close(); session = null
        mutable.update { old -> old.copy(active = false, busy = false, connections = emptyMap(),
            setup = if (old.setup.announcement == InboxAnnouncement.PUBLISHING) old.setup.copy(announcement = InboxAnnouncement.UNKNOWN,
                error = "Publishing was interrupted. The outcome is unknown. Return to this step and retry when connected.") else old.setup,
            messages = old.messages.map { if (it.status == "Sending…") it.copy(status = "Interrupted · outcome unknown") else it }) }
    }
    /** Returns validation/admission; onSaved runs only after durable storage succeeds. */
    fun configure(discoveryText: String, inboxText: String, onSaved: () -> Unit = {}): Boolean {
        val discovery: List<String>; val inboxes: List<String>
        try { discovery = parseRelays(discoveryText); inboxes = parseRelays(inboxText) }
        catch (_: Exception) {
            val message = "Enter 1–3 secure wss:// URLs in each server list, one per line. Credentials and fragments are not allowed."
            error(message); mutable.update { it.copy(setup = it.setup.copy(error = message)) }; return false
        }
        synchronized(lock) {
            if (closed) return false
            if (state.value.busy || configurationSaving || backgroundJob != null) { error("Wait for the current operation before changing servers."); return false }
            if (state.value.configurationLoading || state.value.configurationError != null || state.value.publicKey.isEmpty()) {
                error(state.value.configurationError ?: "Wait for Internet settings to finish loading.")
                return false
            }
            if (configurationStore != null) {
                configurationSaving = true
                mutable.update { it.copy(busy = true) }
                scope.launch {
                    try {
                        configurationStore.save(state.value.publicKey, InternetConfiguration(discovery, inboxes))
                        synchronized(lock) {
                            if (closed) return@launch
                            applyConfiguration(discovery, inboxes, discoveryText, inboxText)
                            onSaved()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { configurationFailure() }
                    finally { synchronized(lock) {
                        configurationSaving = false
                        mutable.update { it.copy(busy = false) }
                    } }
                }
                return true
            }
            applyConfiguration(discovery, inboxes, discoveryText, inboxText)
            onSaved()
            return true
        }
    }
    private fun applyConfiguration(discovery: List<String>, inboxes: List<String>, discoveryText: String, inboxText: String) {
        val active = state.value.active
        val changed = state.value.discovery != discovery || state.value.inboxes != inboxes
        if (changed || session == null) {
            stop()
            mutable.update { it.copy(discovery = discovery, inboxes = inboxes, error = null,
                setup = it.setup.copy(discoveryDraft = discoveryText, inboxDraft = inboxText, error = null,
                    announcement = if (changed) InboxAnnouncement.NOT_PUBLISHED else it.setup.announcement,
                    publications = if (changed) emptyList() else it.setup.publications, finished = if (changed) false else it.setup.finished),
                notice = "Servers configured. Publish your inbox announcement before sharing your ID.") }
            if (active) start()
        }
        mutable.update { it.copy(error = null, setup = it.setup.copy(error = null)) }
    }
    fun updateSetupServers(discovery: String, inbox: String) {
        mutable.update { it.copy(error = null, setup = it.setup.copy(discoveryDraft = discovery, inboxDraft = inbox, error = null)) }
    }
    fun setupBack() { mutable.update { it.copy(setup = it.setup.copy(step = (it.setup.step - 1).coerceAtLeast(0))) } }
    fun setupNext() {
        when (state.value.setup.step) {
            0 -> configure(state.value.setup.discoveryDraft, state.value.setup.inboxDraft) {
                mutable.update { it.copy(setup = it.setup.copy(step = 1)) }
            }
            1 -> if (state.value.readyToShare) mutable.update { it.copy(setup = it.setup.copy(step = 2)) }
        }
    }
    fun finishSetup(): Boolean {
        if (!state.value.readyToShare) return false
        mutable.update { it.copy(setup = it.setup.copy(finished = true)) }
        return true
    }
    fun retrySetup() = synchronized(lock) {
        if (state.value.busy || configurationSaving || backgroundJob != null) return@synchronized
        val active = state.value.active
        stop()
        mutable.update { it.copy(error = null, setup = it.setup.copy(error = null)) }
        if (active) start()
    }
    suspend fun addInternetConversation(npub: String, displayName: String): String? {
        val key = try { recipientKey(npub) } catch (_: Exception) { return null }
        val name = displayName.trim()
        if (name.length > 60 || name.any { it.isISOControl() }) return null
        return if (store(peer = key) { owner -> history.contact(owner, key, name) }) publicNpub(key) else null
    }
    private fun connect() {
        val snapshot = state.value
        if (closed || backgroundJob != null || !snapshot.active || snapshot.configurationLoading || snapshot.configurationError != null || snapshot.storageLoading || snapshot.storageError != null || snapshot.npub.isEmpty() || session != null || snapshot.discovery.isEmpty() || snapshot.inboxes.isEmpty()) return
        val receiveStartedAt = System.currentTimeMillis()
        val current = ++generation
        fun current() = current == generation && state.value.active
        val opened = try { backend.open(snapshot.discovery, { rumor ->
            if (current()) scope.launch {
                if (!current()) return@launch
                val outgoing = rumor.pubkey == state.value.publicKey
                val peer = if (outgoing) rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: rumor.pubkey else rumor.pubkey
                val id = NostrEventCodec.calculateId(rumor)
                if (rumor.createdAtSeconds > Long.MAX_VALUE / 1000) return@launch
                val receiptOf = DeliveryReceipts.receiptTarget(rumor)
                if (receiptOf != null) { if (!outgoing) acknowledgeDelivery(rumor.pubkey, receiptOf); return@launch }
                var inserted = false
                val stored = store(id) { owner ->
                    if (!current()) return@store
                    inserted = !history.containsRumor(owner, id)
                    history.received(owner, id, peer, rumor.content, outgoing, rumor.createdAtSeconds * 1000)
                }
                if (stored && inserted && !outgoing) {
                    pendingReceipts.add(peer to id)
                    session?.let { sendReceipts(it) }
                }
                if (stored && inserted && !outgoing && current() &&
                    `in`.yashd.bitchat.notifications.NotificationPolicy.isLive(rumor.createdAtSeconds * 1000, receiveStartedAt, System.currentTimeMillis())) {
                    runCatching { onIncomingStored(state.value.publicKey, peer,
                        state.value.conversations.firstOrNull { it.publicKey == peer }?.displayName.orEmpty()) }
                }
            }
        }, { url, status -> mutable.update { if (current()) it.copy(connections = it.connections + (url to status)) else it } },
            { message -> mutable.update { if (current()) it.copy(error = message) else it } })
        } catch (_: Exception) {
            error("Could not open Internet connections. Check the relays and network.")
            return
        }
        session = opened
        sendReceipts(opened)
        scope.launch {
            try { await(opened.receive(snapshot.inboxes)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(lock) { if (current()) error("Could not start inbox subscriptions. Check the relays and network.") } }
        }
    }
    /** Newly stored incoming rumors awaiting a delivered receipt, keyed by rumor id (in-memory,
     * best-effort: if the app dies first, the sender honestly stays at relay-accepted). */
    private val pendingReceipts = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private fun sendReceipts(session: InternetSession) {
        val transport = session
        for ((peer, rumorId) in pendingReceipts.toList()) {
            scope.launch {
                val sent = runCatching {
                    val result = transport.sendReceipt(peer, rumorId) ?: return@runCatching false
                    await(result).recipient.any { it.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED }
                }.getOrDefault(false)
                if (sent) pendingReceipts.remove(peer to rumorId)
            }
        }
    }

    /** Handles an incoming delivered receipt: correlates to an outgoing message we sent to the
     * authenticated receipt signer, then marks it delivered. Never stored, never rendered. */
    private suspend fun acknowledgeDelivery(receiptSigner: String, targetRumorId: String) {
        val marked = store { owner -> history.markDelivered(owner, targetRumorId, receiptSigner) }
        if (marked) historyGate.withLock {
            runCatching { mergeHistory(history.changes(state.value.publicKey, targetRumorId, receiptSigner)) }
        }
    }

    /** Pull-to-refresh: reconnect when the session is down, otherwise re-subscribe the inbox. */
    fun refresh() = synchronized(lock) {
        if (closed || state.value.configurationLoading || state.value.configurationError != null ||
            state.value.storageLoading || state.value.storageError != null ||
            state.value.discovery.isEmpty() || state.value.inboxes.isEmpty()) return@synchronized
        val transport = session
        if (transport == null) {
            if (state.value.active) connect() else start()
            return@synchronized
        }
        val current = generation
        scope.launch {
            try { await(transport.receive(state.value.inboxes)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(lock) { if (current == generation) error("Could not refresh inbox subscriptions. Check the relays and network.") } }
        }
    }

    fun announce() = perform { transport, epoch ->
        mutable.update { if (epoch != generation) it else it.copy(setup = it.setup.copy(announcement = InboxAnnouncement.PUBLISHING, publications = emptyList(), error = null)) }
        try {
            val result = await(transport.announce(state.value.inboxes))
            val accepted = result.count { it.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED }
            val status = when {
                result.isNotEmpty() && accepted == result.size -> InboxAnnouncement.ACCEPTED
                accepted > 0 -> InboxAnnouncement.PARTIAL
                result.any { it.stage == NostrMessagingCoordinator.Stage.UNKNOWN } -> InboxAnnouncement.UNKNOWN
                else -> InboxAnnouncement.FAILED
            }
            val problem = when (status) {
                InboxAnnouncement.ACCEPTED -> null
                InboxAnnouncement.PARTIAL -> "Only some discovery servers accepted your announcement. Retry or change the servers that failed."
                InboxAnnouncement.UNKNOWN -> "A server did not confirm acceptance. The outcome is unknown. Check your connection and retry."
                else -> "No server accepted your announcement. Check your connection and server addresses, then retry or choose different servers."
            }
            mutable.update { if (epoch != generation) it else it.copy(notice = "Inbox announcement: $accepted/${result.size} discovery relays accepted (kind 10050).",
                setup = it.setup.copy(announcement = status, publications = result, error = problem)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutable.update { if (epoch != generation) it else it.copy(setup = it.setup.copy(announcement = InboxAnnouncement.FAILED,
                error = "Publishing failed. Check your connection and server addresses, then retry.")) }
            throw e
        }
    }
    fun send(npub: String, body: String) {
        val recipient = try { recipientKey(npub) } catch (_: Exception) { error("Enter a valid recipient npub with a correct checksum."); return }
        if (state.value.threadLoading && state.value.threadPeer == recipient) { error("Wait for this conversation to finish loading."); return }
        if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > 16_000) { error("Enter a message of 1–16,000 UTF-8 bytes."); return }
        val id = UUID.randomUUID().toString()
        perform { transport, _ ->
            try {
                // Finish the durable enqueue even if lifecycle cancellation arrives during the
                // commit, then mark it unknown before leaving. Never publish after cancellation.
                if (!withContext(NonCancellable) {
                    store(id) { owner -> history.outgoing(owner, id, recipient, body, System.currentTimeMillis()) }
                }) return@perform
                currentCoroutineContext().ensureActive()
                val result = await(transport.send(recipient, body) { rumorId ->
                    // Coordinator worker, outside its callback lock; a failed durable bind aborts publication.
                    runBlocking {
                        if (!store(id) { owner ->
                            if (closed) throw CancellationException("Runtime closed")
                            history.bindRumor(owner, id, rumorId)
                        }) throw LocalHistoryException()
                    }
                })
                if (!store(id) { owner -> history.publication(owner, id, result) }) return@perform
                mutable.update { it.copy(
                    notice = if (result.sender.none { p -> p.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED }) "Your inbox copy was not accepted by a relay." else it.notice,
                    error = if (publicationStatus(result) != HistoryStatus.RELAY_ACCEPTED) publicationStatus(result).label() else null) }
            } catch (e: Exception) {
                withContext(NonCancellable) { store(id) { owner -> history.failed(owner, id, unknown = true) } }
                throw e
            }
        }
    }
    private fun perform(action: suspend (InternetSession, Int) -> Unit) = synchronized(lock) {
        val snapshot = state.value
        val transport = session
        if (closed || snapshot.storageLoading || snapshot.storageError != null) return@synchronized
        if (snapshot.npub.isEmpty()) { error("Nostr identity is unavailable."); return@synchronized }
        if (!snapshot.active || transport == null) { error("Configure discovery and inbox relays in Settings and keep the app open."); return@synchronized }
        if (snapshot.busy || configurationSaving) return@synchronized
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        val current = generation
        operation = scope.launch {
            try { action(transport, current) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val missingInbox = generateSequence(e as Throwable) { it.cause }.take(16)
                    .any { it is NostrMessagingCoordinator.MissingInbox }
                if (current == generation && state.value.storageError == null) error(if (missingInbox) "No usable inbox announcement was found for you or the recipient. Both users must publish kind 10050 to the configured discovery relays. A relay timeout can also prevent discovery."
                    else "Network or relay operation failed. Check connectivity and relay configuration; delivery is unconfirmed.")
            } finally { synchronized(lock) { if (current == generation) mutable.update { it.copy(busy = false) } } }
        }
    }
    /** Worker borrows the SAME history gate and exclusive transport ownership. Foreground
     * activation cancels the bounded operation, then waits for its finally/connection close
     * before opening live subscriptions. Never stops a foreground session or active send.
     */
    suspend fun backgroundCatchUp(owner: String,
        action: suspend (suspend (UnsignedNostrEvent) -> Unit) -> Boolean): Boolean? = coroutineScope {
        state.first { !it.storageLoading && !it.configurationLoading }
        val job = currentCoroutineContext()[Job]!!
        val admitted = synchronized(lock) {
            check(!closed && state.value.storageError == null && state.value.configurationError == null)
            check(state.value.publicKey == owner)
            if (state.value.active || session != null || state.value.busy || configurationSaving || backgroundJob != null) false
            else { backgroundJob = job; true }
        }
        if (!admitted) return@coroutineScope null
        try {
            action { rumor ->
                // Transport callbacks may bridge through runBlocking; check the admitted
                // owner's Job as well as the callback coroutine before touching history.
                job.ensureActive()
                currentCoroutineContext().ensureActive()
                require(rumor.createdAtSeconds in 0..Long.MAX_VALUE / 1000)
                val outgoing = rumor.pubkey == owner
                val peer = if (outgoing) requireNotNull(rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)) else rumor.pubkey
                // The transport authenticates the inner rumor before calling this boundary.
                // Room keeps deleted IDs and sender-copy status; no notification callback here.
                historyGate.withLock {
                    job.ensureActive()
                    val id = NostrEventCodec.calculateId(rumor)
                    val receiptOf = DeliveryReceipts.receiptTarget(rumor)
                    if (receiptOf != null) { if (!outgoing) history.markDelivered(owner, receiptOf, rumor.pubkey); return@action }
                    if (!outgoing && !history.containsRumor(owner, id)) pendingReceipts.add(peer to id)
                    history.received(owner, id, peer, rumor.content, outgoing, rumor.createdAtSeconds * 1000)
                    // Keep an already-open latest thread current on resume, while preserving
                    // the same older-page/read-position rules used by foreground reception.
                    mergeHistory(history.changes(owner, id, peer))
                }
            }
        } catch (e: LocalHistoryException) {
            storageFailure()
            throw e
        } finally {
            withContext(NonCancellable) {
                try {
                    historyGate.withLock { showHistory(history.load(owner)) }
                } catch (e: Exception) {
                    storageFailure()
                    throw e
                } finally {
                    synchronized(lock) {
                        backgroundJob = null
                        if (state.value.active && !closed) connect()
                    }
                }
            }
        }
    }

    fun cancelBackgroundCatchUp() = synchronized(lock) {
        backgroundJob?.cancel(BackgroundCatchUpPreempted())
        Unit
    }

    /** Terminal shutdown. UI owners must never call this when they are destroyed. */
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        backgroundJob?.cancel(BackgroundCatchUpPreempted())
        stop()
        val job = scope.coroutineContext[Job]!!
        scope.cancel()
        backend.close()
        // Let cancelled durable writes finish and record unknown outcomes before closing Room.
        CoroutineScope(Dispatchers.IO).launch {
            job.join()
            runCatching { historyGate.withLock { history.close() } }
        }
    }
    companion object {
        fun parseRelays(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)
            .distinct().also { require(it.size in 1..3); it.forEach(NostrInbox::validateUrl) }
        fun recipientKey(npub: String): String = NostrNpub.decode(npub.trim()).joinToString("") { "%02x".format(it.toInt() and 255) }
        private suspend fun <T> await(future: Future<T>): T = try { runInterruptible { future.get() }.also { currentCoroutineContext().ensureActive() } }
            finally { if (!future.isDone) future.cancel(true) }
    }
}

/** A worker yields without marking a successful check when foreground takes ownership. */
class BackgroundCatchUpPreempted : CancellationException("Foreground or settings took ownership")
