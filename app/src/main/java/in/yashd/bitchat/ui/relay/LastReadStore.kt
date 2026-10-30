package `in`.yashd.bitchat.ui.relay

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Last-read timestamp per chat route, encrypted so unread counts survive restarts. */
class LastReadStore(
    context: Context,
    private val cipher: HistoryCipher = HistoryCipher("relay.chat.lastread.aes.v1")
) {
    private val prefs = context.applicationContext.getSharedPreferences("last_read", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val seen = mutable.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load() } }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null)
            if (encrypted == null) mutable.value = emptyMap()
            else {
                val obj = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
                mutable.value = obj.keys().asSequence().associateWith { obj.getLong(it) }
            }
        } catch (_: Exception) {
            // Fail closed: later marks must not replace unreadable saved state.
            storageError = true
        }
    }

    /** Reload under the shared lock so an older UI instance cannot overwrite newer state. */
    fun mark(route: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) false else write(mutable.value + (route to System.currentTimeMillis()))
    }

    fun remove(route: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) false else if (route !in mutable.value) true else write(mutable.value - route)
    }

    private fun write(next: Map<String, Long>): Boolean {
        if (storageError) return false
        return try {
            val obj = JSONObject()
            next.forEach { (route, at) -> obj.put(route, at) }
            if (!cipher.exists()) {
                check(!prefs.contains(KEY)) // Never replace a missing key for saved data.
                cipher.create()
            }
            check(prefs.edit().putString(KEY, cipher.encrypt(obj.toString(), BINDING).encode64()).commit())
            mutable.value = next
            true
        } catch (_: Exception) {
            storageError = true
            false
        }
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items.enc"
        private const val BINDING = "last-read-v1"
    }
}
