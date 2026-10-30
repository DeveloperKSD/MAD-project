package `in`.yashd.bitchat.attachments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import `in`.yashd.bitchat.RelayDesignTheme
import `in`.yashd.bitchat.TransferDetail
import `in`.yashd.bitchat.TransferSummaryRow
import `in`.yashd.bitchat.transfer.ble.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NearbyAttachmentUiTest {
    @get:Rule val compose = createComposeRule()
    private fun status(phase: BleFilePhase, outgoing: Boolean = false, acknowledged: Long = 0) =
        BleFileStatus(UUID.randomUUID(), "0123456789abcdef", phase, outgoing, acknowledged, 1024)
    private fun summary(file: BleFileStatus) {
        compose.setContent { RelayDesignTheme { Column(Modifier.verticalScroll(rememberScrollState())) { TransferSummaryRow(file, null, null) {} } } }
    }
    private fun detail(file: BleFileStatus, connected: Boolean = true, action: (BleFileAction) -> Unit = {}, preview: () -> Unit = {}) {
        compose.setContent { RelayDesignTheme { Column(Modifier.verticalScroll(rememberScrollState())) { TransferDetail(file, connected, false, null, null, null, action, preview) {} } } }
    }
    @Test fun incomingOfferSummaryShowsHonestStateWithoutControls() {
        summary(status(BleFilePhase.AWAITING_ACCEPTANCE))
        compose.onNodeWithText("Incoming file").assertIsDisplayed()
        compose.onNodeWithText("Waiting for your response").assertIsDisplayed()
        compose.onNodeWithText("Accept").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertDoesNotExist()
    }
    @Test fun incomingOfferAcceptRejectLiveInDetailSurface() {
        var action: BleFileAction? = null
        detail(status(BleFilePhase.AWAITING_ACCEPTANCE), action = { action = it })
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText("Accept").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BleFileAction.ACCEPT, action) }
        compose.onNodeWithText("Reject").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BleFileAction.REJECT, action) }
    }
    @Test fun fullyAcknowledgedSenderStillWaitsForVerification() {
        detail(status(BleFilePhase.SENDING, true, 1024))
        compose.onNodeWithText("Awaiting receiver's verified completion.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Completed · verified").assertDoesNotExist()
        compose.onNodeWithText("Pause").performScrollTo().assertIsEnabled()
    }
    @Test fun pausedReceiverCannotPretendToResumeOrRetry() {
        detail(status(BleFilePhase.PAUSED))
        compose.onNodeWithText("Receiver-initiated resume is unavailable.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Offer resume").assertDoesNotExist()
        compose.onNodeWithText("Cancel transfer").performScrollTo().assertIsEnabled()
    }
    @Test fun disconnectedSenderCannotResumeAndFailedFilesCannotPreview() {
        detail(status(BleFilePhase.PAUSED, true), connected = false)
        compose.onNodeWithText("Offer resume").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check / preview received file").assertDoesNotExist()
    }
    @Test fun verifiedReceivedCompletionOffersInspectionButDoesNotOpenAutomatically() {
        var previews = 0
        detail(status(BleFilePhase.COMPLETED), preview = { previews++ })
        compose.runOnIdle { assertEquals(0, previews) }
        compose.onNodeWithText("Check / preview received file").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, previews) }
        compose.onNodeWithText("Remove attachment").performScrollTo().assertIsEnabled()
    }
    @Test fun rejectedTransferExplainsSafeNewOfferInsteadOfFakeRetry() {
        detail(status(BleFilePhase.REJECTED, true))
        compose.onNodeWithText("Rejected").assertIsDisplayed()
        compose.onNodeWithText("Automatic retry is unavailable.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
