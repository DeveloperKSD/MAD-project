package `in`.yashd.bitchat.ui.relay

import androidx.lifecycle.ViewModel
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
}
interface InternetBackend : Closeable {
    fun identity(): NostrIdentity
    fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
             connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession
}
class AndroidInternetBackend(private val identity: NostrIdentityManager) : InternetBackend {
    private val http = OkHttpClient()
    override fun identity() = identity.loadOrCreate()
    override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                      connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
        val coordinator = NostrMessagingCoordinator(identity, http, discovery,
            onReceived = received,
            onRelayIssue = { url, error ->
                if (error in listOf(NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED, NostrMessagingCoordinator.Issue.SUBSCRIPTION_CLOSED))
                    connection(url, "subscription unavailable")
                issue(when (error) {
                    NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED -> "This server requires a login Relay does not support yet. Choose another server."
                    NostrMessagingCoordinator.Issue.SUBSCRIPTION_CLOSED -> "A server closed your inbox subscription. Retry or choose another inbox server."
                    NostrMessagingCoordinator.Issue.CONNECTION_ERROR -> "A server connection failed. Check your Internet connection and server addresses, then retry."
                    NostrMessagingCoordinator.Issue.RECEIVE_QUEUE_FULL -> "Incoming messages are temporarily busy. Keep Relay open and try again."
                    else -> "A server sent unsupported data. If this continues, choose another server."
                })
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
                           val outgoing: Boolean, val status: String, val createdAtMs: Long = 0)
data class InternetConversation(val publicKey: String, val npub: String, val displayName: String = "")
enum class InboxAnnouncement { NOT_PUBLISHED, PUBLISHING, ACCEPTED, PARTIAL, FAILED, UNKNOWN }
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
    val conversations: List<InternetConversation> = emptyList(), val setup: InternetSetupState = InternetSetupState()
) {
    val inboxesConnected: Boolean get() = active && inboxes.isNotEmpty() && inboxes.all { connections[it] == "connected" }
    val readyToShare: Boolean get() = !storageLoading && storageError == null && inboxesConnected && setup.announcement == InboxAnnouncement.ACCEPTED && npub.isNotEmpty()
}

class RelayViewModel(private val backend: InternetBackend,
                     dispatcher: CoroutineDispatcher = Dispatchers.IO,
                     private val history: InternetHistoryRepository) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(RelayUiState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private val historyGate = Mutex()
    private var session: InternetSession? = null
    @Volatile private var generation = 0
    private var operation: Job? = null
    init {
        scope.launch {
            val identity = try { backend.identity() } catch (_: Exception) {
                mutable.update { it.copy(storageLoading = false) }
                error("Nostr identity could not be loaded. Existing identity data was preserved. Restart to retry.")
                return@launch
            }
            mutable.update { it.copy(npub = identity.npub, publicKey = identity.publicKeyHex) }
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
    private fun storageFailure() {
        mutable.update { it.copy(storageLoading = false,
            storageError = "Local Internet history is unavailable. Existing data has been preserved. Restart Relay to retry. If this continues, the storage key or data may be damaged; do not clear app data if you need this history.") }
    }
    private fun showHistory(snapshot: HistorySnapshot) {
        mutable.update { it.copy(storageLoading = false,
            conversations = snapshot.contacts.map { c -> InternetConversation(c.peer, publicNpub(c.peer), c.name) },
            messages = snapshot.messages.map { m -> InternetMessage(m.recordId, m.peer, m.body, m.outgoing, m.status.label(), m.createdAtMs) }) }
    }
    private suspend fun store(action: suspend (String) -> Unit): Boolean {
        if (state.value.storageError != null || state.value.storageLoading) return false
        return try {
            historyGate.withLock {
                if (state.value.storageError != null) return false
                val owner = state.value.publicKey
                action(owner)
                showHistory(history.load(owner))
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { storageFailure(); false }
    }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun start() = synchronized(lock) {
        mutable.update { it.copy(active = true) }
        connect()
    }
    fun stop() = synchronized(lock) {
        generation++
        operation?.cancel(); operation = null
        session?.close(); session = null
        mutable.update { old -> old.copy(active = false, busy = false, connections = emptyMap(),
            setup = if (old.setup.announcement == InboxAnnouncement.PUBLISHING) old.setup.copy(announcement = InboxAnnouncement.UNKNOWN,
                error = "Publishing was interrupted. The outcome is unknown. Return to this step and retry when connected.") else old.setup,
            messages = old.messages.map { if (it.status == "Sending…") it.copy(status = "Interrupted · outcome unknown") else it }) }
    }
    fun configure(discoveryText: String, inboxText: String): Boolean {
        val discovery: List<String>; val inboxes: List<String>
        try { discovery = parseRelays(discoveryText); inboxes = parseRelays(inboxText) }
        catch (_: Exception) {
            val message = "Enter 1–3 secure wss:// URLs in each server list, one per line. Credentials and fragments are not allowed."
            error(message); mutable.update { it.copy(setup = it.setup.copy(error = message)) }; return false
        }
        synchronized(lock) {
            if (state.value.busy) { error("Wait for the current operation before changing servers."); return false }
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
            return true
        }
    }
    fun updateSetupServers(discovery: String, inbox: String) {
        mutable.update { it.copy(error = null, setup = it.setup.copy(discoveryDraft = discovery, inboxDraft = inbox, error = null)) }
    }
    fun setupBack() { mutable.update { it.copy(setup = it.setup.copy(step = (it.setup.step - 1).coerceAtLeast(0))) } }
    fun setupNext() {
        when (state.value.setup.step) {
            0 -> if (configure(state.value.setup.discoveryDraft, state.value.setup.inboxDraft))
                mutable.update { it.copy(setup = it.setup.copy(step = 1)) }
            1 -> if (state.value.readyToShare) mutable.update { it.copy(setup = it.setup.copy(step = 2)) }
        }
    }
    fun finishSetup(): Boolean {
        if (!state.value.readyToShare) return false
        mutable.update { it.copy(setup = it.setup.copy(finished = true)) }
        return true
    }
    fun retrySetup() = synchronized(lock) {
        if (state.value.busy) return@synchronized
        val active = state.value.active
        stop()
        mutable.update { it.copy(error = null, setup = it.setup.copy(error = null)) }
        if (active) start()
    }
    suspend fun addInternetConversation(npub: String, displayName: String): String? {
        val key = try { recipientKey(npub) } catch (_: Exception) { return null }
        val name = displayName.trim()
        if (name.length > 60 || name.any { it.isISOControl() }) return null
        return if (store { owner -> history.contact(owner, key, name) }) publicNpub(key) else null
    }
    private fun connect() {
        val snapshot = state.value
        if (snapshot.storageLoading || snapshot.storageError != null || snapshot.npub.isEmpty() || session != null || snapshot.discovery.isEmpty() || snapshot.inboxes.isEmpty()) return
        val current = ++generation
        fun current() = current == generation && state.value.active
        val opened = backend.open(snapshot.discovery, { rumor ->
            if (current()) scope.launch {
                val outgoing = rumor.pubkey == state.value.publicKey
                val peer = if (outgoing) rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: rumor.pubkey else rumor.pubkey
                store { owner -> history.received(owner, NostrEventCodec.calculateId(rumor), peer, rumor.content, outgoing, rumor.createdAtSeconds * 1000) }
            }
        }, { url, status -> mutable.update { if (current()) it.copy(connections = it.connections + (url to status)) else it } },
            { message -> mutable.update { if (current()) it.copy(error = message) else it } })
        session = opened
        operation = scope.launch {
            try { await(opened.receive(snapshot.inboxes)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(lock) { if (current()) error("Could not start inbox subscriptions. Check the relays and network.") } }
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
        if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > 16_000) { error("Enter a message of 1–16,000 UTF-8 bytes."); return }
        val id = UUID.randomUUID().toString()
        perform { transport, _ ->
            try {
                // Finish the durable enqueue even if lifecycle cancellation arrives during the
                // commit, then mark it unknown before leaving. Never publish after cancellation.
                if (!withContext(NonCancellable) {
                    store { owner -> history.outgoing(owner, id, recipient, body, System.currentTimeMillis()) }
                }) return@perform
                currentCoroutineContext().ensureActive()
                val result = await(transport.send(recipient, body) { rumorId ->
                    // Coordinator worker, outside its callback lock; a failed durable bind aborts publication.
                    runBlocking {
                        if (!store { owner -> history.bindRumor(owner, id, rumorId) }) throw LocalHistoryException()
                    }
                })
                if (!store { owner -> history.publication(owner, id, result) }) return@perform
                mutable.update { it.copy(
                    notice = if (result.sender.none { p -> p.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED }) "Your inbox copy was not accepted by a relay." else it.notice,
                    error = if (publicationStatus(result) != HistoryStatus.RELAY_ACCEPTED) publicationStatus(result).label() else null) }
            } catch (e: Exception) {
                withContext(NonCancellable) { store { owner -> history.failed(owner, id, unknown = true) } }
                throw e
            }
        }
    }
    private fun perform(action: suspend (InternetSession, Int) -> Unit) = synchronized(lock) {
        val snapshot = state.value
        val transport = session
        if (snapshot.storageLoading || snapshot.storageError != null) return@synchronized
        if (snapshot.npub.isEmpty()) { error("Nostr identity is unavailable."); return@synchronized }
        if (!snapshot.active || transport == null) { error("Configure discovery and inbox relays in Settings and keep the app open."); return@synchronized }
        if (snapshot.busy) return@synchronized
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
    override fun onCleared() {
        stop(); scope.cancel(); backend.close()
        CoroutineScope(Dispatchers.IO).launch { runCatching { historyGate.withLock { history.close() } } }
    }
    companion object {
        fun parseRelays(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)
            .distinct().also { require(it.size in 1..3); it.forEach(NostrInbox::validateUrl) }
        fun recipientKey(npub: String): String = NostrNpub.decode(npub.trim()).joinToString("") { "%02x".format(it.toInt() and 255) }
        private suspend fun <T> await(future: Future<T>): T = try { runInterruptible { future.get() }.also { currentCoroutineContext().ensureActive() } }
            finally { if (!future.isDone) future.cancel(true) }
    }
}
