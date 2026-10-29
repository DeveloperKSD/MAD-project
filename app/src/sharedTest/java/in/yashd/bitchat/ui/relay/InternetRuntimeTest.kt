package `in`.yashd.bitchat.ui.relay

import androidx.lifecycle.ViewModelStore
import `in`.yashd.bitchat.data.internet.TestHistoryRepository
import `in`.yashd.bitchat.nostr.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The same ownership contract runs on the JVM and Android, without public relay dependencies. */
class InternetRuntimeTest {
    private class Backend : InternetBackend {
        var opens = 0
        var closes = 0
        var backendCloses = 0
        var subscriptions = 0
        var received: (UnsignedNostrEvent) -> Unit = {}
        var connection: (String, String) -> Unit = { _, _ -> }
        var pending: CompletableFuture<NostrMessagingCoordinator.SendResult>? = null
        val sending = CountDownLatch(1)
        override fun identity() = NostrIdentity("01".repeat(32), NostrNpub.encode(ByteArray(32) { 1 }))
        override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                          connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
            opens++
            this.received = received
            this.connection = connection
            return object : InternetSession {
                override fun receive(inboxes: List<String>): CompletableFuture<Unit> {
                    subscriptions += inboxes.size
                    return CompletableFuture.completedFuture(Unit)
                }
                override fun announce(inboxes: List<String>) = CompletableFuture.completedFuture(emptyList<NostrMessagingCoordinator.Publication>())
                override fun send(recipient: String, body: String, prepared: (String) -> Unit): CompletableFuture<NostrMessagingCoordinator.SendResult> {
                    sending.countDown()
                    return pending ?: CompletableFuture.completedFuture(NostrMessagingCoordinator.SendResult(emptyList(), emptyList()))
                }
                override fun close() { closes++ }
            }
        }
        override fun close() { backendCloses++ }
    }
    private fun runtime(backend: Backend, alert: () -> Unit = {}) = InternetRuntime(backend,
        if (backend.pending != null) Dispatchers.IO else Dispatchers.Unconfined, TestHistoryRepository(), onIncomingStored = { _, _, _ -> alert() }).apply {
        await { !state.value.storageLoading && !state.value.configurationLoading }
        configure("wss://discovery.example", "wss://one.example\nwss://two.example")
        start()
    }
    @Test fun repeatedAndCompetingStartsOpenOneSessionAndOneSubscriptionPerInbox() {
        val b = Backend()
        runtime(b).use { r ->
            val threads = List(20) { Thread { repeat(20) { r.start() } } }
            threads.forEach(Thread::start); threads.forEach(Thread::join)
            assertEquals(1, b.opens)
            assertEquals(2, b.subscriptions)
            r.stop(); r.stop()
            assertEquals(1, b.closes)
            r.start(); r.start()
            assertEquals(2, b.opens)
            assertEquals(4, b.subscriptions)
        }
        assertEquals(2, b.closes)
        assertEquals(1, b.backendCloses)
    }
    @Test fun replacingUiAdapterDoesNotCancelSendOrCloseSharedSession() {
        val b = Backend().apply { pending = CompletableFuture() }
        runtime(b).use { r ->
            val first = RelayViewModel(r)
            val holder = ViewModelStore().apply { put("relay", first) }
            first.send(r.state.value.npub, "survives recreation")
            assertTrue(b.sending.await(3, TimeUnit.SECONDS))
            holder.clear()
            val second = RelayViewModel(r)
            assertSame(first.state, second.state)
            assertEquals(0, b.closes)
            assertFalse(b.pending!!.isCancelled)
            b.pending!!.complete(NostrMessagingCoordinator.SendResult(emptyList(), emptyList()))
            await { !r.state.value.busy }
            assertEquals(1, b.opens)
            assertEquals(1, second.state.value.messages.size)
        }
    }
    @Test fun configurationReplacementClosesOldSessionAndRejectsItsCallbacks() {
        val b = Backend()
        runtime(b).use { r ->
            val oldConnection = b.connection
            val oldReceived = b.received
            r.configure("wss://new.example", "wss://new-inbox.example")
            assertEquals(2, b.opens)
            assertEquals(1, b.closes)
            oldConnection("wss://one.example", "connected")
            oldReceived(message())
            assertTrue(r.state.value.connections.isEmpty())
            assertTrue(r.state.value.messages.isEmpty())
            r.configure("wss://new.example", "wss://new-inbox.example")
            assertEquals(2, b.opens)
        }
    }
    @Test fun notificationDeduplicationSurvivesUiReplacementAndReconnect() {
        val b = Backend()
        var alerts = 0
        runtime(b) { alerts++ }.use { r ->
            val incoming = message()
            b.received(incoming)
            val holder = ViewModelStore().apply { put("relay", RelayViewModel(r)) }
            holder.clear()
            b.received(incoming)
            r.stop(); r.start()
            b.received(incoming)
            assertEquals(1, alerts)
            assertEquals(1, r.state.value.messages.size)
        }
    }
    @Test fun shutdownCancelsSendIsTerminalAndSuppressesCallbacks() {
        val b = Backend().apply { pending = CompletableFuture() }
        val r = runtime(b)
        r.send(r.state.value.npub, "cancel on real shutdown")
        assertTrue(b.sending.await(3, TimeUnit.SECONDS))
        r.close(); r.close()
        await { b.pending!!.isCancelled }
        b.connection("wss://one.example", "connected")
        b.received(message())
        r.start()
        assertFalse(r.state.value.active)
        assertTrue(r.state.value.connections.isEmpty())
        assertEquals(1, b.opens)
        assertEquals(1, b.backendCloses)
        assertFalse(r.configure("wss://other.example", "wss://other.example"))
    }
    @Test fun configurationChangeDuringSendIsRejectedWithoutInterruptingSend() {
        val b = Backend().apply { pending = CompletableFuture() }
        runtime(b).use { r ->
            r.send(r.state.value.npub, "busy")
            assertTrue(b.sending.await(3, TimeUnit.SECONDS))
            assertFalse(r.configure("wss://other.example", "wss://other.example"))
            r.retrySetup()
            assertEquals(1, b.opens)
            assertEquals(0, b.closes)
            assertFalse(b.pending!!.isCancelled)
        }
    }
    private fun message() = UnsignedNostrEvent("02".repeat(32), System.currentTimeMillis() / 1000 + 2, 14, content = "hello")
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
