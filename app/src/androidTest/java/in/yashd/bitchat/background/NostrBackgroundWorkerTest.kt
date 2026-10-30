package `in`.yashd.bitchat.background

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import `in`.yashd.bitchat.data.internet.*
import `in`.yashd.bitchat.nostr.*
import `in`.yashd.bitchat.ui.relay.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NostrBackgroundWorkerTest {
    @Test fun workerCompletesFiniteSilentRunAndClosesTemporaryTransport() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "01".repeat(32)
        var decision = BackgroundSyncDecision()
        val store = object : BackgroundSyncStore {
            override fun read() = decision
            override fun write(value: BackgroundSyncDecision) { decision = value }
        }
        val scheduler = object : BackgroundSyncScheduler {
            override suspend fun ensureScheduled() {}
            override suspend fun cancel() {}
        }
        val saved = SavedSyncConfiguration(owner, InternetConfiguration(listOf("wss://discovery.example"), listOf("wss://inbox.example")))
        val controller = BackgroundSyncController(store, scheduler, { saved })
        controller.setEnabled(true, true)
        val backend = object : InternetBackend {
            override fun identity() = NostrIdentity(owner, NostrNpub.encode(ByteArray(32) { 1 }))
            override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession = error("No foreground connection")
            override fun close() {}
        }
        var alerts = 0
        var closed = false
        InternetRuntime(backend, Dispatchers.IO, TestHistoryRepository(), onIncomingStored = { _, _, _ -> alerts++ }).use { runtime ->
            val runner = BackgroundSyncRunner(controller, { runtime }, { _, _, stored ->
                try {
                    val rumor = UnsignedNostrEvent("02".repeat(32), 1000, 14, listOf(listOf("p", owner)), "sample")
                    repeat(2) { stored(rumor) }
                    true
                } finally { closed = true }
            }, now = { 2000 })
            val worker = TestListenableWorkerBuilder<NostrBackgroundWorker>(context).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    NostrBackgroundWorker(appContext, workerParameters, runner)
            }).build()
            assertEquals(ListenableWorker.Result.success(), withTimeout(10_000) { worker.doWork() })
            assertTrue(closed)
            assertEquals(1, runtime.state.value.messages.size)
            assertEquals(0, alerts)
            assertEquals(2000L, decision.lastSuccessSeconds)
        }
    }
}
