package `in`.yashd.bitchat.ui.relay

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import `in`.yashd.bitchat.*
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.nostr.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@Composable
internal fun CurrentChat(state: RelayUiState, npub: String) {
    RelayDesignTheme {
        RelayChatScreen(describeRoute(internetRoute(npub), MeshUiState(), state), MeshUiState(), state,
            System.currentTimeMillis(), {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Composable
internal fun CurrentChats(loading: Boolean = false, unavailable: Boolean = false,
                         rows: List<ChatListItem> = emptyList(), open: (String) -> Unit = {}) {
    RelayDesignTheme { RelayChatsScreen(rows, "Me", 0, emptyList(), loading, unavailable, {}, open, {}, {}) }
}

class RelayUiTest {
    @get:Rule val compose = createComposeRule()
    private val npub = NostrNpub.encode(ByteArray(32) { 1 })

    @Test fun settingsShowsPublicIdentityAndSetupEntry() {
        var opened = false
        compose.setContent { RelayDesignTheme {
            RelaySettingsPage(MeshUiState(), RelayUiState(npub = "npub-public"), "Me", {}, {}, { opened = true }, {})
        } }
        compose.onNodeWithText("npub-public").assertIsDisplayed()
        compose.onNodeWithText("Nostr Relays").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }

    @Test fun chatDisablesSendWithoutConfiguration() {
        compose.setContent { CurrentChat(RelayUiState(storageLoading = false), npub) }
        compose.onNode(hasSetTextAction()).performTextInput("hello")
        compose.onNodeWithContentDescription("Send encrypted message").assertIsNotEnabled()
        compose.onNodeWithText("Set up Internet messaging before you can send.").assertIsDisplayed()
    }

    @Test fun chatShowsAcceptanceWithoutDeliveryClaim() {
        val state = RelayUiState(storageLoading = false, messages = listOf(
            InternetMessage("id", "01".repeat(32), "hello", true, "Relay accepted · delivery unconfirmed")))
        compose.setContent { CurrentChat(state, npub) }
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("Relay accepted · delivery unconfirmed").assertIsDisplayed()
    }

    private fun withApp(test: () -> Unit) {
        val backend = object : InternetBackend {
            override fun identity() = NostrIdentity("01".repeat(32), npub)
            override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
                connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession = error("No relays configured")
            override fun close() {}
        }
        val model = RelayViewModel(backend, history = `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = androidx.lifecycle.ViewModelStore().apply { put("relay", model) }
        try {
            compose.setContent { RelayHome(model, MeshUiState(bluetoothReadiness = BluetoothReadiness(true, false, true, true, true, true)), {}, {}, {}, {},
                nearbySettings = { item { androidx.compose.material3.Text("Nearby identity controls") } }) }
            test()
        } finally { store.clear() }
    }

    @Test fun productionTabsAndNearbyControlsAreReachable() = withApp {
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Discover").performClick()
        compose.onNodeWithText("Find Nearby").assertIsDisplayed()
        compose.onNodeWithText("Open Nearby group").performScrollTo().performClick()
        compose.onNodeWithText("Nearby group").assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back to Chats").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Bluetooth").performScrollTo().performClick()
        compose.onNodeWithText("Nearby identity controls").assertIsDisplayed()
    }

    @Test fun newChatValidatesAndReopensNamedConversationWithoutIdEntry() = withApp {
        compose.onNodeWithContentDescription("New Chat").performClick()
        compose.onNodeWithText("Chat Online").performClick()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").performTextInput("invalid")
        compose.onNodeWithText("Start Chat").performScrollTo().performClick()
        compose.onNodeWithText("That public ID doesn’t look right. Ask your friend to copy their full npub again.").assertIsDisplayed()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").performTextReplacement(npub)
        compose.onNodeWithText("Name for this chat (optional)").performTextInput("Aarav")
        compose.onNodeWithText("Start Chat").performScrollTo().performClick()
        compose.onNodeWithText("Aarav").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Chats").performClick()
        compose.onNodeWithText("Aarav").performClick()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").assertDoesNotExist()
        compose.onNodeWithText("Aarav").assertIsDisplayed()
    }

    @Test fun newChatNearbyChoiceOpensDiscover() = withApp {
        compose.onNodeWithContentDescription("New Chat").performClick()
        compose.onNodeWithText("Chat Nearby").performClick()
        compose.onNodeWithText("Meet people around you").assertIsDisplayed()
    }

    @Test fun discoverInvokesPermissionStartAndStopActions() {
        var starts = 0; var permissions = 0; var stops = 0
        var ready by mutableStateOf(false)
        var running by mutableStateOf(false)
        compose.setContent { RelayDesignTheme {
            RelayDiscoverPage(MeshUiState(serviceRunning = running,
                bluetoothReadiness = BluetoothReadiness(true, ready, true, true, true, true)), emptyList(), "Me", {},
                { starts++ }, { stops++ }, { permissions++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Find Nearby").performClick()
        compose.runOnIdle { assertEquals(1, permissions); ready = true }
        compose.onNodeWithText("Find Nearby").performClick()
        compose.runOnIdle { assertEquals(1, starts); running = true }
        compose.onNodeWithText("Stop finding nearby").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun loadingAndUnavailableHistoryAreNotPresentedAsAnEmptyNewAccount() {
        var loading by mutableStateOf(true)
        var unavailable by mutableStateOf(false)
        compose.setContent { CurrentChats(loading, unavailable) }
        compose.onNodeWithText("Loading your chats…").assertIsDisplayed()
        compose.runOnIdle { loading = false; unavailable = true }
        compose.onNodeWithText("Saved Internet chats are unavailable").assertIsDisplayed()
        compose.onNodeWithText("No chats yet").assertDoesNotExist()
    }

    @Test fun incomingMessageDoesNotPullReaderAwayFromHistory() {
        val peer = "01".repeat(32)
        var state by mutableStateOf(RelayUiState(storageLoading = false, messages = (1..60).map {
            InternetMessage("id-$it", peer, "Message $it", false, "Received", it.toLong())
        }))
        compose.setContent { CurrentChat(state, npub) }
        compose.onNodeWithTag("relay-message-list").performScrollToIndex(0)
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(messages = state.messages + InternetMessage("id-61", peer, "Message 61", false, "Received", 61)) }
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.onNodeWithText("Message 61").assertDoesNotExist()
    }

    @Test fun productionFiltersRestoreButPrivateSearchDoesNot() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        val rows = listOf(
            ChatListItem(RouteInfo("nearby:alice", true, "alice", "Alice", "Nearby", true, null, "alice"), "Nearby hello", 1, false, false, 0),
            ChatListItem(RouteInfo(internetRoute(npub), false, npub, "Bob", "Internet", true, null, npub), "Online hello", 2, false, false, 0))
        var opened: String? = null
        restoration.setContent { CurrentChats(rows = rows, open = { opened = it }) }
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Bob").assertIsDisplayed()
        compose.onAllNodesWithText("Internet").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Bob").performClick()
        compose.runOnIdle { assertEquals(internetRoute(npub), opened) }
        compose.onNode(hasSetTextAction()).performTextInput("PRIVATE_SEARCH")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithText("Bob").assertIsDisplayed()
    }
}
