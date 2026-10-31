package `in`.yashd.bitchat.attachments

import `in`.yashd.bitchat.transfer.TransferProfile
import `in`.yashd.bitchat.transfer.ble.BleFilePhase
import `in`.yashd.bitchat.transfer.ble.BleFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class TransferPresentationTest {
    private fun status(phase: BleFilePhase, outgoing: Boolean = true, confirmed: Long = 0, total: Long = 100) =
        BleFileStatus(UUID.randomUUID(), "peer", phase, outgoing, confirmed, total, TransferProfile.DIRECT_BLE)

    @Test fun stateLabelsCoverEveryPhase() {
        BleFilePhase.entries.forEach { assert(transferStateLabel(it).isNotBlank()) { it.name } }
        assertEquals("Waiting for acceptance", transferStateLabel(BleFilePhase.OFFERED))
        assertEquals("Waiting for your response", transferStateLabel(BleFilePhase.AWAITING_ACCEPTANCE))
        assertEquals("Transferring", transferStateLabel(BleFilePhase.SENDING))
        assertEquals("Completed · verified", transferStateLabel(BleFilePhase.COMPLETED))
    }

    @Test fun incomingNeverShowsFabricatedFilename() {
        assertEquals("Incoming file", transferDisplayName(false, null, null))
        assertEquals("Incoming file", transferDisplayName(false, "photo.jpg", null))
        assertEquals("Image", transferDisplayName(false, null, AttachmentKind.JPEG))
        assertEquals("PDF document", transferDisplayName(false, null, AttachmentKind.PDF))
    }

    @Test fun outgoingShowsChosenNameWithFallback() {
        assertEquals("photo.jpg", transferDisplayName(true, "photo.jpg", null))
        assertEquals("File", transferDisplayName(true, null, null))
        assertEquals("File", transferDisplayName(true, "", null))
    }

    @Test fun progressIsClamped() {
        assertEquals(0f, transferProgress(0, 0))
        assertEquals(0f, transferProgress(10, 0))
        assertEquals(0.5f, transferProgress(50, 100))
        assertEquals(1f, transferProgress(200, 100))
    }

    @Test fun galleryIncludesCompletedTransfersInBothDirections() {
        assert(galleryEligible(status(BleFilePhase.COMPLETED, outgoing = true)))
        assert(galleryEligible(status(BleFilePhase.COMPLETED, outgoing = false)))
        BleFilePhase.entries.filter { it != BleFilePhase.COMPLETED }.forEach {
            assert(!galleryEligible(status(it))) { it.name }
        }
    }

    @Test fun notificationTextOnlyForActiveTransfers() {
        assertNull(transferNotificationText(emptyList()))
        assertNull(transferNotificationText(listOf(status(BleFilePhase.COMPLETED))))
        assertNull(transferNotificationText(listOf(status(BleFilePhase.FAILED))))
        assertEquals("Incoming file offer · open Relay to accept" to null,
            transferNotificationText(listOf(status(BleFilePhase.AWAITING_ACCEPTANCE, outgoing = false))))
        assertEquals("Sending file · 42%" to 42,
            transferNotificationText(listOf(status(BleFilePhase.SENDING, confirmed = 42, total = 100))))
        assertEquals("Receiving file · 0%" to 0,
            transferNotificationText(listOf(status(BleFilePhase.RECEIVING, outgoing = false, confirmed = 0, total = 0))))
        assertEquals("File transfer paused" to null,
            transferNotificationText(listOf(status(BleFilePhase.PAUSED))))
    }
}
