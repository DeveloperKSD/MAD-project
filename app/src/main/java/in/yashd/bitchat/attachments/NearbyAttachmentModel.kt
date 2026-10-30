package `in`.yashd.bitchat.attachments

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.yashd.bitchat.mesh.MeshRuntime
import `in`.yashd.bitchat.mesh.PeerConnectionState
import `in`.yashd.bitchat.transfer.ble.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.util.UUID

internal data class AttachmentUiState(val peer: String? = null, val draft: PickedAttachment? = null,
    val busy: Boolean = false, val message: String? = null, val previews: Map<UUID, VerifiedAttachment> = emptyMap(), val labels: Map<UUID, String> = emptyMap())
internal data class VerifiedAttachment(val file: File, val kind: AttachmentKind, val bitmap: Bitmap?)

/** Activity-scoped ViewModel: picker imports/confirmation survive activity recreation, never auto-send. */
internal class NearbyAttachmentModel(application: Application) : AndroidViewModel(application) {
    private val privateFiles = PrivateAttachments(application)
    private val mutable = MutableStateFlow(AttachmentUiState())
    val state = mutable.asStateFlow()
    init {
        // Only our disposable UI copies, never Stage 1 payload/checkpoints or unrelated files.
        privateFiles.directory.listFiles()?.filter { it.name.startsWith("attachment-") && it.name.endsWith(".private") }?.forEach { it.delete() }
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { mutable.value = mutable.value.copy(message = "The file provider timed out. Choose a local file and try again.") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(message = when (e) {
                is SecurityException -> "File access was denied. Choose it again using the system picker."
                is IllegalArgumentException -> e.message ?: "File is not supported or exceeds the BLE limit."
                else -> "Attachment action failed. Check Nearby, consent, available storage and the file; nothing was marked delivered."
            }) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private var selectionPeer: String? = null
    private var selectionMultiHop = false
    fun requestSelection(peer: String, multiHop: Boolean) { selectionPeer = peer; selectionMultiHop = multiHop }
    fun picked(uri: Uri) {
        val peer = selectionPeer
        if (peer == null) { mutable.value = mutable.value.copy(message = "The picker was interrupted. Choose the file again; nothing was sent."); return }
        selectionPeer = null
        pick(uri, peer, selectionMultiHop)
    }
    fun pick(uri: Uri, peer: String, multiHop: Boolean) = operation {
        var imported: PickedAttachment? = null
        try {
            withTimeout(30_000) { runInterruptible(Dispatchers.IO) { imported = privateFiles.import(uri, multiHop) } }
            mutable.value.draft?.file?.delete()
            mutable.value = mutable.value.copy(peer = peer, draft = requireNotNull(imported))
            imported = null
        } finally { imported?.file?.delete() }
    }
    fun dismiss() { if (!mutable.value.busy) { mutable.value.draft?.file?.delete(); mutable.value = mutable.value.copy(draft = null) } }
    private fun connected(peer: String): Boolean {
        val mesh = MeshRuntime.state.value
        return mesh.serviceRunning && mesh.peers.any { it.identity.peerId == peer && it.identity.verified && it.state == PeerConnectionState.Authenticated }
    }
    fun send() = operation {
        val draft = requireNotNull(mutable.value.draft); val peer = requireNotNull(mutable.value.peer)
        val transfers = MeshRuntime.fileTransfers.value
        require(transfers.none { it.phase !in setOf(BleFilePhase.COMPLETED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED, BleFilePhase.FAILED) }) {
            "Another transfer reserves this device. Cancel it before offering another file; pausing does not release the slot."
        }
        require(transfers.size < 4) { "Four transfer records are retained. Remove a completed or failed attachment before sending another." }
        require(connected(peer)) { "This recipient is disconnected. Turn on Nearby and reconnect before confirming." }
        val direct = MeshRuntime.state.value.peers.any { it.identity.peerId == peer && it.directDeviceAddress != null }
        require(draft.multiHop || direct) { "The direct BLE link changed. Choose the file again to confirm the multi-hop limit." }
        require(draft.size <= AttachmentPolicy.maximum(draft.multiHop)) { "File exceeds this BLE method's size limit." }
        val transferId = withContext(Dispatchers.IO) {
            require(draft.file.length() == draft.size && privateFiles.validate(draft.file) == draft.kind) { "Selected private file changed. Choose it again." }
            AttachmentPolicy.checkSpace(privateFiles.directory.usableSpace, draft.size)
            draft.file.inputStream().use { MeshRuntime.offerAttachment(peer, it, draft.multiHop).getOrThrow() }
        }
        draft.file.delete()
        mutable.value = mutable.value.copy(draft = null, labels = mutable.value.labels + (transferId to draft.name), message = "Offer pending. Waiting for the recipient's explicit acceptance; the file is not delivered yet.")
    }
    fun action(status: BleFileStatus, action: BleFileAction) = operation {
        if (action in setOf(BleFileAction.ACCEPT, BleFileAction.RESUME)) require(connected(status.peer)) { "Reconnect to this friend in Nearby first." }
        if (action == BleFileAction.ACCEPT) withContext(Dispatchers.IO) { AttachmentPolicy.checkSpace(privateFiles.directory.usableSpace, status.totalBytes) }
        MeshRuntime.attachmentAction(status.id, action).getOrThrow()
        if (action == BleFileAction.DISCARD) {
            mutable.value.previews[status.id]?.file?.delete()
            mutable.value = mutable.value.copy(previews = mutable.value.previews - status.id, labels = mutable.value.labels - status.id)
        }
        mutable.value = mutable.value.copy(message = when (action) {
            BleFileAction.ACCEPT -> if (MeshRuntime.fileTransfers.value.none { it.id == status.id }) "The offer expired before acceptance. Ask the sender to offer again." else "Accepted. Completion still requires all chunks and SHA-256 verification."
            BleFileAction.REJECT -> "Offer rejected. No further file data is accepted."
            BleFileAction.RESUME -> "Resume pending. The recipient must accept again."
            BleFileAction.PAUSE -> "Transfer paused. In-flight packets may still arrive; completion still requires verification."
            BleFileAction.CANCEL -> "Transfer cancelled locally. An offline peer may learn this only after reconnecting."
            BleFileAction.DISCARD -> "Attachment removed from this device."
        })
    }
    /** Quiet gallery thumbnail loading: no busy flag, errors are silent (detail preview still reports them). */
    fun quietPreview(status: BleFileStatus) {
        if (mutable.value.busy || mutable.value.previews.containsKey(status.id)) return
        if (status.phase != BleFilePhase.COMPLETED) return
        viewModelScope.launch {
            runCatching {
                val verified = withContext(Dispatchers.IO) {
                    AttachmentPolicy.checkSpace(privateFiles.directory.usableSpace, status.totalBytes)
                    val file = privateFiles.temporary()
                    try {
                        file.outputStream().use { MeshRuntime.copyVerifiedAttachment(status.id, it).getOrThrow() }
                        val kind = privateFiles.validate(file)
                        VerifiedAttachment(file, kind, if (kind == AttachmentKind.PDF) null else privateFiles.preview(file))
                    } catch (e: Exception) { file.delete(); throw e }
                }
                mutable.value.previews[status.id]?.file?.delete()
                mutable.value = mutable.value.copy(previews = mutable.value.previews + (status.id to verified))
            }
        }
    }
    fun preview(status: BleFileStatus) = operation {
        require(status.phase == BleFilePhase.COMPLETED)
        val verified = withContext(Dispatchers.IO) {
            AttachmentPolicy.checkSpace(privateFiles.directory.usableSpace, status.totalBytes)
            val file = privateFiles.temporary()
            try {
                file.outputStream().use { MeshRuntime.copyVerifiedAttachment(status.id, it).getOrThrow() }
                val kind = privateFiles.validate(file)
                VerifiedAttachment(file, kind, if (kind == AttachmentKind.PDF) null else privateFiles.preview(file))
            } catch (e: Exception) { file.delete(); throw e }
        }
        mutable.value.previews[status.id]?.file?.delete()
        mutable.value = mutable.value.copy(previews = mutable.value.previews + (status.id to verified))
    }
    fun save(uri: Uri, id: UUID) = operation {
        val verified = requireNotNull(mutable.value.previews[id]) { "Preview was interrupted. Check the completed file again before saving." }
        // Recheck the engine's completed bytes before writing to a user-selected SAF destination.
        withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "w")) { "Cannot open the selected destination." }.use { output ->
                MeshRuntime.copyVerifiedAttachment(id, output).getOrThrow()
            }
        }
        mutable.value = mutable.value.copy(message = "Saved verified ${verified.kind.extension.uppercase()} to your chosen location.")
    }
    override fun onCleared() {
        mutable.value.draft?.file?.delete()
        mutable.value.previews.values.forEach { it.file.delete() }
    }
}
