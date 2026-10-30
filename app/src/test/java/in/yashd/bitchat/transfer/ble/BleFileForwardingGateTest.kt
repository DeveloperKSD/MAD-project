package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BleFileForwardingGateTest {
    private val a = testIdentity("a")
    private val b = testIdentity("b")
    private val peers = listOf(a, b).associate { it.peerId to BleFileProtocol.peer(it) }
    private val manifest = TransferManifest(UUID.randomUUID(), 1024, 1024, "00".repeat(32))
    private val h = BleFileHeader(BleFileCode.OFFER, manifest.id, a.peerId, b.peerId, BleFileProtocol.nonce(),
        BleFileProtocol.ZERO_NONCE, BleFileProtocol.binding(a, peers.getValue(b.peerId), manifest, TransferProfile.MULTI_HOP_BLE), TransferProfile.MULTI_HOP_BLE)
    private fun packet(header: BleFileHeader): MeshPacket {
        val source = if (header.sender == a.peerId) a else b
        return BleFileProtocol.seal(source, peers.getValue(header.recipient), header, byteArrayOf())
    }
    @Test fun fileBytesRequireSignedReceiverAcceptanceAndOnlyOneRouteCanOwnDevice() {
        val gate = BleFileForwardingGate({ peers[it] }); val now = System.currentTimeMillis()
        assertFalse(gate.allow(packet(h.copy(code = BleFileCode.CHUNK)), now))
        assertTrue(gate.allow(packet(h), now)); assertTrue(gate.busy(now))
        assertFalse(gate.allow(packet(h.copy(id = UUID.randomUUID())), now + 2000))
        val challenge = BleFileProtocol.nonce()
        assertFalse(gate.allow(packet(h.copy(code = BleFileCode.CHUNK, acceptance = challenge)), now))
        val accept = h.copy(code = BleFileCode.ACCEPT, sender = b.peerId, recipient = a.peerId, acceptance = challenge)
        assertTrue(gate.allow(packet(accept), now))
        assertTrue(gate.allow(packet(h.copy(code = BleFileCode.CHUNK, acceptance = challenge)), now))
        assertFalse(gate.allow(packet(h.copy(code = BleFileCode.CHUNK)), now))
        assertFalse(gate.allow(packet(h.copy(code = BleFileCode.ACK, acceptance = challenge)), now))
    }
    @Test fun cancellationResetAndExpiryDropQueuedChunksAndRetireOldEpoch() {
        val dropped = mutableListOf<UUID>(); val gate = BleFileForwardingGate({ peers[it] }, { dropped.add(it) })
        val now = System.currentTimeMillis()
        assertTrue(gate.allow(packet(h), now))
        assertTrue(gate.allow(packet(h.copy(code = BleFileCode.CANCEL)), now))
        assertFalse(gate.allow(packet(h), now)); assertEquals(listOf(h.id), dropped)
        val fresh = h.copy(epoch = BleFileProtocol.nonce())
        assertTrue(gate.allow(packet(fresh), now + 2000))
        gate.reset(now + 3000)
        assertFalse(gate.allow(packet(fresh), now + 4000))
        val next = fresh.copy(epoch = BleFileProtocol.nonce())
        assertTrue(gate.allow(packet(next), now + 5000))
        assertFalse(gate.busy(now + 36000)); assertEquals(3, dropped.size)
    }
    @Test fun forgedUnknownDirectAndMalformedPacketsCannotReserveForwardingResources() {
        val gate = BleFileForwardingGate({ peers[it] }); val now = System.currentTimeMillis()
        val valid = packet(h)
        assertFalse(gate.allow(valid.copy(signature = byteArrayOf(1)), now))
        assertFalse(BleFileForwardingGate({ null }).allow(valid, now))
        assertFalse(gate.allow(valid.copy(payload = ByteArray(100)), now))
        assertFalse(gate.allow(packet(h.copy(profile = TransferProfile.DIRECT_BLE)), now))
        assertFalse(gate.busy(now))
    }
}
