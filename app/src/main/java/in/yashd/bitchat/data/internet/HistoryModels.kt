package `in`.yashd.bitchat.data.internet

import `in`.yashd.bitchat.nostr.NostrMessagingCoordinator

enum class HistoryStatus { QUEUED, RELAY_ACCEPTED, RELAY_REJECTED, FAILED, UNKNOWN, RECEIVED, SENDER_COPY_RECEIVED, DELETED }
data class SavedContact(val peer: String, val name: String, val lastMessageAtMs: Long? = null)
data class SavedMessage(val recordId: String, val rumorId: String?, val peer: String, val body: String,
    val outgoing: Boolean, val createdAtMs: Long, val status: HistoryStatus)
data class HistorySnapshot(val contacts: List<SavedContact> = emptyList(), val messages: List<SavedMessage> = emptyList())
class LocalHistoryException : RuntimeException("Local Internet history could not be opened or saved. Existing data was preserved.")

interface InternetHistoryRepository {
    suspend fun load(owner: String): HistorySnapshot
    suspend fun changes(owner: String, id: String?, peer: String?): HistorySnapshot {
        val snapshot = load(owner)
        val messages = snapshot.messages.filter { it.recordId == id || it.rumorId == id }
        val target = peer ?: messages.firstOrNull()?.peer
        return HistorySnapshot(snapshot.contacts.filter { it.peer == target }, messages)
    }
    suspend fun page(owner: String, peer: String, beforeTime: Long? = null, beforeId: String = ""): List<SavedMessage> =
        load(owner).messages.filter { it.peer == peer && (beforeTime == null || it.createdAtMs < beforeTime ||
            it.createdAtMs == beforeTime && it.recordId < beforeId) }
            .sortedWith(compareBy<SavedMessage> { it.createdAtMs }.thenBy { it.recordId }).takeLast(100)
    suspend fun contact(owner: String, peer: String, name: String)
    suspend fun outgoing(owner: String, id: String, peer: String, body: String, time: Long)
    suspend fun bindRumor(owner: String, id: String, rumorId: String)
    suspend fun received(owner: String, rumorId: String, peer: String, body: String, outgoing: Boolean, time: Long)
    suspend fun publication(owner: String, id: String, result: NostrMessagingCoordinator.SendResult)
    suspend fun failed(owner: String, id: String, unknown: Boolean)
    suspend fun interruptPending(owner: String)
    /** Local-only delete. Keeps an empty tombstone so relays that redeliver the message cannot bring it back. */
    suspend fun deleteMessage(owner: String, id: String)
    /** Local-only delete of a whole chat: every message is erased and the chat is hidden until new activity. */
    suspend fun deleteConversation(owner: String, peer: String)
    suspend fun close()
}
fun HistoryStatus.label(): String = when (this) {
    HistoryStatus.QUEUED -> "Sending…"
    HistoryStatus.RELAY_ACCEPTED -> "Relay accepted · delivery unconfirmed"
    HistoryStatus.RELAY_REJECTED -> "Failed · recipient relays rejected the message"
    HistoryStatus.FAILED -> "Failed · no recipient relay accepted"
    HistoryStatus.UNKNOWN -> "Outcome unknown · delivery unconfirmed"
    HistoryStatus.RECEIVED -> "Received encrypted message"
    HistoryStatus.SENDER_COPY_RECEIVED -> "Received encrypted sender copy · delivery unconfirmed"
    HistoryStatus.DELETED -> "Deleted"
}
fun publicationStatus(result: NostrMessagingCoordinator.SendResult): HistoryStatus = when {
    result.recipient.any { it.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED } -> HistoryStatus.RELAY_ACCEPTED
    result.recipient.any { it.stage in listOf(NostrMessagingCoordinator.Stage.UNKNOWN, NostrMessagingCoordinator.Stage.SUBMITTED, NostrMessagingCoordinator.Stage.QUEUED) } -> HistoryStatus.UNKNOWN
    result.recipient.isNotEmpty() && result.recipient.all { it.stage == NostrMessagingCoordinator.Stage.RELAY_REJECTED } -> HistoryStatus.RELAY_REJECTED
    else -> HistoryStatus.FAILED
}
