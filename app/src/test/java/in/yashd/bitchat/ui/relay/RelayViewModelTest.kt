package `in`.yashd.bitchat.ui.relay

import androidx.lifecycle.ViewModelStore
import `in`.yashd.bitchat.nostr.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class RelayViewModelTest {
    private val npub = NostrNpub.encode(ByteArray(32) { 1 })
    private class Backend : InternetBackend {
        var closed = false
        var opened = 0
        var announcementStages = listOf(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)
        var announcementFailure: Exception? = null
        var pendingAnnouncement: CompletableFuture<List<NostrMessagingCoordinator.Publication>>? = null
        var sessionsClosed = 0
        var received: (UnsignedNostrEvent) -> Unit = {}
        var connected: (String, String) -> Unit = { _, _ -> }
        var sendResult = NostrMessagingCoordinator.SendResult(emptyList(), emptyList())
        var sendFailure: Exception? = null
        var preparedId: String? = null
        var pendingSend: CompletableFuture<NostrMessagingCoordinator.SendResult>? = null
        val sendStarted = java.util.concurrent.CountDownLatch(1)
        override fun identity() = NostrIdentity("01".repeat(32), NostrNpub.encode(ByteArray(32) { 1 }))
        override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit, connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
            opened++
            this.received = received; connected = connection
            return object : InternetSession {
                override fun receive(inboxes: List<String>) = CompletableFuture.completedFuture(Unit)
                override fun announce(inboxes: List<String>): CompletableFuture<List<NostrMessagingCoordinator.Publication>> =
                    pendingAnnouncement ?: if (announcementFailure != null) CompletableFuture<List<NostrMessagingCoordinator.Publication>>().apply { completeExceptionally(announcementFailure!!) }
                    else CompletableFuture.completedFuture(announcementStages.map(::publication))
                override fun send(recipient: String, body: String, prepared: (String) -> Unit): CompletableFuture<NostrMessagingCoordinator.SendResult> {
                    sendStarted.countDown()
                    pendingSend?.let { return it }
                    preparedId?.let(prepared)
                    return if (sendFailure != null) CompletableFuture<NostrMessagingCoordinator.SendResult>().apply { completeExceptionally(sendFailure!!) }
                    else CompletableFuture.completedFuture(sendResult)
                }
                override fun close() { sessionsClosed++ }
            }
        }
        override fun close() { closed = true }
    }
    private fun withModel(action: suspend (RelayViewModel, Backend) -> Unit) {
        val backend = Backend()
        val model = RelayViewModel(backend, Dispatchers.Unconfined, `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = ViewModelStore(); store.put("relay", model)
        try { kotlinx.coroutines.runBlocking { action(model, backend) } } finally { store.clear() }
        assertTrue(backend.closed)
    }
    private fun configured(model: RelayViewModel) { model.start(); model.configure("wss://discovery.example", "wss://inbox.example") }
    @Test fun relayValidationRejectsInsecureAndAmbiguousUrls() {
        listOf("", "ws://example.com", "https://example.com", "wss://u:p@example.com", "wss://example.com/#fragment", "wss://example.com:0").forEach {
            assertThrows(Exception::class.java) { RelayViewModel.parseRelays(it) }
        }
        assertEquals(listOf("wss://example.com"), RelayViewModel.parseRelays(" wss://example.com\nwss://example.com "))
        assertThrows(Exception::class.java) { RelayViewModel.parseRelays((1..4).joinToString("\n") { "wss://relay$it.example" }) }
    }
    @Test fun recipientValidationRequiresChecksum() {
        assertEquals("01".repeat(32), RelayViewModel.recipientKey(npub))
        assertThrows(Exception::class.java) { RelayViewModel.recipientKey(npub.dropLast(1) + "x") }
    }
    @Test fun missingConfigurationAndInvalidNpubAreExplained() = withModel { model, _ ->
        model.send("bad", "hello"); assertTrue(model.state.value.error!!.contains("npub"))
        model.send(npub, "hello"); assertTrue(model.state.value.error!!.contains("Configure"))
        assertTrue(model.state.value.messages.isEmpty())
    }
    @Test fun acceptedIsNeverCalledDelivered() = withModel { model, backend ->
        configured(model)
        backend.sendResult = NostrMessagingCoordinator.SendResult(listOf(publication(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList())
        model.send(npub, "hello")
        assertEquals("Relay accepted · delivery unconfirmed", model.state.value.messages.single().status)
        assertFalse(model.state.value.busy)
    }
    @Test fun timeoutRemainsUnknown() = withModel { model, backend ->
        configured(model)
        backend.sendResult = NostrMessagingCoordinator.SendResult(listOf(publication(NostrMessagingCoordinator.Stage.UNKNOWN)), emptyList())
        model.send(npub, "hello")
        assertTrue(model.state.value.messages.single().status.startsWith("Outcome unknown"))
    }
    @Test fun missingInboxExplainsBothAnnouncements() = withModel { model, backend ->
        configured(model); backend.sendFailure = NostrMessagingCoordinator.MissingInbox()
        model.send(npub, "hello")
        assertTrue(model.state.value.toString(), model.state.value.error!!.contains("Both users")); assertFalse(model.state.value.busy)
    }
    @Test fun lifecycleClosesConnectionsAndIgnoresLateCallbacks() = withModel { model, backend ->
        configured(model); backend.connected("wss://inbox.example", "connected")
        assertEquals("connected", model.state.value.connections.values.single())
        model.stop(); backend.connected("wss://inbox.example", "connected")
        assertEquals(1, backend.sessionsClosed); assertTrue(model.state.value.connections.isEmpty())
        model.start(); assertTrue(model.state.value.active)
    }
    @Test fun announcementsReportRelayAcceptance() = withModel { model, _ ->
        configured(model); model.announce()
        assertTrue(model.state.value.notice!!.contains("1/1 discovery relays accepted"))
    }
    @Test fun incomingMessagesDeduplicateAcrossReconnects() = withModel { model, backend ->
        configured(model)
        val rumor = UnsignedNostrEvent("02".repeat(32), 123L, 14, listOf(listOf("p", "01".repeat(32))), "hello")
        backend.received(rumor); backend.received(rumor)
        model.stop(); model.start(); backend.received(rumor)
        assertEquals(1, model.state.value.messages.size)
        assertFalse(model.state.value.messages.single().outgoing)
    }
    @Test fun senderInboxEchoDoesNotDuplicateOptimisticMessage() = withModel { model, backend ->
        configured(model)
        val rumor = UnsignedNostrEvent("01".repeat(32), 123L, 14, listOf(listOf("p", "01".repeat(32))), "hello")
        backend.preparedId = NostrEventCodec.calculateId(rumor)
        backend.sendResult = NostrMessagingCoordinator.SendResult(listOf(publication(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList())
        model.send(npub, "hello"); backend.received(rumor)
        assertEquals(1, model.state.value.messages.size)
        assertEquals("Relay accepted · delivery unconfirmed", model.state.value.messages.single().status)
    }
    @Test fun invalidRelayChangePreservesWorkingConfiguration() = withModel { model, _ ->
        configured(model); model.configure("ws://insecure.example", "wss://inbox.example")
        assertEquals(listOf("wss://discovery.example"), model.state.value.discovery)
        assertTrue(model.state.value.error!!.contains("secure wss://"))
    }
    @Test fun networkFailureStopsBusyStateAndPreservesUnconfirmedMessage() = withModel { model, backend ->
        configured(model); backend.sendFailure = java.io.IOException("offline")
        model.send(npub, "hello")
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.error!!.contains("Network"))
        assertTrue(model.state.value.messages.single().status.contains("unconfirmed"))
    }
    @Test fun stopCancelsOutstandingFutureAndMarksOutcomeUnknown() {
        val backend = Backend()
        val pending = CompletableFuture<NostrMessagingCoordinator.SendResult>()
        backend.pendingSend = pending
        val model = RelayViewModel(backend, history = `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = ViewModelStore(); store.put("relay", model)
        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(condition())
        }
        try {
            awaitCondition { model.state.value.npub.isNotEmpty() && !model.state.value.storageLoading }
            configured(model); model.send(npub, "hello")
            assertTrue(backend.sendStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            model.stop()
            awaitCondition { pending.isCancelled }
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.active)
            assertTrue(model.state.value.messages.single().status.contains("unknown") ||
                model.state.value.messages.single().status.contains("unconfirmed"))
        } finally { store.clear() }
    }
    @Test fun setupValidationKeepsProgressAndDraftWithoutInventingConnections() = withModel { model, _ ->
        model.start()
        model.updateSetupServers("ws://unsafe.example", "wss://inbox.example")
        model.setupNext()
        assertEquals(0, model.state.value.setup.step)
        assertNotNull(model.state.value.setup.error)
        assertTrue(model.state.value.discovery.isEmpty())
        model.updateSetupServers("wss://discovery.example", "wss://inbox.example")
        model.setupNext()
        assertEquals(1, model.state.value.setup.step)
        assertFalse(model.state.value.inboxesConnected)
        assertFalse(model.state.value.readyToShare)
        assertFalse(model.finishSetup())
        model.setupBack()
        assertEquals("wss://discovery.example", model.state.value.setup.discoveryDraft)
    }
    @Test fun configuredAndAcceptedStillRequireVerifiedLiveConnections() = withModel { model, backend ->
        model.start(); model.updateSetupServers("wss://discovery.example", "wss://inbox.example"); model.setupNext()
        model.announce(); model.setupNext()
        assertEquals(InboxAnnouncement.ACCEPTED, model.state.value.setup.announcement)
        assertEquals(1, model.state.value.setup.step)
        assertFalse(model.state.value.readyToShare)
        backend.connected("wss://inbox.example", "connected")
        assertTrue(model.state.value.readyToShare)
        model.setupNext(); assertEquals(2, model.state.value.setup.step)
        assertTrue(model.finishSetup())
        model.stop()
        assertFalse(model.state.value.readyToShare)
        assertTrue(model.state.value.connections.isEmpty())
        assertEquals(2, model.state.value.setup.step)
        assertEquals(InboxAnnouncement.ACCEPTED, model.state.value.setup.announcement)
        assertFalse(model.finishSetup())
        model.start(); assertFalse(model.state.value.readyToShare)
        backend.connected("wss://inbox.example", "connected")
        assertTrue(model.state.value.readyToShare)
    }
    @Test fun failedAnnouncementCanBeRetriedWithoutLosingServerDrafts() = withModel { model, backend ->
        configured(model); backend.connected("wss://inbox.example", "connected")
        backend.announcementStages = listOf(NostrMessagingCoordinator.Stage.RELAY_REJECTED)
        model.announce()
        assertEquals(InboxAnnouncement.FAILED, model.state.value.setup.announcement)
        assertNotNull(model.state.value.setup.error)
        assertFalse(model.state.value.readyToShare)
        backend.announcementStages = listOf(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)
        model.announce()
        assertEquals(InboxAnnouncement.ACCEPTED, model.state.value.setup.announcement)
        assertNull(model.state.value.setup.error)
        assertTrue(model.state.value.readyToShare)
        assertEquals("wss://inbox.example", model.state.value.setup.inboxDraft)
    }
    @Test fun connectionRetryIgnoresStaleCallbacksAndRequiresFreshHandshake() = withModel { model, backend ->
        configured(model)
        val stale = backend.connected
        backend.connected("wss://inbox.example", "error")
        model.retrySetup()
        assertEquals(2, backend.opened)
        stale("wss://inbox.example", "connected")
        assertFalse(model.state.value.inboxesConnected)
        backend.connected("wss://inbox.example", "connected")
        assertTrue(model.state.value.inboxesConnected)
    }
    @Test fun changedServersInvalidateEarlierAnnouncementAcceptance() = withModel { model, backend ->
        configured(model); backend.connected("wss://inbox.example", "connected"); model.announce()
        assertTrue(model.state.value.readyToShare)
        model.configure("wss://other.example", "wss://new-inbox.example")
        assertEquals(InboxAnnouncement.NOT_PUBLISHED, model.state.value.setup.announcement)
        assertTrue(model.state.value.setup.publications.isEmpty())
        assertFalse(model.state.value.readyToShare)
    }
    @Test fun partialAndUnknownPublicationResultsAreNotSuccessfulSetup() = withModel { model, backend ->
        configured(model); backend.connected("wss://inbox.example", "connected")
        backend.announcementStages = listOf(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED, NostrMessagingCoordinator.Stage.FAILED)
        model.announce(); assertEquals(InboxAnnouncement.PARTIAL, model.state.value.setup.announcement)
        assertFalse(model.state.value.readyToShare)
        backend.announcementStages = listOf(NostrMessagingCoordinator.Stage.UNKNOWN)
        model.announce(); assertEquals(InboxAnnouncement.UNKNOWN, model.state.value.setup.announcement)
        assertFalse(model.state.value.readyToShare)
    }
    @Test fun announcementNetworkErrorDoesNotExposeExceptionDetails() = withModel { model, backend ->
        configured(model); backend.announcementFailure = java.io.IOException("sensitive token and data")
        model.announce()
        assertEquals(InboxAnnouncement.FAILED, model.state.value.setup.announcement)
        assertFalse(model.state.value.setup.error!!.contains("sensitive"))
        assertFalse(model.state.value.error!!.contains("sensitive"))
        assertFalse(model.state.value.busy)
    }
    @Test fun emptyNamedConversationIsImmediatelyReusableAndDuplicateSafe() = withModel { model, _ ->
        assertEquals(npub, model.addInternetConversation(npub, "  Aarav  "))
        model.addInternetConversation(npub.uppercase(), "")
        assertEquals(1, model.state.value.conversations.size)
        val row = relayConversations(model.state.value, `in`.yashd.bitchat.mesh.MeshUiState()).single()
        assertEquals("Aarav", row.title)
        assertEquals(npub, row.recipient)
        assertEquals(ConversationTransport.Internet, row.transport)
        assertTrue(model.state.value.messages.isEmpty())
        model.stop(); model.start()
        assertEquals("Aarav", model.state.value.conversations.single().displayName)
    }
    @Test fun invalidIdAndInvalidNameCannotCreateConversation() = withModel { model, _ ->
        assertNull(model.addInternetConversation("not-an-id", "Friend"))
        assertNull(model.addInternetConversation(npub, "a".repeat(61)))
        assertNull(model.addInternetConversation(npub, "line\nbreak"))
        assertTrue(model.state.value.conversations.isEmpty())
    }
    @Test fun conversationsRemainPerKeyEvenWhenNamesMatchAndIncomingMessagesArrive() = withModel { model, backend ->
        configured(model)
        model.addInternetConversation(npub, "Friend")
        val other = NostrNpub.encode(ByteArray(32) { 2 })
        model.addInternetConversation(other, "Friend")
        backend.received(UnsignedNostrEvent("02".repeat(32), 1, 14, listOf(listOf("p", "01".repeat(32))), "hi"))
        val rows = relayConversations(model.state.value, `in`.yashd.bitchat.mesh.MeshUiState())
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.recipient }.distinct().size)
        assertEquals(2, rows.count { it.title == "Friend" })
    }
    @Test fun newViewModelStartsWithoutConversationsOrFalseSetupSuccess() {
        withModel { model, _ -> model.addInternetConversation(npub, "Friend") }
        withModel { model, _ ->
            assertTrue(model.state.value.conversations.isEmpty())
            assertEquals(InboxAnnouncement.NOT_PUBLISHED, model.state.value.setup.announcement)
            assertFalse(model.state.value.readyToShare)
        }
    }
    @Test fun allInboxConnectionsMustBeVerifiedAndSubscriptionFailureRevokesReadiness() = withModel { model, backend ->
        model.start(); model.configure("wss://discovery.example", "wss://one.example\nwss://two.example")
        model.announce()
        backend.connected("wss://one.example", "connected")
        assertFalse(model.state.value.readyToShare)
        backend.connected("wss://two.example", "connected")
        assertTrue(model.state.value.readyToShare)
        backend.connected("wss://one.example", "subscription unavailable")
        assertFalse(model.state.value.readyToShare)
    }
    @Test fun interruptedAnnouncementIsUnknownAndCannotFinishSetup() {
        val backend = Backend()
        val pending = CompletableFuture<List<NostrMessagingCoordinator.Publication>>()
        backend.pendingAnnouncement = pending
        val model = RelayViewModel(backend, history = `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = ViewModelStore(); store.put("relay", model)
        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(condition())
        }
        try {
            awaitCondition { model.state.value.npub.isNotEmpty() && !model.state.value.storageLoading }
            configured(model); backend.connected("wss://inbox.example", "connected")
            model.announce()
            awaitCondition { model.state.value.setup.announcement == InboxAnnouncement.PUBLISHING }
            model.stop()
            awaitCondition { pending.isCancelled }
            assertEquals(InboxAnnouncement.UNKNOWN, model.state.value.setup.announcement)
            assertFalse(model.state.value.readyToShare)
            assertFalse(model.finishSetup())
        } finally { store.clear() }
    }
    @Test fun repositoryFailureShowsStorageErrorAndBlocksInternetWithoutReplacingHistory() {
        val backend = Backend()
        val repository = object : `in`.yashd.bitchat.data.internet.InternetHistoryRepository by `in`.yashd.bitchat.data.internet.TestHistoryRepository() {
            override suspend fun load(owner: String): `in`.yashd.bitchat.data.internet.HistorySnapshot = throw IllegalStateException("sensitive storage detail")
        }
        val model = RelayViewModel(backend, Dispatchers.Unconfined, repository)
        val store = ViewModelStore(); store.put("relay", model)
        try {
            assertFalse(model.state.value.storageLoading)
            assertNotNull(model.state.value.storageError)
            assertFalse(model.state.value.storageError!!.contains("sensitive storage detail"))
            configured(model); model.send(npub, "must not publish")
            assertEquals(0, backend.opened)
            assertEquals(1L, backend.sendStarted.count)
            assertFalse(model.state.value.readyToShare)
        } finally { store.clear() }
    }
    @Test fun freshViewModelRestoresNamesPendingAndAcceptedMessagesFromRepository() = kotlinx.coroutines.runBlocking {
        val history = `in`.yashd.bitchat.data.internet.TestHistoryRepository()
        val owner = "01".repeat(32)
        val peer = "02".repeat(32)
        history.contact(owner, peer, "Saved friend")
        history.outgoing(owner, "accepted", peer, "saved text", 1)
        history.publication(owner, "accepted", NostrMessagingCoordinator.SendResult(listOf(publication(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList()))
        history.outgoing(owner, "pending", peer, "unfinished", 2)
        val model = RelayViewModel(Backend(), Dispatchers.Unconfined, history)
        val store = ViewModelStore(); store.put("relay", model)
        try {
            assertFalse(model.state.value.storageLoading)
            assertEquals("Saved friend", model.state.value.conversations.single().displayName)
            assertEquals(2, model.state.value.messages.size)
            assertEquals("Relay accepted · delivery unconfirmed", model.state.value.messages.first { it.id == "accepted" }.status)
            assertTrue(model.state.value.messages.first { it.id == "pending" }.status.contains("unknown"))
        } finally { store.clear() }
    }
    @Test fun cancellationDuringDurableEnqueueBecomesUnknownWithoutPublishing() = kotlinx.coroutines.runBlocking {
        val saved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backing = `in`.yashd.bitchat.data.internet.TestHistoryRepository()
        val repository = object : `in`.yashd.bitchat.data.internet.InternetHistoryRepository by backing {
            override suspend fun outgoing(owner: String, id: String, peer: String, body: String, time: Long) {
                backing.outgoing(owner, id, peer, body, time)
                saved.complete(Unit)
                release.await() // Model a commit completing just as the Activity stops.
            }
        }
        val backend = Backend()
        val model = RelayViewModel(backend, history = repository)
        val models = ViewModelStore().apply { put("relay", model) }
        try {
            kotlinx.coroutines.withTimeout(5_000) { while (model.state.value.storageLoading) kotlinx.coroutines.delay(10) }
            configured(model)
            model.send(npub, "durable but interrupted")
            kotlinx.coroutines.withTimeout(5_000) { saved.await() }
            model.stop()
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) {
                while (model.state.value.messages.singleOrNull()?.status?.startsWith("Outcome unknown") != true) kotlinx.coroutines.delay(10)
            }
            assertNull(model.state.value.storageError)
            assertEquals(1L, backend.sendStarted.count)
            assertEquals(`in`.yashd.bitchat.data.internet.HistoryStatus.UNKNOWN, backing.load("01".repeat(32)).messages.single().status)
        } finally { release.complete(Unit); models.clear() }
    }
    companion object {
        private fun publication(stage: NostrMessagingCoordinator.Stage) = NostrMessagingCoordinator.Publication("id", "wss://relay.example", stage)
    }
}
