package `in`.yashd.bitchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.yashd.bitchat.bluetooth.BluetoothReadinessChecker
import `in`.yashd.bitchat.mesh.BluetoothReadiness
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.MeshUiState
import `in`.yashd.bitchat.service.MeshForegroundService

class MainActivity : ComponentActivity() {
    private var notificationRoute by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute = `in`.yashd.bitchat.notifications.MessageNotifications.consume(intent)
    }

    private val internetModel by lazy {
        androidx.lifecycle.ViewModelProvider(this, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return `in`.yashd.bitchat.ui.relay.RelayViewModel(
                    (application as RelayApplication).internetRuntime) as T
            }
        })[`in`.yashd.bitchat.ui.relay.RelayViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as RelayApplication).prepareForegroundIdentity()
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        `in`.yashd.bitchat.notifications.MessageNotifications.channels(this)
        notificationRoute = `in`.yashd.bitchat.notifications.MessageNotifications.consume(intent)
        val permissionPrefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !permissionPrefs.getBoolean("requested", false) &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (permissionPrefs.edit().putBoolean("requested", true).commit())
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        MeshRuntime.initialize(applicationContext)
        setContent {
            // Splash and onboarding from the Figma (RelayStart.kt), then the app as before.
            RelayStartFlow {
                RelayMeshHost(internetModel, notificationRoute) { notificationRoute = null }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // e.g. after the user turns Bluetooth on in system settings
        MeshRuntime.refreshReadiness()
    }
}

@Composable
private fun RelayMeshHost(model: `in`.yashd.bitchat.ui.relay.RelayViewModel, notificationRoute: String?, consumed: () -> Unit) {
    val context = LocalContext.current
    val state by MeshRuntime.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MeshRuntime.refreshReadiness()
    }
    val request: () -> Unit = { launcher.launch(runtimePermissions()) }
    val start: () -> Unit = {
        MeshRuntime.start()
        ContextCompat.startForegroundService(context, Intent(context, MeshForegroundService::class.java))
    }
    val stop: () -> Unit = {
        context.stopService(Intent(context, MeshForegroundService::class.java))
        MeshRuntime.stop()
    }
    val bluetoothSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { model.error("Couldn’t open Bluetooth settings.") }
    }
    RelayHome(model, state, start, stop, request, bluetoothSettings,
        notificationRoute = notificationRoute, notificationConsumed = consumed,
        nearbySettings = {
            item { SectionLabel("Nearby identity") }
            item { IdentityPanel(state,
                onRename = { MeshRuntime.renameSelf(it) },
                onRotate = { MeshRuntime.rotateIdentity() },
                onCopyId = { copyToClipboard(context, "Relay Nearby ID", state.localPeerId) },
                onShareId = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "My Relay Nearby ID: ${state.localPeerId}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share your Relay ID"))
                }) }
            item {
                var advanced by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nearby connection", color = RelayColors.TextPrimary, fontSize = RelayType.Body, fontWeight = FontWeight.SemiBold)
                    Text(if (!state.bluetoothReadiness.hasBle) "Nearby isn’t available on this device. Internet messaging still works." else if (state.serviceRunning) "Nearby is on" else "Nearby is off", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.bluetoothReadiness.hasPermissions) TextButton(onClick = request) { Text("Allow Bluetooth") }
                    TextButton(onClick = bluetoothSettings) { Text("Open Bluetooth settings") }
                    if (state.serviceRunning) TextButton(onClick = stop) { Text("Stop Nearby") }
                    else Button(onClick = when {
                        !state.bluetoothReadiness.hasPermissions -> request
                        !state.bluetoothReadiness.bluetoothEnabled -> bluetoothSettings
                        else -> start
                    }, enabled = state.bluetoothReadiness.hasBle) { Text("Find Nearby") }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide Nearby Advanced" else "Nearby Advanced") }
                    if (advanced) ReadinessPanel(state.bluetoothReadiness, state.serviceRunning, request, bluetoothSettings, start, stop)
                }
            }
            item { AboutCard(runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown") }
        })
}

@Composable
private fun IdentityPanel(
    state: MeshUiState,
    onRename: (String) -> Unit,
    onRotate: () -> Unit,
    onCopyId: () -> Unit,
    onShareId: () -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var confirmRotate by rememberSaveable { mutableStateOf(false) }
    val name = state.displayName.ifBlank { "nearby" }
    val hasId = state.localPeerId.isNotBlank()

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("rotate identity?") },
            text = {
                Text("this creates a new peer id and new keys. nearby peers will see you as a different person, and the mesh will stop until you start it again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    onRotate()
                }) {
                    Text("rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) {
                    Text("cancel")
                }
            }
        )
    }

    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PersonAvatar(name = name, size = 56.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = name,
                        color = RelayColors.TextPrimary,
                        fontSize = RelayType.SubTitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.localPeerId.ifBlank { "identity loading" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Your Nearby identity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyId,
                    enabled = hasId,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("copy ID", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onShareId,
                    enabled = hasId,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("share ID", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { editing = !editing }) {
                    Text("rename", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { confirmRotate = true }) {
                    Text("rotate", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    Button(
                        onClick = {
                            onRename(draftName)
                            editing = false
                        },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("save", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessPanel(
    readiness: BluetoothReadiness,
    serviceRunning: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val transportReady = readiness.canAdvertise && readiness.canScan && readiness.canGattServer
    val transportHint = if (!readiness.hasPermissions || !readiness.bluetoothEnabled) {
        "Fix the steps above first"
    } else {
        "This phone can't scan or advertise over Bluetooth LE"
    }
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadinessRow(
                label = "permissions",
                detail = "scan, advertise and connect allowed",
                ready = readiness.hasPermissions,
                hint = "Allow Bluetooth permissions",
                actionLabel = "allow",
                onAction = onRequestPermissions
            )
            ReadinessRow(
                label = "bluetooth",
                detail = "bluetooth is on",
                ready = readiness.bluetoothEnabled,
                hint = "Turn on Bluetooth",
                actionLabel = "turn on",
                onAction = onOpenBluetoothSettings
            )
            ReadinessRow(
                label = "mesh radio",
                detail = "can scan, advertise and connect",
                ready = transportReady,
                hint = transportHint,
                actionLabel = null,
                onAction = null
            )
            ReadinessRow(
                label = "hardware",
                detail = "bluetooth low energy supported",
                ready = readiness.hasBle,
                hint = "This device doesn't support Bluetooth Low Energy",
                actionLabel = null,
                onAction = null
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = if (readiness.hasPermissions) onStart else onRequestPermissions,
                    modifier = Modifier.weight(1f),
                    enabled = !serviceRunning,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (readiness.hasPermissions) "start mesh" else "allow bluetooth", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = serviceRunning,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                ) {
                    Text("stop", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    label: String,
    detail: String,
    ready: Boolean,
    hint: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = if (ready) detail else hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
            )
        }
        if (ready) {
            StatusPill(text = "ready", color = MaterialTheme.colorScheme.primary)
        } else if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(18.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            StatusPill(text = "unavailable", color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AboutCard(version: String) {
    CompactCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Relay",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "version $version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "how the mesh works",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Phones nearby find each other over Bluetooth Low Energy and swap signed announcements. " +
                        "Public messages go out to everyone in range, and private messages are encrypted so only the chosen peer can read them. " +
                        "Every phone also passes packets along, so a message can hop through other phones to reach someone out of direct range. " +
                        "No internet, account or phone number is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun runtimePermissions(): Array<String> {
    val permissions = BluetoothReadinessChecker.requiredPermissions().toMutableList()
    return permissions.toTypedArray()
}
