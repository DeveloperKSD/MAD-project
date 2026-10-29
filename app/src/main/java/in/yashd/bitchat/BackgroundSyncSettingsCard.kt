package `in`.yashd.bitchat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.background.BackgroundSyncStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackgroundSyncSettingsCard(status: BackgroundSyncStatus, setupReady: Boolean,
                                       changing: Boolean, onEnabled: (Boolean) -> Unit) {
    RelayCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Background Sync", Modifier.weight(1f), color = RelayColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold)
                Switch(checked = status.enabled, enabled = !changing && (status.enabled || setupReady),
                    modifier = Modifier.testTag("background-sync-toggle"), onCheckedChange = onEnabled)
            }
            Text("Checks for Internet messages about every 15 minutes when connected. Checks may be delayed by Android; these are not instant notifications. Messages are saved silently.",
                color = RelayColors.TextSecondary, fontSize = 13.sp)
            Text(if (status.text == "Disabled" && !setupReady) "Disabled · finish Internet Setup to enable" else status.text,
                color = RelayColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.testTag("background-sync-status"))
            if (status.enabled && status.lastSuccessSeconds > 0) {
                Text("Last successful check: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(status.lastSuccessSeconds * 1000)), color = RelayColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}
