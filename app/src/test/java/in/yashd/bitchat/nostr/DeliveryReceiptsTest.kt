package `in`.yashd.bitchat.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import `in`.yashd.bitchat.data.internet.HistoryStatus
import `in`.yashd.bitchat.data.internet.TestHistoryRepository

class DeliveryReceiptsTest {
    private val alice = ByteArray(32).also { it[31] = 3 }
    private val bob = ByteArray(32).also { it[31] = 4 }
    private val nip17 = Nip17()
    private fun pub(key: ByteArray) = SchnorrSignature().publicKey(key).lowercaseHex()
    private val originalId = "ab".repeat(32)

    private fun receiptRumor(signer: String = pub(bob), target: String = originalId, content: String = "") =
        UnsignedNostrEvent(signer, 1_700_000_000, 14,
            listOf(listOf("p", pub(alice)), listOf(DeliveryReceipts.TAG, DeliveryReceipts.DELIVERED, target)), content)

    @Test fun `encrypted receipt roundtrip resolves the original rumor id`() {
        val wraps = nip17.createReceipt(bob, pub(alice), originalId, 1_700_000_000)
        val rumor = nip17.decodeMessage(wraps.recipientCopy, alice, pub(bob), pub(alice))
        assertEquals(originalId, DeliveryReceipts.receiptTarget(rumor))
        // The sender's own copy decodes to the same receipt rumor.
        val copy = nip17.decodeMessage(wraps.senderCopy, bob, pub(bob), pub(alice))
        assertEquals(originalId, DeliveryReceipts.receiptTarget(copy))
        assertEquals(wraps.rumorId, NostrEventCodec.calculateId(rumor))
    }

    @Test fun `ordinary message is never classified as receipt`() {
        val message = UnsignedNostrEvent(pub(bob), 1_700_000_000, 14, listOf(listOf("p", pub(alice))), "hello")
        assertNull(DeliveryReceipts.receiptTarget(message))
        // Even a typed body mimicking the tag cannot be a receipt: bodies carry no tags.
        val typed = message.copy(content = "relay-receipt delivered $originalId")
        assertNull(DeliveryReceipts.receiptTarget(typed))
    }

    @Test fun `malformed receipts are rejected`() {
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor(content = "not empty")))
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor(target = "not-hex")))
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor(target = originalId.uppercase())))
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor().copy(kind = 15)))
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor().copy(
            tags = listOf(listOf("p", pub(alice)), listOf(DeliveryReceipts.TAG, "read", originalId)))))
        assertNull(DeliveryReceipts.receiptTarget(receiptRumor().copy(
            tags = listOf(listOf("p", pub(alice)), listOf(DeliveryReceipts.TAG, DeliveryReceipts.DELIVERED)))))
    }

    @Test fun `markDelivered correlates only the authenticated recipient of our outgoing message`() = runBlocking {
        val repo = TestHistoryRepository()
        repo.outgoing("owner", "m1", pub(bob), "hi", 1000)
        repo.bindRumor("owner", "m1", originalId)
        repo.publication("owner", "m1", NostrMessagingCoordinator.SendResult(
            listOf(NostrMessagingCoordinator.Publication("e", "wss://r", NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList()))
        // Wrong signer (not the recipient) cannot mark it delivered.
        assertFalse(repo.markDelivered("owner", originalId, pub(alice)))
        // Unknown or incoming ids cannot be marked.
        assertFalse(repo.markDelivered("owner", "cd".repeat(32), pub(bob)))
        // The authenticated recipient's receipt marks exactly this message delivered.
        assertTrue(repo.markDelivered("owner", originalId, pub(bob)))
        assertEquals(HistoryStatus.DELIVERED, repo.load("owner").messages.single().status)
        // Replayed receipts are idempotent no-ops.
        assertFalse(repo.markDelivered("owner", originalId, pub(bob)))
        assertEquals(HistoryStatus.DELIVERED, repo.load("owner").messages.single().status)
        assertEquals(1000, repo.load("owner").messages.single().createdAtMs)
    }

    @Test fun `markDelivered never touches incoming or terminal messages`() = runBlocking {
        val repo = TestHistoryRepository()
        repo.received("owner", originalId, pub(bob), "hi", false, 1000)
        assertFalse(repo.markDelivered("owner", originalId, pub(bob)))
        repo.outgoing("owner", "m2", pub(bob), "yo", 2000)
        repo.bindRumor("owner", "m2", "ef".repeat(32))
        repo.failed("owner", "m2", unknown = false)
        assertFalse(repo.markDelivered("owner", "ef".repeat(32), pub(bob)))
        assertEquals(HistoryStatus.FAILED, repo.load("owner").messages.first { it.recordId == "m2" }.status)
    }
}
