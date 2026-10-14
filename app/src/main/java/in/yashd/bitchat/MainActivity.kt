package `in`.yashd.bitchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.yashd.bitchat.bluetooth.BluetoothReadinessChecker
import `in`.yashd.bitchat.mesh.BluetoothReadiness
import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState
import `in`.yashd.bitchat.service.MeshForegroundService
import `in`.yashd.bitchat.ui.theme.BitchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshRuntime.initialize(applicationContext)
        setContent {
            BitchatTheme {
                MeshChatApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // e.g. after the user turns Bluetooth on in system settings
        MeshRuntime.refreshReadiness()
    }
}

private enum class MainTab(val label: String, val glyph: String) {
    Peers("peers", "◉"),
    Chat("chat", "✉"),
    Settings("settings", "⚙")
}

@Composable
private fun MeshChatApp() {
    val context = LocalContext.current
    val state by MeshRuntime.state.collectAsState()
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = MainTab.values()
    val tab = tabs[tabIndex]

    // ticks every 15s so "2m ago" keeps updating
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        MeshRuntime.refreshReadiness()
    }

    LaunchedEffect(Unit) {
        MeshRuntime.refreshReadiness()
    }

    val onRequestPermissions: () -> Unit = {
        permissionLauncher.launch(runtimePermissions())
    }
    val onStart: () -> Unit = {
        MeshRuntime.start()
        ContextCompat.startForegroundService(
            context,
            Intent(context, MeshForegroundService::class.java)
        )
    }
    val onStop: () -> Unit = {
        context.stopService(Intent(context, MeshForegroundService::class.java))
        MeshRuntime.stop()
    }

    // Per-chat filtering: public feed vs. private chat with the selected peer
    val retriedIds = remember { mutableStateListOf<String>() }
    val visibleMessages = remember(state.currentConversation, selectedPeerId, retriedIds.size) {
        state.currentConversation.filter { it.messageId !in retriedIds }.filter { message ->
            if (selectedPeerId == null) {
                // public chat: only broadcast messages
                message.recipientId == null
            } else {
                // private chat: only encrypted messages to/from the selected peer
                message.isPrivate &&
                        (message.senderId == selectedPeerId || message.recipientId == selectedPeerId)
            }
        }
    }

    val selectedPeerName = state.peers.firstOrNull { it.identity.peerId == selectedPeerId }?.identity?.displayName
    val peerNames = remember(state.peers) {
        state.peers.associate { it.identity.peerId to it.identity.displayName }
    }
    val chatListState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { text ->
        snackScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    // unread badge: inbound messages that arrived while the chat tab was not open
    var lastReadMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tab, state.currentConversation) {
        if (tab == MainTab.Chat) lastReadMs = System.currentTimeMillis()
    }
    val unreadCount = if (tab == MainTab.Chat) 0 else state.currentConversation.count {
        it.direction == MessageDirection.Inbound && (it.receivedAtMs ?: it.createdAtMs) > lastReadMs
    }

    val onCopyMessage: (ChatMessage) -> Unit = { message ->
        copyToClipboard(context, "message", message.body)
        showMessage("Message copied")
    }
    val onCopyId: () -> Unit = {
        if (state.localPeerId.isNotBlank()) {
            copyToClipboard(context, "peer id", state.localPeerId)
            showMessage("ID copied")
        }
    }
    val onShareId: () -> Unit = {
        if (state.localPeerId.isNotBlank()) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "My bitchat ID: ${state.localPeerId}")
            }
            context.startActivity(Intent.createChooser(send, "Share your ID"))
        }
    }
    val onOpenBluetoothSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { showMessage("Couldn't open Bluetooth settings") }
    }
    val onRetryMessage: (ChatMessage) -> Unit = { message ->
        val target = message.recipientId
        if (target != null && state.peers.none { it.identity.peerId == target }) {
            // never resend a private message unless the peer is nearby (it would go out unencrypted)
            showMessage("Peer not nearby, try again when they are back")
        } else {
            retriedIds.add(message.messageId)
            MeshRuntime.sendMessage(message.body, target)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            MeshControlBar(
                state = state,
                onRequestPermissions = onRequestPermissions,
                onStart = onStart,
                onStop = onStop
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column {
                if (tab == MainTab.Chat) {
                    MessageComposer(
                        selectedPeerName = selectedPeerName,
                        onSend = { body -> MeshRuntime.sendMessage(body, selectedPeerId) }
                    )
                }
                NavigationBar {
                    tabs.forEachIndexed { index, item ->
                        val badgeCount = if (item == MainTab.Chat) unreadCount else 0
                        NavigationBarItem(
                            selected = index == tabIndex,
                            onClick = { tabIndex = index },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (badgeCount > 0) {
                                            Badge { Text(if (badgeCount > 9) "9+" else "$badgeCount") }
                                        }
                                    }
                                ) {
                                    Text(item.glyph)
                                }
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (tab) {
            MainTab.Peers -> PeersTab(
                state = state,
                selectedPeerId = selectedPeerId,
                now = now,
                modifier = Modifier.padding(innerPadding),
                onPeerClick = { peer ->
                    if (selectedPeerId == peer.identity.peerId) {
                        selectedPeerId = null
                    } else {
                        selectedPeerId = peer.identity.peerId
                        tabIndex = MainTab.Chat.ordinal
                    }
                }
            )

            MainTab.Chat -> ChatTab(
                state = state,
                messages = visibleMessages,
                peerNames = peerNames,
                now = now,
                conversationKey = selectedPeerId,
                selectedPeerName = selectedPeerName,
                listState = chatListState,
                modifier = Modifier.padding(innerPadding),
                onBackToPublic = { selectedPeerId = null },
                onCopy = onCopyMessage,
                onRetry = onRetryMessage
            )

            MainTab.Settings -> SettingsTab(
                state = state,
                modifier = Modifier.padding(innerPadding),
                onRename = { name ->
                    MeshRuntime.renameSelf(name)
                    showMessage("Name updated")
                },
                onRotate = {
                    MeshRuntime.rotateIdentity()
                    showMessage("Identity rotated")
                },
                onCopyId = onCopyId,
                onShareId = onShareId,
                onRequestPermissions = onRequestPermissions,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onStart = onStart,
                onStop = onStop
            )
        }
    }
}

private fun LazyListScope.topItems(state: MeshUiState) {
    if (state.errors.isNotEmpty()) {
        item {
            ErrorPanel(state.errors)
        }
    }
}

@Composable
private fun PeersTab(
    state: MeshUiState,
    selectedPeerId: String?,
    now: Long,
    modifier: Modifier = Modifier,
    onPeerClick: (PeerState) -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        topItems(state)
        item {
            SectionLabel("nearby")
        }
        if (state.peers.isEmpty()) {
            item {
                EmptyRow("no peers discovered yet")
            }
        } else {
            items(state.peers) { peer ->
                PeerRow(
                    peer = peer,
                    selected = peer.identity.peerId == selectedPeerId,
                    now = now,
                    onClick = { onPeerClick(peer) }
                )
            }
        }
    }
}

private sealed class ChatRow {
    data class DateHeader(val dayKey: Int, val label: String) : ChatRow()
    data class Bubble(
        val message: ChatMessage,
        val senderName: String,
        val showName: Boolean
    ) : ChatRow()
}

private fun dayKey(ms: Long, dayOffset: Int = 0): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = ms
    calendar.add(Calendar.DAY_OF_YEAR, dayOffset)
    return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(ms: Long, now: Long): String {
    val key = dayKey(ms)
    return when (key) {
        dayKey(now) -> "Today"
        dayKey(now, -1) -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

private fun buildChatRows(
    messages: List<ChatMessage>,
    peerNames: Map<String, String>,
    now: Long
): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var lastDay = -1
    var lastSender: String? = null
    for (message in messages) {
        val day = dayKey(message.createdAtMs)
        if (day != lastDay) {
            rows.add(ChatRow.DateHeader(day, dayLabel(message.createdAtMs, now)))
            lastDay = day
            lastSender = null
        }
        val mine = message.direction == MessageDirection.Outbound
        val name = if (mine) "me" else (peerNames[message.senderId] ?: message.senderId.take(8))
        rows.add(ChatRow.Bubble(message, name, showName = message.senderId != lastSender))
        lastSender = message.senderId
    }
    return rows
}

@Composable
private fun ChatTab(
    state: MeshUiState,
    messages: List<ChatMessage>,
    peerNames: Map<String, String>,
    now: Long,
    conversationKey: String?,
    selectedPeerName: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onBackToPublic: () -> Unit,
    onCopy: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit
) {
    val today = dayKey(now)
    val rows = remember(messages, peerNames, today) { buildChatRows(messages, peerNames, now) }
    val scope = rememberCoroutineScope()

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    // read during composition, so it still holds the value from before a new message is laid out
    val wasAtBottom = atBottom
    val newestIsMine = messages.lastOrNull()?.direction == MessageDirection.Outbound
    val lastIndex = rows.size - 1

    var unseen by remember { mutableStateOf(0) }
    var prevKey by remember { mutableStateOf(conversationKey) }
    var prevCount by remember { mutableStateOf(-1) }

    LaunchedEffect(conversationKey, messages.size) {
        if (prevCount == -1 || prevKey != conversationKey) {
            // opened the chat or switched conversation: jump to the newest message
            unseen = 0
            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
        } else if (messages.size > prevCount) {
            if (wasAtBottom || newestIsMine) {
                unseen = 0
                if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
            } else {
                unseen += messages.size - prevCount
            }
        }
        prevKey = conversationKey
        prevCount = messages.size
    }
    LaunchedEffect(atBottom) {
        if (atBottom) unseen = 0
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (state.errors.isNotEmpty()) {
            Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
                ErrorPanel(state.errors)
            }
        }
        if (selectedPeerName != null) {
            Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
                CompactCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "encrypted chat with $selectedPeerName",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = onBackToPublic) {
                            Text("public feed", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (rows.isEmpty()) {
                Box(modifier = Modifier.padding(16.dp)) {
                    EmptyRow("send a public message or select a peer for encrypted direct chat")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        rows,
                        key = { row ->
                            when (row) {
                                is ChatRow.DateHeader -> "date-${row.dayKey}"
                                is ChatRow.Bubble -> row.message.messageId
                            }
                        }
                    ) { row ->
                        when (row) {
                            is ChatRow.DateHeader -> DateSeparator(row.label)
                            is ChatRow.Bubble -> MessageBubble(
                                row = row,
                                onCopy = { onCopy(row.message) },
                                onRetry = { onRetry(row.message) }
                            )
                        }
                    }
                }
            }
            if (!atBottom && unseen > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            scope.launch {
                                if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
                                unseen = 0
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "\u2193 $unseen new",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsTab(
    state: MeshUiState,
    modifier: Modifier = Modifier,
    onRename: (String) -> Unit,
    onRotate: () -> Unit,
    onCopyId: () -> Unit,
    onShareId: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        topItems(state)
        item {
            SectionLabel("Identity")
        }
        item {
            IdentityPanel(
                state = state,
                onRename = onRename,
                onRotate = onRotate,
                onCopyId = onCopyId,
                onShareId = onShareId
            )
        }
        item {
            SectionLabel("Connection")
        }
        item {
            ReadinessPanel(
                readiness = state.bluetoothReadiness,
                serviceRunning = state.serviceRunning,
                onRequestPermissions = onRequestPermissions,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onStart = onStart,
                onStop = onStop
            )
        }
        item {
            SectionLabel("About")
        }
        item {
            AboutCard(version)
        }
    }
}

@Composable
private fun MeshControlBar(
    state: MeshUiState,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "bitchat",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (state.serviceRunning) "mesh on / ${state.peers.size} nearby" else "mesh off",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.serviceRunning) {
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("stop", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Button(
                    onClick = if (state.bluetoothReadiness.hasPermissions) onStart else onRequestPermissions,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (state.bluetoothReadiness.hasPermissions) "start mesh" else "allow bluetooth",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: MeshUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "bitchat",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "bluetooth-only mesh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusPill(
            text = if (state.serviceRunning) "mesh on" else "mesh off",
            color = if (state.serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun InitialAvatar(name: String, size: Dp) {
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun IdentityPanel(
    state: MeshUiState,
    onRename: (String) -> Unit,
    onRotate: () -> Unit,
    onCopyId: () -> Unit,
    onShareId: () -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var confirmRotate by rememberSaveable { mutableStateOf(false) }
    val name = state.displayName.ifBlank { "nearby" }
    val hasId = state.localPeerId.isNotBlank()

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("rotate identity?") },
            text = {
                Text("this creates a new peer id and new keys. nearby peers will see you as a different person, and the mesh will stop until you start it again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    onRotate()
                }) {
                    Text("rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) {
                    Text("cancel")
                }
            }
        )
    }

    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InitialAvatar(name = name, size = 56.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.localPeerId.ifBlank { "identity loading" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "persistent signed identity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyId,
                    enabled = hasId,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("copy ID", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onShareId,
                    enabled = hasId,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("share ID", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { editing = !editing }) {
                    Text("rename", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { confirmRotate = true }) {
                    Text("rotate", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            onRename(draftName)
                            editing = false
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("save", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessPanel(
    readiness: BluetoothReadiness,
    serviceRunning: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val transportReady = readiness.canAdvertise && readiness.canScan && readiness.canGattServer
    val transportHint = if (!readiness.hasPermissions || !readiness.bluetoothEnabled) {
        "Fix the steps above first"
    } else {
        "This phone can't scan or advertise over Bluetooth LE"
    }
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadinessRow(
                label = "permissions",
                detail = "scan, advertise and connect allowed",
                ready = readiness.hasPermissions,
                hint = "Allow Bluetooth permissions",
                actionLabel = "allow",
                onAction = onRequestPermissions
            )
            ReadinessRow(
                label = "bluetooth",
                detail = "bluetooth is on",
                ready = readiness.bluetoothEnabled,
                hint = "Turn on Bluetooth",
                actionLabel = "turn on",
                onAction = onOpenBluetoothSettings
            )
            ReadinessRow(
                label = "mesh radio",
                detail = "can scan, advertise and connect",
                ready = transportReady,
                hint = transportHint,
                actionLabel = null,
                onAction = null
            )
            ReadinessRow(
                label = "hardware",
                detail = "bluetooth low energy supported",
                ready = readiness.hasBle,
                hint = "This device doesn't support Bluetooth Low Energy",
                actionLabel = null,
                onAction = null
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = if (readiness.hasPermissions) onStart else onRequestPermissions,
                    modifier = Modifier.weight(1f),
                    enabled = !serviceRunning,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (readiness.hasPermissions) "start mesh" else "allow bluetooth", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = serviceRunning,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("stop", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    label: String,
    detail: String,
    ready: Boolean,
    hint: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = if (ready) detail else hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
            )
        }
        if (ready) {
            StatusPill(text = "ready", color = MaterialTheme.colorScheme.primary)
        } else if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(8.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            StatusPill(text = "unavailable", color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AboutCard(version: String) {
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "bitchat",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "version $version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "how the mesh works",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Phones nearby find each other over Bluetooth Low Energy and swap signed announcements. " +
                        "Public messages go out to everyone in range, and private messages are encrypted so only the chosen peer can read them. " +
                        "Every phone also passes packets along, so a message can hop through other phones to reach someone out of direct range. " +
                        "No internet, account or phone number is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PeerRow(peer: PeerState, selected: Boolean, now: Long, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerDot(peer.state == PeerConnectionState.Authenticated)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(peer.identity.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(peer.rssi?.let { "$it dBm" } ?: "nearby", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SignalBars(peer.rssi?.toInt())
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${peer.state.name.lowercase()} / ${peer.identity.peerId}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatLastSeen(peer.identity.lastSeenMs, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalBars(rssi: Int?) {
    val level = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (bar in 1..4) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((3 + bar * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (bar <= level) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

private fun formatLastSeen(lastSeenMs: Long?, now: Long): String {
    if (lastSeenMs == null) return "unknown"
    val seconds = ((now - lastSeenMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

@Composable
private fun FailedBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(row: ChatRow.Bubble, onCopy: () -> Unit, onRetry: () -> Unit) {
    val message = row.message
    val mine = message.direction == MessageDirection.Outbound
    val failed = message.status == MessageStatus.Failed
    val shape = RoundedCornerShape(8.dp)
    val statusText = when (message.status) {
        MessageStatus.Pending -> "sending"
        MessageStatus.SentToTransport -> "sent"
        MessageStatus.Delivered -> "delivered"
        MessageStatus.Read -> "read"
        MessageStatus.Failed -> "failed, tap to retry"
    }
    val footer = "${formatTime(message.createdAtMs)} / $statusText / ${if (message.isPrivate) "encrypted" else "public"}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (row.showName) 8.dp else 0.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (mine && failed) {
            FailedBadge(onClick = onRetry)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(shape)
                .combinedClickable(
                    onClick = { if (failed) onRetry() },
                    onLongClick = onCopy
                ),
            shape = shape,
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (row.showName) {
                    Text(
                        text = row.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(message.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = footer,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(selectedPeerName: String?, onSend: (String) -> Unit) {
    var body by rememberSaveable { mutableStateOf("") }

    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HorizontalDivider(thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = selectedPeerName?.let { "encrypted direct to $it" } ?: "public nearby message",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("message", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = {
                        onSend(body)
                        body = ""
                    },
                    enabled = body.isNotBlank(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("send", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(errors: List<String>) {
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            errors.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompactCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun PeerDot(verified: Boolean) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun runtimePermissions(): Array<String> {
    val permissions = BluetoothReadinessChecker.requiredPermissions().toMutableList()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += android.Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions.toTypedArray()
}

@Preview(showBackground = true)
@Composable
private fun MeshChatPreview() {
    BitchatTheme(dynamicColor = false) {
        MeshChatApp()
    }
}