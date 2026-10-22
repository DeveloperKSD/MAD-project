package `in`.yashd.bitchat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.ui.relay.RelayUiState
import `in`.yashd.bitchat.ui.relay.RelayViewModel
import `in`.yashd.bitchat.ui.relay.sendNearbySafely

// ---------------------------------------------------------------------------
// Conversation screen (Figma 2). Works for Nearby (Bluetooth) and Internet chats.
// Sending goes through the same calls the old screens used.
// ---------------------------------------------------------------------------

/** One message as the screen draws it, whatever transport it came from. */
private data class BubbleUi(
    val id: String,
    val body: String,
    val mine: Boolean,
    val atMs: Long,
    val sender: String?,
    val status: String?,
    val ticks: Int,
    val failed: Boolean,
    val pending: Boolean,
    val nearbyMessage: ChatMessage?
)

private sealed interface ThreadRow {
    data class Day(val key: String, val label: String) : ThreadRow
    data class Message(val bubble: BubbleUi) : ThreadRow
}

private fun nearbyBubbles(mesh: MeshUiState, peerId: String?, hidden: List<String>): List<BubbleUi> {
    val names = mesh.peers.associate { it.identity.peerId to it.identity.displayName }
    return nearbyThread(mesh, peerId).filter { it.messageId !in hidden }.map { m ->
        val mine = m.direction == MessageDirection.Outbound
        BubbleUi(
            id = m.messageId,
            body = m.body,
            mine = mine,
            atMs = m.createdAtMs,
            sender = if (!mine && peerId == null) names[m.senderId]?.ifBlank { null } ?: m.senderId.take(8) else null,
            status = if (!mine) null else when (m.status) {
                MessageStatus.Pending -> "Sending • BLE"
                MessageStatus.SentToTransport -> if (peerId == null) "Sent • Nearby group" else "Sent • BLE"
                MessageStatus.Delivered -> "Delivered • BLE"
                MessageStatus.Read -> "Read • BLE"
                MessageStatus.Failed -> "Not sent • Tap to retry"
            },
            ticks = if (!mine) 0 else when (m.status) {
                MessageStatus.Delivered, MessageStatus.Read -> 2
                MessageStatus.SentToTransport -> 1
                else -> 0
            },
            failed = mine && m.status == MessageStatus.Failed,
            pending = mine && m.status == MessageStatus.Pending,
            nearbyMessage = m
        )
    }
}

private fun internetBubbles(internet: RelayUiState, npub: String): List<BubbleUi> =
    internetThread(internet, npub).map { m ->
        BubbleUi(
            id = m.id,
            body = m.body,
            mine = m.outgoing,
            atMs = m.createdAtMs,
            sender = null,
            // Shown exactly as the app reports it: relay acceptance is not proof of delivery.
            status = if (m.outgoing) m.status else null,
            ticks = if (m.outgoing && m.status.startsWith("Relay accepted")) 1 else 0,
            failed = m.outgoing && (m.status.startsWith("Failed") || m.status.startsWith("Interrupted")),
            pending = m.outgoing && m.status == "Sending…",
            nearbyMessage = null
        )
    }

private fun withDays(bubbles: List<BubbleUi>, now: Long): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(bubbles.size + 4)
    var lastDay: Long? = null
    for (b in bubbles) {
        if (b.atMs > 0 && (lastDay == null || !sameDay(lastDay, b.atMs))) {
            rows.add(ThreadRow.Day("day-${b.id}", dayTitle(b.atMs, now)))
            lastDay = b.atMs
        }
        rows.add(ThreadRow.Message(b))
    }
    return rows
}

@Composable
internal fun RelayChatScreen(
    info: RouteInfo,
    mesh: MeshUiState,
    internet: RelayUiState,
    now: Long,
    back: () -> Unit,
    openProfile: () -> Unit,
    sendInternet: (String, String) -> Unit,
    openSetup: () -> Unit,
    openDiscover: () -> Unit,
    markSeen: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    // Draft stays in memory only (saved state is not encrypted storage).
    var draft by remember(info.route) { mutableStateOf("") }
    val retried = remember(info.route) { mutableStateListOf<String>() }
    val bubbles = if (info.nearby) nearbyBubbles(mesh, info.target, retried) else internetBubbles(internet, info.publicId)
    val rows = withDays(bubbles, now)
    val lastMineId = bubbles.lastOrNull { it.mine }?.id
    val listState = rememberLazyListState()

    LaunchedEffect(info.route, rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    LaunchedEffect(info.route, bubbles.size) { markSeen() }

    val internetKey = if (info.nearby) null else runCatching { RelayViewModel.recipientKey(info.publicId) }.getOrNull()
    val canSend = if (info.nearby) draft.isNotBlank() else
        !internet.storageLoading && internet.storageError == null && !internet.busy && draft.isNotBlank() &&
            internetKey != null && internet.npub.isNotEmpty() && internet.inboxes.isNotEmpty()

    val send: () -> Unit = {
        if (info.nearby) {
            val sent = sendNearbySafely(draft, info.target, MeshRuntime.state.value.peers, MeshRuntime::sendMessage)
            if (sent) {
                draft = ""
            } else {
                toast("This friend is no longer nearby. Find them in Discover before sending a private message.")
            }
        } else {
            sendInternet(info.publicId, draft)
            if (draft.toByteArray(Charsets.UTF_8).size <= 16_000) {
                draft = ""
            }
        }
    }
    val retry: (BubbleUi) -> Unit = { b ->
        val m = b.nearbyMessage
        if (m != null) {
            val sent = sendNearbySafely(m.body, m.recipientId, MeshRuntime.state.value.peers, MeshRuntime::sendMessage)
            if (sent) {
                retried.add(m.messageId)
            } else {
                toast("This friend is no longer nearby. Your private message was not resent.")
            }
        } else {
            toast("Couldn't send. Check your relays in Settings, then send it again.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(info, back, openProfile, toast) { copyText(context, "Relay ID", info.publicId); toast("ID copied") }

        // Short notes the old screens showed, kept so nothing important is lost.
        when {
            info.isGroup -> Notice("Everyone in Bluetooth range can read this group.")
            info.nearby && !mesh.serviceRunning -> Notice("Nearby is off.", "Turn on", openDiscover)
            !info.nearby && internet.inboxes.isEmpty() -> Notice("Set up Internet messaging before you can send.", "Set up", openSetup)
        }
        if (!info.nearby) internet.notice?.let { Notice(it) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("No messages yet", color = RelayColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (info.isGroup) "Say hello to everyone nearby." else "Say hello to start the conversation.",
                        color = RelayColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is ThreadRow.Day -> row.key
                            is ThreadRow.Message -> row.bubble.id
                        }
                    }) { row ->
                        when (row) {
                            is ThreadRow.Day -> Text(
                                text = row.label,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                color = RelayColors.TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            is ThreadRow.Message -> MessageBubbleView(
                                b = row.bubble,
                                nearby = info.nearby,
                                showStatus = row.bubble.failed || row.bubble.id == lastMineId,
                                onCopy = { copyText(context, "Relay message", row.bubble.body); toast("Message copied") },
                                onRetry = { retry(row.bubble) }
                            )
                        }
                    }
                }
            }
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            canSend = canSend,
            sendDescription = if (info.nearby) "Send message" else "Send encrypted message",
            onSend = send,
            onPlus = { toast("Sending photos is coming soon") }
        )
    }
}

@Composable
private fun ChatTopBar(info: RouteInfo, back: () -> Unit, openProfile: () -> Unit, toast: (String) -> Unit, copyId: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = back) { RIcon(R.drawable.relay_back, "Back to Chats", tint = RelayColors.TextPrimary) }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !info.isGroup, onClick = openProfile)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(info, 38)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.title, color = RelayColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info.linkText, color = RelayColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!info.isGroup) {
            IconButton(onClick = { toast("Voice calls aren't available yet") }) {
                RIcon(R.drawable.relay_call, "Call", tint = RelayColors.TextPrimary, size = 20.dp)
            }
            Box {
                IconButton(onClick = { menu = true }) { RIcon(R.drawable.relay_more, "More options", tint = RelayColors.TextPrimary, size = 20.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("View contact") }, onClick = { menu = false; openProfile() })
                    DropdownMenuItem(text = { Text("Copy ID") }, onClick = { menu = false; copyId() })
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RelayColors.Card)
            .padding(horizontal = 14.dp, vertical = if (action == null) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), color = RelayColors.TextSecondary, fontSize = 12.sp)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = RelayColors.Blue, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleView(b: BubbleUi, nearby: Boolean, showStatus: Boolean, onCopy: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (b.mine) Alignment.End else Alignment.Start
    ) {
        if (b.sender != null) {
            Text(
                b.sender,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                color = avatarColor(b.sender),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        !b.mine -> RelayColors.CardHigh
                        b.failed -> RelayColors.BlueBubble.copy(alpha = 0.45f)
                        else -> RelayColors.BlueBubble
                    }
                )
                .combinedClickable(onClick = { if (b.failed) onRetry() }, onLongClick = onCopy)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(b.body, color = RelayColors.TextPrimary, fontSize = 15.sp)
        }
        Row(
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (b.atMs > 0) Text(clockTime(b.atMs), color = RelayColors.TextMuted, fontSize = 11.sp)
            if (b.ticks == 2) RIcon(R.drawable.relay_check_double, "Delivered", tint = RelayColors.Bluetooth, size = 15.dp)
            if (b.ticks == 1) RIcon(R.drawable.relay_check, "Sent", tint = RelayColors.TextSecondary, size = 15.dp)
        }
        if (showStatus && b.status != null) {
            val icon = when {
                b.failed -> R.drawable.relay_info
                b.pending -> R.drawable.relay_clock
                nearby -> R.drawable.relay_bluetooth
                else -> R.drawable.relay_broadcast
            }
            val color = when {
                b.failed -> RelayColors.Red
                b.pending -> RelayColors.Orange
                nearby -> RelayColors.Bluetooth
                else -> RelayColors.Nostr
            }
            Row(
                modifier = Modifier
                    .padding(top = 2.dp, start = 4.dp, end = 4.dp)
                    .let { if (b.failed) it.clickable(onClick = onRetry) else it },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                RIcon(icon, null, tint = color, size = 14.dp)
                Text(
                    b.status,
                    color = if (b.failed) RelayColors.Red else if (b.pending) RelayColors.Orange else RelayColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    canSend: Boolean,
    sendDescription: String,
    onSend: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(RelayColors.CardHigh)
                .clickable(onClick = onPlus),
            contentAlignment = Alignment.Center
        ) {
            RIcon(R.drawable.relay_plus, "Add attachment", tint = RelayColors.TextPrimary, size = 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(RelayColors.Card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (draft.isEmpty()) Text("Message…", color = RelayColors.TextMuted, fontSize = 15.sp)
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                maxLines = 5,
                textStyle = TextStyle(color = RelayColors.TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(RelayColors.Blue),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (canSend) RelayColors.Blue else RelayColors.CardHigh)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            RIcon(
                R.drawable.relay_arrow_up,
                sendDescription,
                tint = if (canSend) Color.White else RelayColors.TextMuted,
                size = 22.dp
            )
        }
    }
}
