package `in`.yashd.bitchat.ui.relay

import org.junit.Assert.*
import org.junit.Test

class ChatListsCoreTest {
    @Test fun createTrimsAndCapsNames() {
        val state = ChatListsCore.create(ChatListsState(), "1", "  Work chats  ")
        assertEquals(listOf("1" to "Work chats"), state!!.lists)
        val long = ChatListsCore.create(ChatListsState(), "2", "x".repeat(40))
        assertEquals(30, long!!.lists.single().second.length)
    }

    @Test fun blankNameIsRejected() {
        assertNull(ChatListsCore.create(ChatListsState(), "1", "   "))
    }

    @Test fun toggleAddsAndRemovesMembership() {
        val added = ChatListsCore.toggle(ChatListsState(lists = listOf("1" to "Work")), "nearby:peer", "1")
        assertEquals(setOf("1"), added.membership["nearby:peer"])
        val removed = ChatListsCore.toggle(added, "nearby:peer", "1")
        assertFalse(removed.membership.containsKey("nearby:peer"))
    }

    @Test fun oneChatCanLiveInSeveralLists() {
        var state = ChatListsState(lists = listOf("1" to "Work", "2" to "Family"))
        state = ChatListsCore.toggle(state, "nearby:peer", "1")
        state = ChatListsCore.toggle(state, "nearby:peer", "2")
        assertEquals(setOf("1", "2"), state.membership["nearby:peer"])
    }

    @Test fun deletingAListKeepsTheChatInOtherLists() {
        var state = ChatListsState(lists = listOf("1" to "Work", "2" to "Family"))
        state = ChatListsCore.toggle(state, "nearby:peer", "1")
        state = ChatListsCore.toggle(state, "nearby:peer", "2")
        val after = ChatListsCore.deleteList(state, "1")
        assertEquals(listOf("2" to "Family"), after.lists)
        assertEquals(setOf("2"), after.membership["nearby:peer"])
    }

    @Test fun deletingTheOnlyListDropsTheMembershipEntry() {
        var state = ChatListsState(lists = listOf("1" to "Work"))
        state = ChatListsCore.toggle(state, "nearby:peer", "1")
        val after = ChatListsCore.deleteList(state, "1")
        assertTrue(after.lists.isEmpty())
        assertTrue(after.membership.isEmpty())
    }

    @Test fun removeRouteOnlyTouchesThatChat() {
        var state = ChatListsState(lists = listOf("1" to "Work"))
        state = ChatListsCore.toggle(state, "nearby:a", "1")
        state = ChatListsCore.toggle(state, "nearby:b", "1")
        val after = ChatListsCore.removeRoute(state, "nearby:a")
        assertEquals(setOf("1"), after.membership["nearby:b"])
        assertFalse(after.membership.containsKey("nearby:a"))
    }
}
