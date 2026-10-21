package `in`.yashd.bitchat.nostr

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okio.ByteString

/** One relay connection. Callbacks run serially under the client lock; keep them short.
 * The supplied OkHttpClient remains owned by the caller. close() releases this client's resources.
 * Publish returns queue admission only; Acceptance responses report relay acceptance, not delivery.
 * Publications are never replayed automatically (disconnects leave their outcome unknown).
 */
class NostrRelayClient(
    relayUrl: String,
    private val http: OkHttpClient,
    private val maxMessageBytes: Int = 256 * 1024,
    private val maxReconnectAttempts: Int = 5,
    private val initialBackoffMillis: Long = 1_000,
    private val maxBackoffMillis: Long = 30_000,
    private val onState: (State) -> Unit = {},
    private val onResponse: (RelayResponse) -> Unit = {},
    private val onInvalidMessage: () -> Unit = {}
) : Closeable {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    // A WebSocket redirect would send inbox traffic to an unadvertised destination.
    // The derived client shares the caller's dispatcher and pool; neither is shut down here.
    private val transport = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val request: Request
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nostr-relay-reconnect").apply { isDaemon = true }
    }
    private val subscriptions = linkedMapOf<String, String>()
    private var socket: WebSocket? = null
    private var generation = 0L
    private var attempts = 0
    private var stopped = false
    private var retry: ScheduledFuture<*>? = null
    @Volatile var state: State = State.DISCONNECTED
        private set

    init {
        require(relayUrl.startsWith("wss://")) { "Only wss:// relays are allowed" }
        require(maxMessageBytes > 0 && maxReconnectAttempts in 0..30)
        require(initialBackoffMillis > 0 && maxBackoffMillis >= initialBackoffMillis)
        request = Request.Builder().url(relayUrl).build()
        require(request.url.username.isEmpty() && request.url.password.isEmpty())
    }
    private fun notifySafely(callback: () -> Unit) { try { callback() } catch (_: Exception) { /* Isolate consumers from transport. */ } }
    private fun transition(next: State) { state = next; notifySafely { onState(next) } }

    @Synchronized fun connect() {
        check(!stopped) { "Client is closed" }
        if (state == State.CONNECTED || state == State.CONNECTING || retry != null) return
        attempts = 0
        open()
    }
    private fun open() {
        val epoch = ++generation
        transition(State.CONNECTING)
        if (stopped || generation != epoch) return
        socket = transport.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@NostrRelayClient) {
                if (stopped || epoch != generation) { webSocket.cancel(); return@synchronized }
                socket = webSocket
                // Budget is per connect() lifecycle, so rapidly flapping relays cannot retry forever.
                // Restore before notifying consumers: a CONNECTED callback may subscribe,
                // replace or cancel a subscription and must not trigger a second replay.
                subscriptions.values.toList().forEach { webSocket.send(it) }
                transition(State.CONNECTED)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@NostrRelayClient) {
                if (stopped || epoch != generation) return@synchronized
                if (text.length > maxMessageBytes || text.toByteArray(Charsets.UTF_8).size > maxMessageBytes) {
                    notifySafely(onInvalidMessage)
                    failed(epoch)
                    return@synchronized
                }
                val response = NostrRelayProtocol.decode(text, maxMessageBytes)
                if (response == null) { notifySafely(onInvalidMessage); return@synchronized }
                val id = when (response) {
                    is RelayResponse.Event -> response.subscriptionId
                    is RelayResponse.EndOfStoredEvents -> response.subscriptionId
                    is RelayResponse.Closed -> response.subscriptionId
                    else -> null
                }
                if (id != null && id !in subscriptions) return@synchronized
                if (response is RelayResponse.Closed) subscriptions.remove(response.subscriptionId)
                notifySafely { onResponse(response) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(this@NostrRelayClient) {
                if (stopped || epoch != generation) return@synchronized
                notifySafely(onInvalidMessage)
                failed(epoch)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@NostrRelayClient) { failed(epoch) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@NostrRelayClient) { failed(epoch) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@NostrRelayClient) { failed(epoch) }
        })
    }
    private fun failed(epoch: Long) {
        if (stopped || epoch != generation) return
        generation++
        socket?.cancel(); socket = null
        // Reserve the retry before notifying consumers. An ERROR callback may call
        // connect() or close(); it must see (or cancel) this existing retry.
        if (attempts < maxReconnectAttempts) {
            var delay = initialBackoffMillis
            repeat(attempts) { delay = if (delay > maxBackoffMillis - delay) maxBackoffMillis else delay * 2 }
            attempts++
            retry = scheduler.schedule({ synchronized(this) {
                retry = null
                if (!stopped) open()
            } }, delay, TimeUnit.MILLISECONDS)
        }
        transition(State.ERROR)
    }

    @Synchronized fun subscribe(id: String, filters: List<JsonObject>) {
        check(!stopped)
        val encoded = NostrRelayProtocol.request(id, filters)
        subscriptions[id] = encoded
        if (state == State.CONNECTED) socket?.send(encoded)
    }
    @Synchronized fun cancelSubscription(id: String) {
        val encoded = NostrRelayProtocol.close(id)
        if (subscriptions.remove(id) != null && state == State.CONNECTED) socket?.send(encoded)
    }
    @Synchronized fun publish(event: NostrEvent): Boolean {
        check(!stopped)
        require(NostrEventSigner().verify(event)) { "Invalid event" }
        return state == State.CONNECTED && socket?.send(NostrRelayProtocol.publish(event)) == true
    }
    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        generation++
        retry?.cancel(false); retry = null
        subscriptions.clear()
        socket?.cancel(); socket = null
        scheduler.shutdownNow()
        transition(State.DISCONNECTED)
    }
}
