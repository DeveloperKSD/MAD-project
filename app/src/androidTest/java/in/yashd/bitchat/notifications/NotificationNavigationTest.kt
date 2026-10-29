package `in`.yashd.bitchat.notifications

import android.content.Intent
import `in`.yashd.bitchat.nostr.NostrNpub
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun coldAndWarmLaunchRoutesAreConsumedExactlyOnce() {
        listOf("nearby:", "nearby:abcdef", "internet:" + NostrNpub.encode(ByteArray(32) { 1 })).forEach { route ->
            val intent = Intent().putExtra(MessageNotifications.EXTRA_ROUTE, route)
            assertEquals(route, MessageNotifications.consume(intent))
            assertNull(MessageNotifications.consume(intent))
        }
    }
    @Test fun malformedRoutesAreRejected() {
        listOf("settings", "internet:invalid", "nearby:../../").forEach { route ->
            assertNull(MessageNotifications.consume(Intent().putExtra(MessageNotifications.EXTRA_ROUTE, route)))
        }
    }
}
