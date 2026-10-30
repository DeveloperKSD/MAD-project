package `in`.yashd.bitchat.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class FileTransferRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val data = ByteArray(207) { it.toByte() }
    private fun offer() = TransferManifest(UUID.randomUUID(), data.size.toLong(), 64, TransferStore.sha256(data).hex())
    private fun directory(parent: File, id: UUID) = File(parent, "relay-transfers-v1/$id")
    private fun file(parent: File, id: UUID, name: String = "payload.bin") = File(directory(parent, id), name)
    private fun packet(offer: TransferManifest, index: Int) = ChunkProtocol.encode(offer.id, index, offer.totalChunks,
        data.copyOfRange(index * offer.chunkBytes, index * offer.chunkBytes + offer.payloadBytes(index)))
    private fun partial(parent: File, offer: TransferManifest) {
        FileTransferEngine(parent).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            engine.accept(packet(offer, 2)); engine.accept(packet(offer, 0))
        }
    }
    @Test fun closeAndRestartResumeOnlyMissingChunks() {
        val parent = temp.newFolder(); val offer = offer()
        partial(parent, offer)
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.PAUSED, engine.snapshot(offer.id).state)
            assertEquals(2, engine.snapshot(offer.id).confirmedChunks)
            assertEquals(listOf(1, 3), engine.missingChunks(offer.id))
            assertThrows(IllegalStateException::class.java) { engine.accept(packet(offer, 1)) }
            engine.start(offer.id)
            assertEquals(ChunkReceipt.DUPLICATE, engine.accept(packet(offer, 2)))
            engine.accept(packet(offer, 1)); engine.accept(packet(offer, 3))
        }
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
            val output = ByteArrayOutputStream(); engine.copyCompletedTo(offer.id, output)
            assertArrayEquals(data, output.toByteArray())
        }
    }
    @Test fun previouslyStoredChunkIsRecheckedAndCorruptionBecomesMissingOnRestart() {
        val parent = temp.newFolder(); val offer = offer()
        partial(parent, offer)
        RandomAccessFile(file(parent, offer.id), "rw").use { it.seek(130); it.write(55) }
        FileTransferEngine(parent).use { engine ->
            assertEquals(1, engine.snapshot(offer.id).confirmedChunks)
            assertEquals(listOf(1, 2, 3), engine.missingChunks(offer.id))
            engine.start(offer.id)
            engine.missingChunks(offer.id).forEach { engine.accept(packet(offer, it)) }
            assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
        }
    }
    @Test fun uncommittedDataAndTornCheckpointAreNotAcknowledgedAfterCrash() {
        val parent = temp.newFolder(); val offer = offer()
        partial(parent, offer)
        // Simulate death after payload write but before committing a receipt bit.
        RandomAccessFile(file(parent, offer.id), "rw").use { it.seek(64); it.write(data, 64, 64); it.fd.sync() }
        TransferStore(parent).use { store ->
            val record = store.load(store.directory(offer.id), TransferLimits())
            record.state = TransferState.TRANSFERRING
            store.save(record) // Simulate the last committed state of a process killed in flight.
        }
        file(parent, offer.id, "checkpoint.tmp").writeText("torn checkpoint")
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.PAUSED, engine.snapshot(offer.id).state)
            assertEquals(listOf(1, 3), engine.missingChunks(offer.id))
            engine.start(offer.id)
            assertEquals(ChunkReceipt.STORED, engine.accept(packet(offer, 1)))
            assertEquals(ChunkReceipt.COMPLETED, engine.accept(packet(offer, 3)))
        }
    }
    @Test fun corruptOrUnsupportedCheckpointIsPreservedAndCannotBeReplaced() {
        listOf(false, true).forEach { unsupported ->
            val parent = temp.newFolder(); val offer = offer()
            partial(parent, offer)
            val checkpoint = file(parent, offer.id, "checkpoint.bin")
            val changed = checkpoint.readBytes()
            if (unsupported) {
                changed[4] = 99
                val digest = TransferStore.sha256(changed.copyOfRange(0, changed.size - 32))
                digest.copyInto(changed, changed.size - 32)
            } else changed[10] = (changed[10].toInt() xor 32).toByte()
            checkpoint.writeBytes(changed)
            val payload = file(parent, offer.id).readBytes()
            FileTransferEngine(parent).use { engine ->
                assertTrue(engine.snapshots().isEmpty())
                assertEquals(offer.id.toString(), engine.recoveryIssues.single().directory)
                assertThrows(IllegalArgumentException::class.java) { engine.beginIncoming(offer, TransferProfile.DIRECT_BLE) }
            }
            assertArrayEquals(changed, checkpoint.readBytes())
            assertArrayEquals(payload, file(parent, offer.id).readBytes())
        }
    }
    @Test fun missingPayloadFailsWithoutRecreatingAnEmptyReplacement() {
        val parent = temp.newFolder(); val offer = offer()
        partial(parent, offer)
        assertTrue(file(parent, offer.id).delete())
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.FAILED, engine.snapshot(offer.id).state)
            assertEquals(TransferFailure.MISSING_DATA, engine.snapshot(offer.id).failure)
            assertFalse(file(parent, offer.id).exists())
        }
    }
    @Test fun missingReceiptsAreAStorageFailureNotSilentlyRecreated() {
        val parent = temp.newFolder(); val offer = offer()
        FileTransferEngine(parent).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            assertTrue(file(parent, offer.id, "receipts.bin").delete())
            assertThrows(IOException::class.java) { engine.accept(packet(offer, 0)) }
            assertEquals(TransferFailure.STORAGE, engine.snapshot(offer.id).failure)
            assertFalse(file(parent, offer.id).exists())
            assertFalse(file(parent, offer.id, "receipts.bin").exists())
        }
    }
    @Test fun threadInterruptionPausesWithoutLosingCommittedProgress() {
        val parent = temp.newFolder(); val offer = offer()
        FileTransferEngine(parent).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            engine.accept(packet(offer, 0))
            Thread.currentThread().interrupt()
            try { assertThrows(InterruptedIOException::class.java) { engine.accept(packet(offer, 1)) } }
            finally { Thread.interrupted() }
            assertEquals(TransferState.PAUSED, engine.snapshot(offer.id).state)
            assertEquals(1, engine.snapshot(offer.id).confirmedChunks)
            assertTrue(file(parent, offer.id).exists())
            engine.start(offer.id)
            engine.missingChunks(offer.id).forEach { engine.accept(packet(offer, it)) }
            assertEquals(TransferState.COMPLETED, engine.snapshot(offer.id).state)
        }
    }
    @Test fun outgoingSnapshotSurvivesOriginalSourceChangesAndRestoresAckTracking() {
        val parent = temp.newFolder()
        val original = temp.newFile().apply { writeBytes(data) }
        val offer: TransferManifest
        FileTransferEngine(parent).use { engine ->
            offer = original.inputStream().use { engine.prepareOutgoing(it, TransferProfile.DIRECT_BLE, 64).manifest }
            original.writeText("changed by caller")
            engine.start(offer.id)
            assertArrayEquals(data.copyOfRange(0, 64), ChunkProtocol.decode(engine.readChunk(offer.id, 0)).payload)
            engine.acknowledge(offer.id, 0)
        }
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.PAUSED, engine.snapshot(offer.id).state)
            assertEquals(listOf(1, 2, 3), engine.missingChunks(offer.id))
            engine.start(offer.id)
            assertArrayEquals(data.copyOfRange(192, 207), ChunkProtocol.decode(engine.readChunk(offer.id, 3)).payload)
        }
    }
    @Test fun damagedOutgoingSnapshotFailsOnRecovery() {
        val parent = temp.newFolder()
        val offer = FileTransferEngine(parent).use { it.prepareOutgoing(ByteArrayInputStream(data), TransferProfile.DIRECT_BLE).manifest }
        RandomAccessFile(file(parent, offer.id), "rw").use { it.write(55) }
        FileTransferEngine(parent).use { engine ->
            assertEquals(TransferState.FAILED, engine.snapshot(offer.id).state)
            assertEquals(TransferFailure.HASH_MISMATCH, engine.snapshot(offer.id).failure)
            assertFalse(file(parent, offer.id).exists())
        }
    }
    @Test fun completedPayloadIsReverifiedBeforeExport() {
        val parent = temp.newFolder(); val offer = offer()
        FileTransferEngine(parent).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.start(offer.id)
            repeat(offer.totalChunks) { engine.accept(packet(offer, it)) }
            RandomAccessFile(file(parent, offer.id), "rw").use { it.write(55) }
            val output = ByteArrayOutputStream()
            assertThrows(TransferIntegrityException::class.java) { engine.copyCompletedTo(offer.id, output) }
            assertEquals(0, output.size())
            assertEquals(TransferState.FAILED, engine.snapshot(offer.id).state)
        }
    }
    @Test fun privatePermissionsAndExclusiveOwnershipPreventTwoWriters() {
        val parent = temp.newFolder(); val engine = FileTransferEngine(parent)
        val offer = engine.prepareOutgoing(ByteArrayInputStream(data), TransferProfile.DIRECT_BLE).manifest
        assertThrows(IOException::class.java) { FileTransferEngine(parent) }
        val forbidden = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        listOf(File(parent, "relay-transfers-v1"), directory(parent, offer.id), file(parent, offer.id),
            file(parent, offer.id, "checkpoint.bin")).forEach {
            assertTrue(Files.getPosixFilePermissions(it.toPath()).intersect(forbidden).isEmpty())
        }
        engine.close(); engine.close()
        assertThrows(IllegalStateException::class.java) { engine.snapshots() }
        FileTransferEngine(parent).use { assertEquals(TransferState.PENDING, it.snapshot(offer.id).state) }
    }
    @Test fun unexpectedFilesAndSymlinksAreNeverDeletedOrFollowed() {
        val parent = temp.newFolder(); val offer = offer()
        FileTransferEngine(parent).use { engine ->
            engine.beginIncoming(offer, TransferProfile.DIRECT_BLE); engine.cancel(offer.id)
            val extra = File(directory(parent, offer.id), "do-not-delete").apply { writeText("keep") }
            assertThrows(IllegalArgumentException::class.java) { engine.discard(offer.id) }
            assertEquals("keep", extra.readText())
        }
        val otherParent = temp.newFolder(); val other = offer()
        partial(otherParent, other)
        val outside = temp.newFile().apply { writeText("outside") }
        assertTrue(file(otherParent, other.id).delete())
        Files.createSymbolicLink(file(otherParent, other.id).toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { FileTransferEngine(otherParent) }
        assertEquals("outside", outside.readText())
    }
}
