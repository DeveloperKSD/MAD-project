package `in`.yashd.bitchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import `in`.yashd.bitchat.transfer.TransferProfile
import `in`.yashd.bitchat.transfer.ble.BleFilePhase
import `in`.yashd.bitchat.transfer.ble.BleFileStatus
import org.junit.Test

class ChatThreadGroupingTest {
    private fun bubble(id: String, mine: Boolean, atMs: Long) =
        BubbleUi(id, "text", mine, atMs, null, null, 0, failed = false, pending = false, nearbyMessage = null)

    private fun messages(rows: List<ThreadRow>) = rows.filterIsInstance<ThreadRow.Message>()

    @Test
    fun `same sender run starts once and stays tight`() {
        val base = 1_700_000_000_000L
        val rows = withDays(listOf(bubble("a", false, base), bubble("b", false, base + 60_000)), base)
        val msgs = messages(rows)
        assertTrue(msgs[0].bubble.runStart)
        assertFalse(msgs[1].bubble.runStart)
        assertFalse(msgs[0].bigGapBefore)
        assertFalse(msgs[1].bigGapBefore)
    }

    @Test
    fun `side switch starts a new run with a bigger gap`() {
        val base = 1_700_000_000_000L
        val rows = withDays(listOf(bubble("a", false, base), bubble("b", true, base + 60_000)), base)
        val msgs = messages(rows)
        assertTrue(msgs[1].bubble.runStart)
        assertTrue(msgs[1].bigGapBefore)
    }

    @Test
    fun `day break starts a run without an extra gap`() {
        val day1 = 1_700_000_000_000L
        val day2 = day1 + 86_400_000L
        val rows = withDays(listOf(bubble("a", false, day1), bubble("b", false, day2)), day2)
        val msgs = messages(rows)
        assertTrue(rows.any { it is ThreadRow.Day })
        assertTrue(msgs[1].bubble.runStart)
        assertFalse(msgs[1].bigGapBefore)
    }

    @Test
    fun `attachment lands in chronological position with day grouping`() {
        val base = 1_700_000_000_000L
        val file = BleFileStatus(java.util.UUID.randomUUID(), "peer", BleFilePhase.COMPLETED, true,
            100L, 100L, TransferProfile.DIRECT_BLE, createdAtMs = base + 30_000)
        val rows = withDays(listOf(bubble("a", false, base), bubble("b", false, base + 60_000)), base,
            listOf(AttachmentItem(file)))
        val order = rows.map { when (it) {
            is ThreadRow.Day -> "day"
            is ThreadRow.Message -> it.bubble.id
            is ThreadRow.Attachment -> "att"
        } }
        assertEquals(listOf("day", "a", "att", "b"), order)
    }

    @Test
    fun `legacy attachment without timestamp sorts last`() {
        val base = 1_700_000_000_000L
        val file = BleFileStatus(java.util.UUID.randomUUID(), "peer", BleFilePhase.COMPLETED, false,
            100L, 100L, TransferProfile.DIRECT_BLE, createdAtMs = 0L)
        val rows = withDays(listOf(bubble("a", false, base)), base, listOf(AttachmentItem(file)))
        assertTrue(rows.last() is ThreadRow.Attachment)
    }
}
