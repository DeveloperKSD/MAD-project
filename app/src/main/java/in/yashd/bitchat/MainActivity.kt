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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import `in`.yashd.bitchat.service.MeshForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val internetModel by lazy {
        androidx.lifecycle.ViewModelProvider(this, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return `in`.yashd.bitchat.ui.relay.RelayViewModel(
                    `in`.yashd.bitchat.ui.relay.AndroidInternetBackend(
                        `in`.yashd.bitchat.nostr.NostrIdentityManager(applicationContext)),
                    history = `in`.yashd.bitchat.data.internet.RoomInternetHistoryRepository(applicationContext)) as T
            }
        })[`in`.yashd.bitchat.ui.relay.RelayViewModel::class.java]
    }

    override fun onStart() {
        super.onStart()
        internetModel.start()
    }

    override fun onStop() {
        internetModel.stop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        MeshRuntime.initialize(applicationContext)
        setContent {
            RelayMeshHost(internetModel)
        }
    }

    override fun onResume() {
        super.onResume()
        // e.g. after the user turns Bluetooth on in system settings
        MeshRuntime.refreshReadiness()
    }
}

@Composable
private fun RelayMeshHost(model: `in`.yashd.bitchat.ui.relay.RelayViewModel) {
    val context = LocalContext.current
    val state by MeshRuntime.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MeshRuntime.refreshReadiness()
    }
    val request: () -> Unit = { launcher.launch(runtimePermissions()) }
    val start: () -> Unit = {
        MeshRuntime.start()
        ContextCompat.startForegroundService(context, Intent(context, MeshForegroundService::class.java))
    }
    val stop: () -> Unit = {
        context.stopService(Intent(context, MeshForegroundService::class.java))
        MeshRuntime.stop()
    }
    val bluetoothSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { model.error("Couldn’t open Bluetooth settings.") }
    }
    // New Figma UI (RelayHome.kt). The previous screens in ui/relay are left as they were.
    RelayHome(model, state, start, stop, request, bluetoothSettings,
        nearbySettings = {
            item { SectionLabel("Nearby identity") }
            item { IdentityPanel(state,
                onRename = { MeshRuntime.renameSelf(it) },
                onRotate = { MeshRuntime.rotateIdentity() },
                onCopyId = { copyToClipboard(context, "Relay Nearby ID", state.localPeerId) },
                onShareId = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "My Relay Nearby ID: ${state.localPeerId}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share your Relay ID"))
                }) }
            item {
                var advanced by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nearby connection", style = MaterialTheme.typography.titleMedium)
                    Text(if (!state.bluetoothReadiness.hasBle) "Nearby isn’t available on this device. Internet messaging still works." else if (state.serviceRunning) "Nearby is on" else "Nearby is off", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.bluetoothReadiness.hasPermissions) TextButton(onClick = request) { Text("Allow Bluetooth") }
                    TextButton(onClick = bluetoothSettings) { Text("Open Bluetooth settings") }
                    if (state.serviceRunning) TextButton(onClick = stop) { Text("Stop Nearby") }
                    else Button(onClick = when {
                        !state.bluetoothReadiness.hasPermissions -> request
                        !state.bluetoothReadiness.bluetoothEnabled -> bluetoothSettings
                        else -> start
                    }, enabled = state.bluetoothReadiness.hasBle) { Text("Find Nearby") }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide Nearby Advanced" else "Nearby Advanced") }
                    if (advanced) ReadinessPanel(state.bluetoothReadiness, state.serviceRunning, request, bluetoothSettings, start, stop)
                }
            }
            item { AboutCard(runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown") }
        })
}

@Composable
private fun NearbyConversationScreen(state: MeshUiState, peerId: String?, back: () -> Unit,
    openGroup: () -> Unit, showError: (String) -> Unit) {
    val retriedIds = remember { mutableStateListOf<String>() }
    val messages = state.currentConversation.filter { message ->
        message.messageId !in retriedIds && if (peerId == null) !message.isPrivate && message.recipientId == null
        else message.isPrivate && (message.senderId == peerId || message.recipientId == peerId)
    }
    val name = if (peerId == null) null else state.peers.firstOrNull { it.identity.peerId == peerId }?.identity?.displayName ?: peerId.take(12)
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val send: (String) -> Boolean = { body ->
        `in`.yashd.bitchat.ui.relay.sendNearbySafely(body, peerId, MeshRuntime.state.value.peers, MeshRuntime::sendMessage).also {
            if (!it) showError("This friend is no longer nearby. Find them in Discover before sending a private message.")
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = back) {
                `in`.yashd.bitchat.ui.relay.RelayIcon(R.drawable.relay_back, "Back to Chats")
            }
            Column(Modifier.weight(1f)) {
                Text(name ?: "Nearby group", style = MaterialTheme.typography.titleMedium)
                Text(if (peerId == null) "Nearby · public group" else "Nearby · private chat", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (peerId == null) Text("Everyone in range can read this group. Choose a person in Discover for a private chat.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        ChatTab(state, messages, state.peers.associate { it.identity.peerId to it.identity.displayName }, now,
            peerId, name, rememberLazyListState(), Modifier.weight(1f), openGroup,
            onCopy = { copyToClipboard(context, "Relay message", it.body) },
            onRetry = { message ->
                if (`in`.yashd.bitchat.ui.relay.sendNearbySafely(message.body, message.recipientId,
                    MeshRuntime.state.value.peers, MeshRuntime::sendMessage)) retriedIds.add(message.messageId)
                else showError("This friend is no longer nearby. Your private message was not resent.")
            })
        androidx.compose.runtime.key(peerId) { MessageComposer(name, send) }
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
                            Text("Nearby group", style = MaterialTheme.typography.labelMedium)
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
                    EmptyRow(if (conversationKey == null) "Say hello to people nearby. Everyone in range can read this group." else "Say hello! Messages in this Nearby Chat are private.")
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
                        text = "Your Nearby identity",
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
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("copy ID", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onShareId,
                    enabled = hasId,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
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
                        shape = RoundedCornerShape(18.dp)
                    )
                    Button(
                        onClick = {
                            onRename(draftName)
                            editing = false
                        },
                        shape = RoundedCornerShape(18.dp)
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
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (readiness.hasPermissions) "start mesh" else "allow bluetooth", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = serviceRunning,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
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
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(18.dp)) {
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
                    text = "Relay",
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
    val shape = RoundedCornerShape(18.dp)
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
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
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
                    color = if (failed) MaterialTheme.colorScheme.error else if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(selectedPeerName: String?, onSend: (String) -> Boolean) {
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
                text = selectedPeerName?.let { "Private Nearby Chat with $it" } ?: "Nearby group · everyone in range can read this",
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
                    shape = RoundedCornerShape(18.dp)
                )
                Button(
                    onClick = {
                        if (onSend(body)) body = ""
                    },
                    enabled = body.isNotBlank(),
                    shape = RoundedCornerShape(18.dp)
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
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
    Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
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
