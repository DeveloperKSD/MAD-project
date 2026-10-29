package `in`.yashd.bitchat.transfer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class ChunkProtocolTest {
    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private fun packet() = ChunkProtocol.encode(id, 1, 3, byteArrayOf(0, -1, 127))
    private fun invalid(bytes: ByteArray) = assertThrows(IllegalArgumentException::class.java) { ChunkProtocol.decode(bytes) }

    @Test fun goldenPacketFixesByteOrderOffsetsAndBinaryContent() {
        val bytes = packet()
        assertEquals("524c46540100002400112233445566778899aabbccddeeff00000001000000030000000300ff7f", bytes.hex())
        val chunk = ChunkProtocol.decode(bytes)
        assertEquals(id, chunk.transferId)
        assertEquals(1, chunk.index); assertEquals(3, chunk.totalChunks)
        assertArrayEquals(byteArrayOf(0, -1, 127), chunk.payload)
    }
    @Test fun allTruncationsAndTrailingBytesAreRejected() {
        val bytes = packet()
        for (length in 0 until bytes.size) invalid(bytes.copyOf(length))
        invalid(bytes + 0)
    }
    @Test fun rejectsMagicVersionFlagsAndHeaderLength() {
        listOf(0, 1, 2, 3, 4, 5, 6, 7).forEach { offset ->
            val bytes = packet(); bytes[offset] = (bytes[offset].toInt() xor 64).toByte(); invalid(bytes)
        }
    }
    @Test fun rejectsUnsignedOverflowCountsIndicesAndLengthMismatches() {
        listOf(24 to -1, 24 to 3, 24 to Int.MAX_VALUE, 28 to 0, 28 to -1,
            28 to TransferLimits.MAX_CHUNKS + 1, 32 to 0, 32 to -1,
            32 to Int.MAX_VALUE, 32 to 2, 32 to 4).forEach { (offset, value) ->
            invalid(packet().also { ByteBuffer.wrap(it).putInt(offset, value) })
        }
    }
    @Test fun encoderAndDecoderApplyHardCapsBeforePayloadAllocation() {
        assertThrows(IllegalArgumentException::class.java) { ChunkProtocol.encode(id, 0, 1, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ChunkProtocol.encode(id, -1, 1, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChunkProtocol.encode(id, 0, 0, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChunkProtocol.encode(id, 0, 1, ByteArray(TransferLimits.MAX_CHUNK_BYTES + 1)) }
        invalid(ByteArray(ChunkProtocol.MAX_PACKET_BYTES + 1))
        val maximum = ChunkProtocol.encode(id, TransferLimits.MAX_CHUNKS - 1, TransferLimits.MAX_CHUNKS, ByteArray(TransferLimits.MAX_CHUNK_BYTES))
        assertEquals(ChunkProtocol.MAX_PACKET_BYTES, maximum.size)
        assertEquals(TransferLimits.MAX_CHUNK_BYTES, ChunkProtocol.decode(maximum).payload.size)
    }
    @Test fun policiesAndManifestBoundsAreCentralized() {
        val limits = TransferLimits()
        assertEquals(1_048_576L, limits.maxBytes(TransferProfile.DIRECT_BLE))
        assertEquals(256_000L, limits.maxBytes(TransferProfile.MULTI_HOP_BLE))
        assertEquals(26_214_400L, limits.maxBytes(TransferProfile.WIFI_DIRECT))
        val hash = "00".repeat(32)
        val file = TransferManifest(id, 5, 4, hash)
        assertEquals(2, file.totalChunks); assertEquals(4, file.payloadBytes(0)); assertEquals(1, file.payloadBytes(1))
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, -1, 4, hash) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, Long.MAX_VALUE, 4, hash) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, 1, 0, hash) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, 1, 4, "AB".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, 1, 4, hash, 2) }
        assertThrows(IllegalArgumentException::class.java) { TransferManifest(id, TransferLimits.MAX_CHUNKS + 1L, 1, hash) }
        assertThrows(IllegalArgumentException::class.java) { file.payloadBytes(2) }
    }
    @Test fun futureAdapterContractsRejectUnboundedControlsAndCheckPacketBudget() {
        assertThrows(IllegalArgumentException::class.java) { TransferControl.Missing(id, (0..128).toList()) }
        assertThrows(IllegalArgumentException::class.java) { TransferControl.Missing(id, listOf(1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { TransferControl.Ack(id, -1) }
        assertThrows(IllegalArgumentException::class.java) { TransferTransportCapabilities(TransferProfile.DIRECT_BLE, 36) }
        assertThrows(IllegalArgumentException::class.java) { TransferTransportCapabilities(TransferProfile.DIRECT_BLE, 100, maxInFlightChunks = 1000) }
        val capabilities = TransferTransportCapabilities(TransferProfile.DIRECT_BLE, 100)
        assertTrue(capabilities.accepts(TransferManifest(id, 1000, 64, "00".repeat(32))))
        assertFalse(capabilities.accepts(TransferManifest(id, 1000, 65, "00".repeat(32))))
        val mutable = mutableListOf(1, 2)
        val missing = TransferControl.Missing(id, mutable)
        mutable.clear()
        assertEquals(listOf(1, 2), missing.indices)
    }
}
