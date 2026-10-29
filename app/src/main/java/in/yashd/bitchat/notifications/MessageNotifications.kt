package `in`.yashd.bitchat.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.yashd.bitchat.MainActivity
import `in`.yashd.bitchat.R
import `in`.yashd.bitchat.ui.relay.MutedChatsStore

/** Process-wide visibility is cleared whenever the activity leaves the foreground. */
object MessageNotifications {
    @Volatile var visibleRoute: String? = null
    @Volatile var foreground = false
    const val EXTRA_ROUTE = "message_conversation"
    private const val CHANNEL = "incoming_messages_v1"

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "New messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }

    fun stored(context: Context, key: String, route: String, name: String) {
        // Delivery is best effort and must never change the success of message storage.
        runCatching {
            val mutes = MutedChatsStore(context)
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!NotificationPolicy.shouldAlert(enabled, mutes.storageError || key in mutes.mutes.value,
                    foreground && visibleRoute == route)) return
            channels(context)
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("relay://message/" + Uri.encode(key)))
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.relay_chat)
                .setContentTitle("Relay").setContentText("New message").build()
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.relay_chat)
                .setContentTitle(name.ifBlank { "Relay" }).setContentText("New message")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setAutoCancel(true).setContentIntent(pending).build()
            NotificationManagerCompat.from(context).notify(key, 2001, notification)
        }
    }

    fun consume(intent: Intent?): String? {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return null
        intent.removeExtra(EXTRA_ROUTE)
        return route.takeIf { it == "nearby:" || it.matches(Regex("nearby:[0-9a-f]+")) ||
            it.startsWith("internet:") && runCatching {
                `in`.yashd.bitchat.nostr.NostrNpub.decode(it.substringAfter(':'))
            }.isSuccess }
    }
}
