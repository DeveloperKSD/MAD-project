package `in`.yashd.bitchat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState
import `in`.yashd.bitchat.ui.relay.ConversationTransport
import `in`.yashd.bitchat.ui.relay.InternetMessage
import `in`.yashd.bitchat.ui.relay.RelayUiState
import `in`.yashd.bitchat.ui.relay.RelayViewModel
import `in`.yashd.bitchat.ui.relay.relayConversations


// ---------------------------------------------------------------------------
// Chats screen (Figma 1)
// ---------------------------------------------------------------------------

private val chatFilters = listOf("All", "Nearby", "Internet", "Offline", "Favorites")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayChatsScreen(
    items: List<ChatListItem>,
    myName: String,
    now: Long,
    favorites: List<String>,
    loading: Boolean,
    storageProblem: Boolean,
    onAvatar: () -> Unit,
    openChat: (String) -> Unit,
    newChat: () -> Unit,
    discover: () -> Unit,
    newInternetChat: () -> Unit = {},
    pinned: List<String> = emptyList(),
    togglePin: (String) -> Unit = {},
    deleteChat: (String) -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    toggleFavorite: (String) -> Unit = {},
    requestsCount: Int = 0,
    openRequests: () -> Unit = {},
    lists: List<Pair<String, String>> = emptyList(),
    listMembership: Map<String, Set<String>> = emptyMap(),
    createList: (String) -> Unit = {},
    toggleListMembership: (String, String) -> Unit = { _, _ -> },
    deleteList: (String) -> Unit = {}
) {
    // Search text can contain private names, so it is kept in memory only.
    var search by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val query = search.trim()
    val visible = items.filter { item ->
        val matches = query.isEmpty() || item.info.title.contains(query, true) ||
            item.preview.contains(query, true) || item.info.publicId.contains(query, true)
        matches && when {
            filter == "Nearby" -> item.info.nearby
            filter == "Internet" -> !item.info.nearby
            filter == "Offline" -> !item.info.online
            filter == "Favorites" -> item.info.route in favorites
            filter.startsWith("list:") -> filter.removePrefix("list:") in listMembership[item.info.route].orEmpty()
            else -> true
        }
    }.sortedByDescending { it.info.route in pinned } // stable: pinned chats first, newest first inside each group
    // Which row's menu is open, which chat is waiting for delete confirmation,
    // whether the new-list dialog shows, and which chat's list picker is open.
    var menuRoute by remember { mutableStateOf<String?>(null) }
    var confirmRoute by remember { mutableStateOf<String?>(null) }
    var creatingList by remember { mutableStateOf(false) }
    var listRoute by remember { mutableStateOf<String?>(null) }
    if (creatingList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creatingList = false },
            confirmButton = {
                TextButton(onClick = {
                    createList(name); creatingList = false
                }, enabled = name.isNotBlank()) { Text("Create", color = RelayColors.Blue) }
            },
            dismissButton = { TextButton(onClick = { creatingList = false }) { Text("Cancel", color = RelayColors.TextSecondary) } },
            title = { Text("New list", color = RelayColors.TextPrimary) },
            text = {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    singleLine = true,
                    textStyle = TextStyle(color = RelayColors.TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(RelayColors.Blue),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("List name", color = RelayColors.TextMuted, fontSize = 15.sp)
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RelayColors.CardHigh)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            },
            containerColor = RelayColors.Card
        )
    }
    listRoute?.let { route ->
        AlertDialog(
            onDismissRequest = { listRoute = null },
            confirmButton = { TextButton(onClick = { listRoute = null }) { Text("Done", color = RelayColors.Blue) } },
            title = { Text("Lists", color = RelayColors.TextPrimary) },
            text = {
                Column {
                    if (lists.isEmpty()) Text("No lists yet. Tap + above the chats to make one.", color = RelayColors.TextSecondary, fontSize = 14.sp)
                    lists.forEach { (id, listName) ->
                        val inList = id in listMembership[route].orEmpty()
                        Row(
                            Modifier.fillMaxWidth().clickable { toggleListMembership(route, id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(listName, Modifier.weight(1f), color = RelayColors.TextPrimary, fontSize = 15.sp)
                            Text(if (inList) "✓" else "", color = RelayColors.Blue, fontSize = 16.sp)
                        }
                    }
                }
            },
            containerColor = RelayColors.Card
        )
    }
    confirmRoute?.let { route ->
        val name = items.firstOrNull { it.info.route == route }?.info?.title ?: "this chat"
        ConfirmDeleteDialog(
            title = "Delete chat?",
            text = "All messages with $name will be removed from this phone. The other person keeps their copy.",
            confirmLabel = "Delete chat",
            onConfirm = { confirmRoute = null; deleteChat(route) },
            onDismiss = { confirmRoute = null }
        )
    }
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = refreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = refreshState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = RelayColors.CardHigh,
                color = RelayColors.Blue
            )
        }
    ) {
        // Leave room for the 56dp FAB and its margins when the last chat is scrolled into view.
        LazyColumn(Modifier.fillMaxSize().testTag("relay-chat-list"), contentPadding = PaddingValues(bottom = 88.dp)) {
            item { RelayHeader("Relay", myName, onAvatar) }
            item { RelaySearchField(search, { search = it }, "Find your friends") }
            item { FilterChipsRow(filter, { filter = it }, lists, { creatingList = true }, deleteList) }
            if (requestsCount > 0) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(RelayColors.Card)
                            .clickable { openRequests() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RIcon(R.drawable.relay_chat, null, tint = RelayColors.Blue, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Chat requests", color = RelayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(if (requestsCount == 1) "1 person wants to message you" else "$requestsCount people want to message you",
                                color = RelayColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        when {
                            loading -> EmptyCard(R.drawable.relay_clock, "Loading your chats…", "Saved Internet chats are being opened securely.")
                            storageProblem && items.isEmpty() -> EmptyCard(R.drawable.relay_info, "Saved Internet chats are unavailable",
                                "Your history wasn't deleted. Nearby chats still work.")
                            filter == "Internet" && query.isEmpty() -> EmptyCard(R.drawable.relay_broadcast, "No internet chats",
                                "Add a friend with their Internet ID to chat from anywhere.") {
                                TextButton(onClick = newInternetChat) { Text("Start an internet chat", color = RelayColors.Blue) }
                            }
                            query.isNotEmpty() || filter != "All" -> EmptyCard(R.drawable.relay_search, "No chats found",
                                "Try another name or filter.")
                            else -> EmptyCard(R.drawable.relay_chat, "No chats yet",
                                "Find people around you in Discover, or start an internet chat with a friend's Internet ID.") {
                                Row {
                                    TextButton(onClick = discover) { Text("Find people nearby", color = RelayColors.Blue) }
                                    TextButton(onClick = newInternetChat) { Text("Start an internet chat", color = RelayColors.Blue) }
                                }
                            }
                        }
                    }
                }
            }
            items(visible, key = { it.info.route }) { item ->
                val route = item.info.route
                val isPinned = route in pinned
                Box {
                    ChatListRow(item, now, isPinned,
                        onClick = { openChat(route) }, onLongClick = { menuRoute = route })
                    DropdownMenu(expanded = menuRoute == route, onDismissRequest = { menuRoute = null }) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Unpin chat" else "Pin chat") },
                            onClick = { menuRoute = null; togglePin(route) }
                        )
                        DropdownMenuItem(
                            text = { Text("Lists") },
                            onClick = { menuRoute = null; listRoute = route }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete chat", color = RelayColors.Red) },
                            onClick = { menuRoute = null; confirmRoute = route }
                        )
                    }
                }
            }
        }
        // The host Scaffold already reserves the bottom navigation bar and its system insets.
        FloatingActionButton(
            onClick = newChat,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp),
            shape = CircleShape,
            containerColor = RelayColors.Blue,
            contentColor = Color.White
        ) {
            RIcon(R.drawable.relay_plus, "New Chat", tint = Color.White, size = 24.dp)
        }
    }
}

@Composable
internal fun RelaySearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(RelayColors.Card)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(R.drawable.relay_search, null, tint = RelayColors.TextSecondary, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = RelayColors.TextMuted, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = RelayColors.TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(RelayColors.Blue),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    selected: String,
    onSelect: (String) -> Unit,
    lists: List<Pair<String, String>> = emptyList(),
    onCreate: (Boolean) -> Unit = {},
    deleteList: (String) -> Unit = {}
) {
    var confirmList by remember { mutableStateOf<Pair<String, String>?>(null) }
    confirmList?.let { (id, listName) ->
        ConfirmDeleteDialog(
            title = "Delete list?",
            text = "$listName will be removed. The chats in it stay.",
            confirmLabel = "Delete list",
            onConfirm = { confirmList = null; deleteList(id) },
            onDismiss = { confirmList = null }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val chip: @Composable (String, String, (() -> Unit)?) -> Unit = { label, key, onLong ->
            val isOn = key == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isOn) RelayColors.BlueSelected else RelayColors.Card)
                    .combinedClickable(onClick = { onSelect(key) }, onLongClick = onLong)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(label, color = if (isOn) RelayColors.Blue else RelayColors.TextPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.Normal)
            }
        }
        chatFilters.forEach { name -> chip(name, name, null) }
        lists.forEach { (id, listName) -> chip(listName, "list:" + id) { confirmList = id to listName } }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(RelayColors.Card)
                .clickable { onCreate(true) }
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("+ New list", color = RelayColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListRow(item: ChatListItem, now: Long, pinned: Boolean,
                        onClick: () -> Unit, onLongClick: () -> Unit) {
    val info = item.info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationAvatar(info, 42)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.title,
                    modifier = Modifier.weight(1f),
                    color = RelayColors.TextPrimary,
                    fontSize = RelayType.RowTitle,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pinned) {
                    RIcon(R.drawable.relay_pin, "Pinned", tint = RelayColors.TextSecondary, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                }
                // Unread chats get the accent-coloured time, like the reference client.
                Text(item.lastAtMs?.let { listTime(it, now) } ?: "",
                    color = if (item.unread > 0) RelayColors.Blue else RelayColors.TextSecondary, fontSize = RelayType.Meta)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionLine(item)
                }
                if (item.unread > 0) UnreadBadge(item.unread)
            }
        }
    }
}

/** The single line under the name: status icon + status text, then the message preview. */
@Composable
private fun RowScope.ConnectionLine(item: ChatListItem) {
    val info = item.info
    when {
        item.failed -> {
            RIcon(R.drawable.relay_info, null, tint = RelayColors.Red, size = 15.dp)
            Text("Message not sent", color = RelayColors.Red, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        item.waiting -> {
            RIcon(R.drawable.relay_clock, null, tint = RelayColors.Orange, size = 15.dp)
            Text(if (info.nearby) "Waiting to send" else "Waiting for relay sync", color = RelayColors.Orange, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        else -> {
            val icon = when {
                info.isGroup -> R.drawable.relay_group
                info.linkText.startsWith("Offline") -> R.drawable.relay_offline
                info.nearby -> R.drawable.relay_bluetooth
                else -> R.drawable.relay_broadcast
            }
            val tint = when {
                !info.online -> RelayColors.TextSecondary
                info.nearby -> RelayColors.Bluetooth
                else -> RelayColors.Nostr
            }
            RIcon(icon, null, tint = tint, size = 15.dp)
            Text(info.linkText, color = RelayColors.TextMuted, fontSize = 12.sp, maxLines = 1)
            if (item.preview.isNotBlank()) {
                Text(
                    "·  " + item.preview,
                    color = RelayColors.TextSecondary,
                    fontSize = RelayType.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(RelayColors.Blue)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Person avatar, or a blue group icon for the public Nearby group. */
@Composable
internal fun ConversationAvatar(info: RouteInfo, sizeDp: Int) {
    if (info.isGroup) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(RelayColors.BlueSelected),
            contentAlignment = Alignment.Center
        ) {
            RIcon(R.drawable.relay_group, null, tint = Color.White, size = (sizeDp * 0.48f).dp)
        }
    } else {
        PersonAvatar(info.title, sizeDp.dp)
    }
}

/** Shared "are you sure?" box for deleting a message or a chat. */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RelayColors.Card,
        titleContentColor = RelayColors.TextPrimary,
        textContentColor = RelayColors.TextSecondary,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = RelayColors.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = RelayColors.TextPrimary) } }
    )
}
