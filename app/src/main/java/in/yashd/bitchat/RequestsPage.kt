package `in`.yashd.bitchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshUiState

/** Nearby chat requests: first messages from people not yet accepted. */
@Composable
fun RequestsPage(
    mesh: MeshUiState,
    pending: Map<String, Long>,
    back: () -> Unit,
    accept: (String) -> Unit,
    decline: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = back) { Text("Back", color = RelayColors.Blue) }
            Spacer(Modifier.weight(1f))
            Text("Requests", color = RelayColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(pending.entries.sortedByDescending { it.value }, key = { it.key }) { (peerId, _) ->
                val name = mesh.peers.firstOrNull { it.identity.peerId == peerId }
                    ?.identity?.displayName?.ifBlank { null } ?: peerId.take(12)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(RelayColors.Card)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = RelayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Wants to message you", color = RelayColors.TextSecondary, fontSize = 13.sp)
                    }
                    TextButton(onClick = { accept(peerId) }) { Text("Accept", color = RelayColors.Blue) }
                    TextButton(onClick = { decline(peerId) }) { Text("Decline", color = RelayColors.Red) }
                }
            }
        }
    }
}
