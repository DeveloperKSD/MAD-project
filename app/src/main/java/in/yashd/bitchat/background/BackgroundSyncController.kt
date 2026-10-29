package `in`.yashd.bitchat.background

import `in`.yashd.bitchat.data.internet.InternetConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

/** No secrets, server URLs, message text or peer identifiers in scheduling metadata. */
data class BackgroundSyncDecision(val enabled: Boolean = false, val binding: String = "",
                                  val approval: String = "", val lastSuccessSeconds: Long = 0)
interface BackgroundSyncStore {
    fun read(): BackgroundSyncDecision
    fun write(value: BackgroundSyncDecision)
}
interface BackgroundSyncScheduler {
    suspend fun ensureScheduled()
    suspend fun cancel()
}
data class SavedSyncConfiguration(val owner: String, val configuration: InternetConfiguration) {
    fun binding(): String {
        require(owner.matches(Regex("[0-9a-f]{64}")))
        configuration.validated()
        val value = listOf(owner, configuration.discovery.joinToString("\n"), configuration.inboxes.joinToString("\n")).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
data class BackgroundSyncStatus(val enabled: Boolean = false, val text: String = "Disabled", val lastSuccessSeconds: Long = 0)
data class BackgroundSyncTicket(val decision: BackgroundSyncDecision, val saved: SavedSyncConfiguration)

/** Reconciled on app launch and before EVERY worker. KEEP preserves the existing periodic
 * schedule over repeated launches/upgrades. Changed configuration needs a fresh explicit opt-in.
 */
class BackgroundSyncController(
    private val store: BackgroundSyncStore,
    private val scheduler: BackgroundSyncScheduler,
    private val loadSaved: () -> SavedSyncConfiguration?,
    private val cancelRunning: () -> Unit = {}
) {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(BackgroundSyncStatus())
    val state = mutable.asStateFlow()

    suspend fun reconcile(): BackgroundSyncTicket? = gate.withLock { guarded { reconcileLocked() } }

    suspend fun setEnabled(enabled: Boolean, setupReady: Boolean): Boolean = gate.withLock {
        guarded {
            if (!enabled) {
                store.write(BackgroundSyncDecision())
                cancelRunning()
                scheduler.cancel()
                mutable.value = BackgroundSyncStatus()
                return@guarded true
            }
            if (!setupReady) return@guarded false
            val saved = loadSaved() ?: return@guarded false
            val decision = BackgroundSyncDecision(true, saved.binding(), UUID.randomUUID().toString())
            store.write(decision)
            reconcileLocked() != null
        } ?: false
    }

    private suspend fun reconcileLocked(): BackgroundSyncTicket? {
        val decision = store.read()
        if (!decision.enabled) {
            scheduler.cancel()
            mutable.value = BackgroundSyncStatus()
            return null
        }
        val saved = loadSaved()
        if (saved == null || saved.binding() != decision.binding) {
            cancelRunning()
            scheduler.cancel()
            mutable.value = BackgroundSyncStatus(true, "Paused · finish Internet Setup, then switch off and on again")
            return null
        }
        scheduler.ensureScheduled()
        mutable.value = BackgroundSyncStatus(true, "Enabled · checks may be delayed", decision.lastSuccessSeconds)
        return BackgroundSyncTicket(decision, saved)
    }

    suspend fun finished(ticket: BackgroundSyncTicket, startedAtSeconds: Long, complete: Boolean) = gate.withLock {
        guarded {
            val current = store.read()
            if (!current.enabled || current.approval != ticket.decision.approval ||
                loadSaved()?.binding() != ticket.decision.binding) return@guarded
            val next = if (complete) current.copy(lastSuccessSeconds = startedAtSeconds) else current
            if (complete) store.write(next)
            mutable.value = BackgroundSyncStatus(true,
                if (complete) "Enabled" else "Enabled · check incomplete; will try later", next.lastSuccessSeconds)
        }
        Unit
    }

    private suspend fun <T> guarded(action: suspend () -> T): T? = try { action() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        cancelRunning()
        try { scheduler.cancel() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* WorkManager may itself be unavailable; eligibility still fails closed. */ }
        // Keep all damaged files/keys. Never reinterpret failed reads as a fresh install.
        mutable.value = BackgroundSyncStatus(text = "Unavailable · saved settings were preserved")
        null
    }
}
