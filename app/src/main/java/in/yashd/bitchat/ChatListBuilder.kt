package `in`.yashd.bitchat

import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState
import `in`.yashd.bitchat.ui.relay.ConversationTransport
import `in`.yashd.bitchat.ui.relay.InternetMessage
import `in`.yashd.bitchat.ui.relay.RelayUiState
import `in`.yashd.bitchat.ui.relay.RelayViewModel
import `in`.yashd.bitchat.ui.relay.relayConversations

// ---------------------------------------------------------------------------
// Routes: which conversation is open.
//   "nearby:<peerId>"  private Bluetooth chat
//   "nearby:"          the public Nearby group
//   "internet:<npub>"  Internet (Nostr) chat
// ---------------------------------------------------------------------------

internal fun nearbyRoute(peerId: String?): String = "nearby:" + (peerId ?: "")
internal fun internetRoute(npub: String): String = "internet:$npub"
internal fun routeIsNearby(route: String): Boolean = route.startsWith("nearby:")
internal fun routeTarget(route: String): String? = route.substringAfter(':').ifEmpty { null }

private fun PeerState?.isLinked(): Boolean =
    this != null && (state == PeerConnectionState.Connected || state == PeerConnectionState.Authenticated)

/** Everything the screens need to show about one conversation or contact. */
internal data class RouteInfo(
    val route: String,
    val nearby: Boolean,
    val target: String?,
    val title: String,
    val linkText: String,
    val online: Boolean,
    val peer: PeerState?,
    val publicId: String
) {
    val isGroup: Boolean get() = nearby && target == null
}

internal fun describeRoute(route: String, mesh: MeshUiState, internet: RelayUiState): RouteInfo {
    val target = routeTarget(route)
    if (routeIsNearby(route)) {
        val peer = target?.let { id -> mesh.peers.firstOrNull { it.identity.peerId == id } }
        val linked = mesh.serviceRunning && peer.isLinked()
        val inRange = mesh.peers.count { it.isLinked() }
        val title = when {
            target == null -> "Nearby group"
            peer != null && peer.identity.displayName.isNotBlank() -> peer.identity.displayName
            else -> mesh.knownNames[target]?.ifBlank { null } ?: target.take(12)
        }
        val linkText = when {
            target == null -> if (mesh.serviceRunning) "Public group • $inRange in range" else "Public group • Nearby is off"
            !mesh.serviceRunning -> "Offline • Nearby is off"
            peer == null -> "Offline • No route"
            linked -> "BLE only • Nearby"
            peer.state == PeerConnectionState.Connecting -> "BLE only • Connecting…"
            peer.state == PeerConnectionState.Failed -> "BLE only • Connection failed"
            peer.state == PeerConnectionState.Disconnected -> "Offline • No route"
            else -> "BLE only • Found nearby"
        }
        return RouteInfo(
            route = route,
            nearby = true,
            target = target,
            title = title,
            linkText = linkText,
            online = if (target == null) mesh.serviceRunning else linked,
            peer = peer,
            publicId = target.orEmpty()
        )
    }
    val npub = target.orEmpty()
    val key = runCatching { RelayViewModel.recipientKey(npub) }.getOrNull()
    val record = internet.conversations.firstOrNull { it.publicKey == key }
    val linkText = when {
        internet.inboxes.isEmpty() -> "Nostr only • Not set up"
        internet.inboxesConnected -> "Nostr only • Relay connected"
        else -> "Nostr only • Relay offline"
    }
    return RouteInfo(
        route = route,
        nearby = false,
        target = npub,
        title = record?.displayName?.ifBlank { null } ?: shortId(npub, 12, 6),
        linkText = linkText,
        online = internet.inboxesConnected,
        peer = null,
        publicId = npub
    )
}

/** Messages of one Nearby conversation (same rule the old chat screen used). */
internal fun nearbyThread(mesh: MeshUiState, peerId: String?): List<ChatMessage> =
    mesh.currentConversation.filter { m ->
        if (peerId == null) !m.isPrivate && m.recipientId == null
        else m.isPrivate && (m.senderId == peerId || m.recipientId == peerId)
    }

/** Messages of one Internet conversation. */
internal fun internetThread(internet: RelayUiState, npub: String): List<InternetMessage> {
    val key = runCatching { RelayViewModel.recipientKey(npub) }.getOrNull() ?: return emptyList()
    return if (internet.threadPeer == key) internet.thread else internet.messages.filter { it.peer == key }
}

/** One row of the Chats list. */
internal data class ChatListItem(
    val info: RouteInfo,
    val preview: String,
    val lastAtMs: Long?,
    val waiting: Boolean,
    val failed: Boolean,
    val unread: Int
)

/**
 * Builds the Chats list from the existing conversation data (relayConversations) and adds
 * the extra details the design shows: time, connection line and unread count.
 * Unread counts use the persisted last-read store: messages that arrived after the last open
 * (or after this session started, for chats never opened).
 */
internal fun buildChatList(
    internet: RelayUiState,
    mesh: MeshUiState,
    seenAt: Map<String, Long>,
    sessionStart: Long
): List<ChatListItem> {
    val names = mesh.knownNames + mesh.peers.associate { it.identity.peerId to it.identity.displayName }
    return relayConversations(internet, mesh).mapNotNull { row ->
        val route = when (row.transport) {
            ConversationTransport.Nearby -> nearbyRoute(row.recipient)
            ConversationTransport.Internet -> internetRoute(row.recipient ?: return@mapNotNull null)
        }
        val info = describeRoute(route, mesh, internet)
        val since = seenAt[route] ?: sessionStart
        if (info.nearby) {
            val thread = nearbyThread(mesh, info.target)
            val last = thread.lastOrNull()
            val mineLast = last?.direction == MessageDirection.Outbound
            val preview = when {
                last == null -> row.preview
                info.isGroup && !mineLast -> (names[last.senderId]?.ifBlank { null } ?: last.senderId.take(8)) + ": " + last.body
                else -> last.body
            }
            ChatListItem(
                info = info,
                preview = preview,
                lastAtMs = last?.createdAtMs,
                waiting = mineLast && last?.status == MessageStatus.Pending,
                failed = mineLast && last?.status == MessageStatus.Failed,
                unread = thread.count { it.direction == MessageDirection.Inbound && (it.receivedAtMs ?: it.createdAtMs) > since }
            )
        } else {
            val thread = internetThread(internet, info.publicId)
            val last = thread.lastOrNull()
            val mineLast = last?.outgoing == true
            ChatListItem(
                info = info,
                preview = last?.body ?: row.preview,
                lastAtMs = last?.createdAtMs?.takeIf { it > 0 }
                    ?: internet.conversations.firstOrNull { it.publicKey == last?.peer || it.npub == info.publicId }?.lastMessageAtMs,
                waiting = mineLast && last?.status == "Sending…",
                failed = mineLast && last?.status?.startsWith("Failed") == true,
                unread = thread.count { !it.outgoing && it.createdAtMs > since }
            )
        }
    }.sortedByDescending { it.lastAtMs ?: 0L }
}
