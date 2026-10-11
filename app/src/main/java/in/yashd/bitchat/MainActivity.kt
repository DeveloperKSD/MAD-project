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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

@Composable
private fun MeshChatApp() {
    val context = LocalContext.current
    val state by MeshRuntime.state.collectAsState()
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        MeshRuntime.refreshReadiness()
    }

    LaunchedEffect(Unit) {
        MeshRuntime.refreshReadiness()
    }

    Scaffold(
        bottomBar = {
            MessageComposer(
                selectedPeerName = state.peers.firstOrNull { it.identity.peerId == selectedPeerId }?.identity?.displayName,
                onSend = { body -> MeshRuntime.sendMessage(body, selectedPeerId) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Header(state)
            }
            item {
                IdentityPanel(
                    state = state,
                    onRename = MeshRuntime::renameSelf,
                    onRotate = MeshRuntime::rotateIdentity
                )
            }
            item {
                ReadinessPanel(
                    readiness = state.bluetoothReadiness,
                    serviceRunning = state.serviceRunning,
                    onRequestPermissions = {
                        permissionLauncher.launch(runtimePermissions())
                    },
                    onStart = {
                        MeshRuntime.start()
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, MeshForegroundService::class.java)
                        )
                    },
                    onStop = {
                        context.stopService(Intent(context, MeshForegroundService::class.java))
                        MeshRuntime.stop()
                    }
                )
            }
            if (state.errors.isNotEmpty()) {
                item {
                    ErrorPanel(state.errors)
                }
            }
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
                        onClick = {
                            selectedPeerId = if (selectedPeerId == peer.identity.peerId) null else peer.identity.peerId
                        }
                    )
                }
            }
            item {
                SectionLabel("messages")
            }
            if (state.currentConversation.isEmpty()) {
                item {
                    EmptyRow("send a public message or select a peer for encrypted direct chat")
                }
            } else {
                items(state.currentConversation) { message ->
                    MessageRow(message)
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
                    TextButton(onClick = onRotate) {
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
private fun PeerRow(peer: PeerState, selected: Boolean, onClick: () -> Unit) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(peer.identity.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(peer.rssi?.let { "$it dBm" } ?: "nearby", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "${peer.state.name.lowercase()} / ${peer.identity.peerId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
                .navigationBarsPadding()
                .imePadding()
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
