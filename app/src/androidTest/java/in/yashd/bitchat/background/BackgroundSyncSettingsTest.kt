package `in`.yashd.bitchat.background

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import `in`.yashd.bitchat.BackgroundSyncSettingsCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackgroundSyncSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultOffNeedsSetupAndExplainsDelayedSilentChecks() {
        compose.setContent { BackgroundSyncSettingsCard(BackgroundSyncStatus(), false, false) {} }
        compose.onNodeWithText("Background Sync").assertIsDisplayed()
        compose.onNodeWithTag("background-sync-toggle").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("not instant notifications", substring = true).assertIsDisplayed()
        compose.onNodeWithText("saved silently", substring = true).assertIsDisplayed()
    }
    @Test fun explicitToggleWorksAndDisableRemainsAvailableAfterSetupBecomesUnavailable() {
        val status = mutableStateOf(BackgroundSyncStatus())
        val ready = mutableStateOf(true)
        var enabled: Boolean? = null
        compose.setContent { BackgroundSyncSettingsCard(status.value, ready.value, false) { enabled = it } }
        compose.onNodeWithTag("background-sync-toggle").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(true, enabled)
            status.value = BackgroundSyncStatus(true, "Enabled", 1000)
            ready.value = false
        }
        compose.onNodeWithTag("background-sync-toggle").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, enabled) }
        compose.onNodeWithText("Last successful check:", substring = true).assertIsDisplayed()
    }
}
