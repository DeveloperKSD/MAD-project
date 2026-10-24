package `in`.yashd.bitchat.ui.relay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnlineChatScreen(add: suspend (String, String) -> String?, open: (String) -> Unit, back: () -> Unit, setup: () -> Unit, canSave: Boolean = true) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var publicId by rememberSaveable { mutableStateOf("") }
    // Sensitive contact metadata must not enter plaintext Android saved state.
    var name by remember { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Chat Online", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Paste your friend’s public Relay ID. They only need to share it once; this conversation saves it on this device.")
        OutlinedTextField(publicId, { publicId = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Friend’s public Relay ID (npub)") }, shape = RoundedCornerShape(18.dp), isError = problem != null)
        OutlinedTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Name for this chat (optional)") }, shape = RoundedCornerShape(18.dp))
        Text("This name is only a label on your device. It is not a username or a way to find someone. Public IDs identify conversations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (name.trim().length > 60 || name.trim().any { it.isISOControl() }) problem = "Use a name of up to 60 characters without line breaks."
            else {
                if (runCatching { RelayViewModel.recipientKey(publicId) }.isFailure) problem = "That public ID doesn’t look right. Ask your friend to copy their full npub again."
                else scope.launch {
                    saving = true
                    try {
                        val canonical = add(publicId, name)
                        if (canonical == null) problem = "Couldn’t save this chat locally. Check the local-storage message above and try again."
                        else open(canonical)
                    } finally { saving = false }
                }
            }
        }, enabled = !saving && canSave, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Start Chat") }
        Text("Internet conversations and names are saved on this device. Message bodies are encrypted locally. They are not backed up or synchronized.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = setup) { Text("Need Internet Setup?") }
        OutlinedButton(onClick = back) { Text("Back") }
    }
}
