package `in`.yashd.bitchat

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import `in`.yashd.bitchat.notifications.MessageNotifications
import `in`.yashd.bitchat.notifications.testNotification

@Composable
internal fun DebugNotificationAction(feedback: (String) -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = { feedback(MessageNotifications.testNotification(context)) }) {
        Text("Test Notification")
    }
}
