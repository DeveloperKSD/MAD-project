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

/** Encrypted persistence around ChatLog; queue and dedup decisions live in ChatLog. */
class MessageStore(context: Context, private val cipher: HistoryCipher = HistoryCipher("relay.nearby.messages.aes.v1")) {
    private val prefs = context.applicationContext.getSharedPreferences("messages", Context.MODE_PRIVATE)
    private val log = ChatLog()
    var storageError: Boolean = false
        private set
    private val _conversation = MutableStateFlow<List<ChatMessage>>(emptyList())
    val conversation: StateFlow<List<ChatMessage>> = _conversation

    init {
        load()
    }

    fun createOutbound(localPeerId: String, recipientId: String?, body: String, isPrivate: Boolean): ChatMessage {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        val message = log.createOutbound(localPeerId, recipientId, body, isPrivate,
            UUID.randomUUID().toString(), System.currentTimeMillis())
        saveOrThrow()
        publish()
        return message
    }

    fun addInbound(packetIdHex: String, senderId: String, recipientId: String?, body: String, isPrivate: Boolean,
                   senderMessageId: String? = null): Boolean {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        val added = log.addInbound(UUID.randomUUID().toString(), packetIdHex, senderId, recipientId, body,
            isPrivate, senderMessageId, System.currentTimeMillis())
        if (added) {
            saveOrThrow()
            publish()
        }
        return added
    }

    /** Private-chat peers present in saved history (either direction). */
    fun knownPeerIds(): Set<String> = log.knownPeerIds()

    /** Outbound messages waiting for a route, oldest first. */
    fun pendingOutbound(): List<ChatMessage> = log.pendingOutbound()

    fun updateStatus(messageId: String, packetIdHex: String?, status: MessageStatus) {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        if (log.updateStatus(messageId, packetIdHex, status) != null) {
            saveOrThrow()
            publish()
        }
    }

    fun markPacketDelivered(packetIdHex: String, senderId: String, hops: Int? = null) {
        check(!storageError) { "Nearby history is unavailable; existing data preserved" }
        if (log.markPacketDelivered(packetIdHex, senderId, hops) != null) {
            saveOrThrow()
            publish()
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
        if (!log.removeMatches(matches)) return
        saveOrThrow()
        publish()
    }

    private fun saveOrThrow() {
        try { save() } catch (e: Exception) { storageError = true; throw e }
    }

    private fun publish() {
        _conversation.value = log.all().sortedBy { it.createdAtMs }.takeLast(100)
    }

    private fun save() {
        val array = JSONArray()
        log.all().takeLast(200).forEach { message ->
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
            val loaded = ArrayList<ChatMessage>(array.length())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                loaded.add(ChatMessage(
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
                ))
            }
            // Queued (Pending) messages intentionally survive restarts; the runtime flushes them.
            log.replaceAll(loaded)
            if (encrypted == null) save()
            publish()
        } catch (_: Exception) {
            log.clear()
            storageError = true
        }
    }
}
