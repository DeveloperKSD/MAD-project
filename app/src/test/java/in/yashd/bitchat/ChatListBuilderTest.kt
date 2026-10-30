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
