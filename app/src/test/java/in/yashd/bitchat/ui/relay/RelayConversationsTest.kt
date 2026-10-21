package `in`.yashd.bitchat.ui.relay

import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.*
import org.junit.Assert.*
import org.junit.Test

class RelayConversationsTest {
    private val peer = PeerState(PeerIdentity("friend", "Aarav", byteArrayOf(), byteArrayOf(), 0, 0, true),
        PeerConnectionState.Authenticated, null, -60, null, null, null)
    private fun message(id: String, sender: String, target: String?, private: Boolean, direction: MessageDirection) =
        ChatMessage(id, null, sender, target, "Hello $id", 1, null, private, MessageStatus.Pending, direction)
    private val mesh get() = MeshUiState(localPeerId = "me", peers = listOf(peer), currentConversation = listOf(
        message("group", "me", null, false, MessageDirection.Outbound),
        message("private", "me", "friend", true, MessageDirection.Outbound),
        message("reply", "friend", "me", true, MessageDirection.Inbound)))
    private val internet = RelayUiState(messages = listOf(InternetMessage("internet", "01".repeat(32), "Online hello", false, "Received")))
    @Test fun combinesNearbyGroupPrivateAndInternetWithoutDuplicatingNearbyPeer() {
        val rows = relayConversations(internet, mesh)
        assertEquals(3, rows.size)
        assertEquals(2, rows.count { it.transport == ConversationTransport.Nearby })
        assertEquals("Hello reply", rows.single { it.recipient == "friend" }.preview)
        assertEquals("Aarav", rows.single { it.recipient == "friend" }.title)
    }
    @Test fun allAndTransportFiltersStayIndependentOfSearch() {
        val rows = relayConversations(internet, mesh)
        assertEquals(3, filterConversations(rows, ConversationFilter.All, "").size)
        assertEquals(2, filterConversations(rows, ConversationFilter.Nearby, "").size)
        assertEquals(1, filterConversations(rows, ConversationFilter.Internet, "").size)
        assertEquals("friend", filterConversations(rows, ConversationFilter.All, " aaRAv ").single().recipient)
        assertEquals(0, filterConversations(rows, ConversationFilter.Internet, "Aarav").size)
        assertEquals(1, filterConversations(rows, ConversationFilter.All, "Online").size)
        assertTrue(filterConversations(rows, ConversationFilter.All, "missing").isEmpty())
    }
    @Test fun disconnectedConversationRemainsAccessibleById() {
        val rows = relayConversations(internet, mesh.copy(peers = emptyList()))
        assertEquals("friend", rows.single { it.recipient == "friend" }.title)
    }
    @Test fun sameIdentifierOnDifferentTransportsNeverMergesRoutes() {
        val id = "01".repeat(32)
        val nearby = mesh.copy(currentConversation = listOf(message("private", "me", id, true, MessageDirection.Outbound)))
        assertEquals(2, relayConversations(internet, nearby).map { it.id }.distinct().size)
    }
    @Test fun unknownPrivateRecipientNeverCallsTransportWithPublicFallback() {
        var calls = 0
        assertFalse(sendNearbySafely("secret", "unknown", listOf(peer)) { _, _ -> calls++ })
        assertFalse(sendNearbySafely("secret", "friend", emptyList()) { _, _ -> calls++ })
        assertEquals(0, calls)
    }
    @Test fun knownPrivateRecipientAndExplicitPublicGroupKeepTheirTargets() {
        val targets = mutableListOf<String?>()
        assertTrue(sendNearbySafely("secret", "friend", listOf(peer)) { _, target -> targets.add(target) })
        assertTrue(sendNearbySafely("hello group", null, emptyList()) { _, target -> targets.add(target) })
        assertEquals(listOf("friend", null), targets)
        assertFalse(sendNearbySafely(" ", null, emptyList()) { _, _ -> fail("Blank message sent") })
    }
}
