package `in`.yashd.bitchat

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import `in`.yashd.bitchat.attachments.*
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.TransferProfile
import `in`.yashd.bitchat.transfer.ble.*
import java.util.UUID

internal fun Context.activity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Attachment picker requires an activity")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var detailId by rememberSaveable(peerId) { mutableStateOf<String?>(null) }
    var viewerIndex by rememberSaveable(peerId) { mutableStateOf(-1) }
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
    LaunchedEffect(files.map { it.id to it.phase }) {
        files.filter { !it.outgoing && it.phase == BleFilePhase.COMPLETED && ui.previews[it.id] == null }.forEach { model.quietPreview(it) }
    }

    // Picker: a sheet matching the New Chat sheet, not a dialog.
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = RelayColors.Card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share a file", color = RelayColors.TextPrimary, fontSize = RelayType.SubTitle, fontWeight = FontWeight.SemiBold)
            Text("JPEG/PNG images and PDF documents only. Nothing is sent until you confirm and your friend accepts.",
                color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RelayColors.CardHigh)) {
                RelayListRow(R.drawable.relay_image, "Photo", "JPEG or PNG · ${if (multiHop) "250 KiB" else "1 MiB"} maximum",
                    iconTint = RelayColors.Blue) {
                    if (connected && !ui.busy) { picker = false; model.requestSelection(peerId, multiHop); photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                RowDivider()
                RelayListRow(R.drawable.relay_storage, "File", "Image or PDF · ${if (multiHop) "Multi-hop BLE · 250 KiB" else "Direct BLE · 1 MiB"} maximum",
                    iconTint = RelayColors.Blue) {
                    if (connected && !ui.busy) { picker = false; model.requestSelection(peerId, multiHop); document.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
                }
            }
            if (!connected) Text("Turn on Nearby and reconnect to this friend before sending.", color = RelayColors.TextSecondary, fontSize = RelayType.Label)
        }
    }

    ui.draft?.takeIf { ui.peer == peerId }?.let { draft ->
        NearbyAttachmentConfirmation(draft, peer?.identity?.displayName?.takeIf { it.isNotBlank() } ?: peerId,
            peerId, ui.busy, connected, ui.message, model::send, model::dismiss)
    }

    if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking attachment…", color = RelayColors.TextSecondary) }
    (loadError ?: ui.message)?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = RelayColors.TextSecondary) }

    // Transcript keeps only a compact summary per transfer; detail/actions live in the sheet.
    if (files.isNotEmpty()) {
        Text("Shared media", color = RelayColors.TextSecondary, fontSize = RelayType.Label,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }
    if (files.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("nearby-attachments"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(files, key = { it.id.toString() }) { file ->
            TransferSummaryRow(file, ui.previews[file.id]?.kind, ui.labels[file.id],
                preview = ui.previews[file.id]?.bitmap,
                onOpen = { detailId = file.id.toString() },
                onView = { viewerIndex = files.filter { it.phase == BleFilePhase.COMPLETED && !it.outgoing }.indexOfFirst { it.id == file.id } })
        }
    }

    // Full-screen viewer with left/right swipe across verified received images.
    if (viewerIndex >= 0) {
        val viewable = files.filter { it.phase == BleFilePhase.COMPLETED && !it.outgoing }
        if (viewable.isEmpty()) viewerIndex = -1
        else AttachmentPagerViewer(viewable, ui.previews, viewerIndex, model::quietPreview) { viewerIndex = -1 }
    }

    detailId?.let { id ->
        val file = files.firstOrNull { it.id.toString() == id }
        if (file == null) detailId = null else ModalBottomSheet(onDismissRequest = { detailId = null }, containerColor = RelayColors.Card) {
            TransferDetail(file, connected, ui.busy, ui.labels[file.id], ui.previews[file.id], ui.message,
                onAction = { model.action(file, it) }, onPreview = { model.preview(file) },
                onView = { viewerIndex = files.filter { it.phase == BleFilePhase.COMPLETED && !it.outgoing }.indexOfFirst { it.id == file.id } },
                onSave = { verified ->
                    destinationId = file.id.toString()
                    val name = "attachment-${file.id.toString().take(8)}.${verified.kind.extension}"
                    when (verified.kind) {
                        AttachmentKind.PDF -> savePdf.launch(name)
                        AttachmentKind.PNG -> savePng.launch(name)
                        AttachmentKind.JPEG -> saveJpeg.launch(name)
                    }
                })
        }
    }
}

/** Compact transcript row: kind icon, honest name, state and live progress. Controls live only in the detail sheet. */
@Composable
internal fun TransferSummaryRow(file: BleFileStatus, kind: AttachmentKind?, filename: String?,
    preview: android.graphics.Bitmap? = null, onOpen: () -> Unit, onView: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RelayColors.Card)
            .clickable(onClick = onOpen)
            .padding(12.dp)
            .testTag("attachment-${file.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RIcon(
                if (kind == AttachmentKind.JPEG || kind == AttachmentKind.PNG) R.drawable.relay_image else R.drawable.relay_storage,
                null, tint = RelayColors.TextSecondary, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(transferDisplayName(file.outgoing, filename, kind), color = RelayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                DotLabel(transferStateLabel(file.phase), transferStateColor(file.phase))
            }
            RIcon(R.drawable.relay_chevron, "Transfer details", tint = RelayColors.TextMuted, size = 18.dp)
        }
        preview?.let { bmp ->
            Image(bmp.asImageBitmap(), "Verified received image",
                Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onView))
        }
        if (file.phase == BleFilePhase.SENDING || file.phase == BleFilePhase.RECEIVING) {
            val progress = transferProgress(file.confirmedBytes, file.totalBytes)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}% of ${file.totalBytes} bytes", color = RelayColors.TextSecondary, fontSize = RelayType.Meta)
        }
    }
}

internal fun transferStateColor(phase: BleFilePhase) = when (phase) {
    BleFilePhase.COMPLETED -> RelayColors.Green
    BleFilePhase.FAILED, BleFilePhase.REJECTED -> RelayColors.Red
    BleFilePhase.CANCELLED, BleFilePhase.PAUSED -> RelayColors.TextMuted
    else -> RelayColors.Blue
}

/** Full negotiation/status surface for one transfer. */
@Composable
internal fun TransferDetail(file: BleFileStatus, connected: Boolean, busy: Boolean, filename: String?,
    verified: VerifiedAttachment?, message: String?, onAction: (BleFileAction) -> Unit,
    onPreview: () -> Unit, onSave: (VerifiedAttachment) -> Unit, onView: () -> Unit = {}) {
    val kind = verified?.kind
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(transferDisplayName(file.outgoing, filename, kind), color = RelayColors.TextPrimary, fontSize = RelayType.SubTitle, fontWeight = FontWeight.SemiBold)
        Text(
            "${if (file.outgoing) "Outgoing" else "Incoming"} · ${if (file.profile == TransferProfile.MULTI_HOP_BLE) "Multi-hop BLE" else "Direct BLE"} · ${file.totalBytes} bytes",
            color = RelayColors.TextSecondary, fontSize = RelayType.Label)
        DotLabel(transferStateLabel(file.phase), transferStateColor(file.phase))
        if (file.phase == BleFilePhase.SENDING || file.phase == BleFilePhase.RECEIVING) {
            val progress = transferProgress(file.confirmedBytes, file.totalBytes)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${file.confirmedBytes} / ${file.totalBytes} bytes ${if (file.outgoing) "acknowledged by receiver" else "stored"}",
                color = RelayColors.TextSecondary, fontSize = RelayType.Meta)
            if (file.outgoing && file.confirmedBytes == file.totalBytes) Text("Awaiting receiver's verified completion.", color = RelayColors.TextSecondary, fontSize = RelayType.Meta)
        }
        if (!connected && !isTerminalPhase(file.phase)) Text("Nearby is off or this friend is disconnected. Reconnect to continue.", color = RelayColors.TextSecondary, fontSize = RelayType.Label)
        when (file.phase) {
            BleFilePhase.AWAITING_ACCEPTANCE -> Text("Filename and type are not sent by this protocol. Content is checked after verified reception. Accept only from someone you trust; offers expire after 30 seconds.",
                color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            BleFilePhase.PAUSED -> Text(
                if (file.outgoing && file.confirmedBytes == 0L) "An expired offer may require Cancel transfer, then choosing the file again for a new offer."
                else if (file.outgoing) "Offer resume to continue; the recipient must accept again."
                else "Ask the sender to offer resume, then accept again. Receiver-initiated resume is unavailable.",
                color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            BleFilePhase.FAILED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED ->
                Text("Automatic retry is unavailable. Remove this record, choose the file again and confirm a new offer.", color = RelayColors.TextSecondary, fontSize = RelayType.Label)
            else -> {}
        }
        if (file.phase == BleFilePhase.AWAITING_ACCEPTANCE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = connected && !busy, onClick = { onAction(BleFileAction.ACCEPT) }) { Text("Accept") }
                TextButton(enabled = !busy, onClick = { onAction(BleFileAction.REJECT) }) { Text("Reject") }
            }
        } else if (!isTerminalPhase(file.phase)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file.phase == BleFilePhase.SENDING || file.phase == BleFilePhase.RECEIVING) TextButton(enabled = !busy, onClick = { onAction(BleFileAction.PAUSE) }) { Text("Pause") }
                if (file.phase == BleFilePhase.PAUSED && file.outgoing) TextButton(enabled = connected && !busy, onClick = { onAction(BleFileAction.RESUME) }) { Text("Offer resume") }
                TextButton(enabled = !busy, onClick = { onAction(BleFileAction.CANCEL) }) { Text("Cancel transfer") }
            }
        }
        if (file.phase == BleFilePhase.COMPLETED && !file.outgoing) {
            if (verified == null) TextButton(enabled = !busy, onClick = onPreview) { Text("Check / preview received file") }
            verified?.let {
                it.bitmap?.let { bmp -> Image(bmp.asImageBitmap(), "Verified received image", Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onView)) }
                if (it.kind == AttachmentKind.PDF) Text("PDF verified. Open saved PDFs only with a trusted viewer.", color = RelayColors.TextSecondary, fontSize = RelayType.Label)
                TextButton(enabled = !busy, onClick = { onSave(it) }) { Text("Save verified file…") }
            }
        }
        if (isTerminalPhase(file.phase)) TextButton(enabled = !busy, onClick = { onAction(BleFileAction.DISCARD) }) { Text("Remove attachment") }
        message?.let { Text(it, color = RelayColors.TextSecondary, fontSize = RelayType.Label) }
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

/** Full-screen swipeable viewer for verified received attachments (images render, PDFs note honestly). */
@Composable
internal fun AttachmentPagerViewer(viewable: List<BleFileStatus>, previews: Map<UUID, VerifiedAttachment>,
    startIndex: Int, onLoadPreview: (BleFileStatus) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, viewable.lastIndex)) { viewable.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val file = viewable[page]
                val verified = previews[file.id]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        verified?.bitmap != null -> Image(verified.bitmap.asImageBitmap(), "Verified received image",
                            Modifier.fillMaxSize().clickable(onClick = onDismiss))
                        verified?.kind == AttachmentKind.PDF -> Text("PDF document. Open the saved file with a trusted viewer.", color = Color.White)
                        else -> {
                            LaunchedEffect(file.id) { onLoadPreview(file) }
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            Text("${pager.currentPage + 1} / ${viewable.size}", color = Color.White, fontSize = RelayType.Meta,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }
    }
}
