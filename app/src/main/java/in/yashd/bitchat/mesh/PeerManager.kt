package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.PeerIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PeerManager {
    private val peersById = LinkedHashMap<String, PeerState>()
    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    val peers: StateFlow<List<PeerState>> = _peers

    fun upsertAnnouncement(identity: PeerIdentity, deviceAddress: String?, rssi: Int?) {
        val existing = peersById[identity.peerId]
        val firstSeen = existing?.identity?.firstSeenMs ?: identity.firstSeenMs
        val updatedIdentity = identity.copy(firstSeenMs = firstSeen, lastSeenMs = System.currentTimeMillis())
        peersById[identity.peerId] = PeerState(
            identity = updatedIdentity,
            state = if (identity.verified) PeerConnectionState.Authenticated else PeerConnectionState.Discovered,
            directDeviceAddress = deviceAddress,
            rssi = rssi,
            lastConnectedMs = existing?.lastConnectedMs,
            lastDisconnectedMs = existing?.lastDisconnectedMs,
            failureReason = null
        )
        publish()
    }

    fun markConnected(peerId: String?, deviceAddress: String?, rssi: Int?) {
        if (peerId == null) return
        peersById[peerId]?.let {
            peersById[peerId] = it.copy(
                state = PeerConnectionState.Connected,
                directDeviceAddress = deviceAddress,
                rssi = rssi,
                lastConnectedMs = System.currentTimeMillis(),
                failureReason = null
            )
            publish()
        }
    }

    fun markDisconnected(peerId: String?, reason: String?) {
        if (peerId == null) return
        peersById[peerId]?.let {
            peersById[peerId] = it.copy(
                state = PeerConnectionState.Disconnected,
                lastDisconnectedMs = System.currentTimeMillis(),
                failureReason = reason
            )
            publish()
        }
    }

    fun get(peerId: String): PeerState? = peersById[peerId]

    private fun publish() {
        _peers.value = peersById.values.sortedByDescending { it.identity.lastSeenMs }
    }
}
