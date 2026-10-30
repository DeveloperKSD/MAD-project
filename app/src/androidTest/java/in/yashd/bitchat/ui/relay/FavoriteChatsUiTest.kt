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
import org.junit.Assert.*
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
        restoration.setContent {
            val store = remember { FavoriteChatsStore(context, HistoryCipher(alias)) }
            val favorites by store.favorites.collectAsState()
            val rows = listOf(nearby to "Nearby friend", internet to "Internet friend").map { (route, title) ->
                ChatListItem(describeRoute(route, MeshUiState(), RelayUiState()).copy(title = title),
                    "Saved text", 1L, false, false, 0)
            }
            RelayDesignTheme {
                RelayChatsScreen(rows, "Me", 1L, if (key in favorites) listOf(nearby) else emptyList(),
                    false, false, {}, {}, {}, {}, toggleFavorite = { route ->
                        store.toggle(if (route == nearby) key else FavoriteChatsStore.key("internet", "alice", "02".repeat(32)))
                    })
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
        compose.onNodeWithContentDescription("Remove Nearby friend from favorites").assertIsOn().performTouchInput { click() }
        compose.onNodeWithText("No chats found").assertIsDisplayed()
        compose.onNodeWithText("All", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.onNodeWithText("Internet friend").assertIsDisplayed()
    }
    @Test fun starsToggleBothTransportsAndGroupWithoutOpeningRowsAndSurviveReopening() {
        val peer = "02".repeat(32)
        val routes = listOf(nearbyRoute(peer), internetRoute(publicNpub(peer)), nearbyRoute(null))
        val names = listOf("Nearby friend", "Internet friend", "Nearby group")
        val keys = listOf(FavoriteChatsStore.key("nearby", "alice", peer),
            FavoriteChatsStore.key("internet", "alice", peer), FavoriteChatsStore.key("nearby", "alice", null))
        var opened: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val store = remember { FavoriteChatsStore(context, HistoryCipher(alias)) }
            val favorites by store.favorites.collectAsState()
            val rows = routes.zip(names).map { (route, title) ->
                ChatListItem(describeRoute(route, MeshUiState(), RelayUiState()).copy(title = title),
                    "Saved text", 1L, false, false, 1)
            }
            RelayDesignTheme {
                RelayChatsScreen(rows, "Me", 1L, routes.filterIndexed { i, _ -> keys[i] in favorites },
                    false, false, {}, { opened = it }, {}, {},
                    toggleFavorite = { store.toggle(keys[routes.indexOf(it)]) })
            }
        }
        names.forEachIndexed { i, name ->
            compose.onNodeWithContentDescription("Add $name to favorites").assertIsOff().performTouchInput { click() }
            compose.onNodeWithContentDescription("Remove $name from favorites").assertIsOn()
            compose.runOnIdle {
                assertNull(opened)
                assertTrue(keys[i] in FavoriteChatsStore(context, HistoryCipher(alias)).favorites.value)
            }
        }
        // Drop all remembered stores and reopen the screen against the encrypted preferences.
        restoration.emulateSavedInstanceStateRestore()
        names.forEach { compose.onNodeWithContentDescription("Remove $it from favorites").assertIsOn() }
        compose.onNodeWithText("Internet friend").performTouchInput { click() }
        compose.runOnIdle { assertEquals(routes[1], opened); opened = null }
        compose.onNodeWithContentDescription("Remove Internet friend from favorites").performTouchInput { click() }
        compose.onNodeWithContentDescription("Add Internet friend to favorites").assertIsOff()
        compose.onNodeWithContentDescription("Remove Nearby friend from favorites").assertIsOn()
        compose.runOnIdle { assertNull(opened) }
    }

    @Test fun failedSaveKeepsFilledStarAndFavoritesFilterRow() {
        val route = nearbyRoute("bob")
        val key = FavoriteChatsStore.key("nearby", "alice", "bob")
        FavoriteChatsStore(context, HistoryCipher(alias)).toggle(key)
        var opened = false
        compose.setContent {
            val store = remember { FavoriteChatsStore(context, HistoryCipher(alias)) }
            val favorites by store.favorites.collectAsState()
            val row = ChatListItem(describeRoute(route, MeshUiState(), RelayUiState()).copy(title = "Nearby friend"),
                "Saved text", 1L, false, false, 0)
            RelayDesignTheme {
                RelayChatsScreen(listOf(row), "Me", 1L, if (key in favorites) listOf(route) else emptyList(),
                    false, false, {}, { opened = true }, {}, {}, toggleFavorite = { store.toggle(key) })
            }
        }
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.runOnIdle {
            context.getSharedPreferences("favorite_chats", Context.MODE_PRIVATE).edit()
                .putString("items.enc", "damaged ciphertext").commit()
        }
        compose.onNodeWithContentDescription("Remove Nearby friend from favorites").performTouchInput { click() }
        compose.onNodeWithContentDescription("Remove Nearby friend from favorites").assertIsOn()
        compose.onNodeWithText("Nearby friend").assertIsDisplayed()
        compose.runOnIdle { assertFalse(opened) }
    }

}
