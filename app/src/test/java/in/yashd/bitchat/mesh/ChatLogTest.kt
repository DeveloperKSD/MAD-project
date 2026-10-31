package `in`.yashd.bitchat.mesh

import org.junit.Assert.*
import org.junit.Test

class ChatLogTest {
    private fun outbound(log: ChatLog, id: String, to: String? = "friend", at: Long = 1000L) =
        log.createOutbound("me", to, "hello $id", to != null, id, at)

    @Test fun outboundMessagesQueueOldestFirstUntilDispatched() {
        val log = ChatLog()
        outbound(log, "m1"); outbound(log, "m2"); outbound(log, "m3")
        assertEquals(listOf("m1", "m2", "m3"), log.pendingOutbound().map { it.messageId })
        log.updateStatus("m1", "aa", MessageStatus.SentToTransport)
        assertEquals(listOf("m2", "m3"), log.pendingOutbound().map { it.messageId })
    }

    @Test fun resentPacketWithSameSenderMessageIdIsNotDuplicated() {
        val log = ChatLog()
        assertTrue(log.addInbound("in1", "packet1", "friend", "me", "hi", true, "sender-msg-1", 1000L))
        // The queued message arrives again over a new packet: same sender message id.
        assertFalse(log.addInbound("in2", "packet2", "friend", "me", "hi", true, "sender-msg-1", 2000L))
        assertEquals(1, log.all().count { it.senderMessageId == "sender-msg-1" })
    }

    @Test fun samePacketIdIsNotDuplicatedWithoutSenderMessageId() {
        val log = ChatLog()
        assertTrue(log.addInbound("in1", "packet1", "friend", "me", "hi", true, null, 1000L))
        assertFalse(log.addInbound("in2", "packet1", "friend", "me", "hi", true, null, 2000L))
    }

    @Test fun distinctMessagesFromOneSenderBothArrive() {
        val log = ChatLog()
        assertTrue(log.addInbound("in1", "packet1", "friend", "me", "one", true, "s1", 1000L))
        assertTrue(log.addInbound("in2", "packet2", "friend", "me", "two", true, "s2", 1001L))
        assertEquals(2, log.size)
    }

    @Test fun deliveryAckMatchesOnlyTheIntendedOutboundChat() {
        val log = ChatLog()
        outbound(log, "mine", to = "friend")
        log.updateStatus("mine", "pk", MessageStatus.SentToTransport)
        // An ACK from someone else must not mark this message delivered.
        assertNull(log.markPacketDelivered("pk", "stranger", 1))
        assertEquals(MessageStatus.SentToTransport, log["mine"]!!.status)
        val delivered = log.markPacketDelivered("pk", "friend", 2)
        assertEquals(MessageStatus.Delivered, delivered!!.status)
        assertEquals(2, delivered.deliveredHops)
    }

    @Test fun groupMessageAckFromAnyMemberCounts() {
        val log = ChatLog()
        outbound(log, "announcement", to = null)
        log.updateStatus("announcement", "pk", MessageStatus.SentToTransport)
        assertNotNull(log.markPacketDelivered("pk", "any-member", 0))
    }

    @Test fun inboundPacketsNeverBecomeDeliveredByAck() {
        val log = ChatLog()
        log.addInbound("in1", "pk", "friend", "me", "hi", true, null, 1000L)
        assertNull(log.markPacketDelivered("pk", "friend", 0))
    }

    @Test fun logIsBoundedAndDropsTheOldest() {
        val log = ChatLog(maxMessages = 5)
        repeat(7) { outbound(log, "m$it") }
        assertEquals(5, log.size)
        assertNull(log["m0"])
        assertNotNull(log["m6"])
    }

    @Test fun queuedMessagesSurviveAReload() {
        val log = ChatLog()
        outbound(log, "kept")
        val restored = ChatLog()
        restored.replaceAll(log.all())
        assertEquals(listOf("kept"), restored.pendingOutbound().map { it.messageId })
    }
}
