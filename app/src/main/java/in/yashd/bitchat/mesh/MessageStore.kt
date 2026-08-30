package `in`.yashd.bitchat.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MessageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("messages", Context.MODE_PRIVATE)
    private val messages = LinkedHashMap<String, ChatMessage>()
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

    fun addInbound(packetIdHex: String, senderId: String, recipientId: String?, body: String, isPrivate: Boolean) {
        if (messages.values.any { it.packetIdHex == packetIdHex }) return
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
                direction = MessageDirection.Inbound
            )
        )
    }

    fun updateStatus(messageId: String, packetIdHex: String?, status: MessageStatus) {
        messages[messageId]?.let {
            put(it.copy(packetIdHex = packetIdHex ?: it.packetIdHex, status = status))
        }
    }

    fun markPacketDelivered(packetIdHex: String) {
        messages.values.firstOrNull { it.packetIdHex == packetIdHex }?.let {
            updateStatus(it.messageId, packetIdHex, MessageStatus.Delivered)
        }
    }

    private fun put(message: ChatMessage) {
        messages[message.messageId] = message
        publish()
        save()
    }

    private fun publish() {
        _conversation.value = messages.values.sortedBy { it.createdAtMs }.takeLast(100)
    }

    private fun save() {
        val array = JSONArray()
        messages.values.toList().takeLast(200).forEach { message ->
            array.put(
                JSONObject()
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
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("items", null) ?: return
        runCatching {
            val array = JSONArray(raw)
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
                    direction = MessageDirection.valueOf(item.getString("direction"))
                )
                messages[message.messageId] = message
            }
            publish()
        }
    }
}
