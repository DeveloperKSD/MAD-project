package `in`.yashd.bitchat.mesh

/**
 * In-memory nearby message log: queue ordering, re-send dedup and delivery bookkeeping.
 * Persistence lives in MessageStore; this class is pure and unit-testable.
 */
class ChatLog(private val maxMessages: Int = 200) {
    private val messages = LinkedHashMap<String, ChatMessage>()

    fun all(): List<ChatMessage> = messages.values.toList()
    operator fun get(messageId: String): ChatMessage? = messages[messageId]
    val size: Int get() = messages.size

    fun clear() = messages.clear()

    fun replaceAll(loaded: List<ChatMessage>) {
        messages.clear()
        loaded.takeLast(maxMessages).forEach { messages[it.messageId] = it }
    }

    fun createOutbound(localPeerId: String, recipientId: String?, body: String, isPrivate: Boolean,
                       messageId: String, nowMs: Long): ChatMessage {
        val message = ChatMessage(
            messageId = messageId, packetIdHex = null, senderId = localPeerId, recipientId = recipientId,
            body = body.trim(), createdAtMs = nowMs, receivedAtMs = null, isPrivate = isPrivate,
            status = MessageStatus.Pending, direction = MessageDirection.Outbound
        )
        put(message)
        return message
    }

    /** A queued message re-sent with a new packet still carries the same sender message id. */
    fun addInbound(messageId: String, packetIdHex: String, senderId: String, recipientId: String?, body: String,
                   isPrivate: Boolean, senderMessageId: String?, nowMs: Long): Boolean {
        if (messages.values.any { it.packetIdHex == packetIdHex ||
                (senderMessageId != null && it.senderMessageId == senderMessageId) }) return false
        put(ChatMessage(
            messageId = messageId, packetIdHex = packetIdHex, senderId = senderId, recipientId = recipientId,
            body = body, createdAtMs = nowMs, receivedAtMs = nowMs, isPrivate = isPrivate,
            status = MessageStatus.Delivered, direction = MessageDirection.Inbound, senderMessageId = senderMessageId
        ))
        return true
    }

    /** Private-chat peers present in saved history (either direction). */
    fun knownPeerIds(): Set<String> = messages.values.mapNotNull { m ->
        if (!m.isPrivate) null else if (m.direction == MessageDirection.Outbound) m.recipientId else m.senderId
    }.toSet()

    /** Outbound messages waiting for a route, oldest first. */
    fun pendingOutbound(): List<ChatMessage> =
        messages.values.filter { it.direction == MessageDirection.Outbound && it.status == MessageStatus.Pending }

    fun updateStatus(messageId: String, packetIdHex: String?, status: MessageStatus): ChatMessage? =
        messages[messageId]?.let {
            val next = it.copy(packetIdHex = packetIdHex ?: it.packetIdHex, status = status)
            put(next)
            next
        }

    fun markPacketDelivered(packetIdHex: String, senderId: String, hops: Int? = null): ChatMessage? =
        messages.values.firstOrNull {
            it.packetIdHex == packetIdHex && it.direction == MessageDirection.Outbound &&
                (it.recipientId == senderId || it.recipientId == null)
        }?.let {
            val next = it.copy(status = MessageStatus.Delivered, deliveredHops = hops)
            put(next)
            next
        }

    fun removeMatches(matches: (ChatMessage) -> Boolean): Boolean = messages.values.removeAll(matches)

    private fun put(message: ChatMessage) {
        messages[message.messageId] = message
        while (messages.size > maxMessages) messages.remove(messages.keys.first())
    }
}
