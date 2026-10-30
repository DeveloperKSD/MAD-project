package `in`.yashd.bitchat.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.decode64
import `in`.yashd.bitchat.identity.encode64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MessageStore(context: Context, private val cipher: HistoryCipher = HistoryCipher("relay.nearby.messages.aes.v1")) {
    private val prefs = context.applicationContext.getSharedPreferences("messages", Context.MODE_PRIVATE)
    private val messages = LinkedHashMap<String, ChatMessage>()
    var storageError: Boolean = false
        private set
    private val _conversation = MutableStateFlow<List<ChatMessage>>(emptyList())
    val conversation: StateFlow<List<ChatMessage>> = _conversation

    init {
        load()
    }

    fun createOutbound(localPeerId: String, recipientId: String?, body: String, isPrivate: Boolean): ChatMessage {
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            messageId = UUID.randomUUID().toString(),
            packetIdHex = null,
            senderId = localPeerId,
            recipientId = recipientId,
            body = body.trim(),
            createdAtMs = now,
            receivedAtMs = null,
            isPrivate = isPrivate,
            status = MessageStatus.Pending,
            direction = MessageDirection.Outbound
        )
        put(message)
        return message
    }

    fun addInbound(packetIdHex: String, senderId: String, recipientId: String?, body: String, isPrivate: Boolean,
                   senderMessageId: String? = null): Boolean {
        // A queued message re-sent with a new packet still carries the same sender message id.
        if (messages.values.any { it.packetIdHex == packetIdHex ||
                (senderMessageId != null && it.senderMessageId == senderMessageId) }) return false
        val now = System.currentTimeMillis()
        put(
            ChatMessage(
                messageId = UUID.randomUUID().toString(),
                packetIdHex = packetIdHex,
                senderId = senderId,
                recipientId = recipientId,
                body = body,
                createdAtMs = now,
                receivedAtMs = now,
                isPrivate = isPrivate,
                status = MessageStatus.Delivered,
                direction = MessageDirection.Inbound,
                senderMessageId = senderMessageId
            )
        )
        return true
    }

    /** Outbound messages waiting for a route, oldest first. */
    fun pendingOutbound(): List<ChatMessage> =
        messages.values.filter { it.direction == MessageDirection.Outbound && it.status == MessageStatus.Pending }

    fun updateStatus(messageId: String, packetIdHex: String?, status: MessageStatus) {
        messages[messageId]?.let {
            put(it.copy(packetIdHex = packetIdHex ?: it.packetIdHex, status = status))
        }
    }

    fun markPacketDelivered(packetIdHex: String, senderId: String, hops: Int? = null) {
        messages.values.firstOrNull { it.packetIdHex == packetIdHex && it.direction == MessageDirection.Outbound &&
            (it.recipientId == senderId || it.recipientId == null) }?.let {
            put(it.copy(packetIdHex = packetIdHex, status = MessageStatus.Delivered, deliveredHops = hops))
        }
    }

    /** Removes one message from this phone only. */
    fun deleteMessage(messageId: String) = remove { it.messageId == messageId }

    /** Removes a whole Nearby chat from this phone: one private peer, or the public group when [peerId] is null. */
    fun deleteConversation(peerId: String?) = remove { message ->
        if (peerId == null) !message.isPrivate && message.recipientId == null
        else message.isPrivate && (message.senderId == peerId || message.recipientId == peerId)
    }

    private fun remove(matches: (ChatMessage) -> Boolean) {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        if (!messages.values.removeAll(matches)) return
        try { save() } catch (e: Exception) { storageError = true; throw e }
        publish()
    }

    private fun put(message: ChatMessage) {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        messages[message.messageId] = message
        while (messages.size > 200) messages.remove(messages.keys.first())
        try { save() } catch (e: Exception) { storageError = true; throw e }
        publish()
    }

    private fun publish() {
        _conversation.value = messages.values.sortedBy { it.createdAtMs }.takeLast(100)
    }

    private fun save() {
        val array = JSONArray()
        messages.values.toList().takeLast(200).forEach { message ->
            val item = JSONObject()
                    .put("messageId", message.messageId)
                    .put("packetIdHex", message.packetIdHex)
                    .put("senderId", message.senderId)
                    .put("recipientId", message.recipientId)
                    .put("body", message.body)
                    .put("createdAtMs", message.createdAtMs)
                    .put("receivedAtMs", message.receivedAtMs)
                    .put("isPrivate", message.isPrivate)
                    .put("status", message.status.name)
                    .put("direction", message.direction.name)
            if (message.deliveredHops != null) item.put("deliveredHops", message.deliveredHops)
            if (message.senderMessageId != null) item.put("senderMessageId", message.senderMessageId)
            array.put(item)
        }
        if (!cipher.exists()) cipher.create()
        val encrypted = cipher.encrypt(array.toString(), "nearby-messages-v1").encode64()
        check(prefs.edit().putString("items.enc", encrypted).remove("items").commit())
    }

    private fun load() {
        try {
            val encrypted = prefs.getString("items.enc", null)
            val raw = if (encrypted != null) cipher.decrypt(encrypted.decode64(), "nearby-messages-v1")
                else prefs.getString("items", null) ?: return
            val array = JSONArray(raw)
            val loaded = LinkedHashMap<String, ChatMessage>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val message = ChatMessage(
                    messageId = item.getString("messageId"),
                    packetIdHex = item.optString("packetIdHex").takeIf { it.isNotBlank() && it != "null" },
                    senderId = item.getString("senderId"),
                    recipientId = item.optString("recipientId").takeIf { it.isNotBlank() && it != "null" },
                    body = item.getString("body"),
                    createdAtMs = item.getLong("createdAtMs"),
                    receivedAtMs = if (item.isNull("receivedAtMs")) null else item.getLong("receivedAtMs"),
                    isPrivate = item.getBoolean("isPrivate"),
                    status = MessageStatus.valueOf(item.getString("status")),
                    direction = MessageDirection.valueOf(item.getString("direction")),
                    deliveredHops = if (item.has("deliveredHops")) item.getInt("deliveredHops") else null,
                    senderMessageId = item.optString("senderMessageId").takeIf { it.isNotBlank() && it != "null" }
                )
                loaded[message.messageId] = message
            }
            // Queued (Pending) messages intentionally survive restarts; the runtime flushes them.
            messages.putAll(loaded.entries.toList().takeLast(200).associate { it.toPair() })
            if (encrypted == null) save()
            publish()
        } catch (_: Exception) {
            messages.clear()
            storageError = true
        }
    }
}
