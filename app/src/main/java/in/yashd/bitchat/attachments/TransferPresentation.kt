package `in`.yashd.bitchat.attachments

import `in`.yashd.bitchat.transfer.ble.BleFilePhase
import `in`.yashd.bitchat.transfer.ble.BleFileStatus

/** Pure presentation mapping for Nearby (BLE) transfer summaries, details and notifications. */
internal fun transferStateLabel(phase: BleFilePhase): String = when (phase) {
    BleFilePhase.OFFERED -> "Waiting for acceptance"
    BleFilePhase.AWAITING_ACCEPTANCE -> "Waiting for your response"
    BleFilePhase.SENDING, BleFilePhase.RECEIVING -> "Transferring"
    BleFilePhase.PAUSED -> "Paused"
    BleFilePhase.COMPLETED -> "Completed · verified"
    BleFilePhase.FAILED -> "Failed"
    BleFilePhase.CANCELLED -> "Cancelled"
    BleFilePhase.REJECTED -> "Rejected"
}

/** The BLE protocol never sends filenames: incoming files only get a generic kind label,
 * known only after verified reception. Outgoing files show the chosen local name. */
internal fun transferDisplayName(outgoing: Boolean, label: String?, kind: AttachmentKind?): String =
    if (outgoing && !label.isNullOrBlank()) label else when (kind) {
        AttachmentKind.JPEG, AttachmentKind.PNG -> "Image"
        AttachmentKind.PDF -> "PDF document"
        null -> if (outgoing) "File" else "Incoming file"
    }

internal fun transferProgress(confirmedBytes: Long, totalBytes: Long): Float =
    if (totalBytes <= 0L) 0f else (confirmedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

internal fun isTerminalPhase(phase: BleFilePhase): Boolean =
    phase in setOf(BleFilePhase.COMPLETED, BleFilePhase.FAILED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED)

/** Ongoing foreground-service notification while transfers are active; null when idle. */
internal fun transferNotificationText(statuses: List<BleFileStatus>): Pair<String, Int?>? {
    val active = statuses.firstOrNull { it.phase == BleFilePhase.AWAITING_ACCEPTANCE }
        ?: statuses.firstOrNull { !isTerminalPhase(it.phase) }
        ?: return null
    return when (active.phase) {
        BleFilePhase.AWAITING_ACCEPTANCE -> "Incoming file offer · open Relay to accept" to null
        BleFilePhase.SENDING, BleFilePhase.RECEIVING -> {
            val pct = (transferProgress(active.confirmedBytes, active.totalBytes) * 100).toInt()
            val direction = if (active.outgoing) "Sending" else "Receiving"
            "$direction file · $pct%" to pct
        }
        BleFilePhase.PAUSED -> "File transfer paused" to null
        else -> "${if (active.outgoing) "Sending" else "Receiving"} file · waiting for acceptance" to null
    }
}
