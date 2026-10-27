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
import androidx.compose.foundation.layout.Spacer
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
// Routes: which conversation is open.
//   "nearby:<peerId>"  private Bluetooth chat
//   "nearby:"          the public Nearby group
//   "internet:<npub>"  Internet (Nostr) chat
// ---------------------------------------------------------------------------

internal fun nearbyRoute(peerId: String?): String = "nearby:" + (peerId ?: "")
internal fun internetRoute(npub: String): String = "internet:$npub"
internal fun routeIsNearby(route: String): Boolean = route.startsWith("nearby:")
internal fun routeTarget(route: String): String? = route.substringAfter(':').ifEmpty { null }

private fun PeerState?.isLinked(): Boolean =
    this != null && (state == PeerConnectionState.Connected || state == PeerConnectionState.Authenticated)

/** Everything the screens need to show about one conversation or contact. */
internal data class RouteInfo(
    val route: String,
    val nearby: Boolean,
    val target: String?,
    val title: String,
    val linkText: String,
    val online: Boolean,
    val peer: PeerState?,
    val publicId: String
) {
    val isGroup: Boolean get() = nearby && target == null
}

internal fun describeRoute(route: String, mesh: MeshUiState, internet: RelayUiState): RouteInfo {
    val target = routeTarget(route)
    if (routeIsNearby(route)) {
        val peer = target?.let { id -> mesh.peers.firstOrNull { it.identity.peerId == id } }
        val linked = mesh.serviceRunning && peer.isLinked()
        val inRange = mesh.peers.count { it.isLinked() }
        val title = when {
            target == null -> "Nearby group"
            peer != null && peer.identity.displayName.isNotBlank() -> peer.identity.displayName
            else -> target.take(12)
        }
        val linkText = when {
            target == null -> if (mesh.serviceRunning) "Public group • $inRange in range" else "Public group • Nearby is off"
            !mesh.serviceRunning -> "Offline • Nearby is off"
            peer == null -> "Offline • No route"
            linked -> "BLE only • Nearby"
            peer.state == PeerConnectionState.Connecting -> "BLE only • Connecting…"
            peer.state == PeerConnectionState.Failed -> "BLE only • Connection failed"
            peer.state == PeerConnectionState.Disconnected -> "Offline • No route"
            else -> "BLE only • Found nearby"
        }
        return RouteInfo(
            route = route,
            nearby = true,
            target = target,
            title = title,
            linkText = linkText,
            online = if (target == null) mesh.serviceRunning else linked,
            peer = peer,
            publicId = target.orEmpty()
        )
    }
    val npub = target.orEmpty()
    val key = runCatching { RelayViewModel.recipientKey(npub) }.getOrNull()
    val record = internet.conversations.firstOrNull { it.publicKey == key }
    val linkText = when {
        internet.inboxes.isEmpty() -> "Nostr only • Not set up"
        internet.inboxesConnected -> "Nostr only • Relay connected"
        else -> "Nostr only • Relay offline"
    }
    return RouteInfo(
        route = route,
        nearby = false,
        target = npub,
        title = record?.displayName?.ifBlank { null } ?: shortId(npub, 12, 6),
        linkText = linkText,
        online = internet.inboxesConnected,
        peer = null,
        publicId = npub
    )
}

/** Messages of one Nearby conversation (same rule the old chat screen used). */
internal fun nearbyThread(mesh: MeshUiState, peerId: String?): List<ChatMessage> =
    mesh.currentConversation.filter { m ->
        if (peerId == null) !m.isPrivate && m.recipientId == null
        else m.isPrivate && (m.senderId == peerId || m.recipientId == peerId)
    }

/** Messages of one Internet conversation. */
internal fun internetThread(internet: RelayUiState, npub: String): List<InternetMessage> {
    val key = runCatching { RelayViewModel.recipientKey(npub) }.getOrNull() ?: return emptyList()
    return if (internet.threadPeer == key) internet.thread else internet.messages.filter { it.peer == key }
}

/** One row of the Chats list. */
internal data class ChatListItem(
    val info: RouteInfo,
    val preview: String,
    val lastAtMs: Long?,
    val waiting: Boolean,
    val failed: Boolean,
    val unread: Int
)

/**
 * Builds the Chats list from the existing conversation data (relayConversations) and adds
 * the extra details the design shows: time, connection line and unread count.
 * Unread counts only live in memory: messages that arrive after you last opened a chat.
 */
internal fun buildChatList(
    internet: RelayUiState,
    mesh: MeshUiState,
    seenAt: Map<String, Long>,
    sessionStart: Long
): List<ChatListItem> {
    val names = mesh.peers.associate { it.identity.peerId to it.identity.displayName }
    return relayConversations(internet, mesh).mapNotNull { row ->
        val route = when (row.transport) {
            ConversationTransport.Nearby -> nearbyRoute(row.recipient)
            ConversationTransport.Internet -> internetRoute(row.recipient ?: return@mapNotNull null)
        }
        val info = describeRoute(route, mesh, internet)
        val since = seenAt[route] ?: sessionStart
        if (info.nearby) {
            val thread = nearbyThread(mesh, info.target)
            val last = thread.lastOrNull()
            val mineLast = last?.direction == MessageDirection.Outbound
            val preview = when {
                last == null -> row.preview
                info.isGroup && !mineLast -> (names[last.senderId]?.ifBlank { null } ?: last.senderId.take(8)) + ": " + last.body
                else -> last.body
            }
            ChatListItem(
                info = info,
                preview = preview,
                lastAtMs = last?.createdAtMs,
                waiting = mineLast && last?.status == MessageStatus.Pending,
                failed = mineLast && last?.status == MessageStatus.Failed,
                unread = thread.count { it.direction == MessageDirection.Inbound && (it.receivedAtMs ?: it.createdAtMs) > since }
            )
        } else {
            val thread = internetThread(internet, info.publicId)
            val last = thread.lastOrNull()
            val mineLast = last?.outgoing == true
            ChatListItem(
                info = info,
                preview = last?.body ?: row.preview,
                lastAtMs = last?.createdAtMs?.takeIf { it > 0 }
                    ?: internet.conversations.firstOrNull { it.publicKey == last?.peer || it.npub == info.publicId }?.lastMessageAtMs,
                waiting = mineLast && last?.status == "Sending…",
                failed = mineLast && last?.status?.startsWith("Failed") == true,
                unread = thread.count { !it.outgoing && it.createdAtMs > since }
            )
        }
    }.sortedByDescending { it.lastAtMs ?: 0L }
}

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
    pinned: List<String> = emptyList(),
    togglePin: (String) -> Unit = {},
    deleteChat: (String) -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    // Search text can contain private names, so it is kept in memory only.
    var search by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val query = search.trim()
    val visible = items.filter { item ->
        val matches = query.isEmpty() || item.info.title.contains(query, true) ||
            item.preview.contains(query, true) || item.info.publicId.contains(query, true)
        matches && when (filter) {
            "Nearby" -> item.info.nearby
            "Internet" -> !item.info.nearby
            "Offline" -> !item.info.online
            "Favorites" -> item.info.route in favorites
            else -> true
        }
    }.sortedByDescending { it.info.route in pinned } // stable: pinned chats first, newest first inside each group
    // Which row's menu is open, and which chat is waiting for delete confirmation.
    var menuRoute by remember { mutableStateOf<String?>(null) }
    var confirmRoute by remember { mutableStateOf<String?>(null) }
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { RelayHeader("Relay", myName, onAvatar) }
            item { RelaySearchField(search, { search = it }, "Find your friends") }
            item { FilterChipsRow(filter, { filter = it }, newChat) }
            if (visible.isEmpty()) {
                item {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        when {
                            loading -> EmptyCard(R.drawable.relay_clock, "Loading your chats…", "Saved Internet chats are being opened securely.")
                            storageProblem && items.isEmpty() -> EmptyCard(R.drawable.relay_info, "Saved Internet chats are unavailable",
                                "Your history wasn't deleted. Nearby chats still work.")
                            query.isNotEmpty() || filter != "All" -> EmptyCard(R.drawable.relay_search, "No chats found",
                                "Try another name or filter.")
                            else -> EmptyCard(R.drawable.relay_chat, "No chats yet",
                                "Find people around you in Discover, or tap + to message a friend online.") {
                                TextButton(onClick = discover) { Text("Find people nearby", color = RelayColors.Blue) }
                            }
                        }
                    }
                }
            }
            items(visible, key = { it.info.route }) { item ->
                val route = item.info.route
                val isPinned = route in pinned
                Box {
                    ChatListRow(item, now, isPinned, onClick = { openChat(route) }, onLongClick = { menuRoute = route })
                    DropdownMenu(expanded = menuRoute == route, onDismissRequest = { menuRoute = null }) {
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Unpin chat" else "Pin chat") },
                            onClick = { menuRoute = null; togglePin(route) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete chat", color = RelayColors.Red) },
                            onClick = { menuRoute = null; confirmRoute = route }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RelaySearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(RelayColors.Card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(R.drawable.relay_search, null, tint = RelayColors.TextSecondary, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = RelayColors.TextMuted, fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = RelayColors.TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(RelayColors.Blue),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FilterChipsRow(selected: String, onSelect: (String) -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chatFilters.forEach { name ->
            val isOn = name == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isOn) RelayColors.BlueSelected else Color.Transparent)
                    .border(1.dp, if (isOn) RelayColors.Blue.copy(alpha = 0.5f) else RelayColors.Divider, RoundedCornerShape(16.dp))
                    .clickable { onSelect(name) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(name, color = if (isOn) Color.White else RelayColors.TextPrimary, fontSize = 13.sp)
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.dp, RelayColors.Divider, CircleShape)
                .clickable(onClick = onPlus),
            contentAlignment = Alignment.Center
        ) {
            RIcon(R.drawable.relay_plus, "New Chat", tint = RelayColors.TextPrimary, size = 18.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListRow(item: ChatListItem, now: Long, pinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val info = item.info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationAvatar(info, 48)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.title,
                    modifier = Modifier.weight(1f),
                    color = RelayColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pinned) {
                    RIcon(R.drawable.relay_pin, "Pinned", tint = RelayColors.TextSecondary, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(item.lastAtMs?.let { listTime(it, now) } ?: "", color = RelayColors.TextSecondary, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionLine(item)
                }
                if (item.unread > 0) UnreadBadge(item.unread)
            }
            Text(
                text = item.preview,
                color = RelayColors.TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The small icon + text line under the name ("BLE • Direct connection", "Waiting for relay sync"…). */
@Composable
private fun ConnectionLine(item: ChatListItem) {
    val info = item.info
    when {
        item.failed -> {
            RIcon(R.drawable.relay_info, null, tint = RelayColors.Red, size = 15.dp)
            Text("Message not sent", color = RelayColors.Red, fontSize = 12.sp, maxLines = 1)
        }
        item.waiting -> {
            RIcon(R.drawable.relay_clock, null, tint = RelayColors.Orange, size = 15.dp)
            Text(if (info.nearby) "Waiting to send" else "Waiting for relay sync", color = RelayColors.Orange, fontSize = 12.sp, maxLines = 1)
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
            Text(info.linkText, color = RelayColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(RelayColors.Blue),
        contentAlignment = Alignment.Center
    ) {
        Text(if (count > 9) "9+" else count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
