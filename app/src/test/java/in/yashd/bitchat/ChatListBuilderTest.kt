package `in`.yashd.bitchat

import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.ui.relay.RelayUiState
import org.junit.Assert.*
import org.junit.Test

class ChatListBuilderTest {
    private fun msg(id: String, sender: String, to: String?, at: Long, direction: MessageDirection,
                    status: MessageStatus = MessageStatus.Delivered, receivedAt: Long? = null) =
        ChatMessage(id, null, sender, to, "body $id", at, receivedAt, to != null || direction == MessageDirection.Inbound,
            status, direction)

    private fun chatWith(vararg messages: ChatMessage) =
        buildChatList(RelayUiState(), MeshUiState(localPeerId = "me", currentConversation = messages.toList()),
            emptyMap(), sessionStart = 0L).single { it.info.target == "friend" }

    @Test fun unreadCountsOnlyWhatArrivedAfterLastOpen() {
        val items = buildChatList(
            RelayUiState(),
            MeshUiState(localPeerId = "me", currentConversation = listOf(
                msg("old", "friend", "me", 4000L, MessageDirection.Inbound, receivedAt = 4000L),
                msg("new", "friend", "me", 6000L, MessageDirection.Inbound, receivedAt = 6000L))),
            seenAt = mapOf("nearby:friend" to 5000L), sessionStart = 0L)
        assertEquals(1, items.single { it.info.target == "friend" }.unread)
    }

    @Test fun neverOpenedChatCountsFromSessionStart() {
        val items = buildChatList(
            RelayUiState(),
            MeshUiState(localPeerId = "me", currentConversation = listOf(
                msg("before", "friend", "me", 4000L, MessageDirection.Inbound, receivedAt = 4000L),
                msg("after", "friend", "me", 6000L, MessageDirection.Inbound, receivedAt = 6000L))),
            seenAt = emptyMap(), sessionStart = 5000L)
        assertEquals(1, items.single { it.info.target == "friend" }.unread)
    }

    @Test fun myOwnMessagesAreNeverUnread() {
        val item = chatWith(msg("mine", "me", "friend", 9000L, MessageDirection.Outbound))
        assertEquals(0, item.unread)
    }

    @Test fun queuedLastMessageShowsWaiting() {
        val item = chatWith(msg("mine", "me", "friend", 9000L, MessageDirection.Outbound, MessageStatus.Pending))
        assertTrue(item.waiting)
        assertFalse(item.failed)
    }

    @Test fun failedLastMessageShowsFailed() {
        val item = chatWith(msg("mine", "me", "friend", 9000L, MessageDirection.Outbound, MessageStatus.Failed))
        assertTrue(item.failed)
        assertFalse(item.waiting)
    }

    @Test fun theirLastMessageIsNeitherWaitingNorFailed() {
        val item = chatWith(msg("theirs", "friend", "me", 9000L, MessageDirection.Inbound))
        assertFalse(item.waiting)
        assertFalse(item.failed)
    }

    @Test fun chatsSortByLatestActivity() {
        val items = buildChatList(
            RelayUiState(),
            MeshUiState(localPeerId = "me", currentConversation = listOf(
                msg("a", "alice", "me", 1000L, MessageDirection.Inbound),
                msg("b", "bob", "me", 3000L, MessageDirection.Inbound))),
            emptyMap(), sessionStart = 0L)
        assertEquals(listOf("bob", "alice"), items.map { it.info.target })
    }
}

class PeerNicknameFallbackTest {
    private fun chatWith(vararg messages: ChatMessage) =
        buildChatList(RelayUiState(), MeshUiState(localPeerId = "me", currentConversation = messages.toList()),
            emptyMap(), sessionStart = 0L).single { it.info.target == "friend" }

    private fun msg(id: String, sender: String, to: String) = ChatMessage(id, null, sender, to, "body $id", 4000L, 4000L,
        true, MessageStatus.Delivered, MessageDirection.Inbound)

    @Test fun offlinePeerKeepsLastKnownName() {
        val item = buildChatList(RelayUiState(),
            MeshUiState(localPeerId = "me", knownNames = mapOf("friend" to "Mira"),
                currentConversation = listOf(msg("m", "friend", "me"))),
            emptyMap(), sessionStart = 0L).single { it.info.target == "friend" }
        assertEquals("Mira", item.info.title)
    }

    @Test fun unknownOfflinePeerFallsBackToId() {
        assertEquals("friend", chatWith(msg("m", "friend", "me")).info.title.take(12))
    }

    @Test fun livePeerNameWinsOverStoredName() {
        val peer = `in`.yashd.bitchat.mesh.PeerState(
            `in`.yashd.bitchat.identity.PeerIdentity("friend", "Live Name", byteArrayOf(1), byteArrayOf(2), 0L, 0L, false),
            `in`.yashd.bitchat.mesh.PeerConnectionState.Disconnected, null, null, null, null, null)
        val item = buildChatList(RelayUiState(),
            MeshUiState(localPeerId = "me", peers = listOf(peer), knownNames = mapOf("friend" to "Old Name"),
                currentConversation = listOf(msg("m", "friend", "me"))),
            emptyMap(), sessionStart = 0L).single { it.info.target == "friend" }
        assertEquals("Live Name", item.info.title)
    }

    @Test fun nicknameStoreRoundTripsAndCaps() {
        var map = mapOf("1" to "one")
        map = `in`.yashd.bitchat.mesh.PeerNicknames.remember(map, "2", "two")
        assertEquals(map, `in`.yashd.bitchat.mesh.PeerNicknames.decode(`in`.yashd.bitchat.mesh.PeerNicknames.encode(map)))
        var big = emptyMap<String, String>()
        repeat(600) { big = `in`.yashd.bitchat.mesh.PeerNicknames.remember(big, "id$it", "n$it") }
        assertEquals(512, big.size)
        assertTrue("id599" in big && "id0" !in big)
    }

    @Test fun blankNameNeverOverwritesSavedName() {
        val map = `in`.yashd.bitchat.mesh.PeerNicknames.remember(mapOf("p" to "Mira"), "p", "  ")
        assertEquals("Mira", map["p"])
    }
}
