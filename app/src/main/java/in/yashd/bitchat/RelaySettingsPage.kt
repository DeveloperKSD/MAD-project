package `in`.yashd.bitchat

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
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
// Nostr Relays opens the existing Internet Setup.
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
    var infoPage by remember { mutableStateOf<String?>(null) }

    val pending = internet.messages.count { it.outgoing && it.status == "Sending…" } +
        mesh.currentConversation.count { it.direction == MessageDirection.Outbound && it.status == MessageStatus.Pending }

    if (diagnostics) DiagnosticsDialog(mesh, internet) { diagnostics = false }

    val page = infoPage
    if (page != null) {
        when (page) {
            "privacy" -> SettingsInfoPage("Privacy", { infoPage = null }) {
                infoSection("Nearby chats",
                    "Nearby chats travel over Bluetooth Low Energy between phones. Private chats are encrypted end to end; the public Nearby group can be read by anyone in range.")
                infoSection("Internet chats",
                    "Internet chats are end-to-end encrypted and pass through Nostr relay servers you choose. Publishing your inbox shares your public ID and chosen server addresses, never your private key.")
                infoSection("On this device",
                    "Internet chats are saved encrypted on this device and are not backed up or synchronized. Attachments you receive in Nearby chats are saved on this device.")
                infoSection("Requests",
                    "Accepting a chat request notifies the other phone. Declining sends no reply.")
            }
            "notifications" -> SettingsInfoPage("Notifications", { infoPage = null }) {
                infoSection("How alerts work",
                    "Relay shows notifications for new messages and chat requests on the 'New messages' Android channel, with vibration on.")
                infoSection("Your settings win",
                    "Once you change sound or vibration for the channel in Android settings, Relay keeps your choices. Android may ask for notification permission before alerts can show.")
                item {
                    RelayCard {
                        RelayListRow(R.drawable.relay_bell, "Manage notifications in Android settings", onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }.onFailure { toast("Couldn't open Android settings") }
                        })
                    }
                }
            }
            "storage" -> SettingsInfoPage("Storage", { infoPage = null }) {
                infoSection("Chats",
                    "Internet chats are saved encrypted on this device and are not backed up. Saved chats: " + when {
                        internet.storageLoading -> "loading"
                        internet.storageError != null -> "unavailable right now (existing data was preserved)"
                        else -> "available"
                    })
                infoSection("Attachments",
                    "Files you receive in Nearby chats are saved on this device.")
                infoSection("App data",
                    "Clearing the app's storage in Android settings removes saved chats and attachments.")
            }
            "about" -> SettingsInfoPage("About Relay", { infoPage = null }) {
                item {
                    Text("Version " + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"),
                        color = RelayColors.TextSecondary, fontSize = RelayType.Secondary)
                }
                infoSection("Nearby chats",
                    "Phones nearby find each other over Bluetooth Low Energy. Public messages reach everyone in range, private messages are encrypted, and phones pass messages along so they can hop to people out of direct range.")
                infoSection("Internet chats",
                    "Internet chats are end-to-end encrypted and sent through Nostr relays that you choose.")
            }
            "help" -> SettingsInfoPage("Help & FAQ", { infoPage = null }) {
                infoSection("Why is my message waiting to send?",
                    "Nearby messages wait in a queue until a phone that can carry them is in range. Pull down on the chat list to retry.")
                infoSection("What does '~2 hops' mean?",
                    "An approximate count of the phones your message passed through on the way.")
                infoSection("Why do I see 'Offline'?",
                    "Internet chats are unavailable without a connection. Nearby chats keep working while Bluetooth is on.")
                infoSection("Can I send photos or files?",
                    "Attachments work in private Nearby chats. Group and Internet chats don't support attachments yet.")
                infoSection("What happens when I accept a request?",
                    "The other phone is notified and you can chat. Declining sends no reply.")
            }
        }
    } else {
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
                            fontSize = RelayType.SubTitle,
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
                RelayListRow(R.drawable.relay_shield, "Privacy", onClick = { infoPage = "privacy" })
                RowDivider()
                RelayListRow(R.drawable.relay_bell, "Notifications", onClick = { infoPage = "notifications" })
                RowDivider()
                RelayListRow(R.drawable.relay_storage, "Storage", onClick = { infoPage = "storage" })
            }
        }

        item {
            RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                RelayListRow(R.drawable.relay_help, "Help & FAQ", onClick = { infoPage = "help" })
                RowDivider()
                RelayListRow(R.drawable.relay_pulse, "Diagnostics", onClick = { diagnostics = true })
                RowDivider()
                RelayListRow(R.drawable.relay_info, "About Relay", onClick = { infoPage = "about" })
            }
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
            Text("Bluetooth & identity", color = RelayColors.TextPrimary, fontSize = RelayType.Body, fontWeight = FontWeight.SemiBold)
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
private fun SettingsInfoPage(title: String, back: () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { RIcon(R.drawable.relay_back, "Back", tint = RelayColors.TextPrimary) }
            Text(title, color = RelayColors.TextPrimary, fontSize = RelayType.Body, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

private fun LazyListScope.infoSection(title: String, body: String) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = RelayColors.TextPrimary, fontSize = RelayType.Body, fontWeight = FontWeight.SemiBold)
            Text(body, color = RelayColors.TextSecondary, fontSize = RelayType.Secondary)
        }
    }
}
