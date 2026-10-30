package `in`.yashd.bitchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.ui.relay.InternetSetupScreen
import `in`.yashd.bitchat.ui.relay.inboxStatusSummary
import `in`.yashd.bitchat.ui.relay.OnlineChatScreen
import `in`.yashd.bitchat.ui.relay.FavoriteChatsStore
import `in`.yashd.bitchat.ui.relay.PinnedChatsStore
import `in`.yashd.bitchat.ui.relay.PinnedMessagesStore
import `in`.yashd.bitchat.ui.relay.RelayViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root of the redesigned Relay UI (Figma): Chats, Discover and Settings tabs, plus the
 * conversation and contact pages on top. It only reads state from the existing
 * RelayViewModel / MeshRuntime and calls their existing functions.
 */
@Composable
fun RelayHome(
    model: RelayViewModel,
    mesh: MeshUiState,
    findNearby: () -> Unit,
    stopNearby: () -> Unit,
    requestPermissions: () -> Unit,
    bluetoothSettings: () -> Unit,
    notificationRoute: String? = null,
    notificationConsumed: () -> Unit = {},
    nearbySettings: LazyListScope.() -> Unit = {}
) {
    val internet by model.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chat by rememberSaveable { mutableStateOf<String?>(null) }
    var contact by rememberSaveable { mutableStateOf<String?>(null) }
    var onlineEntry by rememberSaveable { mutableStateOf(false) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var nearbyDetails by rememberSaveable { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var requestsOpen by rememberSaveable { mutableStateOf(false) }
    val pendingRequests by MeshRuntime.pendingRequests.collectAsState()
    // Mutes persist independently; unread markers persist encrypted across restarts.
    val muteContext = LocalContext.current
    val muteStore = remember { `in`.yashd.bitchat.ui.relay.MutedChatsStore(muteContext) }
    val savedMutes by muteStore.mutes.collectAsState()
    val seenStore = remember { `in`.yashd.bitchat.ui.relay.LastReadStore(muteContext) }
    val savedSeen by seenStore.seen.collectAsState()
    // Custom chat lists (Work, Family, ...) persist encrypted.
    val listsStore = remember { `in`.yashd.bitchat.ui.relay.ChatListsStore(muteContext) }
    val savedLists by listsStore.lists.collectAsState()
    val savedListMembership by listsStore.membership.collectAsState()
    // System connectivity (ConnectivityManager): validated internet, not just Wi-Fi association.
    val connectivity = remember { `in`.yashd.bitchat.connectivity.ConnectivityObserver(muteContext) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        connectivity.start()
        onDispose { connectivity.stop() }
    }
    val online by connectivity.online.collectAsState()
    // Favourites and pins are stored independently and encrypted across restarts.
    val context = LocalContext.current
    val favoriteStore = remember { FavoriteChatsStore(context) }
    val savedFavorites by favoriteStore.favorites.collectAsState()
    val pinStore = remember { PinnedChatsStore(context) }
    val pinned by pinStore.pinned.collectAsState()
    // Pinned messages inside chats are saved the same way (ids only).
    val messagePinStore = remember { PinnedMessagesStore(context) }
    val pinnedMessages by messagePinStore.pinned.collectAsState()
    // Pull-down refresh on the Chats list.
    var refreshing by remember { mutableStateOf(false) }
    var refreshedAt by remember { mutableLongStateOf(0L) }
    val sessionStart = remember { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    val chatRoute = chat
    val contactRoute = contact
    LaunchedEffect(chatRoute, internet.storageLoading) {
        if (chatRoute != null && !routeIsNearby(chatRoute) && !internet.storageLoading) {
            model.loadConversation(routeTarget(chatRoute).orEmpty())
        }
    }
    // Chats from people not yet accepted wait in Requests instead of the main list.
    val items = buildChatList(internet, mesh, savedSeen, sessionStart)
        .filter { !(it.info.nearby && it.info.target != null && it.info.target in pendingRequests) }
    fun favoriteKey(route: String): String? = runCatching {
        if (routeIsNearby(route)) FavoriteChatsStore.key("nearby", mesh.localPeerId, routeTarget(route))
        else FavoriteChatsStore.key("internet", internet.publicKey,
            RelayViewModel.recipientKey(routeTarget(route).orEmpty()))
    }.getOrNull()
    val favorites = items.map { it.info.route }.filter { favoriteKey(it) in savedFavorites }
    val toggleFavorite: (String) -> Unit = { route ->
        val key = favoriteKey(route)
        if (key == null || !favoriteStore.toggle(key)) {
            toast("Favorites could not be saved. Existing data was preserved.")
        } else {
            toast(if (key in favoriteStore.favorites.value) "Added to favorites" else "Removed from favorites")
        }
    }
    LaunchedEffect(favoriteStore.storageError) {
        if (favoriteStore.storageError) toast("Saved favorites are unavailable. Existing data was preserved. Restart to retry.")
    }
    val myName = mesh.displayName

    val goTab: (Int) -> Unit = { index ->
        chat = null
        contact = null
        onlineEntry = false
        setupOpen = false
        nearbyDetails = false
        requestsOpen = false
        tab = index
    }
    val openChat: (String) -> Unit = { route ->
        contact = null
        chat = route
    }
    val togglePin: (String) -> Unit = { route ->
        toast(if (pinStore.toggle(route)) "Chat pinned" else "Chat unpinned")
    }
    val deleteChat: (String) -> Unit = { route ->
        val target = routeTarget(route)
        val key = favoriteKey(route)
        val deleted: () -> Unit = {
            // Persist cleanup even if the UI scope was cancelled by recreation during deletion.
            val removed = key == null || favoriteStore.remove(key)
            scope.launch {
                listsStore.removeRoute(route)
                pinStore.remove(route)
                messagePinStore.removeChat(route)
                // Keep mute preferences when deleting history.
                seenStore.remove(route)
                if (chat == route) chat = null
                if (contact == route) contact = null
                toast(if (removed) "Chat deleted" else "Chat deleted, but saved favorites could not be updated. Existing favorites were preserved.")
            }
            Unit
        }
        if (routeIsNearby(route)) MeshRuntime.deleteConversation(target, deleted)
        else if (target != null) model.deleteConversation(target, deleted)
    }
    val atRoot = chatRoute == null && contactRoute == null && !onlineEntry && !setupOpen && !nearbyDetails && !requestsOpen
    val showingChat = chatRoute != null && contactRoute == null && !onlineEntry && !setupOpen && !nearbyDetails && !requestsOpen

    LaunchedEffect(notificationRoute) {
        notificationRoute?.let { route ->
            goTab(0)
            newChat = false
            if (route == "requests") requestsOpen = true else chat = route
            notificationConsumed()
        }
    }
    androidx.compose.runtime.DisposableEffect(showingChat, chatRoute, newChat) {
        `in`.yashd.bitchat.notifications.MessageNotifications.visibleRoute = if (showingChat && !newChat) chatRoute else null
        onDispose { `in`.yashd.bitchat.notifications.MessageNotifications.visibleRoute = null }
    }

    BackHandler(enabled = !atRoot || tab != 0) {
        when {
            requestsOpen -> requestsOpen = false
            setupOpen -> setupOpen = false
            onlineEntry -> onlineEntry = false
            nearbyDetails -> nearbyDetails = false
            contact != null -> contact = null
            chat != null -> chat = null
            else -> tab = 0
        }
    }

    RelayDesignTheme {
        if (newChat) {
            NewChatSheet(
                dismiss = { newChat = false },
                nearby = { newChat = false; goTab(1) },
                online = { newChat = false; onlineEntry = true }
            )
        }
        Scaffold(
            containerColor = RelayColors.Background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (atRoot) RelayBottomBar(tab, goTab) }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
                    .let {
                        // Pages without the bottom bar keep clear of the navigation bar and keyboard.
                        // (The chat composer handles this itself.)
                        if (!atRoot && !showingChat) it.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else it
                    }
            ) {
                internet.error?.takeIf { !setupOpen }?.let { ErrorBanner(it, model::clearError) }
                if (tab == 0 && atRoot && !online) OfflineNotice(mesh.serviceRunning)
                when {
                    requestsOpen -> RequestsPage(
                        mesh = mesh,
                        pending = pendingRequests,
                        back = { requestsOpen = false },
                        accept = { MeshRuntime.acceptRequest(it) },
                        decline = { MeshRuntime.declineRequest(it) }
                    )
                    setupOpen -> InternetSetupScreen(
                        internet, model::updateSetupServers, model::setupBack, model::setupNext,
                        model::announce, model::retrySetup,
                        finish = { if (model.finishSetup()) setupOpen = false },
                        exit = { setupOpen = false }
                    )
                    onlineEntry -> OnlineChatScreen(
                        model::addInternetConversation,
                        npub = internet.npub,
                        inboxSummary = inboxStatusSummary(internet.readyToShare, internet.setup.finished, internet.inboxes),
                        open = { npub ->
                            onlineEntry = false
                            tab = 0
                            openChat(internetRoute(npub))
                        },
                        back = { onlineEntry = false },
                        setup = { setupOpen = true },
                        canSave = !internet.storageLoading && internet.storageError == null
                    )
                    nearbyDetails -> NearbyDetailsPage(back = { nearbyDetails = false }, content = nearbySettings)
                    contactRoute != null -> RelayContactScreen(
                        info = describeRoute(contactRoute, mesh, internet),
                        mesh = mesh,
                        internet = internet,
                        favorite = favoriteKey(contactRoute) in savedFavorites,
                        muted = favoriteKey(contactRoute) in savedMutes,
                        back = { contact = null },
                        openChat = { openChat(contactRoute) },
                        toggleFavorite = { toggleFavorite(contactRoute) },
                        toggleMute = {
                            val key = favoriteKey(contactRoute)
                            if (key == null || !muteStore.toggle(key)) toast("Mute could not be saved. Existing settings were preserved.")
                        },
                        toast = toast
                    )
                    chatRoute != null -> RelayChatScreen(
                        info = describeRoute(chatRoute, mesh, internet),
                        mesh = mesh,
                        internet = internet,
                        now = now,
                        back = { chat = null },
                        openProfile = { contact = chatRoute },
                        sendInternet = model::send,
                        openSetup = { setupOpen = true },
                        openDiscover = { goTab(1) },
                        markSeen = { seenStore.mark(chatRoute) },
                        toast = toast,
                        olderMessages = { model.loadConversation(chatRoute.substringAfter(':'), older = true) },
                        latestMessages = { model.loadConversation(chatRoute.substringAfter(':')) },
                        deleteInternetMessage = model::deleteMessage,
                        pinned = chatRoute in pinned,
                        togglePin = { togglePin(chatRoute) },
                        deleteChat = { deleteChat(chatRoute) },
                        pinnedMessages = pinnedMessages[chatRoute].orEmpty(),
                        togglePinMessage = { id -> messagePinStore.toggle(chatRoute, id) }
                    )
                    tab == 0 -> RelayChatsScreen(
                        items = items,
                        myName = myName,
                        now = maxOf(now, refreshedAt),
                        favorites = favorites,
                        toggleFavorite = toggleFavorite,
                        loading = internet.storageLoading,
                        storageProblem = internet.storageError != null,
                        onAvatar = { goTab(2) },
                        openChat = openChat,
                        newChat = { newChat = true },
                        discover = { goTab(1) },
                        newInternetChat = { onlineEntry = true },
                        pinned = pinned,
                        togglePin = togglePin,
                        deleteChat = deleteChat,
                        requestsCount = pendingRequests.size,
                        openRequests = { requestsOpen = true },
                        lists = savedLists,
                        listMembership = savedListMembership,
                        createList = { if (!listsStore.create(it)) toast("List could not be saved. Existing data was preserved.") },
                        toggleListMembership = { route, id -> if (!listsStore.toggle(route, id)) toast("Lists could not be saved. Existing data was preserved.") },
                        deleteList = { if (!listsStore.deleteList(it)) toast("List could not be deleted. Existing data was preserved.") },
                        refreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                refreshedAt = System.currentTimeMillis()
                                // Real refresh: re-announce + flush queued Nearby messages,
                                // reconnect or re-subscribe the Internet inbox.
                                MeshRuntime.refreshNow()
                                model.refresh()
                                delay(1_000)
                                refreshing = false
                            }
                        }
                    )
                    tab == 1 -> RelayDiscoverPage(
                        mesh = mesh,
                        contacts = items,
                        myName = myName,
                        onAvatar = { goTab(2) },
                        findNearby = findNearby,
                        stopNearby = stopNearby,
                        requestPermissions = requestPermissions,
                        bluetoothSettings = bluetoothSettings,
                        openNearby = { peer -> openChat(nearbyRoute(peer)) },
                        openContact = { route -> contact = route },
                        toast = toast
                    )
                    else -> RelaySettingsPage(
                        mesh = mesh,
                        internet = internet,
                        myName = myName,
                        onAvatar = { nearbyDetails = true },
                        openNearbyDetails = { nearbyDetails = true },
                        openSetup = { setupOpen = true },
                        toast = toast
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Chats", "Discover", "Settings")
    val icons = listOf(R.drawable.relay_chat, R.drawable.relay_broadcast, R.drawable.relay_settings)
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = RelayColors.Divider)
        NavigationBar(
            modifier = Modifier.testTag("relay-bottom-navigation"),
            containerColor = RelayColors.Background,
            tonalElevation = 0.dp
        ) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    icon = { RIcon(icons[index], null, size = 24.dp) },
                    label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RelayColors.Bluetooth,
                        selectedTextColor = RelayColors.Bluetooth,
                        indicatorColor = RelayColors.BlueSelected,
                        unselectedIconColor = RelayColors.TextSecondary,
                        unselectedTextColor = RelayColors.TextSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun OfflineNotice(nearbyOn: Boolean) {
    // Offline only means Internet chats are unavailable; Nearby is only claimed when its
    // Bluetooth service is actually running.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RelayColors.Card)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (nearbyOn) "No internet connection. Internet chats are unavailable; Nearby over Bluetooth is on."
            else "No internet connection. Internet chats are unavailable.",
            Modifier.weight(1f).padding(vertical = 10.dp),
            color = RelayColors.TextPrimary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ErrorBanner(text: String, dismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RelayColors.Red.copy(alpha = 0.15f))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), color = RelayColors.TextPrimary, fontSize = 13.sp)
        TextButton(onClick = dismiss) { Text("Dismiss", color = RelayColors.Red) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(dismiss: () -> Unit, nearby: () -> Unit, online: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = RelayColors.Card) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("New Chat", color = RelayColors.TextPrimary, fontSize = RelayType.SubTitle, fontWeight = FontWeight.SemiBold)
            Text("How would you like to connect?", color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RelayColors.CardHigh)
            ) {
                RelayListRow(
                    R.drawable.relay_bluetooth, "Chat Nearby", "Find people around you using Bluetooth",
                    iconTint = RelayColors.Bluetooth, onClick = nearby
                )
                RowDivider()
                RelayListRow(
                    R.drawable.relay_broadcast, "New internet chat", "Add a friend once with their Internet ID",
                    iconTint = RelayColors.Nostr, onClick = online
                )
            }
        }
    }
}
