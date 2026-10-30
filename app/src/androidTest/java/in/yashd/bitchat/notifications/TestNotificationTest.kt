package `in`.yashd.bitchat.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import `in`.yashd.bitchat.DebugNotificationAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TestNotificationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun buttonPostsExactSampleWhileForegroundWithoutConversationRoute() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val previousForeground = MessageNotifications.foreground
        val previousRoute = MessageNotifications.visibleRoute
        try {
            MessageNotifications.foreground = true
            MessageNotifications.visibleRoute = "nearby:"
            var feedback = ""
            compose.setContent { DebugNotificationAction { feedback = it } }
            compose.onNodeWithText("Test Notification").performClick()
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.tag == "debug-test-notification" } }
            val notification = manager.activeNotifications.single { it.tag == "debug-test-notification" }.notification
            assertEquals("Relay", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("This is a test notification", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            if (Build.VERSION.SDK_INT >= 26) assertTrue(notification.channelId == MessageNotifications.CHANNEL || notification.channelId == MessageNotifications.CHANNEL_V2)
            assertTrue(feedback.contains("sent"))
            assertEquals("nearby:", MessageNotifications.visibleRoute)
            assertNull(MessageNotifications.consume(android.content.Intent()))
        } finally {
            manager.cancel("debug-test-notification", 2001)
            MessageNotifications.foreground = previousForeground
            MessageNotifications.visibleRoute = previousRoute
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun deniedPermissionExplainsSettingsAndDoesNotPost() {
        manager.cancel("debug-test-notification", 2001)
        // Simulate the platform denial through Context so revocation does not kill the
        // instrumented app process (Android revokes permissions by terminating its UID).
        val denied = object : android.content.ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS)
                    android.content.pm.PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        val feedback = MessageNotifications.testNotification(denied)
        assertTrue(feedback.contains("Android Settings → Apps → Relay → Notifications"))
        assertFalse(manager.activeNotifications.any { it.tag == "debug-test-notification" })
    }
}
