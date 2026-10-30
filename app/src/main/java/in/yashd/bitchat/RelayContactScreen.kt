package `in`.yashd.bitchat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.ui.relay.RelayUiState

// ---------------------------------------------------------------------------
// Contact profile (Figma 3). Opened from a chat header or from Discover.
// Call and shared media are placeholders; mute is session-only and favourites are persisted.
// ---------------------------------------------------------------------------

@Composable
internal fun RelayContactScreen(
    info: RouteInfo,
    mesh: MeshUiState,
    internet: RelayUiState,
    favorite: Boolean,
    muted: Boolean,
    back: () -> Unit,
    openChat: () -> Unit,
    toggleFavorite: () -> Unit,
    toggleMute: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(false) }
    val peer = info.peer

    val statusText = when {
        info.nearby && info.online -> "Online nearby"
        info.nearby && peer != null && mesh.serviceRunning -> "Nearby • connecting"
        !info.nearby && info.online -> "Reachable online"
        else -> "Offline"
    }
    val copyId: () -> Unit = {
        copyText(context, "Relay ID", info.publicId)
        toast("ID copied")
    }

    if (verify) {
        val fingerprint = peer?.identity?.let { java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.encryptionPublicKey + it.signingPublicKey) }
            ?.take(16)
            ?.joinToString("") { "%02x".format(it.toInt() and 255) }
            ?.chunked(4)
            ?.joinToString(" ")
        AlertDialog(
            onDismissRequest = { verify = false },
            title = { Text("Verify identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare this with what ${info.title} sees on their phone. If every character matches, you're talking to the right person.")
                    Text(info.publicId, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = RelayColors.TextPrimary)
                    if (fingerprint != null) {
                        Text("Key fingerprint", fontWeight = FontWeight.SemiBold)
                        Text(fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = RelayColors.TextPrimary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { verify = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { copyId(); verify = false }) { Text("Copy ID") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // top bar: back and more
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { RIcon(R.drawable.relay_back, "Back", tint = RelayColors.TextPrimary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = toggleFavorite) {
                RIcon(
                    if (favorite) R.drawable.relay_star_filled else R.drawable.relay_star,
                    if (favorite) "Remove ${info.title} from favorites" else "Add ${info.title} to favorites",
                    tint = if (favorite) RelayColors.Orange else RelayColors.TextPrimary,
                    size = 22.dp
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { RIcon(R.drawable.relay_more, "More options", tint = RelayColors.TextPrimary, size = 20.dp) }
                ContactMenu(menu, { menu = false }, favorite, toggleFavorite, copyId, toast)
            }
        }

        // avatar, name, status
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ConversationAvatar(info, 84)
            Spacer(Modifier.height(14.dp))
            Text(info.title, color = RelayColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(statusText, color = if (info.online) RelayColors.Green else RelayColors.TextSecondary, fontSize = 14.sp)
        }

        // quick actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionTile(R.drawable.relay_chat, "Message", Modifier.weight(1f), openChat)
            ActionTile(R.drawable.relay_call, "Call", Modifier.weight(1f)) { toast("Voice calls aren't available yet") }
            ActionTile(if (muted) R.drawable.relay_bell else R.drawable.relay_bell_off, if (muted) "Unmute" else "Mute", Modifier.weight(1f)) {
                toggleMute()
                toast(if (muted) "Notifications on for ${info.title}" else "${info.title} muted")
            }
            ActionTile(R.drawable.relay_more, "More", Modifier.weight(1f)) { menu = true }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionTitle("Transport status")
            RelayCard {
                val bluetoothOn = info.nearby && info.online
                RelayListRow(
                    icon = R.drawable.relay_bluetooth,
                    title = "Bluetooth",
                    subtitle = when {
                        !info.nearby -> "Not paired nearby"
                        bluetoothOn -> if (peer?.directDeviceAddress != null) "Nearby link available" else "Mesh route available"
                        peer != null && peer.state == PeerConnectionState.Connecting -> "In range • connecting"
                        !mesh.serviceRunning -> "Nearby is off"
                        else -> "Not in range right now"
                    },
                    iconTint = RelayColors.Bluetooth,
                    chevron = false
                ) {
                    if (bluetoothOn) RelayPill("Available", RelayColors.Green, RelayColors.GreenPill)
                    else RelayPill("Unavailable", RelayColors.TextSecondary, RelayColors.CardHigh)
                }
                RowDivider()
                val nostrOn = !info.nearby && internet.inboxesConnected
                RelayListRow(
                    icon = R.drawable.relay_broadcast,
                    title = "Nostr",
                    subtitle = when {
                        info.nearby -> "No Internet ID for this contact"
                        internet.inboxes.isEmpty() -> "Internet messaging not set up"
                        nostrOn -> "Relay sync enabled"
                        else -> "Relays offline"
                    },
                    iconTint = RelayColors.Nostr,
                    chevron = false
                ) {
                    if (nostrOn) RelayPill("Connected", RelayColors.Green, RelayColors.GreenPill)
                    else RelayPill("Offline", RelayColors.TextSecondary, RelayColors.CardHigh)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Identity")
            RelayCard {
                RelayListRow(
                    icon = R.drawable.relay_key,
                    title = if (info.nearby) "Nearby ID" else "Public key",
                    subtitle = shortId(info.publicId, 10, 4),
                    chevron = false
                ) {
                    IconButton(onClick = copyId) {
                        RIcon(R.drawable.relay_copy, "Copy ID", tint = RelayColors.TextSecondary, size = 20.dp)
                    }
                }
                RowDivider()
                RelayListRow(
                    icon = R.drawable.relay_shield,
                    title = "Verify identity",
                    onClick = { verify = true }
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Shared media")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(RelayColors.Card),
                        contentAlignment = Alignment.Center
                    ) {
                        RIcon(R.drawable.relay_image, null, tint = RelayColors.TextMuted, size = 26.dp)
                    }
                }
            }
            Text(
                "Photos you share will show up here.",
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                color = RelayColors.TextMuted,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(16.dp))
            RelayCard {
                RelayListRow(
                    icon = if (muted) R.drawable.relay_bell_off else R.drawable.relay_bell,
                    title = "Notifications",
                    subtitle = if (muted) "Muted" else "On",
                    onClick = {
                        toggleMute()
                        toast(if (muted) "Notifications on for ${info.title}" else "${info.title} muted")
                    }
                )
            }
        }
    }
}

@Composable
private fun ContactMenu(
    expanded: Boolean,
    dismiss: () -> Unit,
    favorite: Boolean,
    toggleFavorite: () -> Unit,
    copyId: () -> Unit,
    toast: (String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(
            text = { Text(if (favorite) "Remove from favorites" else "Add to favorites") },
            onClick = {
                dismiss()
                toggleFavorite()
            }
        )
        DropdownMenuItem(text = { Text("Copy ID") }, onClick = { dismiss(); copyId() })
    }
}

@Composable
private fun ActionTile(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RelayColors.Card)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RIcon(icon, null, tint = RelayColors.TextPrimary, size = 22.dp)
        Text(label, color = RelayColors.TextPrimary, fontSize = 12.sp)
    }
}
