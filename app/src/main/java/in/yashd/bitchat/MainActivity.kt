package `in`.yashd.bitchat

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.yashd.bitchat.bluetooth.BluetoothReadinessChecker
import `in`.yashd.bitchat.mesh.BluetoothReadiness
import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState
import `in`.yashd.bitchat.service.MeshForegroundService
import `in`.yashd.bitchat.ui.theme.BitchatTheme
import kotlinx.coroutines.delay

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
    val visibleMessages = remember(state.currentConversation, selectedPeerId) {
        state.currentConversation.filter { message ->
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
                        NavigationBarItem(
                            selected = index == tabIndex,
                            onClick = { tabIndex = index },
                            icon = { Text(item.glyph) },
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
                selectedPeerName = selectedPeerName,
                modifier = Modifier.padding(innerPadding),
                onBackToPublic = { selectedPeerId = null }
            )

            MainTab.Settings -> SettingsTab(
                state = state,
                modifier = Modifier.padding(innerPadding),
                onRename = MeshRuntime::renameSelf,
                onRotate = MeshRuntime::rotateIdentity,
                onRequestPermissions = onRequestPermissions,
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

@Composable
private fun ChatTab(
    state: MeshUiState,
    messages: List<ChatMessage>,
    selectedPeerName: String?,
    modifier: Modifier = Modifier,
    onBackToPublic: () -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        topItems(state)
        if (selectedPeerName != null) {
            item {
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
        item {
            SectionLabel("messages")
        }
        if (messages.isEmpty()) {
            item {
                EmptyRow("send a public message or select a peer for encrypted direct chat")
            }
        } else {
            items(messages) { message ->
                MessageRow(message)
            }
        }
    }
}

@Composable
private fun SettingsTab(
    state: MeshUiState,
    modifier: Modifier = Modifier,
    onRename: (String) -> Unit,
    onRotate: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
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
            IdentityPanel(
                state = state,
                onRename = onRename,
                onRotate = onRotate
            )
        }
        item {
            ReadinessPanel(
                readiness = state.bluetoothReadiness,
                serviceRunning = state.serviceRunning,
                onRequestPermissions = onRequestPermissions,
                onStart = onStart,
                onStop = onStop
            )
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
private fun IdentityPanel(
    state: MeshUiState,
    onRename: (String) -> Unit,
    onRotate: () -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var confirmRotate by rememberSaveable { mutableStateOf(false) }

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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = state.displayName.ifBlank { "nearby" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
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
                Row {
                    TextButton(onClick = { editing = !editing }) {
                        Text("rename", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { confirmRotate = true }) {
                        Text("rotate", style = MaterialTheme.typography.labelMedium)
                    }
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
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ReadinessRow("permissions", "scan advertise connect", readiness.hasPermissions)
            ReadinessRow("adapter", "bluetooth enabled", readiness.bluetoothEnabled)
            ReadinessRow("transport", "advertise scan gatt", readiness.canAdvertise && readiness.canScan && readiness.canGattServer)
            ReadinessRow("hardware", "bluetooth le required", readiness.hasBle)
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
private fun ReadinessRow(label: String, detail: String, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(
            text = if (ready) "ready" else "idle",
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
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
private fun MessageRow(message: ChatMessage) {
    val mine = message.direction == MessageDirection.Outbound
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(8.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (mine) "me" else message.senderId.take(8),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${message.status.name.lowercase()} / ${if (message.isPrivate) "encrypted" else "public"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(message.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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