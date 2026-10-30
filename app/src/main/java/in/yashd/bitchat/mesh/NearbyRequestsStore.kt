package `in`.yashd.bitchat.mesh

import android.content.Context
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nearby chat requests: which peers may message straight into the chat list, whose first
 * message is waiting as a request, and who already accepted one of ours. Encrypted storage.
 */
class NearbyRequestsStore(context: Context, private val cipher: HistoryCipher = HistoryCipher("relay.nearby.requests.aes.v1")) {
    private val prefs = context.applicationContext.getSharedPreferences("nearby_requests", Context.MODE_PRIVATE)
    private val _accepted = MutableStateFlow<Set<String>>(emptySet())
    val accepted = _accepted.asStateFlow()
    private val _pending = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pending = _pending.asStateFlow()
    private val _acceptedBy = MutableStateFlow<Set<String>>(emptySet())
    val acceptedBy = _acceptedBy.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load() } }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
            _accepted.value = obj.optJSONArray("accepted")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val p = obj.optJSONObject("pending")
            _pending.value = p?.let { it.keys().asSequence().associateWith { k -> p.getLong(k) } }.orEmpty()
            _acceptedBy.value = obj.optJSONArray("acceptedBy")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        } catch (_: Exception) {
            // Fail closed: unreadable state must not be replaced; requests fall back to gating.
            storageError = true
        }
    }

    private fun write(): Boolean {
        if (storageError) return false
        return try {
            val obj = JSONObject()
                .put("accepted", JSONArray().also { a -> _accepted.value.sorted().forEach(a::put) })
                .put("pending", JSONObject().also { o -> _pending.value.forEach { (k, v) -> o.put(k, v) } })
                .put("acceptedBy", JSONArray().also { a -> _acceptedBy.value.sorted().forEach(a::put) })
            if (!cipher.exists()) cipher.create()
            check(prefs.edit().putString(KEY, cipher.encrypt(obj.toString(), BINDING).encode64()).commit())
            true
        } catch (_: Exception) {
            storageError = true
            false
        }
    }

    /** Peers with existing history keep their chats after the update. */
    fun seedAccepted(peerIds: Set<String>) = synchronized(LOCK) {
        if (storageError || prefs.contains(KEY)) return@synchronized
        _accepted.value = peerIds
        write()
    }

    fun isAccepted(peerId: String): Boolean = storageError || peerId in _accepted.value

    fun accept(peerId: String) = synchronized(LOCK) {
        if (storageError) return@synchronized
        _accepted.value = _accepted.value + peerId
        _pending.value = _pending.value - peerId
        write()
    }

    fun decline(peerId: String) = synchronized(LOCK) {
        if (storageError) return@synchronized
        _pending.value = _pending.value - peerId
        write()
    }

    /** A new incoming request; returns true when it is genuinely new (worth notifying). */
    fun addPending(peerId: String, atMs: Long): Boolean = synchronized(LOCK) {
        if (storageError || peerId in _accepted.value) return@synchronized false
        val fresh = peerId !in _pending.value
        if (fresh) { _pending.value = _pending.value + (peerId to atMs); write() }
        fresh
    }

    /** Their acceptance of our request; returns true the first time (worth notifying). */
    fun markAcceptedBy(peerId: String): Boolean = synchronized(LOCK) {
        if (storageError || peerId in _acceptedBy.value) return@synchronized false
        _acceptedBy.value = _acceptedBy.value + peerId
        write()
        true
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items.enc"
        private const val BINDING = "nearby-requests-v1"
    }
}
