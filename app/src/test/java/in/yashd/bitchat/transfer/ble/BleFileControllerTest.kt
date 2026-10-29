package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class BleFileControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun data() = ByteArray(6000) { (it * 41).toByte() }
    private fun phase(controller: BleFileController) = controller.state.value.single().phase
    private fun start(n: BleFileTestNetwork, bytes: ByteArray = data()): UUID {
        val id = n.a.offer(n.bob.peerId, ByteArrayInputStream(bytes), if (n.multiHop) TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE)
        n.drain(); n.b.accept(id); return id
    }
    @Test fun noFileDataOrReceiverAllocationBeforeAuthenticatedExplicitAcceptance() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        n.drain()
        assertEquals(BleFilePhase.OFFERED, phase(n.a)); assertEquals(BleFilePhase.AWAITING_ACCEPTANCE, phase(n.b))
        assertFalse(n.sent.any { it.type == PacketType.FILE_CHUNK })
        assertFalse(n.payload(n.bob, id).exists())
        assertThrows(Exception::class.java) { n.b.copyCompletedTo(id, ByteArrayOutputStream()) }
        n.b.accept(id); n.drain()
        assertEquals(BleFilePhase.COMPLETED, phase(n.a)); assertEquals(BleFilePhase.COMPLETED, phase(n.b))
        val result = ByteArrayOutputStream(); n.b.copyCompletedTo(id, result)
        assertArrayEquals(data(), result.toByteArray())
    }
    @Test fun directAndThreeNodeForwardedBinaryTransfersVerifyTheSameBytes() {
        listOf(false, true).forEach { multi -> BleFileTestNetwork(temp.newFolder(), multi).use { n ->
            val id = start(n); n.drain()
            assertEquals(BleFilePhase.COMPLETED, phase(n.a)); assertEquals(BleFilePhase.COMPLETED, phase(n.b))
            val result = ByteArrayOutputStream(); n.b.copyCompletedTo(id, result)
            assertArrayEquals(data(), result.toByteArray())
            assertTrue(n.sent.filter { it.type == PacketType.FILE_CHUNK }.all { it.payload.size < 4096 })
        } }
    }
    @Test fun droppedChunkAndAckRetransmitWithBoundedWindowWithoutClaimingGattDelivery() = BleFileTestNetwork(temp.newFolder()).use { n ->
        var lostChunk = false; var lostAck = false
        n.shouldDrop = { packet ->
            val code = BleFileProtocol.header(packet.payload).code
            if (code == BleFileCode.CHUNK && !lostChunk) { lostChunk = true; true }
            else if (code == BleFileCode.ACK && !lostAck) { lostAck = true; true } else false
        }
        val id = start(n); n.drain()
        assertEquals(BleFilePhase.SENDING, phase(n.a))
        repeat(3) { n.tick() }
        assertEquals(BleFilePhase.COMPLETED, phase(n.a))
        assertEquals(2, n.dropped)
        val output = ByteArrayOutputStream(); n.b.copyCompletedTo(id, output); assertArrayEquals(data(), output.toByteArray())
    }
    @Test fun backpressureAndMissingCompletionHaveFiniteRetriesThenPause() = BleFileTestNetwork(temp.newFolder()).use { n ->
        start(n); n.admit = false; n.drain()
        // Existing ACCEPT can arrive, but no newly queued data is admitted.
        repeat(8) { n.tick() }
        assertEquals(BleFilePhase.PAUSED, phase(n.a))
        val count = n.sent.count { it.senderId == n.alice.peerId }; repeat(5) { n.tick() }
        assertEquals(count, n.sent.count { it.senderId == n.alice.peerId })
    }
    @Test fun lostVerifiedCompletionIsRecoveredByAuthenticatedProbe() = BleFileTestNetwork(temp.newFolder()).use { n ->
        var lost = false
        n.shouldDrop = { packet ->
            if (BleFileProtocol.header(packet.payload).code == BleFileCode.COMPLETE && packet.senderId == n.bob.peerId && !lost) { lost = true; true } else false
        }
        start(n); n.drain()
        assertEquals(BleFilePhase.SENDING, phase(n.a))
        n.tick()
        assertEquals(BleFilePhase.COMPLETED, phase(n.a))
    }
    @Test fun rejectedOrCancelledOffersNeverSendFileBytesAndCancellationPersists() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        n.drain(); n.b.reject(id); n.drain()
        assertEquals(BleFilePhase.REJECTED, phase(n.a)); assertTrue(n.b.state.value.isEmpty())
        assertFalse(n.sent.any { it.type == PacketType.FILE_CHUNK })
        n.a.discard(id); n.now += 11000
        val second = n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        n.drain(); n.b.accept(second)
        n.deliverOne() // ACCEPT triggers at most WINDOW DATA frames.
        n.a.cancel(second); n.drain()
        assertEquals(BleFilePhase.CANCELLED, phase(n.a)); assertEquals(BleFilePhase.CANCELLED, phase(n.b))
        n.restart(n.alice); n.restart(n.bob)
        assertEquals(BleFilePhase.CANCELLED, phase(n.a)); assertEquals(BleFilePhase.CANCELLED, phase(n.b))
    }
    @Test fun restartRequiresFreshAcceptanceAndOldChunksOrAcksCannotResumeIt() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = start(n)
        n.deliverOne() // ACCEPT -> first DATA window.
        val stale = n.wire.toList()
        n.wire.clear(); n.restart(n.alice); n.restart(n.bob)
        assertEquals(BleFilePhase.PAUSED, phase(n.a)); assertEquals(BleFilePhase.PAUSED, phase(n.b))
        stale.forEach { n.b.receive(it) }
        assertEquals(BleFilePhase.PAUSED, phase(n.b))
        n.a.resume(id); n.drain()
        assertEquals(BleFilePhase.AWAITING_ACCEPTANCE, phase(n.b))
        stale.forEach { n.b.receive(it) }
        assertEquals(BleFilePhase.AWAITING_ACCEPTANCE, phase(n.b))
        n.b.accept(id); n.drain()
        assertEquals(BleFilePhase.COMPLETED, phase(n.a))
    }
    @Test fun receiverMissingIndicesAfterDiskRecoveryOverridePreviouslyAcknowledgedChunks() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = start(n)
        n.deliverOne(); n.deliverOne(); n.deliverOne() // ACCEPT and two chunks; sender ACKs still queued.
        while (n.wire.isNotEmpty() && n.wire.first().type == PacketType.FILE_CONTROL) n.deliverOne()
        n.wire.clear(); n.a.linkLost(); n.b.linkLost()
        n.nodes.remove(n.bob.peerId)!!.close()
        java.io.RandomAccessFile(n.payload(n.bob, id), "rw").use { it.seek(0); it.write(99) }
        n.restart(n.alice); n.restart(n.bob)
        n.a.resume(id); n.drain(); n.b.accept(id); n.drain()
        assertEquals(BleFilePhase.COMPLETED, phase(n.a))
    }
    @Test fun unknownPeerForgedAcceptanceWrongManifestAndChangedKeysAreRejected() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        n.drain()
        val offered = n.sent.first(); val offer = BleFileProtocol.header(offered.payload)
        val attacker = testIdentity("Bob")
        val forgedHeader = offer.copy(code = BleFileCode.ACCEPT, sender = n.bob.peerId, recipient = n.alice.peerId, acceptance = BleFileProtocol.nonce())
        val fake = PacketCodec.createSigned(PacketType.FILE_CONTROL, n.bob.peerId, n.alice.peerId,
            offered.payload, attacker.signingPrivateKey, ttl = 0)
        n.a.receive(fake)
        assertEquals(BleFilePhase.OFFERED, phase(n.a))
        val badBody = ByteArray(4).also { java.nio.ByteBuffer.wrap(it).putInt(-1) }
        n.a.receive(BleFileProtocol.seal(n.bob, BleFileProtocol.peer(n.alice), forgedHeader, badBody))
        assertEquals(BleFilePhase.OFFERED, phase(n.a))
        assertFalse(n.sent.any { it.type == PacketType.FILE_CHUNK })
        n.known[n.bob.peerId] = BleFileProtocol.peer(attacker)
        assertThrows(Exception::class.java) { n.a.resume(id) }
        repeat(6) { n.tick() }
        assertEquals(BleFilePhase.PAUSED, phase(n.a))
    }
    @Test fun oneTransferPerDeviceAndNoSilentOversizedBleFallback() = BleFileTestNetwork(temp.newFolder()).use { n ->
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(ByteArray(1024 * 1024 + 1)), TransferProfile.DIRECT_BLE) }
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(ByteArray(250 * 1024 + 1)), TransferProfile.MULTI_HOP_BLE) }
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.WIFI_DIRECT) }
        n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE) }
        Unit
    }
    @Test fun bindingStorageFailureFailsClosedBeforeAnyDataAdmission() = BleFileTestNetwork(temp.newFolder()).use { n ->
        n.stores.getValue(n.alice.peerId).fail = true
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE) }
        assertTrue(n.sent.isEmpty())
        assertThrows(Exception::class.java) { n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE) }
        Unit
    }
    @Test fun noAcceptanceRetriesAreBoundedAndIncomingOfferExpires() = BleFileTestNetwork(temp.newFolder()).use { n ->
        n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
        n.drain(); repeat(8) { n.tick() }
        assertEquals(BleFilePhase.PAUSED, phase(n.a))
        assertTrue(n.b.state.value.isEmpty())
        assertFalse(n.sent.any { it.type == PacketType.FILE_CHUNK })
    }
    @Test fun emptyFileCompletesOnlyAfterAcceptanceAndIdleReceiverPauses() {
        BleFileTestNetwork(temp.newFolder()).use { n ->
            start(n, byteArrayOf()); n.drain()
            assertEquals(BleFilePhase.COMPLETED, phase(n.a))
            assertEquals(BleFilePhase.COMPLETED, phase(n.b))
        }
        BleFileTestNetwork(temp.newFolder()).use { n ->
            start(n); n.wire.clear(); n.now += 61_000; n.b.tick()
            assertEquals(BleFilePhase.PAUSED, phase(n.b))
        }
    }

    @Test fun damagedCompletedPayloadNeverProducesAnotherVerifiedCompletion() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = start(n); n.drain()
        n.nodes.remove(n.bob.peerId)!!.close()
        java.io.RandomAccessFile(n.payload(n.bob, id), "rw").use { it.write(99) }
        n.restart(n.bob)
        assertEquals(BleFilePhase.FAILED, phase(n.b))
        val old = BleFileProtocol.header(n.sent.first().payload)
        val replay = BleFileProtocol.seal(n.alice, BleFileProtocol.peer(n.bob), old.copy(epoch = BleFileProtocol.nonce()),
            BleFileProtocol.manifestBytes(n.stores.getValue(n.alice.peerId).rows.single().manifest))
        val before = n.sent.size
        n.b.receive(replay)
        assertEquals(before, n.sent.size)
        assertThrows(Exception::class.java) { n.b.copyCompletedTo(id, ByteArrayOutputStream()) }
        Unit
    }

    @Test fun delayedTickerCannotExtendOfferConsentOrReceivingChallenge() {
        BleFileTestNetwork(temp.newFolder()).use { n ->
            val id = n.a.offer(n.bob.peerId, ByteArrayInputStream(data()), TransferProfile.DIRECT_BLE)
            n.drain(); n.now += 31_000; n.b.accept(id)
            assertTrue(n.b.state.value.isEmpty())
            assertFalse(n.payload(n.bob, id).exists())
            assertFalse(n.sent.any { it.type == PacketType.FILE_CHUNK })
        }
        BleFileTestNetwork(temp.newFolder()).use { n ->
            start(n); n.deliverOne() // Sender queues chunks after ACCEPT.
            val chunk = n.wire.first(); n.now += 61_000
            n.b.receive(chunk) // No tick was allowed to enforce the timeout first.
            assertEquals(BleFilePhase.PAUSED, phase(n.b))
            assertEquals(0L, n.b.state.value.single().confirmedBytes)
        }
    }

    @Test fun durableRemoteCompletionSurvivesCrashBeforeBindingCommit() = BleFileTestNetwork(temp.newFolder()).use { n ->
        start(n); n.drain()
        val store = n.stores.getValue(n.alice.peerId)
        store.rows = store.rows.map { it.copy(phase = BleFilePhase.SENDING) }
        n.restart(n.alice)
        assertEquals(BleFilePhase.COMPLETED, phase(n.a))
    }

    @Test fun corruptExportUpdatesCompletedCardToFailed() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = start(n); n.drain()
        java.io.RandomAccessFile(n.payload(n.bob, id), "rw").use { it.write(99) }
        assertThrows(Exception::class.java) { n.b.copyCompletedTo(id, ByteArrayOutputStream()) }
        assertEquals(BleFilePhase.FAILED, phase(n.b))
    }
    @Test fun rejectingResumeCleansPartialPayloadAndKeepsADiscardableRecord() = BleFileTestNetwork(temp.newFolder()).use { n ->
        val id = start(n); n.deliverOne(); n.deliverOne()
        n.wire.clear(); n.a.linkLost(); n.b.linkLost()
        n.a.resume(id); n.drain()
        assertEquals(BleFilePhase.AWAITING_ACCEPTANCE, phase(n.b))
        n.b.reject(id); n.drain()
        assertEquals(BleFilePhase.REJECTED, phase(n.b))
        assertFalse(n.payload(n.bob, id).exists())
        n.b.discard(id); assertTrue(n.b.state.value.isEmpty())
    }

}
