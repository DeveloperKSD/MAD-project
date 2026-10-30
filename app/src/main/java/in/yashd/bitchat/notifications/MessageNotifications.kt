package `in`.yashd.bitchat.notifications

import android.app.Notification
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

/** Process-wide visibility follows the application foreground lifecycle. */
object MessageNotifications {
    @Volatile var visibleRoute: String? = null
    @Volatile var foreground = false
    const val EXTRA_ROUTE = "message_conversation"
    internal const val CHANNEL = "incoming_messages_v1"
    internal const val CHANNEL_V2 = "incoming_messages_v2"
    private val VIBRATION = longArrayOf(0, 250, 150, 250)

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = manager.getNotificationChannel(CHANNEL)
        if (legacy != null && !userCustomized(legacy)) {
            // The old channel never vibrated; only an untouched channel is replaced, because
            // channel settings belong to the user once they change them.
            manager.deleteNotificationChannel(CHANNEL)
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_V2, "New messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                })
        }
    }

    /** A channel the user changed (importance, vibration or sound) is always kept. */
    private fun userCustomized(channel: NotificationChannel): Boolean =
        channel.importance != NotificationManager.IMPORTANCE_DEFAULT || channel.shouldVibrate() || channel.sound != null

    /** Post to the legacy channel while the user kept it, otherwise to the vibrating one. */
    internal fun activeChannel(context: Context): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return CHANNEL
        }
        return CHANNEL_V2
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
            post(context, key, intent, name.ifBlank { "Relay" }, "New message")
        }
    }

    /** Shared rendering for incoming messages and the debug-only explicit diagnostic. */
    internal fun post(context: Context, key: String, intent: Intent, title: String, text: String) {
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = activeChannel(context)
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.relay_chat)
            .setContentTitle("Relay").setContentText("New message").build()
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.relay_chat)
            .setContentTitle(title).setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pending).build()
        NotificationManagerCompat.from(context).notify(key, 2001, notification)
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
