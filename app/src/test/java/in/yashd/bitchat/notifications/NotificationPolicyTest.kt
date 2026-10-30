package `in`.yashd.bitchat.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun permissionDenialMuteAndActiveConversationSuppressAlerts() {
        assertTrue(NotificationPolicy.shouldAlert(true, false, false))
        assertFalse(NotificationPolicy.shouldAlert(false, false, false))
        assertFalse(NotificationPolicy.shouldAlert(true, true, false))
        assertFalse(NotificationPolicy.shouldAlert(true, false, true))
    }
    @Test fun historyAndFutureDatedMessagesNeverAlert() {
        assertFalse(NotificationPolicy.isLive(999, 1000, 2000))
        assertTrue(NotificationPolicy.isLive(1001, 1000, 2000))
        assertFalse(NotificationPolicy.isLive(62001, 1000, 2000))
    }
}
