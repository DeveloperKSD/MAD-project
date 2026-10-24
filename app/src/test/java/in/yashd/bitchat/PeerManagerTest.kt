package `in`.yashd.bitchat

import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.*
import org.junit.Assert.*
import org.junit.Test

class PeerManagerTest {
    private fun identity(id: String) = PeerIdentity(id, id, byteArrayOf(), byteArrayOf(), 0, 0, true)

    @Test fun relayedPeerIsNotDirectAndSurvivingRouteRemainsAvailable() {
        val peers = PeerManager()
        peers.upsertAnnouncement(identity("alice"), "relay-one", null, direct = false)
        assertNull(peers.get("alice")!!.directDeviceAddress)
        peers.upsertAnnouncement(identity("alice"), "relay-two", null, direct = false)
        peers.linkLost("relay-one")
        assertEquals(PeerConnectionState.Authenticated, peers.get("alice")!!.state)
        peers.linkLost("relay-two")
        assertEquals(PeerConnectionState.Disconnected, peers.get("alice")!!.state)
        assertFalse(`in`.yashd.bitchat.ui.relay.sendNearbySafely("hello", "alice", peers.peers.value) { _, _ -> fail("Disconnected peer must not send") })
    }

    @Test fun routesExpireAndPeerCountIsBounded() {
        val peers = PeerManager()
        peers.upsertAnnouncement(identity("alice"), "phone", null)
        assertEquals("phone", peers.get("alice")!!.directDeviceAddress)
        peers.expire(System.currentTimeMillis() + 90_001)
        assertEquals(PeerConnectionState.Disconnected, peers.get("alice")!!.state)
        repeat(300) { peers.upsertAnnouncement(identity("peer-$it"), "phone", null) }
        assertEquals(256, peers.peers.value.size)
        peers.disconnectAll()
        assertTrue(peers.peers.value.all { it.state == PeerConnectionState.Disconnected })
    }
}
