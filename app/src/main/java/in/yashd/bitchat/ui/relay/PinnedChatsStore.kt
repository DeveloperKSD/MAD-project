package `in`.yashd.bitchat.ui.relay

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Remembers which chats are pinned to the top of the Chats list.
 * Stored encrypted (same approach as the Nearby message store) and kept across restarts.
 * Entries are chat routes such as "nearby:<peerId>" or "internet:<npub>".
 * If storage fails, pinning simply stops working; no chat data is touched.
 */
class PinnedChatsStore(
    context: Context,
    private val cipher: HistoryCipher = HistoryCipher("relay.chat.pins.aes.v1")
) {
    private val prefs = context.applicationContext.getSharedPreferences("pinned_chats", Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow<List<String>>(emptyList())
    val pinned: StateFlow<List<String>> = _pinned

    init { load() }

    /** Pins or unpins [route]. Returns true when the chat is pinned afterwards. */
    fun toggle(route: String): Boolean {
        val current = _pinned.value
        write(if (route in current) current - route else current + route)
        return route in _pinned.value
    }

    fun remove(route: String) {
        val current = _pinned.value
        if (route in current) write(current - route)
    }

    private fun write(list: List<String>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(it) }
            if (!cipher.exists()) cipher.create()
            val encrypted = cipher.encrypt(array.toString(), BINDING).encode64()
            check(prefs.edit().putString(KEY, encrypted).commit())
            _pinned.value = list
        } catch (_: Exception) {
            // Keep the previous pins; the chat list keeps working.
        }
    }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val array = JSONArray(cipher.decrypt(encrypted.decode64(), BINDING))
            _pinned.value = (0 until array.length()).map { array.getString(it) }.distinct()
        } catch (_: Exception) {
            _pinned.value = emptyList()
        }
    }

    private companion object {
        const val KEY = "items.enc"
        const val BINDING = "pinned-chats-v1"
    }
}
