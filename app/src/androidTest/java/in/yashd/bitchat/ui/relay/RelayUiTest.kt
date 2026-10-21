package `in`.yashd.bitchat.ui.relay

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RelayUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsShowsPublicIdentityAndSetupEntry() {
        var opened = false
        compose.setContent { RelayTheme { InternetSettings(RelayUiState(npub = "npub-public"), { opened = true }) } }
        compose.onNodeWithText("npub-public").assertIsDisplayed()
        compose.onNodeWithText("Internet Setup").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }
    @Test fun chatDisablesSendWithoutConfiguration() {
        compose.setContent { RelayTheme { PrivateChat(RelayUiState(storageLoading = false), "invalid", {}, { _, _ -> }) } }
        compose.onNodeWithText("Message…").performTextInput("hello")
        compose.onNodeWithContentDescription("Send encrypted message").assertIsNotEnabled()
        compose.onNodeWithText("Relay acceptance does not confirm delivery or reading.").assertIsDisplayed()
    }
    @Test fun chatShowsAcceptanceWithoutDeliveryClaim() {
        val npub = `in`.yashd.bitchat.nostr.NostrNpub.encode(ByteArray(32) { 1 })
        val state = RelayUiState(storageLoading = false, messages = listOf(InternetMessage("id", "01".repeat(32), "hello", true, "Relay accepted · delivery unconfirmed")))
        compose.setContent { RelayTheme { PrivateChat(state, npub, {}, { _, _ -> }) } }
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("Relay accepted · delivery unconfirmed").assertIsDisplayed()
    }
    private fun withApp(test: () -> Unit) {
        val backend = object : InternetBackend {
            override fun identity() = `in`.yashd.bitchat.nostr.NostrIdentity("01".repeat(32), "npub-public")
            override fun open(discovery: List<String>, received: (`in`.yashd.bitchat.nostr.UnsignedNostrEvent) -> Unit,
                connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession = error("No relays configured")
            override fun close() {}
        }
        val model = RelayViewModel(backend, history = `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = androidx.lifecycle.ViewModelStore(); store.put("relay", model)
        try {
            compose.setContent { RelayApp(model,
                nearbyChat = { peer, _, _ -> androidx.compose.material3.Text("Nearby Chat: ${peer ?: "group"}") },
                nearbySettings = { item { androidx.compose.material3.Text("Nearby identity controls") } }) }
            repeat(3) { compose.onNodeWithText("Continue").performScrollTo().performClick() }
            test()
        } finally { store.clear() }
    }
    @Test fun exactlyThreeDestinationsAndNearbyAccessFromChats() = withApp {
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Recipient npub").assertDoesNotExist()
        compose.onNodeWithText("New Chat").assertIsDisplayed()
        compose.onNodeWithText("Discover").performClick()
        compose.onNodeWithText("Find Nearby").assertIsDisplayed()
        compose.onNodeWithText("Open Nearby group").performScrollTo().performClick()
        compose.onNodeWithText("Nearby Chat: group").assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Nearby identity controls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("Internet Setup"))
        compose.onNodeWithText("Internet Setup").assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
    }
    @Test fun newChatValidatesAndReopensNamedConversationWithoutIdEntry() = withApp {
        compose.onNodeWithText("New Chat").performClick()
        compose.onNodeWithText("Chat Nearby").assertIsDisplayed()
        compose.onNodeWithText("Chat Online").performClick()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").performTextInput("invalid")
        compose.onNodeWithText("Start Chat").performClick()
        compose.onNodeWithText("That public ID doesn’t look right. Ask your friend to copy their full npub again.").assertIsDisplayed()
        val npub = `in`.yashd.bitchat.nostr.NostrNpub.encode(ByteArray(32) { 1 })
        compose.onNodeWithText("Friend’s public Relay ID (npub)").performTextReplacement(npub)
        compose.onNodeWithText("Name for this chat (optional)").performTextInput("Aarav")
        compose.onNodeWithText("Start Chat").performScrollTo().performClick()
        compose.onNodeWithText("Private · NIP-17 · Internet").assertIsDisplayed()
        compose.onNodeWithText("Aarav").assertIsDisplayed()
        compose.onAllNodesWithTag("relay-bottom-navigation").assertCountEquals(1)
        compose.onNodeWithContentDescription("Back to Chats").performClick()
        compose.onNodeWithText("Aarav").assertIsDisplayed()
        compose.onNodeWithText("Aarav").performClick()
        compose.onNodeWithText("Private · NIP-17 · Internet").assertIsDisplayed()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").assertDoesNotExist()
    }
    @Test fun newChatNearbyChoiceOpensDiscover() = withApp {
        compose.onNodeWithText("New Chat").performClick()
        compose.onNodeWithText("Chat Nearby").performClick()
        compose.onNodeWithText("Meet people around you").assertIsDisplayed()
        compose.onNodeWithText("Open Nearby group").performScrollTo().assertIsDisplayed()
    }
    @Test fun conversationFiltersSearchAndTransportRoutes() {
        val rows = listOf(
            RelayConversation("nearby:alice", "Alice", "Meet nearby", ConversationTransport.Nearby, "alice"),
            RelayConversation("internet:bob", "Bob", "Online hello", ConversationTransport.Internet, "npub-bob"))
        var opened: RelayConversation? = null
        compose.setContent { RelayTheme { Conversations(rows, { opened = it }, {}, {}) } }
        compose.onNodeWithTag("conversation-nearby:alice").assertIsDisplayed()
        compose.onNodeWithTag("conversation-internet:bob").assertIsDisplayed()
        compose.onNodeWithTag("filter-Internet").performClick()
        compose.onNodeWithTag("conversation-nearby:alice").assertDoesNotExist()
        compose.onNodeWithTag("conversation-internet:bob").performClick()
        compose.runOnIdle { assertEquals(ConversationTransport.Internet, opened!!.transport) }
        compose.onNodeWithTag("filter-Nearby").performClick()
        compose.onNodeWithTag("conversation-internet:bob").assertDoesNotExist()
        compose.onNodeWithTag("conversation-nearby:alice").performClick()
        compose.runOnIdle { assertEquals("alice", opened!!.recipient) }
        compose.onNodeWithTag("filter-All").performClick()
        compose.onNodeWithText("Search chats").performTextInput("Online")
        compose.onNodeWithTag("conversation-internet:bob").assertIsDisplayed()
        compose.onNodeWithTag("conversation-nearby:alice").assertDoesNotExist()
    }
    @Test fun discoverInvokesExistingPermissionAndMeshActions() {
        var starts = 0
        var permissions = 0
        var stops = 0
        var ready by androidx.compose.runtime.mutableStateOf(false)
        var running by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { RelayTheme {
            DiscoverScreen(`in`.yashd.bitchat.mesh.MeshUiState(serviceRunning = running,
                bluetoothReadiness = `in`.yashd.bitchat.mesh.BluetoothReadiness(true, ready, true, true, true, true)),
                { starts++ }, { stops++ }, { permissions++ }, {}, {})
        } }
        compose.onNodeWithText("Find Nearby").performClick()
        compose.runOnIdle { assertEquals(1, permissions); ready = true }
        compose.onNodeWithText("Find Nearby").performClick()
        compose.runOnIdle { assertEquals(1, starts); running = true }
        compose.onNodeWithText("Stop finding nearby").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun filterSurvivesRestorationButSensitiveSearchDoesNot() {
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        val rows = listOf(
            RelayConversation("nearby:alice", "Alice", "Nearby", ConversationTransport.Nearby, "alice"),
            RelayConversation("internet:bob", "Bob", "Online", ConversationTransport.Internet, "npub-bob"))
        restore.setContent { RelayTheme { Conversations(rows, {}, {}, {}) } }
        compose.onNodeWithTag("filter-Internet").performClick()
        compose.onNodeWithText("Search chats").performTextInput("Bob")
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("conversation-nearby:alice").assertDoesNotExist()
        compose.onNodeWithTag("conversation-internet:bob").assertIsDisplayed()
    }
    @Test fun loadingAndUnavailableHistoryAreNotPresentedAsAnEmptyNewAccount() {
        var loading by androidx.compose.runtime.mutableStateOf(true)
        var unavailable by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { RelayTheme { Conversations(emptyList(), {}, {}, {}, loading, unavailable) } }
        compose.onNodeWithText("Loading saved Internet chats…").assertIsDisplayed()
        compose.runOnIdle { loading = false; unavailable = true }
        compose.onNodeWithText("Saved Internet chats are unavailable").assertIsDisplayed()
        compose.onNodeWithText("Your next conversation starts here").assertDoesNotExist()
        compose.onNodeWithText("Find people nearby").assertIsDisplayed()
    }
}
