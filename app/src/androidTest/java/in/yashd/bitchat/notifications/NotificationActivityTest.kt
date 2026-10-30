package `in`.yashd.bitchat.notifications

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.MainActivity
import `in`.yashd.bitchat.nostr.NostrNpub
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun notificationOpensConversationOnColdLaunchRecreationAndWarmLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The platform permission dialog pauses the activity; exercise routing independently.
        context.getSharedPreferences("notification_permission", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("requested", true).commit()
        val nearby = "nearby:"
        val internet = "internet:" + NostrNpub.encode(ByteArray(32) { 2 })
        fun intent(route: String) = Intent(context, MainActivity::class.java)
            .putExtra(MessageNotifications.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(intent(nearby)).use { scenario ->
            compose.waitUntil(10_000) { MessageNotifications.visibleRoute == nearby }
            scenario.recreate()
            compose.waitUntil(10_000) { MessageNotifications.visibleRoute == nearby }
            scenario.onActivity { it.startActivity(intent(internet)) }
            compose.waitUntil(10_000) { MessageNotifications.visibleRoute == internet }
            scenario.recreate()
            compose.waitUntil(10_000) { MessageNotifications.visibleRoute == internet }
            scenario.onActivity { assertNull(it.intent.getStringExtra(MessageNotifications.EXTRA_ROUTE)) }
        }
    }
}
