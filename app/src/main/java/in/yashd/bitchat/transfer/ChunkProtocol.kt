package `in`.yashd.bitchat.transfer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Version 1 DATA packet, exact frame boundaries supplied by the future adapter.
 * All integers are big-endian. Header: magic u32, version u8, flags u8 (zero),
 * header length u16, UUID 16 bytes, index u32, total u32, payload length u32.
 * A valid packet is exactly HEADER_BYTES + payload length bytes. No Base64.
 */
object ChunkProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 36
    const val MAX_PACKET_BYTES = HEADER_BYTES + TransferLimits.MAX_CHUNK_BYTES
    private const val MAGIC = 0x524C4654 // ASCII RLFT

    fun encode(id: UUID, index: Int, total: Int, payload: ByteArray): ByteArray {
        validate(index, total, payload.size)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(MAGIC); put(VERSION.toByte()); put(0); putShort(HEADER_BYTES.toShort())
            putLong(id.mostSignificantBits); putLong(id.leastSignificantBits)
            putInt(index); putInt(total); putInt(payload.size); put(payload)
        }.array()
    }

    fun decode(packet: ByteArray): FileChunk {
        require(packet.size in HEADER_BYTES + 1..MAX_PACKET_BYTES) { "Invalid chunk packet size" }
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "Invalid chunk magic" }
        require(input.get().toInt() and 255 == VERSION) { "Unsupported chunk version" }
        require(input.get().toInt() == 0) { "Unsupported chunk flags" }
        require(input.short.toInt() and 65535 == HEADER_BYTES) { "Invalid header length" }
        val id = UUID(input.long, input.long)
        val index = input.int
        val total = input.int
        val length = input.int
        // Negative u32 values, including overflowing remote lengths/counts, are rejected.
        validate(index, total, length)
        require(packet.size == HEADER_BYTES + length) { "Truncated or trailing chunk data" }
        return FileChunk(id, index, total, ByteArray(length).also(input::get))
    }

    private fun validate(index: Int, total: Int, length: Int) {
        require(total in 1..TransferLimits.MAX_CHUNKS && index in 0 until total)
        require(length in 1..TransferLimits.MAX_CHUNK_BYTES)
    }
}

/** Payload is caller-owned; the engine never retains it after an accept/read operation. */
data class FileChunk(val transferId: UUID, val index: Int, val totalChunks: Int, val payload: ByteArray)
