package `in`.yashd.bitchat.ui.relay

import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.nostr.NostrNpub

enum class ConversationTransport { Nearby, Internet }
enum class ConversationFilter { All, Nearby, Internet }
data class RelayConversation(val id: String, val title: String, val preview: String,
    val transport: ConversationTransport, val recipient: String?)

/** Transport is part of the route/key: identical identifiers never merge across transports. */
fun relayConversations(internet: RelayUiState, mesh: MeshUiState): List<RelayConversation> {
    val nearby = mesh.currentConversation.groupBy { message ->
        if (!message.isPrivate && message.recipientId == null) null else if (message.direction == MessageDirection.Outbound) message.recipientId else message.senderId
    }.map { (peer, messages) ->
        RelayConversation("nearby:${peer ?: "group"}",
            if (peer == null) "Nearby group" else mesh.peers.firstOrNull { it.identity.peerId == peer }?.identity?.displayName ?: peer.take(12),
            messages.last().body, ConversationTransport.Nearby, peer)
    }
    val messagesByPeer = internet.messages.groupBy { it.peer }
    val records = internet.conversations.associateBy { it.publicKey }
    val internetRows = (records.keys + messagesByPeer.keys).map { peer ->
        val record = records[peer]
        val npub = record?.npub ?: publicNpub(peer)
        RelayConversation("internet:$peer", record?.displayName?.ifBlank { npub } ?: npub,
            messagesByPeer[peer]?.lastOrNull()?.body ?: if (record?.lastMessageAtMs != null) "Open to read saved messages" else "Say hello · Internet chat",
            ConversationTransport.Internet, npub)
    }
    return (nearby + internetRows).asReversed()
}
fun filterConversations(rows: List<RelayConversation>, filter: ConversationFilter, search: String): List<RelayConversation> = rows.filter {
    (filter == ConversationFilter.All || it.transport.name == filter.name) &&
        (it.title.contains(search.trim(), true) || it.preview.contains(search.trim(), true) || it.recipient.orEmpty().contains(search.trim(), true))
}
/** The nullable recipient is reserved for a deliberately selected public Nearby group. */
fun sendNearbySafely(body: String, peerId: String?, peers: List<PeerState>, send: (String, String?) -> Unit): Boolean {
    if (body.isBlank() || peerId != null && peers.none { it.identity.peerId == peerId && it.state != PeerConnectionState.Disconnected }) return false
    send(body, peerId)
    return true
}
internal fun publicNpub(hex: String): String = runCatching {
    NostrNpub.encode(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}.getOrDefault(hex)
