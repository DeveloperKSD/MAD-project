package `in`.yashd.bitchat.transfer

import java.util.UUID

/** Local admission policy, never a capability claimed by a remote peer. */
enum class TransferProfile { DIRECT_BLE, MULTI_HOP_BLE, WIFI_DIRECT }
enum class TransferDirection { OUTGOING, INCOMING }
enum class TransferState { PENDING, TRANSFERRING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class TransferFailure { STORAGE, HASH_MISMATCH, CONFLICTING_CHUNK, MISSING_DATA }

/** Provisional application limits, NOT measured radio capabilities. */
data class TransferLimits(
    val directBleBytes: Long = 1L * 1024 * 1024,
    val multiHopBleBytes: Long = 250L * 1024,
    val wifiDirectBytes: Long = 25L * 1024 * 1024,
    val maxStoredTransfers: Int = 4,
    val maxReservedBytes: Long = 64L * 1024 * 1024
) {
    init {
        require(listOf(directBleBytes, multiHopBleBytes, wifiDirectBytes).all { it in 1..HARD_MAX_FILE_BYTES })
        require(maxStoredTransfers in 1..32 && maxReservedBytes in 1..HARD_MAX_FILE_BYTES * 32)
    }
    fun maxBytes(profile: TransferProfile): Long = when (profile) {
        TransferProfile.DIRECT_BLE -> directBleBytes
        TransferProfile.MULTI_HOP_BLE -> multiHopBleBytes
        TransferProfile.WIFI_DIRECT -> wifiDirectBytes
    }
    companion object {
        const val DEFAULT_CHUNK_BYTES = 1024
        const val MAX_CHUNK_BYTES = 16 * 1024
        const val MAX_CHUNKS = 32768
        const val MAX_MISSING_PAGE = 128
        const val HARD_MAX_FILE_BYTES = MAX_CHUNK_BYTES.toLong() * MAX_CHUNKS
        internal const val IO_BUFFER_BYTES = 8192
    }
}

/** Immutable offer metadata. It must be authenticated by a future control channel.
 * Empty files have zero chunks and the SHA-256 of empty input; no zero-length DATA packet.
 */
data class TransferManifest(
    val id: UUID,
    val sizeBytes: Long,
    val chunkBytes: Int,
    val sha256: String,
    val version: Int = ChunkProtocol.VERSION
) {
    init {
        require(version == ChunkProtocol.VERSION)
        require(sizeBytes in 0..TransferLimits.HARD_MAX_FILE_BYTES)
        require(chunkBytes in 1..TransferLimits.MAX_CHUNK_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(totalChunks <= TransferLimits.MAX_CHUNKS)
    }
    val totalChunks: Int get() = ((sizeBytes + chunkBytes - 1) / chunkBytes).toInt()
    fun payloadBytes(index: Int): Int {
        require(index in 0 until totalChunks)
        return minOf(chunkBytes.toLong(), sizeBytes - index.toLong() * chunkBytes).toInt()
    }
    fun validate(profile: TransferProfile, limits: TransferLimits) {
        require(sizeBytes <= limits.maxBytes(profile)) { "File exceeds local transfer policy" }
    }
}

data class TransferSnapshot(
    val manifest: TransferManifest,
    val profile: TransferProfile,
    val direction: TransferDirection,
    val state: TransferState,
    val confirmedChunks: Int,
    val confirmedBytes: Long,
    val updatedAtMillis: Long,
    val failure: TransferFailure? = null
)

enum class ChunkReceipt { STORED, DUPLICATE, COMPLETED }
/** Corrupt/unknown checkpoint data is preserved and never interpreted as a new transfer. */
data class TransferRecoveryIssue(val directory: String, val reason: String)
