package `in`.yashd.bitchat

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import `in`.yashd.bitchat.attachments.*
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.TransferProfile
import `in`.yashd.bitchat.transfer.ble.*
import java.util.UUID

private fun Context.activity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Attachment picker requires an activity")
}

@Composable
internal fun NearbyAttachments(peerId: String, mesh: MeshUiState, pickerRequest: Int) {
    val context = LocalContext.current
    val model = remember(context) { ViewModelProvider(context.activity())[NearbyAttachmentModel::class.java] }
    val ui by model.state.collectAsState()
    val all by MeshRuntime.fileTransfers.collectAsState()
    val files = all.filter { it.peer == peerId }
    var picker by rememberSaveable(peerId) { mutableStateOf(false) }
    var handledRequest by rememberSaveable(peerId) { mutableIntStateOf(0) }
    var destinationId by rememberSaveable { mutableStateOf<String?>(null) }
    val peer = mesh.peers.firstOrNull { it.identity.peerId == peerId }
    val connected = mesh.serviceRunning && peer?.state == PeerConnectionState.Authenticated && peer.identity.verified
    val multiHop = peer?.directDeviceAddress == null
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { model.picked(it) } }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.picked(it) } }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = destinationId; destinationId = null
        if (uri != null && id != null) model.save(uri, UUID.fromString(id))
    }
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val id = destinationId; destinationId = null
        if (uri != null && id != null) model.save(uri, UUID.fromString(id))
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val id = destinationId; destinationId = null
        if (uri != null && id != null) model.save(uri, UUID.fromString(id))
    }
    var loadError by remember(peerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(peerId, mesh.localPeerId) {
        if (mesh.localPeerId.isNotEmpty()) MeshRuntime.reloadFileTransfers().onFailure { loadError = "Saved attachments are unavailable. Existing files were preserved." }
    }
    LaunchedEffect(pickerRequest) { if (pickerRequest > handledRequest) { handledRequest = pickerRequest; picker = true } }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Nearby attachment") }, text = {
        Column {
            Text("JPEG/PNG images and PDF documents only. BLE is intended for small files. No file is sent until you confirm and your friend accepts.")
            Text(if (connected) "${if (multiHop) "Multi-hop BLE · 250 KiB" else "Direct BLE · 1 MiB"} maximum" else "Turn on Nearby and reconnect to this friend before sending.")
        }
    }, confirmButton = {
        TextButton(enabled = connected && !ui.busy, onClick = { picker = false; model.requestSelection(peerId, multiHop); photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
    }, dismissButton = {
        Row {
            TextButton(enabled = connected && !ui.busy, onClick = { picker = false; model.requestSelection(peerId, multiHop); document.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }) { Text("Choose file") }
            TextButton(onClick = { picker = false }) { Text("Close") }
        }
    })
    ui.draft?.takeIf { ui.peer == peerId }?.let { draft ->
        NearbyAttachmentConfirmation(draft, peer?.identity?.displayName?.takeIf { it.isNotBlank() } ?: peerId,
            peerId, ui.busy, connected, ui.message, model::send, model::dismiss)
    }
    if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking attachment…", color = RelayColors.TextSecondary) }
    (loadError ?: ui.message)?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = RelayColors.TextSecondary) }
    if (files.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("nearby-attachments"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(files, key = { it.id.toString() }) { file ->
            Column {
                NearbyFileCard(file, connected, ui.busy, { model.action(file, it) }, { model.preview(file) }, ui.labels[file.id])
                ui.previews[file.id]?.takeIf { file.phase == BleFilePhase.COMPLETED }?.let { verified ->
                    verified.bitmap?.let { Image(it.asImageBitmap(), "Verified received image", Modifier.fillMaxWidth().heightIn(max = 200.dp)) }
                    if (verified.kind == AttachmentKind.PDF) Text("PDF verified. Open saved PDFs only with a trusted viewer.")
                    TextButton(enabled = !ui.busy, onClick = {
                        destinationId = file.id.toString()
                        val name = "attachment-${file.id.toString().take(8)}.${verified.kind.extension}"
                        if (verified.kind == AttachmentKind.PDF) savePdf.launch(name) else if (verified.kind == AttachmentKind.PNG) savePng.launch(name) else saveJpeg.launch(name)
                    }) { Text("Save verified file…") }
                }
            }
        }
    }
}

@Composable
internal fun NearbyFileCard(file: BleFileStatus, connected: Boolean, busy: Boolean,
    action: (BleFileAction) -> Unit, preview: () -> Unit, filename: String? = null) {
    val terminal = file.phase in setOf(BleFilePhase.COMPLETED, BleFilePhase.FAILED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED)
    val label = when (file.phase) {
        BleFilePhase.OFFERED -> "Pending · waiting for acceptance"
        BleFilePhase.AWAITING_ACCEPTANCE -> "Incoming authenticated file offer"
        BleFilePhase.SENDING, BleFilePhase.RECEIVING -> "Transferring"
        BleFilePhase.PAUSED -> "Paused"
        BleFilePhase.COMPLETED -> "Completed · SHA-256 verified"
        BleFilePhase.FAILED -> "Failed · file unavailable"
        BleFilePhase.CANCELLED -> "Cancelled"
        BleFilePhase.REJECTED -> "Rejected by recipient"
    }
    Card(Modifier.fillMaxWidth().testTag("attachment-${file.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (file.outgoing) "Outgoing attachment" else "Incoming attachment")
            Text(filename ?: "Attachment ${file.id.toString().take(8)}")
            Text(label)
            Text("${file.totalBytes} bytes · ${if (file.profile == TransferProfile.MULTI_HOP_BLE) "Multi-hop BLE" else "Direct BLE"}")
            if (file.phase in setOf(BleFilePhase.SENDING, BleFilePhase.RECEIVING)) {
                LinearProgressIndicator(progress = { if (file.totalBytes == 0L) 0f else (file.confirmedBytes.toFloat() / file.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${file.confirmedBytes} / ${file.totalBytes} bytes ${if (file.outgoing) "acknowledged by receiver" else "stored"}")
                if (file.outgoing && file.confirmedBytes == file.totalBytes) Text("Awaiting receiver's verified completion.")
            }
            if (!connected && !terminal) Text("Nearby is off or this friend is disconnected. Reconnect to continue.")
            if (file.phase == BleFilePhase.AWAITING_ACCEPTANCE) {
                Text("Filename and type are not sent by this protocol. Content is checked after verified reception. Accept only from someone you trust; offers expire after 30 seconds.")
                Row {
                    TextButton(enabled = connected && !busy, onClick = { action(BleFileAction.ACCEPT) }) { Text("Accept") }
                    TextButton(enabled = !busy, onClick = { action(BleFileAction.REJECT) }) { Text("Reject") }
                }
            } else if (!terminal) {
                Row {
                    if (file.phase in setOf(BleFilePhase.SENDING, BleFilePhase.RECEIVING)) TextButton(enabled = !busy, onClick = { action(BleFileAction.PAUSE) }) { Text("Pause") }
                    if (file.phase == BleFilePhase.PAUSED && file.outgoing) TextButton(enabled = connected && !busy, onClick = { action(BleFileAction.RESUME) }) { Text("Offer resume") }
                    TextButton(enabled = !busy, onClick = { action(BleFileAction.CANCEL) }) { Text("Cancel transfer") }
                }
                if (file.phase == BleFilePhase.PAUSED && file.outgoing && file.confirmedBytes == 0L) Text("An expired offer may require Cancel transfer, then choosing the file again for a new offer.")
                if (file.phase == BleFilePhase.PAUSED && !file.outgoing) Text("Ask the sender to offer resume, then accept again. Receiver-initiated resume is unavailable.")
            }
            if (file.phase == BleFilePhase.COMPLETED && !file.outgoing) TextButton(enabled = !busy, onClick = preview) { Text("Check / preview received file") }
            if (file.phase in setOf(BleFilePhase.FAILED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED)) Text("Automatic retry is unavailable. Remove this record, choose the file again and confirm a new offer.")
            if (terminal) TextButton(enabled = !busy, onClick = { action(BleFileAction.DISCARD) }) { Text("Remove attachment") }
        }
    }
}

@Composable
internal fun NearbyAttachmentConfirmation(draft: PickedAttachment, recipient: String, peerId: String,
    busy: Boolean, connected: Boolean, message: String?, send: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Confirm BLE attachment") }, text = {
        Column {
            Text(draft.name); Text("${draft.size} bytes")
            Text("Recipient: $recipient"); Text("Nearby ID: $peerId")
            Text("${if (draft.multiHop) "Multi-hop BLE · 250 KiB" else "Direct BLE · 1 MiB"} maximum")
            Text("The receiver must explicitly accept. Sending can be slow and may pause on disconnection.")
            message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = connected && !busy, onClick = send) { Text(if (busy) "Preparing…" else "Send offer") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel selection") } })
}
