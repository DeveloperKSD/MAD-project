package `in`.yashd.bitchat.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.UUID
import kotlin.random.Random

class FileTransferEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun parent() = temp.newFolder()
    private fun bytes(size: Int) = ByteArray(size) { (it * 37).toByte() }
    private fun manifest(data: ByteArray, chunk: Int = 64) = TransferManifest(UUID.randomUUID(), data.size.toLong(), chunk, TransferStore.sha256(data).hex())
    private fun packet(manifest: TransferManifest, data: ByteArray, index: Int) = ChunkProtocol.encode(manifest.id, index, manifest.totalChunks,
        data.copyOfRange(index * manifest.chunkBytes, index * manifest.chunkBytes + manifest.payloadBytes(index)))
    private fun payload(parent: File, id: UUID) = File(parent, "relay-transfers-v1/$id/payload.bin")
    private fun receipts(parent: File, id: UUID) = File(parent, "relay-transfers-v1/$id/receipts.bin")

    @Test fun binaryRoundTripShuffledChunksAndExactLastChunk() {
        val data = bytes(1027)
        FileTransferEngine(parent()).use { sender ->
            FileTransferEngine(parent()).use { receiver ->
                val offer = sender.prepareOutgoing(ByteArrayInputStream(data), TransferProfile.DIRECT_BLE, 100).manifest
                assertEquals(TransferState.PENDING, sender.snapshot(offer.id).state)
                receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE)
                sender.start(offer.id); receiver.start(offer.id)
                val order = (0 until offer.totalChunks).shuffled(Random(12))
                order.forEachIndexed { position, index ->
                    val frame = sender.readChunk(offer.id, index)
                    assertTrue(frame.size <= 136)
                    val receipt = receiver.accept(frame)
                    assertEquals(if (position == order.lastIndex) ChunkReceipt.COMPLETED else ChunkReceipt.STORED, receipt)
                    sender.acknowledge(offer.id, index)
                }
                assertEquals(TransferState.TRANSFERRING, sender.snapshot(offer.id).state)
                sender.confirmRemoteCompletion(offer.id, offer.sha256)
                assertEquals(TransferState.COMPLETED, sender.snapshot(offer.id).state)
                assertEquals(data.size.toLong(), receiver.snapshot(offer.id).confirmedBytes)
                val output = ByteArrayOutputStream()
                receiver.copyCompletedTo(offer.id, output)
                assertArrayEquals(data, output.toByteArray())
            }
        }
    }
    @Test fun emptyFilesHaveNoDataChunksAndRequireVerifiedRemoteCompletion() {
        val data = byteArrayOf()
        FileTransferEngine(parent()).use { sender -> FileTransferEngine(parent()).use { receiver ->
            val offer = sender.prepareOutgoing(ByteArrayInputStream(data), TransferProfile.WIFI_DIRECT).manifest
            assertEquals(0, offer.totalChunks)
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", offer.sha256)
            receiver.beginIncoming(offer, TransferProfile.WIFI_DIRECT)
            sender.start(offer.id)
            assertEquals(TransferState.COMPLETED, receiver.start(offer.id).state)
            assertTrue(sender.missingChunks(offer.id).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { sender.readChunk(offer.id, 0) }
            assertEquals(TransferState.TRANSFERRING, sender.snapshot(offer.id).state)
            sender.confirmRemoteCompletion(offer.id, offer.sha256)
            receiver.copyCompletedTo(offer.id, ByteArrayOutputStream())
        } }
    }
    @Test fun duplicatesDoNotInflateProgressAndCanBeAcknowledgedAfterCompletion() {
        val data = bytes(100); val offer = manifest(data)
        FileTransferEngine(parent()).use { receiver ->
            receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
            assertEquals(ChunkReceipt.STORED, receiver.accept(packet(offer, data, 0)))
            assertEquals(ChunkReceipt.DUPLICATE, receiver.accept(packet(offer, data, 0)))
            assertEquals(1, receiver.snapshot(offer.id).confirmedChunks)
            assertEquals(64L, receiver.snapshot(offer.id).confirmedBytes)
            assertEquals(ChunkReceipt.COMPLETED, receiver.accept(packet(offer, data, 1)))
            assertEquals(ChunkReceipt.DUPLICATE, receiver.accept(packet(offer, data, 1)))
            val corrupt = packet(offer, data, 0).also { it[it.lastIndex]++ }
            assertThrows(IllegalArgumentException::class.java) { receiver.accept(corrupt) }
            assertEquals(TransferState.COMPLETED, receiver.snapshot(offer.id).state)
        }
    }
    @Test fun missingChunksArePagedAndIncompleteFilesCannotBeExported() {
        val data = bytes(500); val offer = manifest(data)
        FileTransferEngine(parent()).use { receiver ->
            receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
            listOf(0, 2, 4).forEach { receiver.accept(packet(offer, data, it)) }
            assertEquals(listOf(1, 3), receiver.missingChunks(offer.id, limit = 2))
            assertEquals(listOf(5, 6), receiver.missingChunks(offer.id, fromIndex = 4, limit = 2))
            assertEquals(listOf(7), receiver.missingChunks(offer.id, fromIndex = 7))
            assertEquals(TransferState.TRANSFERRING, receiver.snapshot(offer.id).state)
            assertThrows(IllegalStateException::class.java) { receiver.copyCompletedTo(offer.id, ByteArrayOutputStream()) }
            assertThrows(IllegalArgumentException::class.java) { receiver.missingChunks(offer.id, limit = 129) }
        }
    }
    @Test fun conflictingDuplicateFailsAndCleansOnlyItsPayload() {
        val root = parent(); val data = bytes(100); val offer = manifest(data)
        FileTransferEngine(root).use { receiver ->
            receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
            receiver.accept(packet(offer, data, 0))
            val bad = packet(offer, data, 0).also { it[it.lastIndex]++ }
            val error = assertThrows(TransferIntegrityException::class.java) { receiver.accept(bad) }
            assertEquals(TransferFailure.CONFLICTING_CHUNK, error.failure)
            assertEquals(TransferState.FAILED, receiver.snapshot(offer.id).state)
            assertFalse(payload(root, offer.id).exists()); assertFalse(receipts(root, offer.id).exists())
            assertTrue(File(root, "relay-transfers-v1/${offer.id}/checkpoint.bin").exists())
        }
    }
    @Test fun corruptNewChunkOrWrongExpectedHashNeverCompletes() {
        listOf(false, true).forEach { wrongHash ->
            val root = parent(); val data = bytes(100)
            val original = manifest(data)
            val offer = if (wrongHash) original.copy(sha256 = "00".repeat(32)) else original
            FileTransferEngine(root).use { receiver ->
                receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
                receiver.accept(packet(offer, data, 0).also { if (!wrongHash) it[it.lastIndex]++ })
                assertThrows(TransferIntegrityException::class.java) { receiver.accept(packet(offer, data, 1)) }
                assertEquals(TransferFailure.HASH_MISMATCH, receiver.snapshot(offer.id).failure)
                assertFalse(payload(root, offer.id).exists())
            }
        }
    }
    @Test fun invalidPacketGeometryDoesNotMutateExistingTransfer() {
        val data = bytes(100); val offer = manifest(data)
        FileTransferEngine(parent()).use { receiver ->
            receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
            val invalid = listOf(
                byteArrayOf(1),
                ChunkProtocol.encode(UUID.randomUUID(), 0, 2, bytes(64)),
                ChunkProtocol.encode(offer.id, 0, 3, bytes(64)),
                ChunkProtocol.encode(offer.id, 0, 2, bytes(63)),
                ChunkProtocol.encode(offer.id, 1, 2, bytes(64)))
            invalid.forEach { assertThrows(IllegalArgumentException::class.java) { receiver.accept(it) } }
            assertEquals(0, receiver.snapshot(offer.id).confirmedChunks)
            assertEquals(TransferState.TRANSFERRING, receiver.snapshot(offer.id).state)
        }
    }
    @Test fun sizePoliciesRejectBeforeAllocationAndApplyAtExactBoundary() {
        TransferProfile.entries.forEach { profile ->
            val limits = TransferLimits(directBleBytes = 8, multiHopBleBytes = 4, wifiDirectBytes = 32)
            val maximum = limits.maxBytes(profile).toInt()
            val root = parent()
            FileTransferEngine(root, limits).use { engine ->
                val oversized = manifest(bytes(maximum + 1))
                assertThrows(IllegalArgumentException::class.java) { engine.beginIncoming(oversized, profile) }
                assertThrows(IllegalArgumentException::class.java) { engine.prepareOutgoing(ByteArrayInputStream(bytes(maximum + 1)), profile) }
                assertTrue(engine.snapshots().isEmpty())
                assertEquals(listOf("engine.lock"), File(root, "relay-transfers-v1").list()!!.toList())
                val exact = engine.prepareOutgoing(ByteArrayInputStream(bytes(maximum)), profile)
                assertEquals(maximum.toLong(), exact.manifest.sizeBytes)
            }
        }
    }
    @Test fun unknownLengthImportStopsAtLimitAndDoesNotCloseCallerStream() {
        var read = 0; var closed = false
        val source = object : InputStream() {
            override fun read(): Int { read++; return 7 }
            override fun close() { closed = true }
        }
        FileTransferEngine(parent(), TransferLimits(directBleBytes = 100)).use { engine ->
            assertThrows(IllegalArgumentException::class.java) { engine.prepareOutgoing(source, TransferProfile.DIRECT_BLE) }
            assertEquals(101, read)
            assertFalse(closed); assertTrue(engine.snapshots().isEmpty())
        }
    }
    @Test fun cancelledTransferIsDurableCleansFilesAndCannotResurrect() {
        val root = parent(); val data = bytes(100); val offer = manifest(data)
        FileTransferEngine(root).use { receiver ->
            receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE); receiver.start(offer.id)
            receiver.accept(packet(offer, data, 0))
            assertEquals(TransferState.CANCELLED, receiver.cancel(offer.id).state)
            assertEquals(TransferState.CANCELLED, receiver.cancel(offer.id).state)
            assertFalse(payload(root, offer.id).exists()); assertFalse(receipts(root, offer.id).exists())
            assertThrows(IllegalStateException::class.java) { receiver.start(offer.id) }
            assertThrows(IllegalStateException::class.java) { receiver.accept(packet(offer, data, 1)) }
        }
        FileTransferEngine(root).use { receiver ->
            assertEquals(TransferState.CANCELLED, receiver.beginIncoming(offer, TransferProfile.DIRECT_BLE).state)
            receiver.discard(offer.id)
            assertTrue(receiver.snapshots().isEmpty())
            assertFalse(File(root, "relay-transfers-v1/${offer.id}").exists())
        }
    }
    @Test fun cancellationAndDiscardDoNotTouchOtherTransfersOrCallerFiles() {
        val root = parent(); val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val first = manifest(bytes(100)); val second = manifest(bytes(10))
        FileTransferEngine(root).use { engine ->
            engine.beginIncoming(first, TransferProfile.DIRECT_BLE)
            engine.beginIncoming(second, TransferProfile.DIRECT_BLE)
            assertThrows(IllegalStateException::class.java) { engine.discard(second.id) }
            engine.cancel(first.id); engine.discard(first.id)
            assertTrue(payload(root, second.id).exists())
            assertEquals("keep", unrelated.readText())
            assertEquals(TransferState.PENDING, engine.snapshot(second.id).state)
        }
    }
    @Test fun countAndDiskBudgetsIncludeRetainedCompletedRecords() {
        val root = parent()
        FileTransferEngine(root, TransferLimits(maxStoredTransfers = 1)).use { engine ->
            val offer = manifest(byteArrayOf())
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            assertThrows(IllegalArgumentException::class.java) { engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE) }
            engine.discard(offer.id)
            engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE)
        }
        FileTransferEngine(parent(), TransferLimits(maxReservedBytes = 8192)).use { engine ->
            assertThrows(IllegalArgumentException::class.java) { engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE) }
        }
    }
    @Test fun largeSourceIsStreamedAndChunksRemainBounded() {
        val size = 25L * 1024 * 1024
        var remaining = size; var largestRead = 0
        val source = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 90 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 90.toByte()); remaining -= count
                return count
            }
        }
        FileTransferEngine(parent()).use { engine ->
            val offer = engine.prepareOutgoing(source, TransferProfile.WIFI_DIRECT, TransferLimits.MAX_CHUNK_BYTES).manifest
            assertEquals(size, offer.sizeBytes); assertTrue(largestRead <= TransferLimits.IO_BUFFER_BYTES)
            engine.start(offer.id)
            listOf(0, 500, offer.totalChunks - 1).forEach {
                val frame = engine.readChunk(offer.id, it)
                assertTrue(frame.size <= ChunkProtocol.MAX_PACKET_BYTES)
                assertTrue(ChunkProtocol.decode(frame).payload.all { byte -> byte == 90.toByte() })
            }
        }
    }
    @Test fun outgoingNeedsAllAcksAndMatchingCompletionHash() {
        FileTransferEngine(parent()).use { engine ->
            val offer = engine.prepareOutgoing(ByteArrayInputStream(bytes(100)), TransferProfile.DIRECT_BLE, 64).manifest
            engine.start(offer.id)
            assertThrows(IllegalArgumentException::class.java) { engine.confirmRemoteCompletion(offer.id, offer.sha256) }
            repeat(offer.totalChunks) { engine.acknowledge(offer.id, it); engine.acknowledge(offer.id, it) }
            assertEquals(offer.totalChunks, engine.snapshot(offer.id).confirmedChunks)
            assertEquals(TransferState.TRANSFERRING, engine.snapshot(offer.id).state)
            assertThrows(TransferIntegrityException::class.java) { engine.confirmRemoteCompletion(offer.id, "00".repeat(32)) }
            assertEquals(TransferState.FAILED, engine.snapshot(offer.id).state)
        }
    }
    @Test fun exportDestinationFailureDoesNotDestroyVerifiedPayload() {
        val data = bytes(10); val offer = manifest(data)
        FileTransferEngine(parent()).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            engine.accept(packet(offer, data, 0))
            assertThrows(IOException::class.java) { engine.copyCompletedTo(offer.id, object : OutputStream() {
                override fun write(value: Int) { throw IOException("Destination full") }
            }) }
            assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
            val output = ByteArrayOutputStream(); engine.copyCompletedTo(offer.id, output)
            assertArrayEquals(data, output.toByteArray())
        }
    }
    @Test fun explicitPauseRejectsTrafficAndResumeRetainsProgress() {
        val data = bytes(130); val offer = manifest(data)
        FileTransferEngine(parent()).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE)
            assertThrows(IllegalStateException::class.java) { engine.accept(packet(offer, data, 0)) }
            engine.start(offer.id); engine.accept(packet(offer, data, 0)); engine.pause(offer.id)
            assertEquals(TransferState.PAUSED, engine.snapshot(offer.id).state)
            assertThrows(IllegalStateException::class.java) { engine.accept(packet(offer, data, 1)) }
            assertEquals(listOf(1, 2), engine.missingChunks(offer.id))
            engine.start(offer.id)
            engine.missingChunks(offer.id).forEach { engine.accept(packet(offer, data, it)) }
            assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
        }
    }
    @Test fun failingImportCleansItsRecordWithoutTouchingOtherTransfers() {
        val root = parent()
        FileTransferEngine(root).use { engine ->
            val keep = engine.beginIncoming(manifest(bytes(4)), TransferProfile.DIRECT_BLE)
            val source = object : InputStream() {
                var count = 0
                override fun read(): Int { if (++count > 50) throw IOException("Source unavailable"); return 1 }
            }
            assertThrows(IOException::class.java) { engine.prepareOutgoing(source, TransferProfile.DIRECT_BLE) }
            assertEquals(listOf(keep.manifest.id), engine.snapshots().map { it.manifest.id })
            assertEquals(1, File(root, "relay-transfers-v1").listFiles()!!.count { it.isDirectory })
            assertTrue(payload(root, keep.manifest.id).exists())
        }
    }
    @Test fun concurrentOutOfOrderDuplicatesAreSerializedWithoutLostReceipts() {
        val data = bytes(500); val offer = manifest(data)
        FileTransferEngine(parent()).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val tasks = (0 until offer.totalChunks).flatMap { index ->
                    List(3) { java.util.concurrent.Callable { engine.accept(packet(offer, data, index)) } }
                }.shuffled(Random(3))
                pool.invokeAll(tasks).forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(offer.totalChunks, engine.snapshot(offer.id).confirmedChunks)
                assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
                val output = ByteArrayOutputStream(); engine.copyCompletedTo(offer.id, output)
                assertArrayEquals(data, output.toByteArray())
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun diskBudgetReservesCheckpointReplacementSpaceForEveryRecord() {
        val budget = 2L * TransferStore.CHECKPOINT_RESERVATION_BYTES + 65
        FileTransferEngine(parent(), TransferLimits(maxReservedBytes = budget)).use { engine ->
            val first = engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE)
            // Each one-byte incoming file also reserves a 32-byte receipt hash.
            assertThrows(IllegalArgumentException::class.java) {
                engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE)
            }
            engine.cancel(first.manifest.id)
            engine.beginIncoming(manifest(bytes(1)), TransferProfile.DIRECT_BLE)
            assertEquals(2, engine.snapshots().size)
        }
    }
    @Test fun completedOutgoingPayloadCanBeCopiedWithVerification() {
        val data = bytes(5000)
        FileTransferEngine(parent()).use { engine ->
            val snap = engine.prepareOutgoing(ByteArrayInputStream(data), TransferProfile.DIRECT_BLE)
            engine.start(snap.manifest.id)
            for (i in 0 until snap.manifest.totalChunks) engine.acknowledge(snap.manifest.id, i)
            engine.confirmRemoteCompletion(snap.manifest.id, snap.manifest.sha256)
            val out = ByteArrayOutputStream()
            engine.copyCompletedTo(snap.manifest.id, out)
            assertArrayEquals(data, out.toByteArray())
        }
    }
    @Test fun nonCompletedTransfersCannotBeCopied() {
        FileTransferEngine(parent()).use { engine ->
            val pending = engine.prepareOutgoing(ByteArrayInputStream(bytes(100)), TransferProfile.DIRECT_BLE)
            try {
                engine.copyCompletedTo(pending.manifest.id, ByteArrayOutputStream())
                fail("pending transfer must not be copyable")
            } catch (expected: IllegalStateException) { }
            engine.start(pending.manifest.id)
            try {
                engine.copyCompletedTo(pending.manifest.id, ByteArrayOutputStream())
                fail("in-flight transfer must not be copyable")
            } catch (expected: IllegalStateException) { }
        }
    }
    @Test fun tamperedOutgoingCompletionIsRejected() {
        FileTransferEngine(parent()).use { engine ->
            val snap = engine.prepareOutgoing(ByteArrayInputStream(bytes(100)), TransferProfile.DIRECT_BLE)
            engine.start(snap.manifest.id)
            for (i in 0 until snap.manifest.totalChunks) engine.acknowledge(snap.manifest.id, i)
            assertThrows(TransferIntegrityException::class.java) { engine.confirmRemoteCompletion(snap.manifest.id, "0".repeat(64)) }
        }
    }
}
