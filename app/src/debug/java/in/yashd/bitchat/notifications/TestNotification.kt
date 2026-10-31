package `in`.yashd.bitchat.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.yashd.bitchat.MainActivity

/** Explicit diagnostic: no conversation, mute lookup, history write, or transport operation. */
internal fun MessageNotifications.testNotification(context: Context): String {
    val permissionHelp = "Notifications are disabled. Open Android Settings → Apps → Relay → Notifications and allow notifications, then tap Test Notification again."
    return try {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return permissionHelp
        channels(context)
        if (Build.VERSION.SDK_INT >= 26 && context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(activeChannel(context))?.importance == NotificationManager.IMPORTANCE_NONE) {
            return "The New messages channel is disabled. Open Android Settings → Apps → Relay → Notifications → New messages and enable it, then try again."
        }
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("relay://test-notification"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        post(context, "debug-test-notification", intent, "Relay", "This is a test notification")
        "Test notification sent. Check the Android notification shade."
    } catch (_: SecurityException) {
        permissionHelp
    } catch (_: Exception) {
        "Could not show the test notification. Check Android Settings → Apps → Relay → Notifications and try again."
    }
}
