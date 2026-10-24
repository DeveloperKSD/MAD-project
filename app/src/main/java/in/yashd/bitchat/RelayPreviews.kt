package `in`.yashd.bitchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.BluetoothReadiness
import `in`.yashd.bitchat.mesh.ChatMessage
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.mesh.MessageDirection
import `in`.yashd.bitchat.mesh.MessageStatus
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.mesh.PeerState
import `in`.yashd.bitchat.nostr.NostrNpub
import `in`.yashd.bitchat.ui.relay.InternetConversation
import `in`.yashd.bitchat.ui.relay.InternetMessage
import `in`.yashd.bitchat.ui.relay.RelayUiState

/*
 * Android Studio previews of the five Figma pages, filled with sample data.
 * Open this file and switch to "Split" or "Design" view to see them.
 * Previews only run inside Android Studio; nothing here is used by the app itself.
 */

private object Sample {
    private const val MINUTE = 60_000L
    val now = System.currentTimeMillis()

    private fun key(seed: Int) = ByteArray(32) { seed.toByte() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    const val me = "a1b2c3d4e5f60718"
    const val mira = "0f1e2d3c4b5a6978"
    const val rohan = "1122334455667788"
    const val ananya = "99aabbccddeeff00"
    const val nisha = "5566778899001122"
    const val unknown = "7d7d7d7d7d7d7d7d"

    private val aaravKey = key(11)
    private val devKey = key(12)
    private val kabirKey = key(13)
    val aaravNpub: String = NostrNpub.encode(aaravKey)

    private fun peer(id: String, name: String, state: PeerConnectionState, rssi: Int?) = PeerState(
        identity = PeerIdentity(id, name, ByteArray(32) { (it * 7).toByte() }, ByteArray(32), now - 60 * MINUTE, now, true),
        state = state,
        directDeviceAddress = null,
        rssi = rssi,
        lastConnectedMs = now,
        lastDisconnectedMs = null,
        failureReason = null
    )

    private fun ble(id: String, from: String, to: String?, body: String, minutesAgo: Long,
                    status: MessageStatus = MessageStatus.Delivered) = ChatMessage(
        messageId = id,
        packetIdHex = null,
        senderId = from,
        recipientId = to,
        body = body,
        createdAtMs = now - minutesAgo * MINUTE,
        receivedAtMs = now - minutesAgo * MINUTE,
        isPrivate = to != null,
        status = status,
        direction = if (from == me) MessageDirection.Outbound else MessageDirection.Inbound
    )

    val mesh = MeshUiState(
        localPeerId = me,
        displayName = "Yash Deshpande",
        bluetoothReadiness = BluetoothReadiness(true, true, true, true, true, true),
        serviceRunning = true,
        peers = listOf(
            peer(mira, "Mira Shah", PeerConnectionState.Authenticated, -62),
            peer(rohan, "Rohan Iyer", PeerConnectionState.Connecting, -77),
            peer(ananya, "Ananya Kulkarni", PeerConnectionState.Authenticated, -83),
            peer(nisha, "Nisha Verma", PeerConnectionState.Disconnected, null),
            peer(unknown, "Unknown Device", PeerConnectionState.Discovered, -88)
        ),
        currentConversation = listOf(
            ble("n1", nisha, me, "see you tomorrow", 26 * 60),
            ble("n2", rohan, me, "sent you the notes", 25 * 60),
            ble("m1", me, mira, "where are you guys?", 34),
            ble("m2", mira, me, "i'll meet you there", 32)
        )
    )

    val internet = RelayUiState(
        npub = NostrNpub.encode(key(42)),
        publicKey = hex(key(42)),
        inboxes = listOf("wss://relay.example"),
        connections = mapOf("wss://relay.example" to "connected"),
        active = true,
        storageLoading = false,
        conversations = listOf(
            InternetConversation(hex(aaravKey), aaravNpub, "Aarav Mehta"),
            InternetConversation(hex(devKey), NostrNpub.encode(devKey), "Dev Patel"),
            InternetConversation(hex(kabirKey), NostrNpub.encode(kabirKey), "Kabir Rao")
        ),
        messages = listOf(
            InternetMessage("k1", hex(kabirKey), "message saved on this device", true, "Sending…", now - 4 * 60 * MINUTE),
            InternetMessage("d1", hex(devKey), "are you coming?", false, "Received encrypted message", now - 2 * 60 * MINUTE),
            InternetMessage("a1", hex(aaravKey), "hey where are you?", false, "Received encrypted message", now - 9 * MINUTE),
            InternetMessage("a2", hex(aaravKey), "just reached, 5 mins", true, "Relay accepted · delivery unconfirmed", now - 8 * MINUTE),
            InternetMessage("a3", hex(aaravKey), "cool, see you at the gate", false, "Received encrypted message", now - 8 * MINUTE),
            InternetMessage("a4", hex(aaravKey), "perfect", false, "Received encrypted message", now - 3 * MINUTE),
            InternetMessage("a5", hex(aaravKey), "here", true, "Relay accepted · delivery unconfirmed", now - 2 * MINUTE)
        )
    )
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    RelayDesignTheme {
        Box(Modifier.fillMaxSize().background(RelayColors.Background)) { content() }
    }
}

@Preview(name = "1 · Chats", widthDp = 393, heightDp = 852)
@Composable
private fun ChatsPreview() = PreviewFrame {
    RelayChatsScreen(
        items = buildChatList(Sample.internet, Sample.mesh, mapOf(internetRoute(Sample.aaravNpub) to Sample.now - 510_000L), Sample.now - 40 * 60_000L),
        myName = Sample.mesh.displayName,
        now = Sample.now,
        favorites = emptyList(),
        loading = false,
        storageProblem = false,
        onAvatar = {}, openChat = {}, newChat = {}, discover = {}
    )
}

@Preview(name = "2 · Conversation", widthDp = 393, heightDp = 852)
@Composable
private fun ConversationPreview() = PreviewFrame {
    RelayChatScreen(
        info = describeRoute(internetRoute(Sample.aaravNpub), Sample.mesh, Sample.internet),
        mesh = Sample.mesh,
        internet = Sample.internet,
        now = Sample.now,
        back = {}, openProfile = {}, sendInternet = { _, _ -> }, openSetup = {}, openDiscover = {}, markSeen = {}, toast = {}
    )
}

@Preview(name = "3 · Contact profile", widthDp = 393, heightDp = 852)
@Composable
private fun ContactPreview() = PreviewFrame {
    RelayContactScreen(
        info = describeRoute(nearbyRoute(Sample.mira), Sample.mesh, Sample.internet),
        mesh = Sample.mesh,
        internet = Sample.internet,
        favorite = false,
        muted = false,
        back = {}, openChat = {}, toggleFavorite = {}, toggleMute = {}, toast = {}
    )
}

@Preview(name = "4 · Discover", widthDp = 393, heightDp = 852)
@Composable
private fun DiscoverPreview() = PreviewFrame {
    RelayDiscoverPage(
        mesh = Sample.mesh,
        contacts = emptyList(),
        myName = Sample.mesh.displayName,
        onAvatar = {}, findNearby = {}, stopNearby = {}, requestPermissions = {}, bluetoothSettings = {},
        openNearby = {}, openContact = {}, toast = {}
    )
}

@Preview(name = "5 · Settings", widthDp = 393, heightDp = 852)
@Composable
private fun SettingsPreview() = PreviewFrame {
    RelaySettingsPage(
        mesh = Sample.mesh,
        internet = Sample.internet,
        myName = Sample.mesh.displayName,
        onAvatar = {}, openNearbyDetails = {}, openSetup = {}, toast = {}
    )
}
