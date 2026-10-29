package `in`.yashd.bitchat.ui.relay

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.*
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.mesh.MeshUiState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class FavoriteChatsUiTest {
    @get:Rule val compose = createComposeRule()
    private val token = UUID.randomUUID().toString()
    private val alias = "relay.test.favorite.ui.$token"
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("test-$token-$name", mode)
    }

    @After fun cleanup() {
        base.deleteSharedPreferences("test-$token-favorite_chats")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun existingFavoritesFilterRestoresAndUpdatesWithoutChangingAllChats() {
        val nearby = nearbyRoute("bob")
        val internet = internetRoute(publicNpub("02".repeat(32)))
        val key = FavoriteChatsStore.key("nearby", "alice", "bob")
        FavoriteChatsStore(context, HistoryCipher(alias)).toggle(key)
        val restoration = StateRestorationTester(compose)
        var current: FavoriteChatsStore? = null
        restoration.setContent {
            val store = remember { FavoriteChatsStore(context, HistoryCipher(alias)) }
            current = store
            val favorites by store.favorites.collectAsState()
            val rows = listOf(nearby to "Nearby friend", internet to "Internet friend").map { (route, title) ->
                ChatListItem(describeRoute(route, MeshUiState(), RelayUiState()).copy(title = title),
                    "Saved text", 1L, false, false, 0)
            }
            RelayDesignTheme {
                RelayChatsScreen(rows, "Me", 1L, if (key in favorites) listOf(nearby) else emptyList(),
                    false, false, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.onNodeWithText("Internet friend").assertIsDisplayed()
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.onNodeWithText("Internet friend").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.onNodeWithText("Internet friend").assertDoesNotExist()
        compose.runOnIdle { current!!.toggle(key) }
        compose.onNodeWithText("No chats found").assertIsDisplayed()
        compose.onNodeWithText("All", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.onNodeWithText("Internet friend").assertIsDisplayed()
    }
}
