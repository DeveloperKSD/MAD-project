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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Conversation screen (Figma 2). Works for Nearby (Bluetooth) and Internet chats.
// Sending goes through the same calls the old screens used.
// ---------------------------------------------------------------------------

/** One message as the screen draws it, whatever transport it came from. */
internal data class BubbleUi(
    val id: String,
    val body: String,
    val mine: Boolean,
    val atMs: Long,
    val sender: String?,
    val status: String?,
    val ticks: Int,
    val failed: Boolean,
    val pending: Boolean,
    val nearbyMessage: ChatMessage?,
    val runStart: Boolean = false
)

internal sealed interface ThreadRow {
    data class Day(val key: String, val label: String) : ThreadRow
    data class Message(val bubble: BubbleUi, val bigGapBefore: Boolean = false) : ThreadRow
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
                MessageStatus.Pending -> when {
                    !mesh.serviceRunning -> "Queued • Nearby is off"
                    peerId == null -> "Queued • sends when someone's nearby"
                    else -> "Queued • sends when they're in range"
                }
                MessageStatus.SentToTransport -> if (peerId == null) "Sent • Nearby group" else "Sent • BLE"
                MessageStatus.Delivered -> when (m.deliveredHops) {
                    null, 0 -> "Delivered • BLE"
                    1 -> "Delivered • via ~1 hop"
                    else -> "Delivered • via ~${m.deliveredHops} hops"
                }
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
            ticks = if (!m.outgoing) 0 else if (m.status == "Delivered") 2 else if (m.status.startsWith("Relay accepted")) 1 else 0,
            failed = m.outgoing && (m.status.startsWith("Failed") || m.status.startsWith("Interrupted")),
            pending = m.outgoing && m.status == "Sending…",
            nearbyMessage = null
        )
    }

internal fun withDays(bubbles: List<BubbleUi>, now: Long): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(bubbles.size + 4)
    var lastDay: Long? = null
    var lastMine: Boolean? = null
    for (b in bubbles) {
        var dayBreak = false
        if (b.atMs > 0 && (lastDay == null || !sameDay(lastDay, b.atMs))) {
            rows.add(ThreadRow.Day("day-${b.id}", dayTitle(b.atMs, now)))
            lastDay = b.atMs
            dayBreak = true
        }
        // A new same-sender run starts after a day break or a side switch; runs get a
        // tail on their first bubble and a larger gap above them.
        val runStart = dayBreak || lastMine == null || lastMine != b.mine
        rows.add(ThreadRow.Message(b.copy(runStart = runStart), bigGapBefore = !dayBreak && lastMine != null && lastMine != b.mine))
        lastMine = b.mine
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
    toast: (String) -> Unit,
    olderMessages: () -> Unit = {},
    latestMessages: () -> Unit = {},
    deleteInternetMessage: (String) -> Unit = {},
    pinned: Boolean = false,
    togglePin: () -> Unit = {},
    deleteChat: () -> Unit = {},
    pinnedMessages: List<String> = emptyList(),
    togglePinMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var attachmentRequest by rememberSaveable(info.route) { mutableIntStateOf(0) }
    // Draft stays in memory only (saved state is not encrypted storage).
    var draft by remember(info.route) { mutableStateOf("") }
    val retried = remember(info.route) { mutableStateListOf<String>() }
    val bubbles = if (info.nearby) nearbyBubbles(mesh, info.target, retried) else internetBubbles(internet, info.publicId)
    val rows = withDays(bubbles, now)
    val lastMineId = bubbles.lastOrNull { it.mine }?.id
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Only pins whose message is still in this thread are shown (deleted messages drop out by themselves).
    val pinnedBubbles = pinnedMessages.mapNotNull { id -> bubbles.firstOrNull { it.id == id } }
    var pinCursor by remember(info.route) { mutableIntStateOf(0) }

    var openedRoute by remember { mutableStateOf<String?>(null) }
    val layout = listState.layoutInfo
    val nearBottom = layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } ?: true
    LaunchedEffect(info.route, bubbles.lastOrNull()?.id) {
        if (rows.isNotEmpty() && (openedRoute != info.route || nearBottom || bubbles.lastOrNull()?.mine == true)) {
            listState.animateScrollToItem(rows.lastIndex)
        }
        openedRoute = info.route
    }
    LaunchedEffect(info.route, bubbles.size) { markSeen() }

    val internetKey = if (info.nearby) null else runCatching { RelayViewModel.recipientKey(info.publicId) }.getOrNull()
    val canSend = if (info.nearby) draft.isNotBlank() else
        !internet.storageLoading && !internet.threadLoading && internet.storageError == null && !internet.busy && draft.isNotBlank() &&
            internetKey != null && internet.npub.isNotEmpty() && internet.inboxes.isNotEmpty()

    val send: () -> Unit = {
        if (info.nearby) {
            // Offline friends are queued by the runtime and sent when they're back in range.
            if (sendNearbySafely(draft, info.target, MeshRuntime.state.value.peers, MeshRuntime::sendMessage)) draft = ""
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
            if (sendNearbySafely(m.body, m.recipientId, MeshRuntime.state.value.peers, MeshRuntime::sendMessage)) retried.add(m.messageId)
        } else {
            toast("Couldn't send. Check your relays in Settings, then send it again.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(info, back, openProfile, toast, pinned, togglePin, deleteChat) { copyText(context, "Relay ID", info.publicId); toast("ID copied") }

        // Short notes the old screens showed, kept so nothing important is lost.
        when {
            info.isGroup -> Notice("Everyone in Bluetooth range can read this group.")
            info.nearby && !mesh.serviceRunning -> Notice("Nearby is off.", "Turn on", openDiscover)
            !info.nearby && internet.inboxes.isEmpty() -> Notice("Set up Internet messaging before you can send.", "Set up", openSetup)
        }
        if (!info.nearby) internet.notice?.let { Notice(it) }
        if (info.nearby) mesh.errors.lastOrNull()?.let { Notice(it) }
        if (!info.nearby) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = olderMessages, enabled = !internet.threadLoading && internet.threadHasOlder) { Text("Older messages") }
                TextButton(onClick = latestMessages, enabled = !internet.threadLoading) { Text("Latest messages") }
            }
        }

        if (pinnedBubbles.isNotEmpty()) {
            val shown = pinnedBubbles[pinCursor.coerceIn(0, pinnedBubbles.lastIndex)]
            PinnedBanner(
                count = pinnedBubbles.size,
                index = pinCursor.coerceIn(0, pinnedBubbles.lastIndex),
                text = shown.body,
                onClick = {
                    val at = rows.indexOfFirst { it is ThreadRow.Message && it.bubble.id == shown.id }
                    if (at >= 0) scope.launch { listState.animateScrollToItem(at) }
                    pinCursor = (pinCursor + 1) % pinnedBubbles.size
                },
                onUnpin = { togglePinMessage(shown.id); toast("Message unpinned") }
            )
        }

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
                    modifier = Modifier.fillMaxSize().testTag("relay-message-list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is ThreadRow.Day -> row.key
                            is ThreadRow.Message -> row.bubble.id
                        }
                    }) { row ->
                        when (row) {
                            is ThreadRow.Day -> Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(RelayColors.Card)
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Text(text = row.label, color = RelayColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                            is ThreadRow.Message -> Box(
                                modifier = Modifier.padding(top = if (row.bigGapBefore) 8.dp else 0.dp)
                            ) {
                                MessageBubbleView(
                                b = row.bubble,
                                nearby = info.nearby,
                                showStatus = row.bubble.failed || row.bubble.id == lastMineId,
                                pinned = row.bubble.id in pinnedMessages,
                                onTogglePin = {
                                    togglePinMessage(row.bubble.id)
                                    toast(if (row.bubble.id in pinnedMessages) "Message unpinned" else "Message pinned")
                                },
                                onCopy = { copyText(context, "Relay message", row.bubble.body); toast("Message copied") },
                                onRetry = { retry(row.bubble) },
                                onDelete = {
                                    if (!info.nearby && row.bubble.pending) {
                                        toast("Wait until this message finishes sending.")
                                    } else {
                                        if (info.nearby) MeshRuntime.deleteMessage(row.bubble.id) else deleteInternetMessage(row.bubble.id)
                                        if (row.bubble.id in pinnedMessages) togglePinMessage(row.bubble.id)
                                        toast("Message deleted")
                                    }
                                }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (info.nearby && info.target != null) NearbyAttachments(info.target, mesh, attachmentRequest)

        Composer(
            draft = draft,
            onDraft = { draft = it },
            canSend = canSend,
            sendDescription = if (info.nearby) "Send message" else "Send encrypted message",
            onSend = send,
            onPlus = {
                when {
                    info.nearby && info.target != null -> attachmentRequest++
                    info.nearby -> toast("Choose a private Nearby chat to share a file. Group attachments are unavailable.")
                    else -> toast("Internet attachments are not available yet.")
                }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    info: RouteInfo, back: () -> Unit, openProfile: () -> Unit, toast: (String) -> Unit,
    pinned: Boolean, togglePin: () -> Unit, deleteChat: () -> Unit, copyId: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Delete chat?",
            text = "All messages with ${info.title} will be removed from this phone. The other person keeps their copy.",
            confirmLabel = "Delete chat",
            onConfirm = { confirmDelete = false; deleteChat() },
            onDismiss = { confirmDelete = false }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
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
            ConversationAvatar(info, 34)
            Spacer(Modifier.width(8.dp))
            Column {
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
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin chat" else "Pin chat") }, onClick = { menu = false; togglePin() })
                    DropdownMenuItem(text = { Text("Delete chat", color = RelayColors.Red) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
}

/** Bar under the chat header showing a pinned message. Tap to jump to it (and to the next pin if there are several). */
@Composable
private fun PinnedBanner(count: Int, index: Int, text: String, onClick: () -> Unit, onUnpin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RelayColors.Card)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(R.drawable.relay_pin, null, tint = RelayColors.Blue, size = 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                if (count > 1) "Pinned message ${index + 1} of $count" else "Pinned message",
                color = RelayColors.Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
            Text(text, color = RelayColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onUnpin) { Text("Unpin", color = RelayColors.TextSecondary, fontSize = 12.sp) }
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
private fun MessageBubbleView(
    b: BubbleUi, nearby: Boolean, showStatus: Boolean, pinned: Boolean, onTogglePin: () -> Unit,
    onCopy: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Delete message?",
            text = "This removes the message from this phone only. The other person keeps their copy.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
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
        // Rounded rectangle; bubbles that start a same-sender run also get a sharp
        // upper corner on the sender's side, echoing the reference client's tail.
        val bubbleShape = when {
            b.mine && b.runStart -> RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            b.mine -> RoundedCornerShape(16.dp)
            b.runStart -> RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
            else -> RoundedCornerShape(16.dp)
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(
                    when {
                        !b.mine -> RelayColors.CardHigh
                        b.failed -> RelayColors.BlueBubble.copy(alpha = 0.45f)
                        else -> RelayColors.BlueBubble
                    }
                )
                .combinedClickable(onClick = { if (b.failed) onRetry() }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            BubbleBody(b, pinned)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text(if (pinned) "Unpin message" else "Pin message") }, onClick = { menu = false; onTogglePin() })
                DropdownMenuItem(text = { Text("Delete", color = RelayColors.Red) }, onClick = { menu = false; confirmDelete = true })
            }
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

/**
 * Message text with the time/ticks tucked beside the last text line when that line
 * has room, otherwise on a line of its own at the bubble's lower right - the
 * reference client's behavior. Measured synchronously so there is no second frame.
 */
@Composable
private fun BubbleBody(b: BubbleUi, pinned: Boolean) {
    val measurer = rememberTextMeasurer()
    val bodyStyle = TextStyle(color = RelayColors.TextPrimary, fontSize = 15.sp)
    Layout(content = {
        Text(b.body, style = bodyStyle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val metaColor = if (b.mine) Color.White.copy(alpha = 0.8f) else RelayColors.TextMuted
            if (pinned) RIcon(R.drawable.relay_pin, "Pinned message", tint = metaColor, size = 11.dp)
            if (b.atMs > 0) Text(clockTime(b.atMs), color = metaColor, fontSize = 11.sp)
            if (b.ticks == 2) RIcon(R.drawable.relay_check_double, "Delivered", tint = metaColor, size = 13.dp)
            if (b.ticks == 1) RIcon(R.drawable.relay_check, "Sent", tint = metaColor, size = 13.dp)
        }
    }) { measurables, constraints ->
        val meta = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth))
        val gapPx = 6.dp.roundToPx()
        val belowGapPx = 2.dp.roundToPx()
        val result = if (b.body.isBlank()) null
            else measurer.measure(AnnotatedString(b.body), style = bodyStyle, constraints = constraints)
        val lastLine = (result?.lineCount ?: 0) - 1
        val plan = planBubbleLayout(
            lineCount = result?.lineCount ?: 0,
            textBlockWidth = result?.size?.width ?: 0,
            textBlockHeight = result?.size?.height ?: 0,
            lastLineRight = if (lastLine >= 0) result!!.getLineRight(lastLine) else 0f,
            lastLineTop = if (lastLine >= 0) result!!.getLineTop(lastLine) else 0f,
            lastLineBottom = if (lastLine >= 0) result!!.getLineBottom(lastLine) else 0f,
            metaWidth = meta.width,
            metaHeight = meta.height,
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
            gapPx = gapPx,
            belowGapPx = belowGapPx
        )
        val text = measurables[0].measure(Constraints.fixedWidth(plan.textWidth))
        layout(plan.width, plan.height) {
            text.place(0, 0)
            meta.place(plan.metaX, plan.metaY)
        }
    }
}

/** Where the bubble's text block and time/ticks row land. Pure so it is JVM-testable. */
internal data class BubbleLayoutPlan(
    val width: Int,
    val height: Int,
    val textWidth: Int,
    val metaX: Int,
    val metaY: Int,
    val inline: Boolean
)

internal fun planBubbleLayout(
    lineCount: Int,
    textBlockWidth: Int,
    textBlockHeight: Int,
    lastLineRight: Float,
    lastLineTop: Float,
    lastLineBottom: Float,
    metaWidth: Int,
    metaHeight: Int,
    minWidth: Int,
    maxWidth: Int,
    maxHeight: Int,
    gapPx: Int,
    belowGapPx: Int
): BubbleLayoutPlan {
    val textWidth = textBlockWidth.coerceAtMost(maxWidth)
    if (lineCount == 0) {
        // Nothing to align against: stack the meta under the text, right-aligned.
        val width = maxOf(textWidth, metaWidth).coerceIn(minWidth, maxWidth)
        val height = (textBlockHeight + belowGapPx + metaHeight).coerceAtMost(maxHeight)
        return BubbleLayoutPlan(width, height, textWidth,
            metaX = (width - metaWidth).coerceAtLeast(0),
            metaY = (textBlockHeight + belowGapPx).coerceIn(0, (height - metaHeight).coerceAtLeast(0)),
            inline = false)
    }
    val inline = lastLineRight + gapPx + metaWidth <= maxWidth
    val width = maxOf(
        textWidth,
        if (inline) (lastLineRight + gapPx + metaWidth).toInt() else metaWidth
    ).coerceIn(minWidth, maxWidth)
    val height = (if (inline) textBlockHeight else textBlockHeight + belowGapPx + metaHeight).coerceAtMost(maxHeight)
    val maxY = (height - metaHeight).coerceAtLeast(0)
    val metaY = if (inline)
        (lastLineTop + (lastLineBottom - lastLineTop - metaHeight) / 2f).toInt().coerceIn(0, maxY)
    else
        (textBlockHeight + belowGapPx).coerceIn(0, maxY)
    return BubbleLayoutPlan(width, height, textWidth, (width - metaWidth).coerceAtLeast(0), metaY, inline)
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
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(RelayColors.Card)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(28.dp).clickable(onClick = onPlus),
                    contentAlignment = Alignment.Center
                ) {
                    RIcon(R.drawable.relay_plus, "Add attachment", tint = RelayColors.TextSecondary, size = 22.dp)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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
            }
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
