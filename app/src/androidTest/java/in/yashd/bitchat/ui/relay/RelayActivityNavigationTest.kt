package `in`.yashd.bitchat.ui.relay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import `in`.yashd.bitchat.MainActivity
import org.junit.Rule
import org.junit.Test

/** Exercises the production BLE host as well as the shared Relay shell. No radio is started. */
class RelayActivityNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun productionScreensKeepOneBarAndExposeOriginalBleControlsAfterRecreation() {
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Discover").performClick()
        compose.onNodeWithText("Find Nearby").assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Nostr Relays").performScrollTo().performClick()
        compose.onNodeWithText("Choose your servers").assertIsDisplayed()
        compose.onNodeWithTag("internet-setup-list").performScrollToNode(hasText("Discovery server addresses"))
        compose.onNodeWithText("Discovery server addresses").assertIsDisplayed()
        compose.onNodeWithTag("internet-setup-list").performScrollToNode(hasText("Save for later"))
        compose.onNodeWithText("Save for later").performClick()
        compose.onNodeWithText("Bluetooth").performScrollTo().performClick()
        compose.onNodeWithText("Nearby identity").assertIsDisplayed()
        listOf("copy ID", "share ID", "rename", "rotate").forEach {
            compose.onNodeWithText(it).performScrollTo()
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Nearby Advanced").performScrollTo()
        compose.onNodeWithText("Nearby Advanced").performClick()
        compose.onNodeWithText("mesh radio").performScrollTo()
        compose.onNodeWithText("mesh radio").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(0)
        compose.onNodeWithText("mesh radio").performScrollTo()
        compose.onNodeWithText("mesh radio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Chats").performClick()
        compose.onNodeWithContentDescription("New Chat").assertIsDisplayed()
        compose.onNodeWithText("bitchat").assertDoesNotExist()
    }
}
