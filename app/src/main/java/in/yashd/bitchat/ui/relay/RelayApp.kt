package `in`.yashd.bitchat.ui.relay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.yashd.bitchat.R
import `in`.yashd.bitchat.mesh.*
import androidx.compose.ui.platform.testTag

private val Blue = Color(0xFF4169E1)
private val Background = Color(0xFF101010)
private val Panel = Color(0xFF1D1F20)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = Color.White,
        primaryContainer = Color(0xFF243960), onPrimaryContainer = Color.White,
        secondary = Color(0xFF9CB8FF), onSecondary = Background, tertiary = Color(0xFF9CB8FF),
        secondaryContainer = Color(0xFF243960), onSecondaryContainer = Color.White,
        background = Background, surface = Background, surfaceContainer = Panel,
        surfaceVariant = Panel, onSurface = Color(0xFFF4F4F4), onSurfaceVariant = Color(0xFFABAFB5),
        outline = Color(0xFF55585F)), typography = `in`.yashd.bitchat.ui.theme.Typography, content = content)
}

@Composable
fun RelayIcon(id: Int, description: String? = null) {
    Icon(painterResource(id), description, Modifier.size(22.dp))
}

@Composable
fun RelayApp(
    model: RelayViewModel,
    mesh: MeshUiState = MeshUiState(),
    findNearby: () -> Unit = {},
    stopNearby: () -> Unit = {},
    requestPermissions: () -> Unit = {},
    bluetoothSettings: () -> Unit = {},
    nearbyChat: @Composable (String?, () -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    nearbySettings: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}
) {
    val state by model.state.collectAsState()
    val savedScreens = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf(0) }
    var onboarding by rememberSaveable { mutableStateOf(0) }
    var transport by rememberSaveable { mutableStateOf<String?>(null) }
    var recipient by rememberSaveable { mutableStateOf<String?>(null) }
    var newChat by rememberSaveable { mutableStateOf(false) }
    var onlineEntry by rememberSaveable { mutableStateOf(false) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val closeChat: () -> Unit = { transport = null; recipient = null }
    val openNearby: (String?) -> Unit = { peer -> recipient = peer; transport = "Nearby"; page = 0 }
    RelayTheme {
        Surface(Modifier.fillMaxSize()) {
            if (onboarding < 3) Intro(onboarding) { onboarding++ }
            else {
                BackHandler(setupOpen || onlineEntry || transport != null || page != 0) {
                    when { setupOpen -> setupOpen = false; onlineEntry -> onlineEntry = false; transport != null -> closeChat(); else -> page = 0 }
                }
                if (newChat) NewChatDialog(
                    dismiss = { newChat = false },
                    internet = { onlineEntry = true; closeChat(); page = 0; newChat = false },
                    nearby = { closeChat(); page = 1; newChat = false })
                Scaffold(
                    modifier = Modifier.imePadding(),
                    bottomBar = {
                        NavigationBar(Modifier.testTag("relay-bottom-navigation"), containerColor = Background) {
                            listOf("Chats", "Discover", "Settings").forEachIndexed { index, label ->
                                NavigationBarItem(selected = page == index,
                                    onClick = { closeChat(); onlineEntry = false; setupOpen = false; page = index },
                                    icon = { RelayIcon(listOf(R.drawable.relay_chat, R.drawable.relay_nearby, R.drawable.relay_settings)[index]) },
                                    label = { Text(label) })
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        state.error?.takeIf { !setupOpen }?.let { error ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = model::clearError) { Text("Dismiss") }
                                }
                            }
                        }
                        if (state.storageLoading) Text("Loading saved Internet chats…", Modifier.padding(16.dp))
                        state.storageError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                        when {
                            setupOpen -> InternetSetupScreen(state, model::updateSetupServers,
                                model::setupBack, model::setupNext, model::announce, model::retrySetup,
                                finish = { if (model.finishSetup()) setupOpen = false }, exit = { setupOpen = false })
                            onlineEntry -> savedScreens.SaveableStateProvider("online-entry") {
                                OnlineChatScreen(model::addInternetConversation,
                                    open = { recipient = it; transport = "Internet"; onlineEntry = false; page = 0 },
                                    back = { onlineEntry = false }, setup = { setupOpen = true }, canSave = !state.storageLoading && state.storageError == null)
                            }
                            transport == "Internet" && recipient != null -> savedScreens.SaveableStateProvider("internet:$recipient") {
                                PrivateChat(state, recipient!!, closeChat, model::send)
                            }
                            transport == "Nearby" -> savedScreens.SaveableStateProvider("nearby:${recipient ?: "group"}") {
                                nearbyChat(recipient, closeChat, { openNearby(null) })
                            }
                            page == 0 -> savedScreens.SaveableStateProvider("chats") {
                                Conversations(relayConversations(state, mesh), { row ->
                                    recipient = row.recipient; transport = row.transport.name
                                }, { newChat = true }, { page = 1 }, state.storageLoading, state.storageError != null)
                            }
                            page == 1 -> savedScreens.SaveableStateProvider("discover") {
                                DiscoverScreen(mesh, findNearby, stopNearby, requestPermissions, bluetoothSettings, openNearby)
                            }
                            else -> savedScreens.SaveableStateProvider("settings") {
                                InternetSettings(state, { setupOpen = true }, mesh, nearbySettings)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(step: Int, next: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.SpaceBetween) {
        Text("Relay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(shape = RoundedCornerShape(40.dp), color = Blue, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) { RelayIcon(if (step == 2) R.drawable.relay_internet else R.drawable.relay_nearby) }
            }
            Text(listOf("What is Relay?", "How Relay Works", "More Open Network")[step],
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(listOf("All your chats in one place. Talk with people nearby over Bluetooth or with friends online using Internet messaging.",
                "Open Discover and tap Find Nearby. Allow Bluetooth, then choose someone to start a Nearby Chat.",
                "Use New Chat to message a friend online. Internet Setup in Settings helps you connect to relays and publish your inbox. You choose how each chat connects.")[step],
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Text("${step + 1} / 3", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = next, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Continue") }
    }
}

@Composable
fun Conversations(rows: List<RelayConversation>, open: (RelayConversation) -> Unit,
    newChat: () -> Unit, discover: () -> Unit, internetLoading: Boolean = false, internetUnavailable: Boolean = false) {
    // Search can contain private names or message text; never put it in saved-state bundles.
    var search by remember { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ConversationFilter.All.name) }
    val filter = ConversationFilter.valueOf(filterName)
    val visible = filterConversations(rows, filter, search)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Relay", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Chats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Button(onClick = newChat) { Text("New Chat") }
            }
        }
        item { Text("Internet chats are saved on this device. Message bodies are encrypted locally and excluded from backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search chats") },
            leadingIcon = { RelayIcon(R.drawable.relay_search) }, shape = RoundedCornerShape(28.dp), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationFilter.entries.forEach { option ->
                    FilterChip(filter == option, { filterName = option.name }, label = { Text(option.name) }, modifier = Modifier.testTag("filter-${option.name}"))
                }
            }
        }
        if (visible.isEmpty()) item {
            Surface(shape = RoundedCornerShape(24.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (internetLoading) "Loading saved Internet chats…" else if (internetUnavailable) "Saved Internet chats are unavailable" else if (search.isNotBlank()) "No chats match your search" else "Your next conversation starts here", style = MaterialTheme.typography.titleMedium)
                    Text(if (internetLoading) "Your saved chats are being opened securely. Nearby chats remain available."
                        else if (internetUnavailable) "We haven’t cleared your history. See the local-storage message above. You can still find people in Discover."
                        else if (search.isNotBlank()) "Try a friend’s name, public key, or a word from their latest message."
                        else "Tap New Chat to message an Internet friend, or find people around you in Discover.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = discover) { Text("Find people nearby") }
                }
            }
        }
        items(visible, key = { it.id }) { row ->
            Surface(shape = RoundedCornerShape(20.dp), color = Panel,
                modifier = Modifier.fillMaxWidth().testTag("conversation-${row.id}").clickable { open(row) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            RelayIcon(if (row.transport == ConversationTransport.Nearby) R.drawable.relay_nearby else R.drawable.relay_internet)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(row.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.transport.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun NewChatDialog(dismiss: () -> Unit, internet: () -> Unit, nearby: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("New Chat") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("How would you like to connect?")
            Button(onClick = nearby, modifier = Modifier.fillMaxWidth()) {
                RelayIcon(R.drawable.relay_nearby); Spacer(Modifier.width(8.dp)); Text("Chat Nearby")
            }
            Text("Find people around you using Bluetooth.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = internet, modifier = Modifier.fillMaxWidth()) {
                RelayIcon(R.drawable.relay_internet); Spacer(Modifier.width(8.dp)); Text("Chat Online")
            }
            Text("Add a friend once with their public Relay ID.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun DiscoverScreen(mesh: MeshUiState, findNearby: () -> Unit, stop: () -> Unit,
    permissions: () -> Unit, bluetoothSettings: () -> Unit, open: (String?) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { RelayIcon(R.drawable.relay_nearby) }
                    }
                    Text(if (mesh.serviceRunning) "Looking for people nearby" else "Meet people around you", style = MaterialTheme.typography.titleMedium)
                    Text(if (!mesh.bluetoothReadiness.hasBle) "This device doesn’t support Nearby Bluetooth. You can still use Internet chats." else "Nearby Chat uses Bluetooth. Friends also need Relay open with Nearby turned on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = when {
                        !mesh.bluetoothReadiness.hasPermissions -> permissions
                        !mesh.bluetoothReadiness.bluetoothEnabled -> bluetoothSettings
                        else -> findNearby
                    }, enabled = !mesh.serviceRunning && mesh.bluetoothReadiness.hasBle) { Text("Find Nearby") }
                    if (mesh.serviceRunning) TextButton(onClick = stop) { Text("Stop finding nearby") }
                    Text("${mesh.peers.size} people found", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (mesh.peers.isEmpty()) item { Text("No one here yet. Move closer to a friend and check that Bluetooth is on for both of you.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(mesh.peers, key = { it.identity.peerId }) { peer ->
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(peer.identity.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(if (!mesh.serviceRunning) "Nearby is off · tap Find Nearby to reconnect" else when (peer.state) {
                        PeerConnectionState.Authenticated -> "Nearby · ready for a private chat"
                        PeerConnectionState.Connected -> "Nearby · connected"
                        PeerConnectionState.Connecting -> "Nearby · connecting…"
                        PeerConnectionState.Failed -> "Nearby · connection needs attention"
                        PeerConnectionState.Disconnected -> "Nearby · disconnected"
                        else -> "Nearby · found"
                    }, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { open(peer.identity.peerId) }, enabled = mesh.serviceRunning) { Text(if (peer.state == PeerConnectionState.Authenticated) "Nearby Chat" else "Connect") }
                    if (advanced) Text("${peer.identity.peerId} · ${peer.rssi ?: "?"} dBm\n${peer.failureReason.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedButton(onClick = { open(null) }, modifier = Modifier.fillMaxWidth()) { Text("Open Nearby group") }
            Text("A public group for everyone in Bluetooth range.", style = MaterialTheme.typography.bodySmall)
        }
        item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide Advanced" else "Advanced") } }
        if (advanced) {
            item {
                Text("Bluetooth: ${if (mesh.bluetoothReadiness.bluetoothEnabled) "on" else "off"} · Mesh: ${if (mesh.serviceRunning) "running" else "stopped"}")
                TextButton(onClick = permissions) { Text("Bluetooth permissions") }
                TextButton(onClick = bluetoothSettings) { Text("Open Bluetooth settings") }
                Text("More identity and radio controls are in Settings.", style = MaterialTheme.typography.bodySmall)
            }
            items(mesh.errors) { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun PrivateChat(state: RelayUiState, recipient: String, back: () -> Unit, send: (String, String) -> Unit) {
    // Message drafts stay in memory; saved-instance-state is not encrypted storage.
    var draft by remember(recipient) { mutableStateOf("") }
    val key = remember(recipient) { runCatching { RelayViewModel.recipientKey(recipient) }.getOrNull() }
    val messages = state.messages.filter { it.peer == key }
    val title = state.conversations.firstOrNull { it.publicKey == key }?.displayName?.ifBlank { recipient } ?: recipient
    var showId by rememberSaveable(recipient) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { RelayIcon(R.drawable.relay_back, "Back to Chats") }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("Private · NIP-17 · Internet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { showId = !showId }) { Text(if (showId) "Hide public ID" else "Friend’s public ID") }
        if (showId) Text(recipient, style = MaterialTheme.typography.bodySmall)
        Text("Relay acceptance does not confirm delivery or reading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.inboxes.isEmpty()) Text("Configure discovery and inbox relays in Settings before sending.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                    Surface(shape = RoundedCornerShape(18.dp), color = if (message.outgoing) Blue else Panel,
                        modifier = Modifier.widthIn(max = 300.dp)) { Text(message.body, Modifier.padding(14.dp)) }
                    Text(message.status, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message…") },
                shape = RoundedCornerShape(24.dp), maxLines = 5)
            IconButton(onClick = { send(recipient, draft); if (draft.toByteArray(Charsets.UTF_8).size <= 16_000) draft = "" }, enabled = !state.storageLoading && state.storageError == null && !state.busy && draft.isNotBlank() && key != null && state.npub.isNotEmpty() && state.inboxes.isNotEmpty()) {
                RelayIcon(R.drawable.relay_send, "Send encrypted message")
            }
        }
    }
}

@Composable
fun InternetSettings(state: RelayUiState, openSetup: () -> Unit,
    mesh: MeshUiState = MeshUiState(), nearbySettings: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("relay-settings-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { PublicRelayId(state.npub) }
        item {
            Text("Internet messaging", style = MaterialTheme.typography.titleMedium)
            Text("Three steps: choose servers, make yourself reachable, then share your public ID. Both friends need working servers and an inbox announcement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = openSetup, modifier = Modifier.fillMaxWidth()) { Text(if (state.setup.finished) "Review Internet Setup" else "Internet Setup") }
        }
        item { SetupStatus(state) }
        item { Text("Nearby: ${if (mesh.serviceRunning) "on" else "off"}", style = MaterialTheme.typography.titleMedium) }
        nearbySettings()
        if (mesh.errors.isNotEmpty()) item { mesh.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}
