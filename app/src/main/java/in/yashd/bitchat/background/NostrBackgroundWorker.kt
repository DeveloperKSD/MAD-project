package `in`.yashd.bitchat.background

import android.content.Context
import androidx.work.*
import `in`.yashd.bitchat.RelayApplication
import `in`.yashd.bitchat.nostr.*
import `in`.yashd.bitchat.ui.relay.BackgroundCatchUpPreempted
import `in`.yashd.bitchat.ui.relay.InternetRuntime
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WorkManagerBackgroundSyncScheduler(private val manager: WorkManager,
                                         private val workName: String = WORK_NAME) : BackgroundSyncScheduler {
    override suspend fun ensureScheduled() {
        runInterruptible(Dispatchers.IO) {
            manager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.KEEP, request()).result.get()
        }
    }
    override suspend fun cancel() {
        runInterruptible(Dispatchers.IO) { manager.cancelUniqueWork(workName).result.get() }
    }
    companion object {
        const val WORK_NAME = "relay-nostr-inbox-catchup-v1"
        fun request() = PeriodicWorkRequestBuilder<NostrBackgroundWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
    }
}

/** Injectable runner exercises the same worker policy without connecting to public relays. */
class BackgroundSyncRunner(
    private val controller: BackgroundSyncController,
    private val runtime: () -> InternetRuntime,
    private val transport: suspend (BackgroundSyncTicket, InboxReplayWindow, suspend (UnsignedNostrEvent) -> Unit) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val timeoutMillis: Long = 240_000
) {
    enum class Outcome { DONE, RETRY }
    suspend fun run(): Outcome {
        val ticket = controller.reconcile() ?: return Outcome.DONE
        val start = now()
        return try {
            val complete = withTimeout(timeoutMillis) {
                runtime().backgroundCatchUp(ticket.saved.owner) { stored ->
                    transport(ticket, InboxReplayWindow.at(start, ticket.decision.lastSuccessSeconds), stored)
                }
            }
            if (complete != null) controller.finished(ticket, start, complete)
            // Foreground already owns reception: don't wake repeatedly or disrupt a send.
            if (complete == false) Outcome.RETRY else Outcome.DONE
        } catch (_: BackgroundCatchUpPreempted) {
            currentCoroutineContext().ensureActive()
            Outcome.DONE
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            controller.finished(ticket, start, false)
            Outcome.RETRY
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            controller.finished(ticket, start, false)
            Outcome.RETRY
        }
    }
}

class NostrBackgroundWorker @JvmOverloads constructor(context: Context, parameters: WorkerParameters,
    private val runnerOverride: BackgroundSyncRunner? = null) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app by lazy { applicationContext.applicationContext as RelayApplication }
        val runner = runnerOverride ?: BackgroundSyncRunner(app.backgroundSync, { app.internetRuntime }, { ticket, window, store ->
            val identity = NostrIdentityManager(applicationContext)
            // Never create or replace keys from a background entry point.
            check(identity.loadExisting()?.publicKeyHex == ticket.saved.owner)
            val http = OkHttpClient()
            try {
                NostrMessagingCoordinator(identity, http, ticket.saved.configuration.discovery).use { coordinator ->
                    val future = coordinator.catchUp(ticket.saved.configuration.inboxes, window) { rumor ->
                        runBlocking { store(rumor) }
                    }
                    try { runInterruptible { future.get() } }
                    finally { future.cancel(true) }
                }
            } finally {
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            }
        })
        when (runner.run()) {
            BackgroundSyncRunner.Outcome.DONE -> Result.success()
            BackgroundSyncRunner.Outcome.RETRY -> if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
