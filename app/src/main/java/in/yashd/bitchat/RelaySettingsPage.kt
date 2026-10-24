package `in`.yashd.bitchat

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.ui.relay.RelayUiState

// ---------------------------------------------------------------------------
// Settings (Figma 5).
// Bluetooth opens the existing Nearby identity/connection controls,
// Nostr Relays opens the existing Internet Setup. Rows marked "coming soon" are UI only.
// ---------------------------------------------------------------------------

@Composable
internal fun RelaySettingsPage(
    mesh: MeshUiState,
    internet: RelayUiState,
    myName: String,
    onAvatar: () -> Unit,
    openNearbyDetails: () -> Unit,
    openSetup: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    var diagnostics by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    val pending = internet.messages.count { it.outgoing && it.status == "Sending…" } +
        mesh.currentConversation.count { it.direction == MessageDirection.Outbound && it.status == MessageStatus.Pending }

    if (diagnostics) DiagnosticsDialog(mesh, internet) { diagnostics = false }
    if (about) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        AboutDialog(version) { about = false }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("relay-settings-list"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { RelayHeader("Settings", myName, onAvatar) }

        // profile card
        item {
            RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = openNearbyDetails)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonAvatar(myName.ifBlank { "Me" }, 54.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            myName.ifBlank { "You" },
                            color = RelayColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (internet.npub.isEmpty()) "Creating your Relay ID…" else shortId(internet.npub, 9, 4),
                                color = RelayColors.TextSecondary,
                                fontSize = 13.sp
                            )
                            if (internet.npub.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            copyText(context, "Relay ID", internet.npub)
                                            toast("Your Relay ID was copied")
                                        }
                                        .padding(4.dp)
                                ) {
                                    RIcon(R.drawable.relay_copy, "Copy your Relay ID", tint = RelayColors.TextSecondary, size = 16.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // connection rows
        item {
            RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                RelayListRow(R.drawable.relay_bluetooth, "Bluetooth", iconTint = RelayColors.Bluetooth, onClick = openNearbyDetails) {
                    when {
                        !mesh.bluetoothReadiness.hasBle -> DotLabel("Unavailable", RelayColors.TextMuted)
                        mesh.serviceRunning -> DotLabel("On", RelayColors.Green)
                        else -> DotLabel("Off", RelayColors.TextMuted)
                    }
                }
                RowDivider()
                RelayListRow(R.drawable.relay_broadcast, "Nostr Relays", iconTint = RelayColors.Nostr, onClick = openSetup) {
                    when {
                        internet.inboxes.isEmpty() -> DotLabel("Not set up", RelayColors.Orange)
                        internet.inboxesConnected -> DotLabel("Connected", RelayColors.Green)
                        internet.active && internet.connections.isNotEmpty() -> DotLabel("Connecting", RelayColors.Orange)
                        else -> DotLabel("Offline", RelayColors.TextMuted)
                    }
                }
                RowDivider()
                RelayListRow(R.drawable.relay_cloud, "Sync Queue", iconTint = RelayColors.Bluetooth, onClick = {
                    toast(if (pending == 0) "Nothing is waiting to send" else "$pending message(s) waiting to send")
                }) {
                    if (pending > 0) DotLabel("$pending pending", RelayColors.Orange)
                    else DotLabel("Up to date", RelayColors.TextMuted)
                }
            }
        }

        item {
            RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                RelayListRow(R.drawable.relay_shield, "Privacy", onClick = { toast("Privacy settings are coming soon") })
                RowDivider()
                RelayListRow(R.drawable.relay_bell, "Notifications", onClick = { toast("Notification settings are coming soon") })
                RowDivider()
                RelayListRow(R.drawable.relay_storage, "Storage", onClick = {
                    toast("Internet chats are saved encrypted on this device and are not backed up")
                })
            }
        }

        item {
            RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                RelayListRow(R.drawable.relay_help, "Help & FAQ", onClick = { toast("Help & FAQ is coming soon") })
                RowDivider()
                RelayListRow(R.drawable.relay_pulse, "Diagnostics", onClick = { diagnostics = true })
                RowDivider()
                RelayListRow(R.drawable.relay_info, "About Relay", onClick = { about = true })
            }
        }
    }
}

/** Sub-page that hosts the existing Nearby identity and Bluetooth controls from MainActivity. */
@Composable
internal fun NearbyDetailsPage(back: () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { RIcon(R.drawable.relay_back, "Back", tint = RelayColors.TextPrimary) }
            Text("Bluetooth & identity", color = RelayColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun DiagnosticsDialog(mesh: MeshUiState, internet: RelayUiState, dismiss: () -> Unit) {
    val r = mesh.bluetoothReadiness
    val connected = internet.inboxes.count { internet.connections[it] == "connected" }
    val lines = listOf(
        "Bluetooth" to if (r.bluetoothEnabled) "on" else "off",
        "Bluetooth permissions" to if (r.hasPermissions) "granted" else "missing",
        "Bluetooth LE" to if (r.hasBle) "supported" else "not supported",
        "Scan / advertise" to if (r.canScan && r.canAdvertise && r.canGattServer) "ready" else "not ready",
        "Nearby" to if (mesh.serviceRunning) "running" else "stopped",
        "People found" to mesh.peers.size.toString(),
        "Inbox relays" to if (internet.inboxes.isEmpty()) "not set up" else "$connected of ${internet.inboxes.size} connected",
        "Saved chats" to when {
            internet.storageLoading -> "loading"
            internet.storageError != null -> "unavailable"
            else -> "ok"
        }
    )
    val problems = mesh.errors + listOfNotNull(internet.error)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, Modifier.weight(1f), color = RelayColors.TextSecondary, fontSize = 13.sp)
                        Text(value, color = RelayColors.TextPrimary, fontSize = 13.sp)
                    }
                }
                if (problems.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Recent problems", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    problems.forEach { Text(it, color = RelayColors.Orange, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

@Composable
private fun AboutDialog(version: String, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("About Relay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Version $version", color = RelayColors.TextSecondary, fontSize = 13.sp)
                Text(
                    "Phones nearby find each other over Bluetooth Low Energy. Public messages reach everyone in range, " +
                        "private messages are encrypted, and phones pass messages along so they can hop to people out of direct range.",
                    fontSize = 13.sp
                )
                Text(
                    "Internet chats are end-to-end encrypted and sent through Nostr relays that you choose.",
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}
