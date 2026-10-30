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
 * message is waiting as a request, who already accepted one of ours, and which signed
 * accepts still need delivery. Encrypted storage; decisions live in NearbyRequestsCore.
 */
class NearbyRequestsStore(context: Context, private val cipher: HistoryCipher = HistoryCipher("relay.nearby.requests.aes.v1")) {
    private val prefs = context.applicationContext.getSharedPreferences("nearby_requests", Context.MODE_PRIVATE)
    private var state = NearbyRequestsState()
    private val _accepted = MutableStateFlow<Set<String>>(emptySet())
    val accepted = _accepted.asStateFlow()
    private val _pending = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pending = _pending.asStateFlow()
    private val _acceptedBy = MutableStateFlow<Set<String>>(emptySet())
    val acceptedBy = _acceptedBy.asStateFlow()
    private val _acceptOutbox = MutableStateFlow<Set<String>>(emptySet())
    val acceptOutbox = _acceptOutbox.asStateFlow()
    var storageError = false
        private set

    init { synchronized(LOCK) { load(); publish() } }

    private fun publish() {
        _accepted.value = state.accepted
        _pending.value = state.pending
        _acceptedBy.value = state.acceptedBy
        _acceptOutbox.value = state.acceptOutbox
    }

    private fun load() {
        try {
            val encrypted = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(cipher.decrypt(encrypted.decode64(), BINDING))
            val pendingObj = obj.optJSONObject("pending")
            state = NearbyRequestsState(
                accepted = obj.optJSONArray("accepted")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                pending = pendingObj?.let { it.keys().asSequence().associateWith { k -> pendingObj.getLong(k) } }.orEmpty(),
                acceptedBy = obj.optJSONArray("acceptedBy")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                acceptOutbox = obj.optJSONArray("acceptOutbox")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            )
        } catch (_: Exception) {
            // Fail closed: unreadable state must not be replaced; requests fall back to gating.
            storageError = true
        }
    }

    private fun write(): Boolean {
        if (storageError) return false
        return try {
            val obj = JSONObject()
                .put("accepted", JSONArray().also { a -> state.accepted.sorted().forEach(a::put) })
                .put("pending", JSONObject().also { o -> state.pending.forEach { (k, v) -> o.put(k, v) } })
                .put("acceptedBy", JSONArray().also { a -> state.acceptedBy.sorted().forEach(a::put) })
                .put("acceptOutbox", JSONArray().also { a -> state.acceptOutbox.sorted().forEach(a::put) })
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
        state = NearbyRequestsCore.seedAccepted(state, peerIds, alreadyStored = false)
        write()
        publish()
    }

    fun isAccepted(peerId: String): Boolean = storageError || NearbyRequestsCore.isAccepted(state, peerId)

    /**
     * [notifyPeer] queues a signed accept for delivery (Accept in the Requests page);
     * starting a chat ourselves passes false so no spurious accept reaches them.
     */
    fun accept(peerId: String, notifyPeer: Boolean = false) = synchronized(LOCK) {
        if (storageError) return@synchronized
        state = NearbyRequestsCore.accept(state, peerId, notifyPeer)
        write()
        publish()
    }

    /** The signed accept was handed to the mesh; stop retrying it. */
    fun acceptDispatched(peerId: String) = synchronized(LOCK) {
        if (storageError) return@synchronized
        state = NearbyRequestsCore.acceptDispatched(state, peerId)
        write()
        publish()
    }

    fun decline(peerId: String) = synchronized(LOCK) {
        if (storageError) return@synchronized
        state = NearbyRequestsCore.decline(state, peerId)
        write()
        publish()
    }

    /** A new incoming request; returns true when it is genuinely new (worth notifying). */
    fun addPending(peerId: String, atMs: Long): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        val (next, fresh) = NearbyRequestsCore.addPending(state, peerId, atMs)
        if (fresh) {
            state = next
            write()
            publish()
        }
        fresh
    }

    /** Their acceptance of our request; returns true the first time (worth notifying). */
    fun markAcceptedBy(peerId: String): Boolean = synchronized(LOCK) {
        if (storageError) return@synchronized false
        val (next, first) = NearbyRequestsCore.markAcceptedBy(state, peerId)
        if (first) {
            state = next
            write()
            publish()
        }
        first
    }

    companion object {
        private val LOCK = Any()
        private const val KEY = "items.enc"
        private const val BINDING = "nearby-requests-v1"
    }
}
