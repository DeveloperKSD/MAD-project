package `in`.yashd.bitchat.background

import `in`.yashd.bitchat.data.internet.*
import `in`.yashd.bitchat.nostr.*
import `in`.yashd.bitchat.ui.relay.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

/** Runs identically on JVM and emulator. No public network or production preferences. */
class BackgroundSyncTest {
    private val owner = "01".repeat(32)
    private val peer = "02".repeat(32)
    private val saved = SavedSyncConfiguration(owner, InternetConfiguration(listOf("wss://discovery.example"), listOf("wss://inbox.example")))
    private class Store : BackgroundSyncStore {
        var value = BackgroundSyncDecision()
        var fail = false
        override fun read(): BackgroundSyncDecision { check(!fail); return value }
        override fun write(value: BackgroundSyncDecision) { check(!fail); this.value = value }
    }
    private class Scheduler : BackgroundSyncScheduler {
        val active = mutableSetOf<String>()
        var cancels = 0
        override suspend fun ensureScheduled() { active.add("unique") }
        override suspend fun cancel() { cancels++; active.clear() }
    }
    private class Backend(private val owner: String) : InternetBackend {
        var opens = 0
        var closes = 0
        var received: (UnsignedNostrEvent) -> Unit = {}
        var pending: CompletableFuture<NostrMessagingCoordinator.SendResult>? = null
        override fun identity() = NostrIdentity(owner, NostrNpub.encode(ByteArray(32) { 1 }))
        override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                          connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
            opens++
            this.received = received
            return object : InternetSession {
                override fun receive(inboxes: List<String>) = CompletableFuture.completedFuture(Unit)
                override fun announce(inboxes: List<String>) = CompletableFuture.completedFuture(emptyList<NostrMessagingCoordinator.Publication>())
                override fun send(recipient: String, body: String, prepared: (String) -> Unit) = pending ?: CompletableFuture.completedFuture(NostrMessagingCoordinator.SendResult(emptyList(), emptyList()))
                override fun close() { closes++ }
            }
        }
        override fun close() {}
    }
    private fun runtime(backend: Backend = Backend(owner), history: InternetHistoryRepository = TestHistoryRepository(), alert: () -> Unit = {}) =
        InternetRuntime(backend, if (backend.pending != null) Dispatchers.IO else Dispatchers.Unconfined, history, onIncomingStored = { _, _, _ -> alert() }).apply {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (state.value.storageLoading && System.nanoTime() < deadline) Thread.sleep(5)
            check(!state.value.storageLoading)
            configure(saved.configuration.discovery.joinToString("\n"), saved.configuration.inboxes.joinToString("\n"))
        }
    private fun rumor(outgoing: Boolean = false) = UnsignedNostrEvent(if (outgoing) owner else peer,
        1_000_000, 14, listOf(listOf("p", if (outgoing) peer else owner)), "private sample")

    @Test fun explicitOptInRepeatedLaunchesDisableAndRestart() = runBlocking {
        val store = Store(); val scheduler = Scheduler()
        fun controller() = BackgroundSyncController(store, scheduler, { saved })
        val first = controller()
        assertNull(first.reconcile())
        assertFalse(first.setEnabled(true, false))
        assertTrue(scheduler.active.isEmpty())
        assertTrue(first.setEnabled(true, true))
        val ticket = first.reconcile()!!
        first.finished(ticket, 1000, true)
        repeat(20) { assertNotNull(controller().reconcile()) }
        assertEquals(1, scheduler.active.size)
        assertEquals(1000L, controller().reconcile()!!.decision.lastSuccessSeconds)
        assertTrue(controller().setEnabled(false, false))
        assertNull(controller().reconcile())
        assertTrue(scheduler.active.isEmpty())
        assertFalse(store.value.enabled)
    }
    @Test fun invalidOrChangedSavedConfigurationAndCorruptOptInFailClosed() = runBlocking {
        val store = Store(); val scheduler = Scheduler()
        var available: SavedSyncConfiguration? = saved
        val controller = BackgroundSyncController(store, scheduler, { available })
        assertTrue(controller.setEnabled(true, true))
        val original = store.value
        available = saved.copy(configuration = InternetConfiguration(saved.configuration.discovery, listOf("wss://changed.example")))
        assertNull(controller.reconcile())
        assertEquals(original, store.value)
        assertTrue(scheduler.active.isEmpty())
        available = null
        assertFalse(controller.setEnabled(true, true))
        store.fail = true
        assertNull(controller.reconcile())
        assertTrue(controller.state.value.text.startsWith("Unavailable"))
        assertEquals(original, store.value)
    }
    @Test fun staleCompletionCannotUndoDisableOrNewApproval() = runBlocking {
        val store = Store(); val scheduler = Scheduler()
        val controller = BackgroundSyncController(store, scheduler, { saved })
        controller.setEnabled(true, true)
        val old = controller.reconcile()!!
        controller.setEnabled(false, false)
        controller.setEnabled(true, true)
        controller.finished(old, 1000, true)
        assertEquals(0L, store.value.lastSuccessSeconds)
    }
    @Test fun finiteRunnerStoresDuplicatesAndSenderCopiesSilently() = runBlocking {
        val store = Store(); val controller = BackgroundSyncController(store, Scheduler(), { saved })
        controller.setEnabled(true, true)
        var alerts = 0
        val backend = Backend(owner)
        val incoming = rumor().copy(createdAtSeconds = System.currentTimeMillis() / 1000 + 2)
        runtime(backend, alert = { alerts++ }).use { runtime ->
            val runner = BackgroundSyncRunner(controller, { runtime }, { _, _, stored ->
                repeat(3) { stored(incoming); stored(rumor(true)) }; true
            }, now = { 1_100_000 })
            repeat(2) { assertEquals(BackgroundSyncRunner.Outcome.DONE, runner.run()) }
            assertEquals(2, runtime.state.value.messages.size)
            assertEquals(0, alerts)
            assertEquals(1, runtime.state.value.messages.count { it.outgoing })
            assertTrue(runtime.state.value.messages.single { it.outgoing }.status.contains("sender copy"))
            assertEquals(1_100_000L, store.value.lastSuccessSeconds)
            runtime.start()
            backend.received(incoming)
            assertEquals(0, alerts) // A later live replay must remain silent too.
            assertEquals(2, runtime.state.value.messages.size)
        }
    }
    @Test fun catchUpRefreshesAnAlreadyOpenLatestThread() = runBlocking {
        runtime().use { runtime ->
            runtime.loadConversation(NostrNpub.encode(ByteArray(32) { 2 }))
            assertEquals(peer, runtime.state.value.threadPeer)
            assertTrue(runtime.state.value.thread.isEmpty())
            runtime.backgroundCatchUp(owner) { stored -> stored(rumor()); true }
            assertEquals("private sample", runtime.state.value.thread.single().body)
            assertTrue(runtime.state.value.threadLatest)
        }
    }

    @Test fun storageFailureAndIncompleteCheckNeverAdvanceReplayBoundary() = runBlocking {
        val store = Store(); val controller = BackgroundSyncController(store, Scheduler(), { saved })
        controller.setEnabled(true, true)
        val history = object : InternetHistoryRepository by TestHistoryRepository() {
            override suspend fun received(owner: String, rumorId: String, peer: String, body: String, outgoing: Boolean, time: Long) { throw LocalHistoryException() }
        }
        runtime(history = history).use { runtime ->
            val runner = BackgroundSyncRunner(controller, { runtime }, { _, _, stored -> stored(rumor()); true })
            assertEquals(BackgroundSyncRunner.Outcome.RETRY, runner.run())
            assertEquals(0L, store.value.lastSuccessSeconds)
        }
        runtime().use { runtime ->
            assertEquals(BackgroundSyncRunner.Outcome.RETRY,
                BackgroundSyncRunner(controller, { runtime }, { _, _, _ -> false }).run())
            assertEquals(0L, store.value.lastSuccessSeconds)
        }
    }
    @Test fun activeForegroundSendCannotBeInterruptedByWorker() = runBlocking {
        val backend = Backend(owner).apply { pending = CompletableFuture() }
        runtime(backend).use { runtime ->
            runtime.start()
            runtime.send(runtime.state.value.npub, "ongoing send")
            assertTrue(runtime.state.value.busy)
            var entered = false
            assertNull(runtime.backgroundCatchUp(owner) { entered = true; true })
            assertFalse(entered)
            assertEquals(1, backend.opens)
            assertEquals(0, backend.closes)
            assertFalse(backend.pending!!.isCancelled)
            backend.pending!!.complete(NostrMessagingCoordinator.SendResult(emptyList(), emptyList()))
            Unit
        }
    }
    @Test fun foregroundWaitsForWorkerCleanupBeforeOpeningSession() = runBlocking {
        val backend = Backend(owner)
        runtime(backend).use { runtime ->
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val allowClose = CompletableDeferred<Unit>()
            var rejectedLateCallback = false
            val work = async {
                try {
                    runtime.backgroundCatchUp(owner) { stored ->
                        started.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            cleaning.complete(Unit); allowClose.await()
                            try { stored(rumor()) }
                            catch (_: CancellationException) { rejectedLateCallback = true }
                        } }
                    }
                } catch (_: BackgroundCatchUpPreempted) { null }
            }
            started.await()
            runtime.start()
            cleaning.await()
            assertEquals(0, backend.opens)
            allowClose.complete(Unit)
            work.await()
            assertTrue(rejectedLateCallback)
            assertTrue(runtime.state.value.messages.isEmpty())
            assertEquals(1, backend.opens)
            assertTrue(runtime.state.value.active)
        }
    }
    @Test fun cancellationAndDeadlineReleaseOwnershipWithoutSuccessfulCheck() = runBlocking {
        val store = Store(); val controller = BackgroundSyncController(store, Scheduler(), { saved })
        controller.setEnabled(true, true)
        val backend = Backend(owner)
        runtime(backend).use { runtime ->
            var closed = false
            val runner = BackgroundSyncRunner(controller, { runtime }, { _, _, _ ->
                try { awaitCancellation() } finally { closed = true }
            }, timeoutMillis = 50)
            assertEquals(BackgroundSyncRunner.Outcome.RETRY, runner.run())
            assertTrue(closed)
            assertEquals(0L, store.value.lastSuccessSeconds)
            val work = launch { runner.run() }
            yield(); work.cancelAndJoin()
            runtime.start()
            assertEquals(1, backend.opens)
        }
    }
    @Test fun disablingCancelsAnAdmittedWorkerAndDoesNotRecordSuccess() = runBlocking {
        val store = Store()
        runtime().use { runtime ->
            val controller = BackgroundSyncController(store, Scheduler(), { saved }, { runtime.cancelBackgroundCatchUp() })
            controller.setEnabled(true, true)
            val started = CompletableDeferred<Unit>()
            var closed = false
            val runner = BackgroundSyncRunner(controller, { runtime }, { _, _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { closed = true }
            })
            val work = async { runner.run() }
            started.await()
            assertTrue(controller.setEnabled(false, false))
            assertEquals(BackgroundSyncRunner.Outcome.DONE, work.await())
            assertTrue(closed)
            assertFalse(store.value.enabled)
            assertEquals(0L, store.value.lastSuccessSeconds)
        }
    }

    @Test fun randomizedTimestampOverlapHorizonAndClockRollback() {
        val now = 2_000_000L
        val recent = InboxReplayWindow.at(now, now - 900)
        assertTrue(recent.since <= now - 900 - 172800)
        assertEquals(now, recent.until)
        assertEquals(now - InboxReplayWindow.OVERLAP_SECONDS, InboxReplayWindow.at(now, 0).since)
        assertEquals(now - InboxReplayWindow.HORIZON_SECONDS, InboxReplayWindow.at(now, 1).since)
        assertEquals(InboxReplayWindow.at(now, 0), InboxReplayWindow.at(now, now + 100))
        assertEquals(0, InboxReplayWindow.at(50, 0).since)
    }
}
