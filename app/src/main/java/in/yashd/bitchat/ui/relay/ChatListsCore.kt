package `in`.yashd.bitchat.ui.relay

/** Pure decision logic for custom chat lists; persistence lives in ChatListsStore. */
data class ChatListsState(
    val lists: List<Pair<String, String>> = emptyList(),
    val membership: Map<String, Set<String>> = emptyMap()
)

object ChatListsCore {
    fun normalizeName(name: String): String = name.trim().take(30)

    /** Null when the trimmed name is unusable. */
    fun create(state: ChatListsState, listId: String, name: String): ChatListsState? {
        val trimmed = normalizeName(name)
        if (trimmed.isEmpty()) return null
        return state.copy(lists = state.lists + (listId to trimmed))
    }

    /** Chats survive list deletion: only membership in that list is dropped. */
    fun deleteList(state: ChatListsState, listId: String): ChatListsState = state.copy(
        lists = state.lists.filter { it.first != listId },
        membership = state.membership.mapValues { (_, ids) -> ids - listId }.filterValues { it.isNotEmpty() }
    )

    fun toggle(state: ChatListsState, route: String, listId: String): ChatListsState {
        val current = state.membership[route].orEmpty()
        val next = if (listId in current) current - listId else current + listId
        return state.copy(membership = if (next.isEmpty()) state.membership - route else state.membership + (route to next))
    }

    fun removeRoute(state: ChatListsState, route: String): ChatListsState =
        state.copy(membership = state.membership - route)
}
