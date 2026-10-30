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
    private val _lists = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val lists = _lists.asStateFlow()
    private val _membership = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val membership = _membership.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load() } }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
            _lists.value = obj.optJSONArray("lists")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
            }.orEmpty()
            val m = obj.optJSONObject("membership")
            _membership.value = m?.let { mm ->
                mm.keys().asSequence().associateWith { k ->
                    mm.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                }
            }.orEmpty()
        } catch (_: Exception) {
            storageError = true
        }
    }

    private fun write(): Boolean {
        if (storageError) return false
        return try {
            val lists = JSONArray()
            _lists.value.forEach { (id, name) -> lists.put(JSONObject().put("id", id).put("name", name)) }
            val membership = JSONObject()
            _membership.value.forEach { (route, ids) ->
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

    fun create(name: String): Boolean = synchronized(LOCK) {
        val trimmed = name.trim().take(30)
        if (storageError || trimmed.isEmpty()) return@synchronized false
        load()
        if (storageError) return@synchronized false
        _lists.value = _lists.value + (UUID.randomUUID().toString() to trimmed)
        write()
    }

    fun deleteList(listId: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) return@synchronized false
        _lists.value = _lists.value.filter { it.first != listId }
        _membership.value = _membership.value.mapValues { (_, ids) -> ids - listId }.filterValues { it.isNotEmpty() }
        write()
    }

    fun toggle(route: String, listId: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) return@synchronized false
        val current = _membership.value[route].orEmpty()
        val next = if (listId in current) current - listId else current + listId
        _membership.value = if (next.isEmpty()) _membership.value - route else _membership.value + (route to next)
        write()
    }

    fun removeRoute(route: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) false else if (route !in _membership.value) true else {
            _membership.value = _membership.value - route
            write()
        }
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items.enc"
        private const val BINDING = "chat-lists-v1"
    }
}
