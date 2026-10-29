package `in`.yashd.bitchat.ui.relay

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import `in`.yashd.bitchat.nostr.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CompletableFuture

class GuidedInternetUiTest {
    @get:Rule val compose = createComposeRule()
    private val npub = NostrNpub.encode(ByteArray(32) { 1 })
    private class Backend : InternetBackend {
        var connection: (String, String) -> Unit = { _, _ -> }
        var accepted = true
        override fun identity() = NostrIdentity("01".repeat(32), NostrNpub.encode(ByteArray(32) { 1 }))
        override fun open(discovery: List<String>, received: (UnsignedNostrEvent) -> Unit,
            connection: (String, String) -> Unit, issue: (String) -> Unit): InternetSession {
            this.connection = connection
            return object : InternetSession {
                override fun receive(inboxes: List<String>) = CompletableFuture.completedFuture(Unit)
                override fun announce(inboxes: List<String>) = CompletableFuture.completedFuture(discovery.map {
                    NostrMessagingCoordinator.Publication("id", it, if (accepted) NostrMessagingCoordinator.Stage.RELAY_ACCEPTED else NostrMessagingCoordinator.Stage.RELAY_REJECTED)
                })
                override fun send(recipient: String, body: String, prepared: (String) -> Unit) = CompletableFuture.completedFuture(NostrMessagingCoordinator.SendResult(emptyList(), emptyList()))
                override fun close() {}
            }
        }
        override fun close() {}
    }
    private fun withApp(test: (RelayViewModel, Backend) -> Unit) {
        val backend = Backend()
        val model = RelayViewModel(backend, Dispatchers.Unconfined, `in`.yashd.bitchat.data.internet.TestHistoryRepository())
        val store = ViewModelStore(); store.put("relay", model)
        model.start()
        try {
            compose.setContent { `in`.yashd.bitchat.RelayHome(model, `in`.yashd.bitchat.mesh.MeshUiState(), {}, {}, {}, {}) }
            test(model, backend)
        } finally { store.clear() }
    }
    private fun setupNode(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("internet-setup-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun beginSetup() {
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("Nostr Relays"))
        compose.onNodeWithText("Nostr Relays").performClick()
    }
    private fun enterServers() {
        setupNode("Discovery server addresses").performTextReplacement("wss://discovery.example")
        setupNode("Inbox server addresses").performTextReplacement("wss://inbox.example")
        setupNode("Next").performClick()
    }
    @Test fun invalidServersDoNotAdvanceAndProgressSurvivesTabNavigation() = withApp { model, _ ->
        beginSetup()
        setupNode("Discovery server addresses").performTextInput("ws://unsafe.example")
        setupNode("Inbox server addresses").performTextInput("wss://inbox.example")
        setupNode("Next").performClick()
        compose.runOnIdle { assertEquals(0, model.state.value.setup.step); assertNotNull(model.state.value.setup.error) }
        enterServers()
        compose.runOnIdle { assertEquals(1, model.state.value.setup.step); assertFalse(model.state.value.readyToShare) }
        setupNode("Save for later").performClick()
        beginSetup()
        compose.onNodeWithText("Make yourself reachable").assertIsDisplayed()
        setupNode("Next").assertIsNotEnabled()
        setupNode("Back").performClick()
        setupNode("Discovery server addresses").assertTextContains("wss://discovery.example")
    }
    @Test fun failureRetryVerifiedConnectionsAndShareStep() = withApp { model, backend ->
        beginSetup(); enterServers()
        setupNode("Publish my inbox").assertIsNotEnabled()
        compose.runOnIdle { backend.connection("wss://inbox.example", "connected"); backend.accepted = false }
        setupNode("Publish my inbox").performClick()
        setupNode("Announcement: failed").assertIsDisplayed()
        setupNode("Next").assertIsNotEnabled()
        compose.runOnIdle { backend.accepted = true }
        setupNode("Retry announcement").performClick()
        setupNode("Next").performClick()
        compose.onNodeWithText("Share your Relay ID").assertIsDisplayed()
        setupNode(npub).assertIsDisplayed()
        setupNode("Copy").performClick()
        setupNode("Copied").assertIsDisplayed()
        setupNode("Share").assertIsEnabled()
        compose.runOnIdle { model.stop() }
        setupNode("Finish").assertIsNotEnabled()
        compose.runOnIdle { model.start(); backend.connection("wss://inbox.example", "connected") }
        setupNode("Finish").performClick()
        compose.onNodeWithTag("relay-settings-list").performScrollToNode(hasText("Nostr Relays"))
        compose.onNodeWithText("Nostr Relays").assertIsDisplayed()
    }
    @Test fun retryConnectionClearsOldSocketStatus() = withApp { model, backend ->
        beginSetup(); enterServers()
        compose.runOnIdle { backend.connection("wss://inbox.example", "error") }
        setupNode("Inbox connections: failed · retry or change servers").assertIsDisplayed()
        setupNode("Retry connection").performClick()
        compose.runOnIdle { assertFalse(model.state.value.inboxesConnected) }
        setupNode("Publish my inbox").assertIsNotEnabled()
        compose.runOnIdle { backend.connection("wss://inbox.example", "connected") }
        setupNode("Publish my inbox").assertIsEnabled()
    }
    @Test fun duplicateOnlineAddsProduceOneNamedRowAndRememberPublicKey() = withApp { model, _ ->
        repeat(2) {
            compose.onNodeWithContentDescription("New Chat").performClick()
            compose.onNodeWithText("Chat Online").performClick()
            compose.onNodeWithText("Friend’s public Relay ID (npub)").performTextReplacement(if (it == 0) npub else npub.uppercase())
            compose.onNodeWithText("Name for this chat (optional)").performTextReplacement("Aarav")
            compose.onNodeWithText("Start Chat").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back to Chats").performClick()
        }
        compose.runOnIdle { assertEquals(1, model.state.value.conversations.size); assertTrue(model.state.value.messages.isEmpty()) }
        compose.onAllNodesWithText("Aarav").assertCountEquals(1)
        compose.onNodeWithText("Aarav").performClick()
        compose.onNodeWithText("Aarav").performClick()
        compose.onNodeWithText("Verify identity").performScrollTo().performClick()
        compose.onNodeWithText(npub).assertIsDisplayed()
        compose.onNodeWithText("Friend’s public Relay ID (npub)").assertDoesNotExist()
    }
    @Test fun androidSharePayloadContainsOnlyPublicNpub() {
        val intent = publicIdShareIntent(npub)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(npub, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras!!.keySet())
    }
}
