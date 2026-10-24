package `in`.yashd.bitchat.nostr

import java.io.Closeable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Independent, in-memory transport service. Sends and receipts use separate bounded workers; callbacks must
 * be short. One transient connection plus at most three inbox connections, one subscription each.
 * No automatic publication replay: a timeout/disconnect after submission has unknown outcome.
 * Caller owns HTTP client. close() cancels sockets and pending work. No key authentication is sent.
 */
class NostrMessagingCoordinator(
    private val identity: NostrIdentityManager,
    private val http: OkHttpClient,
    discoveryRelays: List<String>,
    private val timeoutMillis: Long = 5_000,
    private val onPublication: (Publication) -> Unit = {},
    private val onReceived: (UnsignedNostrEvent) -> Unit = {},
    private val onRelayIssue: (String, Issue) -> Unit = { _, _ -> },
    private val onInboxState: (String, NostrRelayClient.State) -> Unit = { _, _ -> }
) : Closeable {
    enum class Stage { QUEUED, SUBMITTED, RELAY_ACCEPTED, RELAY_REJECTED, FAILED, UNKNOWN }
    enum class Issue { AUTHENTICATION_REQUIRED, SUBSCRIPTION_CLOSED, CONNECTION_ERROR, INVALID_MESSAGE, RECEIVE_QUEUE_FULL }
    data class Publication(val eventId: String, val relay: String, val stage: Stage)
    data class SendResult(val recipient: List<Publication>, val sender: List<Publication>)
    class MissingInbox : IllegalStateException("No usable advertised inbox relay list")
    private val discovery = discoveryRelays.distinct().also {
        require(it.isNotEmpty() && it.size <= 3); it.forEach(NostrInbox::validateUrl)
    }
    private val policy = NostrInbox()
    private val nip17 = Nip17()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { r -> Thread(r, "nostr-messaging").apply { isDaemon = true } })
    private val receiver = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { r -> Thread(r, "nostr-receiving").apply { isDaemon = true } },
        RejectedExecutionHandler { runnable, executor ->
            // Pause the socket reader instead of dropping history. No relay/lifecycle lock is held here.
            while (!executor.isShutdown) {
                if (executor.queue.offer(runnable, 100, TimeUnit.MILLISECONDS)) {
                    if (executor.isShutdown && executor.remove(runnable)) (runnable as? Future<*>)?.cancel(false)
                    return@RejectedExecutionHandler
                }
            }
            throw RejectedExecutionException("Receiver closed")
        })
    private val lifecycle = Any()
    private val callbackGate = Any()
    private val clients = mutableSetOf<NostrRelayClient>()
    private val work = mutableSetOf<FutureTask<*>>()
    private val pendingIssues = linkedSetOf<Pair<String, Issue>>()
    private val issueWakeup = AtomicBoolean()
    private val inboxes = mutableListOf<NostrRelayClient>()
    private val seen = linkedSetOf<String>()
    @Volatile private var receiveGeneration = 0L
    @Volatile private var closed = false
    init { require(timeoutMillis in 100..60_000) }
    private fun safe(action: () -> Unit) { try { action() } catch (_: Exception) { } }
    private fun checkActive() { check(!closed); if (Thread.currentThread().isInterrupted) throw CancellationException() }
    private fun notifyUser(action: () -> Unit) = synchronized(callbackGate) {
        if (!closed && !Thread.currentThread().isInterrupted) safe(action)
    }
    // Transport callbacks never invoke user code under the relay client's lock. Coalesce
    // repeated issues and retain a bounded backlog even while the worker queue is full.
    private fun issue(url: String, value: Issue) {
        if (closed) return
        synchronized(pendingIssues) { if (pendingIssues.size < 64) pendingIssues.add(url to value) }
        if (issueWakeup.compareAndSet(false, true)) {
            try { worker.execute { drainIssues() } }
            catch (_: RejectedExecutionException) { issueWakeup.set(false) }
        }
    }
    private fun drainIssues() {
        val batch = synchronized(pendingIssues) { pendingIssues.toList().also { pendingIssues.clear() } }
        issueWakeup.set(false)
        batch.forEach { (url, value) -> notifyUser { onRelayIssue(url, value) } }
    }
    private fun <T> task(executor: ThreadPoolExecutor = worker, action: () -> T): Future<T> {
        val future = synchronized(lifecycle) {
            checkActive()
            val future = object : FutureTask<T>(Callable {
                try { checkActive(); action() } finally { drainIssues() }
            }) {
                override fun done() { synchronized(lifecycle) { work.remove(this) } }
            }
            work.add(future)
            future
        }
        try { executor.execute(future) }
        catch (e: Exception) { future.cancel(false); throw e }
        if (closed) future.cancel(true)
        return future
    }
    private fun client(url: String, response: (RelayResponse) -> Unit,
                       state: (NostrRelayClient.State) -> Unit = {}, persistent: Boolean = false): NostrRelayClient {
        checkActive()
        val c = NostrRelayClient(url, http, maxReconnectAttempts = if (persistent) 5 else 0, onResponse = {
            if (it is RelayResponse.AuthenticationRequired ||
                it is RelayResponse.Acceptance && it.message.startsWith("auth-required:") ||
                it is RelayResponse.Closed && it.message.startsWith("auth-required:"))
                issue(url, Issue.AUTHENTICATION_REQUIRED)
            response(it)
        }, onState = state, onInvalidMessage = { issue(url, Issue.INVALID_MESSAGE) })
        synchronized(lifecycle) {
            if (closed || Thread.currentThread().isInterrupted) { c.close(); throw CancellationException() }
            clients.add(c)
        }
        return c
    }
    private fun release(c: NostrRelayClient) { c.close(); synchronized(lifecycle) { clients.remove(c) } }

    /** Each discovery relay is queried to EOSE or deadline; partial results on timeout are ignored. */
    fun discover(owner: String): Future<NostrEvent?> = task { lookup(owner) }
    private fun lookup(owner: String): NostrEvent? {
        require(owner.isLowercaseHex(32))
        var newest: NostrEvent? = null
        discovery.forEach { url ->
            checkActive()
            val q = LinkedBlockingQueue<RelayResponse>(256)
            val overflow = AtomicBoolean()
            val c = client(url, { if (!q.offer(it)) overflow.set(true) })
            try {
                c.subscribe("discovery", listOf(buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(10050))))
                    put("authors", JsonArray(listOf(JsonPrimitive(owner))))
                    put("limit", 64)
                }))
                c.connect()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                var best: NostrEvent? = null
                while (true) {
                    drainIssues()
                    checkActive()
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    when (val r = q.poll(remaining, TimeUnit.NANOSECONDS) ?: break) {
                        is RelayResponse.Event -> best = policy.newest(listOfNotNull(best, r.event), owner)
                        is RelayResponse.EndOfStoredEvents -> {
                            if (!overflow.get()) newest = policy.newest(listOfNotNull(newest, best), owner)
                            break
                        }
                        is RelayResponse.Closed -> break
                        else -> Unit
                    }
                }
            } finally { c.cancelSubscription("discovery"); release(c) }
        }
        checkActive()
        return newest
    }
    private fun destinations(owner: String): List<String> = lookup(owner)?.let { policy.relays(it, owner) }
        ?.takeIf { it.isNotEmpty() }?.take(3) ?: throw MissingInbox()

    /** Signs a new replaceable announcement; use a strictly newer timestamp when updating. */
    fun announce(relays: List<String>, createdAtSeconds: Long = System.currentTimeMillis() / 1000): Future<List<Publication>> {
        val snapshot = relays.toList()
        return task { announceNow(snapshot, createdAtSeconds) }
    }
    private fun announceNow(relays: List<String>, createdAtSeconds: Long): List<Publication> {
        require(relays.size <= 3); relays.forEach(NostrInbox::validateUrl)
        val event = identity.withSecret { checkActive(); NostrEventSigner().createSignedEvent(it, 10050, "",
            relays.distinct().map { url -> listOf("relay", url) }, createdAtSeconds) }
        require(policy.relays(event, event.unsignedEvent.pubkey) != null)
        return discovery.map { checkActive(); publish(it, event) }
    }
    fun send(recipient: String, text: String, onPrepared: (String) -> Unit = {}, beforePublish: (String) -> Unit = {}): Future<SendResult> = task {
        val local = identity.loadOrCreate().publicKeyHex
        // Resolve both lists before producing or publishing either copy.
        val recipientRelays = destinations(recipient)
        val senderRelays = if (recipient == local) recipientRelays else destinations(local)
        checkActive()
        val wraps = identity.withSecret { checkActive(); nip17.createMessage(it, recipient, text) }
        beforePublish(wraps.rumorId)
        checkActive()
        notifyUser { onPrepared(wraps.rumorId) }
        SendResult(recipientRelays.map { publish(it, wraps.recipientCopy) },
            senderRelays.map { publish(it, wraps.senderCopy) })
    }
    private fun publish(url: String, event: NostrEvent): Publication {
        checkActive()
        fun report(stage: Stage): Publication = Publication(event.id, url, stage).also { notifyUser { onPublication(it) } }
        report(Stage.QUEUED)
        val q = LinkedBlockingQueue<RelayResponse>(32)
        val connected = CountDownLatch(1)
        val c = client(url, { q.offer(it) }, { if (it == NostrRelayClient.State.CONNECTED) connected.countDown() })
        var submitted = false
        try {
            c.connect()
            if (!connected.await(timeoutMillis, TimeUnit.MILLISECONDS) || !c.publish(event)) return report(Stage.FAILED)
            submitted = true
            report(Stage.SUBMITTED)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                drainIssues()
                checkActive()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return report(Stage.UNKNOWN)
                val r = q.poll(remaining, TimeUnit.NANOSECONDS) ?: return report(Stage.UNKNOWN)
                if (r is RelayResponse.Acceptance && r.eventId == event.id)
                    return report(if (r.accepted) Stage.RELAY_ACCEPTED else Stage.RELAY_REJECTED)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException()
        } catch (_: Exception) { checkActive(); return report(if (submitted) Stage.UNKNOWN else Stage.FAILED) }
        finally { release(c) }
    }
    /** Configured inboxes are explicit, never inferred from discovery servers. */
    fun startReceiving(relays: List<String>): Future<Unit> {
        val snapshot = relays.toList()
        return task { receiveNow(snapshot) }
    }
    private fun receiveNow(relays: List<String>) {
        require(relays.size <= 3); relays.forEach(NostrInbox::validateUrl)
        val generation = ++receiveGeneration
        inboxes.forEach(::release); inboxes.clear()
        val local = identity.loadOrCreate().publicKeyHex
        relays.distinct().forEach { url ->
            val c = client(url, { r ->
                when (r) {
                    is RelayResponse.Event -> try {
                        if (receiver.queue.remainingCapacity() == 0) issue(url, Issue.RECEIVE_QUEUE_FULL)
                        task(receiver) {
                        if (generation != receiveGeneration) return@task
                        val rumor = try { identity.withSecret { checkActive(); nip17.decodeIncoming(r.event, it) } }
                            catch (_: Exception) { return@task }
                        checkActive()
                        val id = NostrEventCodec.calculateId(rumor)
                        if (seen.add(id)) {
                            if (seen.size > 4096) seen.remove(seen.first())
                            notifyUser { onReceived(rumor) }
                        }
                    } } catch (_: RejectedExecutionException) { if (!closed) issue(url, Issue.RECEIVE_QUEUE_FULL) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    catch (_: IllegalStateException) { /* Closed coordinator. */ }
                    is RelayResponse.Closed -> issue(url, Issue.SUBSCRIPTION_CLOSED)
                    else -> Unit
                }
            }, { state ->
                notifyUser { onInboxState(url, state) }
                if (state == NostrRelayClient.State.ERROR) issue(url, Issue.CONNECTION_ERROR)
            }, persistent = true)
            inboxes.add(c)
            c.subscribe("inbox", listOf(buildJsonObject {
                put("kinds", JsonArray(listOf(JsonPrimitive(1059))))
                put("#p", JsonArray(listOf(JsonPrimitive(local))))
            }))
            c.connect()
        }
    }
    override fun close() {
        synchronized(callbackGate) { closed = true }
        val (tasks, sockets) = synchronized(lifecycle) { work.toList() to clients.toList() }
        tasks.forEach { it.cancel(true) }
        sockets.forEach(::release)
        worker.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
        receiver.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
        synchronized(pendingIssues) { pendingIssues.clear() }
    }
}
