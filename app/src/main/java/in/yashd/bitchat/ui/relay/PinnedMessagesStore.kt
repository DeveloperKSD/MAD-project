package `in`.yashd.bitchat.ui.relay

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which messages are pinned inside each chat.
 * Stored encrypted (same approach as PinnedChatsStore) and kept across restarts.
 * Keys are chat routes such as "nearby:<peerId>" or "internet:<npub>"; values are message ids,
 * oldest pin first. Only ids are stored, never message text.
 * If storage fails, message pinning simply stops working; no chat data is touched.
 */
class PinnedMessagesStore(
    context: Context,
    private val cipher: HistoryCipher = HistoryCipher("relay.message.pins.aes.v1")
) {
    private val prefs = context.applicationContext.getSharedPreferences("pinned_messages", Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val pinned: StateFlow<Map<String, List<String>>> = _pinned

    init { load() }

    /** Pins or unpins [messageId] in [route]. Returns true when the message is pinned afterwards. */
    fun toggle(route: String, messageId: String): Boolean {
        val all = _pinned.value
        val current = all[route].orEmpty()
        val updated = if (messageId in current) current - messageId else current + messageId
        write(if (updated.isEmpty()) all - route else all + (route to updated))
        return messageId in _pinned.value[route].orEmpty()
    }

    /** Forgets one pinned message (for example after it was deleted). */
    fun removeMessage(route: String, messageId: String) {
        val all = _pinned.value
        val current = all[route] ?: return
        if (messageId !in current) return
        val updated = current - messageId
        write(if (updated.isEmpty()) all - route else all + (route to updated))
    }

    /** Forgets every pinned message of a deleted chat. */
    fun removeChat(route: String) {
        val all = _pinned.value
        if (route in all) write(all - route)
    }

    private fun write(map: Map<String, List<String>>) {
        try {
            val root = JSONObject()
            map.forEach { (route, ids) -> root.put(route, JSONArray().also { a -> ids.forEach(a::put) }) }
            if (!cipher.exists()) cipher.create()
            val encrypted = cipher.encrypt(root.toString(), BINDING).encode64()
            check(prefs.edit().putString(KEY, encrypted).commit())
            _pinned.value = map
        } catch (_: Exception) {
            // Keep the previous pins; the chat keeps working.
        }
    }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val root = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
            val result = LinkedHashMap<String, List<String>>()
            root.keys().forEach { route ->
                val array = root.getJSONArray(route)
                val ids = (0 until array.length()).map { array.getString(it) }.distinct()
                if (ids.isNotEmpty()) result[route] = ids
            }
            _pinned.value = result
        } catch (_: Exception) {
            _pinned.value = emptyMap()
        }
    }

    private companion object {
        const val KEY = "items.enc"
        const val BINDING = "pinned-messages-v1"
    }
}
