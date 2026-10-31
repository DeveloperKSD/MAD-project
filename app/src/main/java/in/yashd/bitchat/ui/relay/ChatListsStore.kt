package `in`.yashd.bitchat.ui.relay

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Custom chat lists (Work, Family, ...) shown as filter chips. Local only, encrypted. */
class ChatListsStore(
    context: Context,
    private val cipher: HistoryCipher = HistoryCipher("relay.chat.lists.aes.v1")
) {
    private val prefs = context.applicationContext.getSharedPreferences("chat_lists", Context.MODE_PRIVATE)
    private var state = ChatListsState()
    private val _lists = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val lists = _lists.asStateFlow()
    private val _membership = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val membership = _membership.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load(); publish() } }

    private fun publish() {
        _lists.value = state.lists
        _membership.value = state.membership
    }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
            val lists = obj.optJSONArray("lists")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
            }.orEmpty()
            val m = obj.optJSONObject("membership")
            val membership = m?.let { mm ->
                mm.keys().asSequence().associateWith { k ->
                    mm.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                }
            }.orEmpty()
            state = ChatListsState(lists, membership)
        } catch (_: Exception) {
            storageError = true
        }
    }

    private fun write(): Boolean {
        if (storageError) return false
        return try {
            val lists = JSONArray()
            state.lists.forEach { (id, name) -> lists.put(JSONObject().put("id", id).put("name", name)) }
            val membership = JSONObject()
            state.membership.forEach { (route, ids) ->
                membership.put(route, JSONArray().also { a -> ids.sorted().forEach(a::put) })
            }
            val obj = JSONObject().put("lists", lists).put("membership", membership)
            if (!cipher.exists()) {
                check(!prefs.contains(KEY))
                cipher.create()
            }
            check(prefs.edit().putString(KEY, cipher.encrypt(obj.toString(), BINDING).encode64()).commit())
            true
        } catch (_: Exception) {
            storageError = true
            false
        }
    }

    /** Reload under the shared lock first so an older UI instance cannot overwrite newer state. */
    private fun mutate(transform: (ChatListsState) -> ChatListsState?): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) return@synchronized false
        val next = transform(state) ?: return@synchronized false
        state = next
        val ok = write()
        publish()
        ok
    }

    fun create(name: String): Boolean = mutate { ChatListsCore.create(it, UUID.randomUUID().toString(), name) }
    fun deleteList(listId: String): Boolean = mutate { ChatListsCore.deleteList(it, listId) }
    fun toggle(route: String, listId: String): Boolean = mutate { ChatListsCore.toggle(it, route, listId) }
    fun removeRoute(route: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) return@synchronized false
        if (route !in state.membership) return@synchronized true
        state = ChatListsCore.removeRoute(state, route)
        val ok = write()
        publish()
        ok
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items.enc"
        private const val BINDING = "chat-lists-v1"
    }
}
