package `in`.yashd.bitchat.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.RelayColors
import `in`.yashd.bitchat.RelayType
import kotlinx.coroutines.launch

@Composable
fun OnlineChatScreen(add: suspend (String, String) -> String?, open: (String) -> Unit, back: () -> Unit,
    canSave: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var publicId by rememberSaveable { mutableStateOf("") }
    // Sensitive contact metadata must not enter plaintext Android saved state.
    var name by remember { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("New Internet chat", color = RelayColors.TextPrimary, fontSize = RelayType.SubTitle, fontWeight = FontWeight.SemiBold)

        if (scanning) {
            QrScannerScreen(onResult = { scanned ->
                scanning = false
                val parsed = parseScannedPublicId(scanned)
                if (parsed == null) problem = "That QR code isn’t a Relay Internet ID. Ask your friend to show the QR from their Internet chat screen."
                else { publicId = parsed; problem = null }
            }, onClose = { scanning = false })
            return@Column
        }
        Text("Paste your friend’s Internet ID, or scan the QR code on their phone. They only need to share it once; this conversation saves it on this device.",
            color = RelayColors.TextSecondary, fontSize = RelayType.Label)
        OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
        OutlinedTextField(publicId, { publicId = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Friend’s Internet ID (npub)") }, shape = RoundedCornerShape(18.dp), isError = problem != null)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = RelayType.Meta) }
        OutlinedTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Name for this chat (optional)") }, shape = RoundedCornerShape(18.dp))
        Text("This name is only a label on your device. It is not a username or a way to find someone. Public IDs identify conversations.",
            color = RelayColors.TextMuted, fontSize = RelayType.Meta)
        Button(
            onClick = {
                if (runCatching { RelayViewModel.recipientKey(publicId) }.isFailure) problem = "That Internet ID doesn’t look right. Ask your friend to copy their full npub again."
                else scope.launch {
                    saving = true
                    try {
                        val canonical = add(publicId, name)
                        if (canonical == null) problem = "Couldn’t save this chat locally. Check the local-storage message above and try again."
                        else open(canonical)
                    } finally { saving = false }
                }
            },
            enabled = !saving && canSave, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Start chat") }
        Text("Internet conversations and names are saved on this device. Message bodies are encrypted locally. They are not backed up or synchronized.",
            color = RelayColors.TextMuted, fontSize = RelayType.Meta)
        OutlinedButton(onClick = back) { Text("Back") }
    }
}
