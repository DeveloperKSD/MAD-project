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
        repeat(3) { compose.onNodeWithText("Continue").performScrollTo().performClick() }
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Discover").performClick()
        compose.onNodeWithText("Find Nearby").assertIsDisplayed()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Bluetooth permissions").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Internet Setup").performScrollTo().performClick()
        compose.onNodeWithText("Choose your servers").assertIsDisplayed()
        compose.onNodeWithTag("internet-setup-list").performScrollToNode(hasText("Discovery server addresses"))
        compose.onNodeWithText("Discovery server addresses").assertIsDisplayed()
        compose.onNodeWithTag("internet-setup-list").performScrollToNode(hasText("Save for later"))
        compose.onNodeWithText("Save for later").performClick()
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("Nearby identity"))
        compose.onNodeWithText("Nearby identity").assertIsDisplayed()
        listOf("copy ID", "share ID", "rename", "rotate").forEach {
            compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("Nearby Advanced"))
        compose.onNodeWithText("Nearby Advanced").performClick()
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("mesh radio"))
        compose.onNodeWithText("mesh radio").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("mesh radio"))
        compose.onNodeWithText("mesh radio").assertIsDisplayed()
        compose.onNodeWithText("Chats").performClick()
        compose.onNodeWithText("New Chat").assertIsDisplayed()
        compose.onNodeWithText("bitchat").assertDoesNotExist()
    }
}
