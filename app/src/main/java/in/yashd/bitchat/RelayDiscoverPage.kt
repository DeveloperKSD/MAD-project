package `in`.yashd.bitchat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState

// ---------------------------------------------------------------------------
// Discover (Figma 4): Nearby / Requests / Contacts.
// The Find Nearby button uses the same start/permission/Bluetooth steps as before.
// ---------------------------------------------------------------------------

private val discoverTabs = listOf("Nearby", "Requests", "Contacts")

@Composable
internal fun RelayDiscoverPage(
    mesh: MeshUiState,
    contacts: List<ChatListItem>,
    myName: String,
    onAvatar: () -> Unit,
    findNearby: () -> Unit,
    stopNearby: () -> Unit,
    requestPermissions: () -> Unit,
    bluetoothSettings: () -> Unit,
    openNearby: (String?) -> Unit,
    openContact: (String) -> Unit,
    toast: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val readiness = mesh.bluetoothReadiness
    val startAction: () -> Unit = when {
        !readiness.hasPermissions -> requestPermissions
        !readiness.bluetoothEnabled -> bluetoothSettings
        else -> findNearby
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { RelayHeader("Relay", myName, onAvatar) }
        item { SegmentedTabs(tab) { tab = it } }

        when (tab) {
            0 -> {
                item { ScannerCard(mesh, startAction, stopNearby) }
                if (mesh.serviceRunning && mesh.peers.isEmpty()) {
                    item {
                        Text(
                            "No one yet. Ask your friend to open Relay and turn on Nearby too.",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            color = RelayColors.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                if (mesh.peers.isNotEmpty()) {
                    item {
                        RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            mesh.peers.sortedBy { peerOrder(it.state) }.forEachIndexed { index, peer ->
                                if (index > 0) RowDivider(72.dp)
                                PeerRow(peer) {
                                    if (mesh.serviceRunning) openNearby(peer.identity.peerId)
                                    else toast("Turn on Nearby first to chat with ${peer.identity.displayName.ifBlank { "this person" }}")
                                }
                            }
                        }
                    }
                }
                item {
                    RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        RelayListRow(
                            icon = R.drawable.relay_group,
                            title = "Open Nearby group",
                            subtitle = "A public chat with everyone in Bluetooth range",
                            iconTint = RelayColors.Bluetooth,
                            onClick = { openNearby(null) }
                        )
                    }
                }
                if (mesh.errors.isNotEmpty()) {
                    item {
                        Text(
                            mesh.errors.joinToString("\n"),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            color = RelayColors.Orange,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            1 -> item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    EmptyCard(
                        R.drawable.relay_bell,
                        "No requests yet",
                        "When someone new wants to chat with you, it will show up here."
                    )
                }
            }
            else -> {
                val people = contacts.filter { !it.info.isGroup }
                if (people.isEmpty()) {
                    item {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            EmptyCard(R.drawable.relay_group, "No contacts yet", "People you chat with will appear here.")
                        }
                    }
                } else {
                    item {
                        RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            people.forEachIndexed { index, item ->
                                if (index > 0) RowDivider(72.dp)
                                ContactRow(item) { openContact(item.info.route) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(RelayColors.Card)
            .padding(4.dp)
    ) {
        discoverTabs.forEachIndexed { index, label ->
            val isOn = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isOn) RelayColors.BlueSelected else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isOn) Color.White else RelayColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ScannerCard(mesh: MeshUiState, start: () -> Unit, stop: () -> Unit) {
    val running = mesh.serviceRunning
    val readiness = mesh.bluetoothReadiness
    RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when {
                    !readiness.hasBle -> "This phone doesn't support Bluetooth LE"
                    running -> "Scanning for nearby devices..."
                    else -> "Meet people around you"
                },
                color = RelayColors.TextPrimary,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(16.dp))
            Radar(running)
            Spacer(Modifier.height(16.dp))
            if (running) {
                val found = mesh.peers.count { it.state != PeerConnectionState.Disconnected }
                Text(
                    if (found == 1) "1 device found" else "$found devices found",
                    color = RelayColors.TextSecondary,
                    fontSize = 14.sp
                )
                TextButton(onClick = stop) { Text("Stop finding nearby", color = RelayColors.TextSecondary, fontSize = 13.sp) }
            } else {
                Text(
                    when {
                        !readiness.hasPermissions -> "Allow Bluetooth so Relay can find friends nearby."
                        !readiness.bluetoothEnabled -> "Turn on Bluetooth to find friends nearby."
                        else -> "Nearby Chat uses Bluetooth. Your friends need Relay open with Nearby on."
                    },
                    color = RelayColors.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = start,
                    enabled = readiness.hasBle,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RelayColors.Blue, contentColor = Color.White)
                ) {
                    Text("Find Nearby")
                }
            }
        }
    }
}

/** Bluetooth icon with rings; the outer ring pulses while scanning. */
@Composable
private fun Radar(running: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp).clip(CircleShape).background(RelayColors.Blue.copy(alpha = 0.05f)))
        Box(Modifier.size(126.dp).clip(CircleShape).background(RelayColors.Blue.copy(alpha = 0.08f)))
        Box(Modifier.size(126.dp).border(1.dp, RelayColors.Blue.copy(alpha = 0.18f), CircleShape))
        if (running) {
            Box(
                Modifier
                    .size(170.dp)
                    .graphicsLayer {
                        val scale = 0.45f + 0.55f * pulse
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - pulse
                    }
                    .border(1.5.dp, RelayColors.Blue, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (running) RelayColors.BlueSelected else RelayColors.CardHigh),
            contentAlignment = Alignment.Center
        ) {
            RIcon(R.drawable.relay_bluetooth, null, tint = if (running) Color.White else RelayColors.TextSecondary, size = 30.dp)
        }
    }
}

/** Connected people first, people out of range last. */
private fun peerOrder(state: PeerConnectionState): Int = when (state) {
    PeerConnectionState.Authenticated, PeerConnectionState.Connected -> 0
    PeerConnectionState.Connecting -> 1
    PeerConnectionState.Discovered -> 2
    PeerConnectionState.Failed -> 3
    PeerConnectionState.Disconnected -> 4
}

@Composable
private fun PeerRow(peer: PeerState, onClick: () -> Unit) {
    val name = peer.identity.displayName.ifBlank { peer.identity.peerId.take(8) }
    val (stateText, dot) = when (peer.state) {
        PeerConnectionState.Authenticated, PeerConnectionState.Connected ->
            (if (peer.directDeviceAddress != null) "Nearby link" else "Mesh route") to RelayColors.Green
        PeerConnectionState.Connecting -> "Connecting…" to RelayColors.Orange
        PeerConnectionState.Discovered -> "Found nearby" to RelayColors.Orange
        PeerConnectionState.Failed -> "Connection failed" to RelayColors.Red
        PeerConnectionState.Disconnected -> "Out of range" to RelayColors.TextMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(name, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = RelayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RIcon(R.drawable.relay_bluetooth, null, tint = RelayColors.Bluetooth, size = 14.dp)
                Text(stateText, color = RelayColors.TextSecondary, fontSize = 12.sp)
            }
            distanceText(peer.rssi)?.let { Text(it, color = RelayColors.TextMuted, fontSize = 12.sp) }
        }
        StatusDot(dot, 9.dp)
        Spacer(Modifier.width(10.dp))
        RIcon(R.drawable.relay_chevron, null, tint = RelayColors.TextMuted, size = 18.dp)
    }
}

@Composable
private fun ContactRow(item: ChatListItem, onClick: () -> Unit) {
    val info = item.info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationAvatar(info, 42)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(info.title, color = RelayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RIcon(
                    if (info.nearby) R.drawable.relay_bluetooth else R.drawable.relay_broadcast,
                    null,
                    tint = if (info.nearby) RelayColors.Bluetooth else RelayColors.Nostr,
                    size = 14.dp
                )
                Text(info.linkText, color = RelayColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        StatusDot(if (info.online) RelayColors.Green else RelayColors.TextMuted, 9.dp)
        Spacer(Modifier.width(10.dp))
        RIcon(R.drawable.relay_chevron, null, tint = RelayColors.TextMuted, size = 18.dp)
    }
}
