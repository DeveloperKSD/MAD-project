package `in`.yashd.bitchat.ui.relay

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.MainActivity
import `in`.yashd.bitchat.data.internet.RoomInternetHistoryRepository
import `in`.yashd.bitchat.nostr.NostrIdentityManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import `in`.yashd.bitchat.RelayApplication
import `in`.yashd.bitchat.nostr.NostrNpub
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

/** Uses the production activity, identity keys, Room history, and encrypted favourites. */
class FavoriteStarActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun starPersistsAfterActivityClosesAndReopensAndStaysInSyncWithContactDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Keep this routing/UI test independent of the Android notification permission dialog.
        context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
            .edit().putBoolean("requested", true).commit()
        val owner = NostrIdentityManager(context).loadOrCreate().publicKeyHex
        val peer = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val name = "Star test ${peer.take(8)}"
        val key = FavoriteChatsStore.key("internet", owner, peer)
        // The application runtime can already exist from another Activity/test. Seed through
        // its public boundary so the shared snapshot and encrypted Room stay in sync.
        val runtime = (context.applicationContext as RelayApplication).internetRuntime
        runBlocking {
            withTimeout(10_000) { runtime.state.first { !it.storageLoading && !it.configurationLoading } }
            assertNotNull(runtime.addInternetConversation(NostrNpub.encode(peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), name))
        }
        fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        fun awaitStar(description: String) {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        }
        try {
            launch().use {
                awaitStar("Add $name to favorites")
                compose.onNodeWithContentDescription("Add $name to favorites").assertIsOff().performTouchInput { click() }
                compose.onNodeWithContentDescription("Remove $name from favorites").assertIsOn()
                compose.onNodeWithTag("relay-bottom-navigation").assertIsDisplayed()
                assertTrue(key in FavoriteChatsStore(context).favorites.value)
            }
            // A new activity borrows the same runtime and reloads the favourites store from disk.
            launch().use {
                awaitStar("Remove $name from favorites")
                compose.onNodeWithContentDescription("Remove $name from favorites").assertIsOn()
                compose.onNodeWithText(name).performTouchInput { click() }
                compose.onNodeWithContentDescription("Back to Chats").assertIsDisplayed()
                compose.onNodeWithText(name).performClick() // open contact details via chat header
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNodeWithText("Remove from favorites").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back to Chats").performClick()
                compose.onNodeWithContentDescription("Add $name to favorites").assertIsOff()
                compose.onNodeWithContentDescription("Add $name to favorites").performTouchInput { click() }
                compose.onNodeWithText("Favorites").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Remove $name from favorites").performTouchInput { click() }
                compose.onNodeWithText(name).assertDoesNotExist()
                assertFalse(key in FavoriteChatsStore(context).favorites.value)
            }
        } finally {
            FavoriteChatsStore(context).remove(key)
            runBlocking {
                RoomInternetHistoryRepository(context).let { repository ->
                    try { repository.deleteConversation(owner, peer) } finally { repository.close() }
                }
            }
        }
    }
}
