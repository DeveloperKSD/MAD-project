package `in`.yashd.bitchat.attachments

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import `in`.yashd.bitchat.*
import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.ui.relay.RelayUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyAttachmentHostTest {
    @get:Rule val compose = createComposeRule()
    @Test fun privateNearbyPlusOpensSystemPickerChoicesWithoutSending() {
        val peer = PeerState(PeerIdentity("0123456789abcdef", "Friend", byteArrayOf(), byteArrayOf(), 0, 0, true),
            PeerConnectionState.Authenticated, "test-link", null, null, null, null)
        val mesh = MeshUiState(serviceRunning = true, peers = listOf(peer))
        compose.setContent { RelayDesignTheme { RelayChatScreen(describeRoute(nearbyRoute(peer.identity.peerId), mesh, RelayUiState()),
            mesh, RelayUiState(), System.currentTimeMillis(), {}, {}, { _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Nearby attachment").assertIsDisplayed()
        compose.onNodeWithText("Choose photo").assertIsEnabled()
        compose.onNodeWithText("Choose file").assertIsEnabled()
        compose.onNodeWithText("1 MiB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun confirmationShowsRecipientNameSizeAndMethodWithoutStartingAutomatically() {
        var sent = 0
        val draft = PickedAttachment(File("unused-confirmation-only"), "report.pdf", 1234, AttachmentKind.PDF, true)
        compose.setContent { RelayDesignTheme { NearbyAttachmentConfirmation(draft, "Friend", "0123456789abcdef", false, true, null, { sent++ }, {}) } }
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("1234 bytes").assertIsDisplayed()
        compose.onNodeWithText("Recipient: Friend").assertIsDisplayed()
        compose.onNodeWithText("Multi-hop BLE · 250 KiB maximum").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sent) }
        compose.onNodeWithText("Send offer").performClick()
        compose.runOnIdle { assertEquals(1, sent) }
    }
    @Test fun disconnectedConfirmationCannotOfferButCanDiscardSelection() {
        val draft = PickedAttachment(File("unused-confirmation-only"), "image.png", 100, AttachmentKind.PNG, false)
        compose.setContent { RelayDesignTheme { NearbyAttachmentConfirmation(draft, "Friend", "peer", false, false, null, {}, {}) } }
        compose.onNodeWithText("Send offer").assertIsNotEnabled()
        compose.onNodeWithText("Cancel selection").assertIsEnabled()
    }
    @Test fun attachmentActionRemainsUsableAfterSavedStateRecreation() {
        val peer = PeerState(PeerIdentity("0123456789abcdef", "Friend", byteArrayOf(), byteArrayOf(), 0, 0, true),
            PeerConnectionState.Authenticated, "test-link", null, null, null, null)
        val mesh = MeshUiState(serviceRunning = true, peers = listOf(peer))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { RelayDesignTheme { RelayChatScreen(describeRoute(nearbyRoute(peer.identity.peerId), mesh, RelayUiState()),
            mesh, RelayUiState(), System.currentTimeMillis(), {}, {}, { _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Close").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Nearby attachment").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }

}
