package `in`.yashd.bitchat.ui.relay

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Local preferences only; never owns messages, identities, or pins. */
class FavoriteChatsStore(
    context: Context,
    private val cipher: HistoryCipher = HistoryCipher("relay.chat.favorites.aes.v1")
) {
    private val prefs = context.applicationContext.getSharedPreferences("favorite_chats", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Set<String>>(emptySet())
    val favorites = mutable.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load() } }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null)
            if (encrypted == null) mutable.value = emptySet()
            else {
                val array = JSONArray(cipher.decrypt(encrypted.decode64(), BINDING))
                mutable.value = (0 until array.length()).map { array.getString(it) }.toSet()
            }
        } catch (_: Exception) {
            // Fail closed: subsequent toggles must not replace unreadable saved preferences.
            storageError = true
        }
    }

    /** Reload under the shared lock so an older UI instance cannot overwrite newer choices. */
    fun toggle(key: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) false else write(
            if (key in mutable.value) mutable.value - key else mutable.value + key
        )
    }

    fun remove(key: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) false else if (key !in mutable.value) true else write(mutable.value - key)
    }

    private fun write(next: Set<String>): Boolean {
        if (storageError) return false
        return try {
            val array = JSONArray()
            next.sorted().forEach(array::put)
            if (!cipher.exists()) {
                check(!prefs.contains(KEY)) // Never replace a missing key for saved data.
                cipher.create()
            }
            check(prefs.edit().putString(KEY, cipher.encrypt(array.toString(), BINDING).encode64()).commit())
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
        private const val BINDING = "favorite-chats-v1"

        /** Length-prefixed fields distinguish transport, local owner, recipient, and public group. */
        fun key(transport: String, owner: String, recipient: String?): String {
            require(transport == "nearby" || transport == "internet")
            require(owner.isNotBlank())
            require(recipient == null && transport == "nearby" || !recipient.isNullOrBlank())
            return listOf("v1", transport, owner, if (recipient == null) "group" else "peer", recipient.orEmpty())
                .joinToString("") { "${it.length}:$it" }
        }
    }
}
