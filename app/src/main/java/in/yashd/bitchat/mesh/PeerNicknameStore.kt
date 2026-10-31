package `in`.yashd.bitchat.mesh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last-known display names announced by nearby peers, kept on-device only so offline
 * conversations keep a human label. Names are unverified announcements, not identities. */
class PeerNicknameStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("peer_nicknames", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val names = mutable.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load() } }

    private fun load() {
        try {
            mutable.value = PeerNicknames.decode(prefs.getString(KEY, null))
        } catch (_: Exception) {
            // Fail closed: never replace unreadable saved names.
            storageError = true
        }
    }

    /** Records a peer's announced name; blank names never overwrite a saved one. */
    fun remember(peerId: String, displayName: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        load()
        if (storageError) return@synchronized false
        val next = PeerNicknames.remember(mutable.value, peerId, displayName)
        if (next == mutable.value) true else try {
            check(prefs.edit().putString(KEY, PeerNicknames.encode(next)).commit())
            mutable.value = next
            true
        } catch (_: Exception) {
            storageError = true
            false
        }
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "names.v1"
    }
}

/** Pure map logic: length-prefixed serialization, most-recently-seen ordering, bounded size. */
object PeerNicknames {
    private const val MAX_ENTRIES = 512

    fun remember(names: Map<String, String>, peerId: String, displayName: String): Map<String, String> {
        val name = displayName.trim()
        if (name.isEmpty() || names[peerId] == name) return names
        val next = LinkedHashMap(names)
        next.remove(peerId)
        next[peerId] = name
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        return next
    }

    fun encode(names: Map<String, String>): String =
        names.entries.joinToString("") { (id, name) -> "${id.length}:$id${name.length}:$name" }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        var i = 0
        fun field(): String {
            val colon = raw.indexOf(':', i)
            require(colon > i) { "bad entry" }
            val length = raw.substring(i, colon).toInt()
            require(length >= 0 && colon + 1 + length <= raw.length) { "bad length" }
            return raw.substring(colon + 1, colon + 1 + length).also { i = colon + 1 + length }
        }
        while (i < raw.length) result[field()] = field()
        return result
    }
}
