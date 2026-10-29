package `in`.yashd.bitchat

import android.app.Application
import androidx.work.WorkManager
import `in`.yashd.bitchat.background.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import `in`.yashd.bitchat.data.internet.EncryptedInternetConfigurationStore
import `in`.yashd.bitchat.data.internet.RoomInternetHistoryRepository
import `in`.yashd.bitchat.nostr.NostrIdentityManager
import `in`.yashd.bitchat.nostr.NostrNpub
import `in`.yashd.bitchat.notifications.MessageNotifications
import `in`.yashd.bitchat.ui.relay.AndroidInternetBackend
import `in`.yashd.bitchat.ui.relay.InternetRuntime
import `in`.yashd.bitchat.ui.relay.MutedChatsStore

/** Process foreground owns live reception; periodic workers borrow exclusive finite reception.
 * ProcessLifecycleOwner bridges configuration changes and brief Activity handoffs.
 * Android terminates process resources itself; onTerminate is only called in emulation.
 */
class RelayApplication : Application(), DefaultLifecycleObserver {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var mayCreateIdentity = false
    private val runtimeDelegate = lazy {
        InternetRuntime(AndroidInternetBackend(NostrIdentityManager(this), { mayCreateIdentity }),
            history = RoomInternetHistoryRepository(this),
            configurationStore = EncryptedInternetConfigurationStore(this),
            onIncomingStored = { owner, peer, name ->
                val npub = NostrNpub.encode(peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                MessageNotifications.stored(this, MutedChatsStore.key("internet", owner, peer),
                    internetRoute(npub), name.ifBlank { "Internet conversation" })
            }).also { runtime ->
                applicationScope.launch {
                    runtime.state.map { Triple(it.configurationLoading, it.configurationError, it.discovery to it.inboxes) }
                        .distinctUntilChanged().collect { backgroundSync.reconcile() }
                }
            }
    }
    val internetRuntime: InternetRuntime get() = runtimeDelegate.value
    val backgroundSync: BackgroundSyncController by lazy {
        BackgroundSyncController(FileBackgroundSyncStore(this),
            WorkManagerBackgroundSyncScheduler(WorkManager.getInstance(this)), {
                NostrIdentityManager(this).loadExisting()?.let { identity ->
                    EncryptedInternetConfigurationStore(this).load(identity.publicKeyHex)?.let {
                        SavedSyncConfiguration(identity.publicKeyHex, it)
                    }
                }
            }, { if (runtimeDelegate.isInitialized()) internetRuntime.cancelBackgroundCatchUp() })
    }

    /** Only an Activity entry point may authorize first-use identity creation. */
    fun prepareForegroundIdentity() { mayCreateIdentity = true }

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch { backgroundSync.reconcile() }
    }

    override fun onStart(owner: LifecycleOwner) {
        prepareForegroundIdentity()
        internetRuntime.start()
    }
    override fun onResume(owner: LifecycleOwner) { MessageNotifications.foreground = true }
    override fun onStop(owner: LifecycleOwner) {
        MessageNotifications.foreground = false
        internetRuntime.stop()
    }
    override fun onTerminate() {
        if (runtimeDelegate.isInitialized()) internetRuntime.close()
        applicationScope.cancel()
        super.onTerminate()
    }
}
