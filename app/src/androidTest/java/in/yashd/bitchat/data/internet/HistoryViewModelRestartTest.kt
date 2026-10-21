package `in`.yashd.bitchat.data.internet

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.nostr.*
import `in`.yashd.bitchat.ui.relay.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CompletableFuture

class HistoryViewModelRestartTest {
    private val owner = "01".repeat(32)
    private val peer = "02".repeat(32)
    private fun npub(hex: String) = NostrNpub.encode(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private inner class Backend : InternetBackend {
        var incoming: (UnsignedNostrEvent) -> Unit = {}
        val receivedWrites = java.util.concurrent.atomic.AtomicInteger()
        override fun identity() = NostrIdentity(owner, npub(owner))
        override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
            connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
            incoming = received
            return object : InternetSession {
                override fun receive(inboxes: List<String>) = CompletableFuture.completedFuture(Unit)
                override fun announce(inboxes: List<String>) = CompletableFuture.completedFuture(emptyList<NostrMessagingCoordinator.Publication>())
                override fun send(recipient: String, body: String, prepared: (String) -> Unit): CompletableFuture<NostrMessagingCoordinator.SendResult> {
                    val future = CompletableFuture<NostrMessagingCoordinator.SendResult>()
                    try {
                        prepared(NostrEventCodec.calculateId(UnsignedNostrEvent(owner, 100, 14, listOf(listOf("p", peer)), body)))
                        future.complete(NostrMessagingCoordinator.SendResult(listOf(NostrMessagingCoordinator.Publication("event", "wss://test.example", NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList()))
                    } catch (e: Exception) { future.completeExceptionally(e) }
                    return future
                }
                override fun close() {}
            }
        }
        override fun close() {}
    }
    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }
    @Test fun newViewModelWithReopenedRoomRestoresAndDeduplicatesHistory() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "history-tests/$token/restart.db")
        val alias = "relay.test.history.restart.$token"
        val stores = mutableListOf<ViewModelStore>()
        val repositories = mutableListOf<RoomInternetHistoryRepository>()
        fun open(): Pair<RelayViewModel, Backend> {
            val repository = RoomInternetHistoryRepository(context, file, HistoryCipher(alias)).also { repositories.add(it) }
            val backend = Backend()
            val tracked = object : InternetHistoryRepository by repository {
                override suspend fun received(owner: String, rumorId: String, peer: String, body: String, outgoing: Boolean, time: Long) {
                    repository.received(owner, rumorId, peer, body, outgoing, time)
                    backend.receivedWrites.incrementAndGet()
                }
            }
            val model = RelayViewModel(backend, history = tracked)
            stores.add(ViewModelStore().apply { put("history", model) })
            return model to backend
        }
        try {
            val (first, network) = open()
            await { !first.state.value.storageLoading }
            assertNull(first.state.value.storageError)
            first.addInternetConversation(npub(peer), "Saved friend")
            first.start(); first.configure("wss://discovery.example", "wss://inbox.example")
            first.send(npub(peer), "outgoing persisted")
            await { first.state.value.messages.any { it.status == "Relay accepted · delivery unconfirmed" } }
            val incoming = UnsignedNostrEvent(peer, 101, 14, listOf(listOf("p", owner)), "reply persisted")
            network.incoming(incoming)
            await { first.state.value.messages.size == 2 }
            stores.first().clear(); repositories.first().close()
            val (second, restartedNetwork) = open()
            await { !second.state.value.storageLoading }
            assertNull(second.state.value.storageError)
            assertEquals("Saved friend", second.state.value.conversations.single().displayName)
            assertEquals(setOf("outgoing persisted", "reply persisted"), second.state.value.messages.map { it.body }.toSet())
            assertEquals(1, second.state.value.messages.count { it.status == "Relay accepted · delivery unconfirmed" })
            second.start(); second.configure("wss://discovery.example", "wss://inbox.example")
            restartedNetwork.incoming(incoming)
            restartedNetwork.incoming(UnsignedNostrEvent(owner, 100, 14, listOf(listOf("p", peer)), "outgoing persisted"))
            await { restartedNetwork.receivedWrites.get() >= 2 }
            assertEquals(2, repositories.last().load(owner).messages.size)
            assertEquals(2, second.state.value.messages.size)
        } finally {
            stores.forEach { it.clear() }; repositories.forEach { it.close() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            file.parentFile?.deleteRecursively()
        }
        Unit
    }
}
