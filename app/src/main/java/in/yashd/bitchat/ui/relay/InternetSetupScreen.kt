package `in`.yashd.bitchat.ui.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.yashd.bitchat.nostr.NostrMessagingCoordinator
import java.net.URI

@Composable
fun InternetSetupScreen(state: RelayUiState, updateServers: (String, String) -> Unit,
    back: () -> Unit, next: () -> Unit, publish: () -> Unit, retry: () -> Unit,
    finish: () -> Unit, exit: () -> Unit) {
    val setup = state.setup
    var advanced by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("internet-setup-list"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Internet Setup", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Step ${setup.step + 1} of 3", style = MaterialTheme.typography.bodySmall)
            Text(listOf("Choose your servers", "Make yourself reachable", "Share your Relay ID")[setup.step],
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        when (setup.step) {
            0 -> {
                item { Text("Servers pass encrypted messages between friends. Discovery servers help find your inbox; inbox servers receive your encrypted messages. You choose which operators to use.") }
                item { Text("Ask a trusted friend or administrator for addresses. Relay does not choose public servers for you.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { OutlinedTextField(setup.discoveryDraft,
                    { updateServers(it, setup.inboxDraft) }, Modifier.fillMaxWidth(), enabled = !state.busy && !state.configurationLoading && state.configurationError == null,
                    label = { Text("Discovery server addresses") }, supportingText = { Text("1–3 secure wss:// addresses, one per line") },
                    minLines = 2, shape = RoundedCornerShape(18.dp)) }
                item { OutlinedTextField(setup.inboxDraft,
                    { updateServers(setup.discoveryDraft, it) }, Modifier.fillMaxWidth(), enabled = !state.busy && !state.configurationLoading && state.configurationError == null,
                    label = { Text("Inbox server addresses") }, supportingText = { Text("1–3 secure wss:// addresses, one per line") },
                    minLines = 2, shape = RoundedCornerShape(18.dp)) }
                item { Text(if (state.discovery.isEmpty()) "Not configured yet" else "Server addresses configured. A saved address alone does not prove a connection works.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            1 -> {
                item { Text("Publish an inbox announcement so friends’ apps know which servers to send to. This shares your public ID and chosen inbox addresses, never your private key.") }
                item { SetupStatus(state) }
                item { Text("Inbox server connections", style = MaterialTheme.typography.titleMedium) }
                items(state.inboxes.size) { index ->
                    val url = state.inboxes[index]
                    Text("${serverLabel(url)} · ${state.connections[url] ?: if (state.active) "waiting" else "disconnected"}", style = MaterialTheme.typography.bodySmall)
                }
                item { Text("An open connection verifies a socket handshake, not message delivery or whether an inbox subscription will stay open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Button(onClick = publish, enabled = state.inboxesConnected && !state.storageLoading && state.storageError == null && !state.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(when (setup.announcement) {
                            InboxAnnouncement.PUBLISHING -> "Publishing…"
                            InboxAnnouncement.FAILED, InboxAnnouncement.PARTIAL, InboxAnnouncement.UNKNOWN -> "Retry announcement"
                            InboxAnnouncement.ACCEPTED -> "Publish again"
                            else -> "Publish my inbox"
                        })
                    }
                    OutlinedButton(onClick = retry, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Retry connection") }
                }
                item {
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide Advanced" else "Advanced details") }
                    if (advanced) {
                        Text("Nostr uses a kind-10050 inbox announcement. Accepted means the discovery server acknowledged this publication; it does not mean a friend received a message.", style = MaterialTheme.typography.bodySmall)
                        setup.publications.forEach { result ->
                            Text("${serverLabel(result.relay)} · ${publicationLabel(result.stage)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            2 -> {
                item { Text("Friends only need your public Relay ID when adding you for the first time. After that, they can reopen your conversation without entering it again.") }
                item { PublicRelayId(state.npub) }
                item { SetupStatus(state) }
                item { Text("Internet conversations and local names are saved on this device. Message bodies are encrypted locally. They are excluded from backup, so uninstalling or clearing app data loses this history.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        (state.configurationError ?: setup.error ?: state.error)?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = if (setup.step == 0) exit else back, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Back") }
                if (setup.step < 2) Button(onClick = next, enabled = !state.busy && !state.configurationLoading && state.configurationError == null && (setup.step == 0 || state.readyToShare), modifier = Modifier.weight(1f)) { Text("Next") }
                else Button(onClick = finish, enabled = !state.busy && state.readyToShare, modifier = Modifier.weight(1f)) { Text("Finish") }
            }
            TextButton(onClick = exit) { Text("Save for later") }
        }
    }
}

@Composable
fun SetupStatus(state: RelayUiState) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.readyToShare) "Ready to share your ID" else "Not yet ready", style = MaterialTheme.typography.titleMedium)
            Text(if (state.inboxes.isEmpty()) "Servers: not configured" else "Servers: configured")
            Text(when {
                state.inboxes.isEmpty() -> "Inbox connections: not configured"
                state.inboxesConnected -> "Inbox connections: connected"
                !state.active -> "Inbox connections: paused while Relay is in the background"
                state.inboxes.any { state.connections[it] in listOf("error", "subscription unavailable") } -> "Inbox connections: failed · retry or change servers"
                else -> "Inbox connections: waiting for verified connections"
            })
            Text(when (state.setup.announcement) {
                InboxAnnouncement.NOT_PUBLISHED -> "Announcement: not published yet"
                InboxAnnouncement.PUBLISHING -> "Announcement: publishing"
                InboxAnnouncement.ACCEPTED -> "Announcement: accepted by all configured discovery servers"
                InboxAnnouncement.PARTIAL -> "Announcement: accepted by some servers; others did not accept"
                InboxAnnouncement.FAILED -> "Announcement: failed"
                InboxAnnouncement.UNKNOWN -> "Announcement: outcome unknown"
            })
            Text("Acceptance is the last publication result. Connections are checked live. Neither confirms message delivery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PublicRelayId(npub: String) {
    val context = LocalContext.current
    var copied by rememberSaveable(npub) { mutableStateOf(false) }
    var shareError by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your public Relay ID", style = MaterialTheme.typography.titleMedium)
            Text(npub.ifEmpty { "Loading your secure identity…" }, style = MaterialTheme.typography.bodySmall)
            Text("Public ID only · safe to share with friends", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = npub.isNotEmpty(), onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Relay public ID", npub)); copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
                TextButton(enabled = npub.isNotEmpty(), onClick = {
                    val intent = publicIdShareIntent(npub)
                    shareError = runCatching { context.startActivity(Intent.createChooser(intent, "Share your public Relay ID")) }.isFailure
                }) { Text("Share") }
            }
            if (shareError) Text("Couldn’t open sharing. Use Copy instead.", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Shares only the caller's public npub, never identity storage or a secret key. */
fun publicIdShareIntent(npub: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, npub)
}
private fun serverLabel(url: String): String = runCatching { URI(url).host ?: "Server" }.getOrDefault("Server")
private fun publicationLabel(stage: NostrMessagingCoordinator.Stage): String = when (stage) {
    NostrMessagingCoordinator.Stage.RELAY_ACCEPTED -> "accepted"
    NostrMessagingCoordinator.Stage.RELAY_REJECTED -> "rejected"
    NostrMessagingCoordinator.Stage.FAILED -> "failed"
    NostrMessagingCoordinator.Stage.UNKNOWN -> "outcome unknown"
    else -> "not confirmed"
}
