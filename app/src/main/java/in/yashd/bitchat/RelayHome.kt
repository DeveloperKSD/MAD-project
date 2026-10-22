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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.ui.relay.InternetSetupScreen
import `in`.yashd.bitchat.ui.relay.OnlineChatScreen
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
    // Favourites, mutes and unread markers are kept in memory for this session only.
    val favorites = remember { mutableStateListOf<String>() }
    val muted = remember { mutableStateListOf<String>() }
    val seenAt = remember { mutableStateMapOf<String, Long>() }
    val sessionStart = remember { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    val chatRoute = chat
    val contactRoute = contact
    val items = buildChatList(internet, mesh, seenAt, sessionStart)
    val myName = mesh.displayName

    val goTab: (Int) -> Unit = { index ->
        chat = null
        contact = null
        onlineEntry = false
        setupOpen = false
        nearbyDetails = false
        tab = index
    }
    val openChat: (String) -> Unit = { route ->
        contact = null
        chat = route
    }
    val atRoot = chatRoute == null && contactRoute == null && !onlineEntry && !setupOpen && !nearbyDetails
    val showingChat = chatRoute != null && contactRoute == null && !onlineEntry && !setupOpen && !nearbyDetails

    BackHandler(enabled = !atRoot || tab != 0) {
        when {
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
                when {
                    setupOpen -> InternetSetupScreen(
                        internet, model::updateSetupServers, model::setupBack, model::setupNext,
                        model::announce, model::retrySetup,
                        finish = { if (model.finishSetup()) setupOpen = false },
                        exit = { setupOpen = false }
                    )
                    onlineEntry -> OnlineChatScreen(
                        model::addInternetConversation,
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
                        favorite = contactRoute in favorites,
                        muted = contactRoute in muted,
                        back = { contact = null },
                        openChat = { openChat(contactRoute) },
                        toggleFavorite = {
                            if (contactRoute in favorites) favorites.remove(contactRoute) else favorites.add(contactRoute)
                        },
                        toggleMute = {
                            if (contactRoute in muted) muted.remove(contactRoute) else muted.add(contactRoute)
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
                        markSeen = { seenAt[chatRoute] = System.currentTimeMillis() },
                        toast = toast
                    )
                    tab == 0 -> RelayChatsScreen(
                        items = items,
                        myName = myName,
                        now = now,
                        favorites = favorites,
                        loading = internet.storageLoading,
                        storageProblem = internet.storageError != null,
                        onAvatar = { goTab(2) },
                        openChat = openChat,
                        newChat = { newChat = true },
                        discover = { goTab(1) }
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
                    label = { Text(label, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RelayColors.Blue,
                        selectedTextColor = RelayColors.Blue,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = RelayColors.TextSecondary,
                        unselectedTextColor = RelayColors.TextSecondary
                    )
                )
            }
        }
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
            Text("New Chat", color = RelayColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("How would you like to connect?", color = RelayColors.TextSecondary, fontSize = 14.sp)
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
                    R.drawable.relay_broadcast, "Chat Online", "Add a friend once with their public Relay ID",
                    iconTint = RelayColors.Nostr, onClick = online
                )
            }
        }
    }
}
