package `in`.yashd.bitchat.data.internet

import `in`.yashd.bitchat.nostr.NostrMessagingCoordinator

/** Explicit test fixture; production always injects the Room repository. */
class TestHistoryRepository : InternetHistoryRepository {
    private val contacts = linkedMapOf<String, SavedContact>()
    private val messages = linkedMapOf<String, SavedMessage>()
    override suspend fun load(owner: String) = HistorySnapshot(contacts.filterKeys { it.startsWith("$owner:") }.values.toList(), messages.filterKeys { it.startsWith("$owner:") }.values.toList())
    override suspend fun contact(owner: String, peer: String, name: String) {
        val key = "$owner:$peer"
        contacts[key] = SavedContact(peer, name.ifBlank { contacts[key]?.name.orEmpty() })
    }
    override suspend fun outgoing(owner: String, id: String, peer: String, body: String, time: Long) {
        contact(owner, peer, "")
        messages["$owner:$id"] = SavedMessage(id, null, peer, body, true, time, HistoryStatus.QUEUED)
    }
    override suspend fun bindRumor(owner: String, id: String, rumorId: String) {
        messages.entries.removeAll { it.key.startsWith("$owner:") && it.value.rumorId == rumorId && it.value.recordId != id }
        messages["$owner:$id"] = messages.getValue("$owner:$id").copy(rumorId = rumorId)
    }
    override suspend fun received(owner: String, rumorId: String, peer: String, body: String, outgoing: Boolean, time: Long) {
        if (messages.any { it.key.startsWith("$owner:") && it.value.rumorId == rumorId }) return
        contact(owner, peer, "")
        messages["$owner:$rumorId"] = SavedMessage(rumorId, rumorId, peer, body, outgoing, time, if (outgoing) HistoryStatus.SENDER_COPY_RECEIVED else HistoryStatus.RECEIVED)
    }
    override suspend fun publication(owner: String, id: String, result: NostrMessagingCoordinator.SendResult) { messages["$owner:$id"] = messages.getValue("$owner:$id").copy(status = publicationStatus(result)) }
    override suspend fun markDelivered(owner: String, rumorId: String, peer: String): Boolean {
        val entry = messages.entries.firstOrNull { it.key.startsWith("$owner:") && it.value.rumorId == rumorId } ?: return false
        val m = entry.value
        if (!m.outgoing || m.peer != peer || m.status !in listOf(HistoryStatus.RELAY_ACCEPTED, HistoryStatus.UNKNOWN, HistoryStatus.SENDER_COPY_RECEIVED)) return false
        messages[entry.key] = m.copy(status = HistoryStatus.DELIVERED)
        return true
    }
    override suspend fun failed(owner: String, id: String, unknown: Boolean) { messages["$owner:$id"] = messages.getValue("$owner:$id").copy(status = if (unknown) HistoryStatus.UNKNOWN else HistoryStatus.FAILED) }
    override suspend fun interruptPending(owner: String) { messages.replaceAll { key, m -> if (key.startsWith("$owner:") && m.status == HistoryStatus.QUEUED) m.copy(status = HistoryStatus.UNKNOWN) else m } }
    override suspend fun deleteMessage(owner: String, id: String) { messages.remove("$owner:$id") }
    override suspend fun deleteConversation(owner: String, peer: String) {
        messages.entries.removeAll { it.key.startsWith("$owner:") && it.value.peer == peer }
        contacts.remove("$owner:$peer")
    }
    override suspend fun close() {}
}
