package `in`.yashd.bitchat.ui.relay

import androidx.lifecycle.ViewModel
import `in`.yashd.bitchat.data.internet.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Activity adapter. Production instances borrow the application runtime. */
class RelayViewModel private constructor(val runtime: InternetRuntime, private val ownsRuntime: Boolean) : ViewModel() {
    constructor(runtime: InternetRuntime) : this(runtime, false)

    // Isolated ownership for callers outside the application, including existing unit tests.
    constructor(backend: InternetBackend, dispatcher: CoroutineDispatcher = Dispatchers.IO,
                history: InternetHistoryRepository, configurationStore: InternetConfigurationStore? = null,
                onIncomingStored: (String, String, String) -> Unit = { _, _, _ -> }) :
        this(InternetRuntime(backend, dispatcher, history, configurationStore, onIncomingStored), true)

    val state get() = runtime.state
    fun error(message: String) = runtime.error(message)
    fun clearError() = runtime.clearError()
    fun start() = runtime.start()
    fun stop() = runtime.stop()
    fun configure(discoveryText: String, inboxText: String, onSaved: () -> Unit = {}) = runtime.configure(discoveryText, inboxText, onSaved)
    fun loadConversation(npub: String, older: Boolean = false) = runtime.loadConversation(npub, older)
    fun deleteMessage(id: String) = runtime.deleteMessage(id)
    fun deleteConversation(npub: String, onDeleted: () -> Unit = {}) = runtime.deleteConversation(npub, onDeleted)
    fun updateSetupServers(discovery: String, inbox: String) = runtime.updateSetupServers(discovery, inbox)
    fun setupBack() = runtime.setupBack()
    fun setupNext() = runtime.setupNext()
    fun finishSetup() = runtime.finishSetup()
    fun retrySetup() = runtime.retrySetup()
    suspend fun addInternetConversation(npub: String, displayName: String) = runtime.addInternetConversation(npub, displayName)
    fun announce() = runtime.announce()
    fun refresh() = runtime.refresh()
    fun send(npub: String, body: String) = runtime.send(npub, body)
    override fun onCleared() { if (ownsRuntime) runtime.close() }
    companion object {
        fun parseRelays(text: String) = InternetRuntime.parseRelays(text)
        fun recipientKey(npub: String) = InternetRuntime.recipientKey(npub)
    }
}
