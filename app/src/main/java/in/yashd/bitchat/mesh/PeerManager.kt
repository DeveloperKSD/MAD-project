package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.PeerIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PeerManager {
    private val peersById = LinkedHashMap<String, PeerState>()
    private data class Route(val seenAt: Long, val direct: Boolean)
    private val routes = mutableMapOf<String, MutableMap<String, Route>>()
    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    val peers: StateFlow<List<PeerState>> = _peers

    fun upsertAnnouncement(identity: PeerIdentity, deviceAddress: String?, rssi: Int?, direct: Boolean = true) {
        expire()
        val now = System.currentTimeMillis()
        if (deviceAddress != null) {
            val links = routes.getOrPut(identity.peerId) { mutableMapOf() }
            if (links.size < 32 || deviceAddress in links) links[deviceAddress] = Route(now, direct)
        }
        val existing = peersById[identity.peerId]
        val firstSeen = existing?.identity?.firstSeenMs ?: identity.firstSeenMs
        val updatedIdentity = identity.copy(firstSeenMs = firstSeen, lastSeenMs = System.currentTimeMillis())
        peersById[identity.peerId] = PeerState(
            identity = updatedIdentity,
            state = if (identity.verified) PeerConnectionState.Authenticated else PeerConnectionState.Discovered,
            directDeviceAddress = routes[identity.peerId]?.entries?.firstOrNull { it.value.direct }?.key,
            rssi = rssi,
            lastConnectedMs = existing?.lastConnectedMs,
            lastDisconnectedMs = existing?.lastDisconnectedMs,
            failureReason = null
        )
        while (peersById.size > 256) {
            val oldest = peersById.minBy { it.value.identity.lastSeenMs }.key
            peersById.remove(oldest)
            routes.remove(oldest)
        }
        publish()
    }

    fun get(peerId: String): PeerState? = peersById[peerId]

    fun linkLost(linkId: String) {
        routes.values.forEach { it.remove(linkId) }
        expire()
    }

    fun disconnectAll() {
        routes.clear()
        expire()
    }

    fun expire(now: Long = System.currentTimeMillis()) {
        routes.values.forEach { links -> links.entries.removeAll { now - it.value.seenAt > 90_000 } }
        peersById.replaceAll { id, peer ->
            val links = routes[id].orEmpty()
            peer.copy(state = if (links.isEmpty()) PeerConnectionState.Disconnected else PeerConnectionState.Authenticated,
                directDeviceAddress = links.entries.firstOrNull { it.value.direct }?.key)
        }
        publish()
    }

    private fun publish() {
        _peers.value = peersById.values.sortedByDescending { it.identity.lastSeenMs }
    }
}
