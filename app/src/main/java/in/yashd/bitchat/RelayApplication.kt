package `in`.yashd.bitchat

import android.app.Application
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

/** Stage 1: process foreground owns Internet reception, independently of the BLE service.
 * ProcessLifecycleOwner bridges configuration changes and brief Activity handoffs.
 * Android terminates process resources itself; onTerminate is only called in emulation.
 */
class RelayApplication : Application(), DefaultLifecycleObserver {
    val internetRuntime: InternetRuntime by lazy {
        InternetRuntime(AndroidInternetBackend(NostrIdentityManager(this)),
            history = RoomInternetHistoryRepository(this),
            configurationStore = EncryptedInternetConfigurationStore(this),
            onIncomingStored = { owner, peer, name ->
                val npub = NostrNpub.encode(peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                MessageNotifications.stored(this, MutedChatsStore.key("internet", owner, peer),
                    internetRoute(npub), name.ifBlank { "Internet conversation" })
            })
    }

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { internetRuntime.start() }
    override fun onResume(owner: LifecycleOwner) { MessageNotifications.foreground = true }
    override fun onStop(owner: LifecycleOwner) {
        MessageNotifications.foreground = false
        internetRuntime.stop()
    }
    override fun onTerminate() {
        internetRuntime.close()
        super.onTerminate()
    }
}
