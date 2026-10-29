package `in`.yashd.bitchat.transfer

import java.util.UUID

/** Contracts only: Stage 1 installs NO transport, scheduler, sockets or radio permissions. */
data class TransferTransportCapabilities(
    val profile: TransferProfile,
    val maxPacketBytes: Int,
    val maxQueuedChunks: Int = 4,
    val maxInFlightChunks: Int = 4
) {
    init {
        require(maxPacketBytes in ChunkProtocol.HEADER_BYTES + 1..ChunkProtocol.MAX_PACKET_BYTES)
        require(maxQueuedChunks in 1..32 && maxInFlightChunks in 1..32)
    }
    fun accepts(manifest: TransferManifest): Boolean =
        ChunkProtocol.HEADER_BYTES + manifest.chunkBytes <= maxPacketBytes
}

enum class TransferQueueAdmission { ACCEPTED, BACKPRESSURE, CLOSED }

/** Future adapters must bind every offer/control/chunk to the authenticated expected peer,
 * frame streams before decoding, and cap their queues. ACCEPTED means queue admission only.
 * ACK is a durable chunk receipt; COMPLETE additionally asserts verified whole-file SHA-256.
 * Control wire encoding, authentication, retries and scheduling are intentionally Stage 2.
 */
interface TransferTransport {
    val capabilities: TransferTransportCapabilities
    fun offer(manifest: TransferManifest): TransferQueueAdmission
    fun trySendChunk(packet: ByteArray): TransferQueueAdmission
    fun trySendControl(control: TransferControl): TransferQueueAdmission
}

sealed interface TransferControl {
    val transferId: UUID
    data class Accept(override val transferId: UUID) : TransferControl
    data class Ack(override val transferId: UUID, val index: Int) : TransferControl {
        init { require(index in 0 until TransferLimits.MAX_CHUNKS) }
    }
    class Missing(override val transferId: UUID, indices: List<Int>) : TransferControl {
        init { require(indices.size in 1..TransferLimits.MAX_MISSING_PAGE) }
        val indices = indices.toList()
        init {
            require(this.indices.distinct().size == this.indices.size)
            require(this.indices.all { it in 0 until TransferLimits.MAX_CHUNKS })
        }
    }
    data class Pause(override val transferId: UUID) : TransferControl
    data class Resume(override val transferId: UUID) : TransferControl
    data class Complete(override val transferId: UUID, val sha256: String) : TransferControl {
        init { require(sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
    data class Cancel(override val transferId: UUID) : TransferControl
}
