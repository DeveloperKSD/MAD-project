package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.identity.IdentityManager
import `in`.yashd.bitchat.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BleFileProtocolTest {
    private val alice = testIdentity("Alice"); private val bob = testIdentity("Bob")
    private val a = BleFileProtocol.peer(alice); private val b = BleFileProtocol.peer(bob)
    private val manifest = TransferManifest(UUID.randomUUID(), 10, 1024, "00".repeat(32))
    private fun header(code: BleFileCode = BleFileCode.OFFER) = BleFileHeader(code, manifest.id, alice.peerId, bob.peerId,
        BleFileProtocol.nonce(), BleFileProtocol.ZERO_NONCE, BleFileProtocol.binding(alice, b, manifest, TransferProfile.DIRECT_BLE), TransferProfile.DIRECT_BLE)
    @Test fun signedFileEnvelopeRoundTripIsEncryptedAndKeepsChatEncodingSeparate() {
        val plaintext = "PRIVATE BINARY FILE CONTENT".toByteArray()
        val h = header(); val packet = BleFileProtocol.seal(alice, b, h, plaintext)
        assertEquals(PacketType.FILE_CONTROL, packet.type); assertEquals(0, packet.ttl)
        assertFalse(String(packet.payload, Charsets.ISO_8859_1).contains("PRIVATE BINARY"))
        assertTrue(PacketCodec.verify(packet, a.signingPublicKey))
        assertArrayEquals(plaintext, BleFileProtocol.open(bob, a, packet, System.currentTimeMillis()).body)
        val text = PacketCodec.createSigned(PacketType.MESSAGE, alice.peerId, null, "hello".toByteArray(), alice.signingPrivateKey)
        assertEquals(2, PacketCodec.encode(text)[1].toInt())
        assertArrayEquals("hello".toByteArray(), PacketCodec.decode(PacketCodec.encode(text)).payload)
    }
    @Test fun wrongKeysSignatureSenderRecipientAndCiphertextAreRejected() {
        val packet = BleFileProtocol.seal(alice, b, header(), byteArrayOf(1, 2))
        val attacker = testIdentity("Attacker")
        val bad = listOf(packet.copy(signature = byteArrayOf(1)), packet.copy(senderId = attacker.peerId),
            packet.copy(recipientId = attacker.peerId), packet.copy(payload = packet.payload.copyOf().also { it[it.lastIndex]++ }))
        bad.forEach { value -> assertThrows(Exception::class.java) { BleFileProtocol.open(bob, a, value, System.currentTimeMillis()) } }
        assertThrows(Exception::class.java) { BleFileProtocol.open(attacker, a, packet, System.currentTimeMillis()) }
        assertThrows(Exception::class.java) { BleFileProtocol.open(bob, BleFileProtocol.peer(attacker), packet, System.currentTimeMillis()) }
    }
    @Test fun authenticatedHeaderCannotBeReboundEvenWhenResignedBySender() {
        val packet = BleFileProtocol.seal(alice, b, header(), byteArrayOf(1))
        listOf(5, 8, 24, 32, 40, 56, 72, 104).forEach { offset ->
            val bytes = packet.payload.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val changed = PacketCodec.createSigned(packet.type, packet.senderId, packet.recipientId, bytes, alice.signingPrivateKey, ttl = 0)
            assertThrows(Exception::class.java) { BleFileProtocol.open(bob, a, changed, System.currentTimeMillis()) }
        }
    }
    @Test fun replayAgeInvalidLengthsVersionsAndWrongPacketClassAreRejected() {
        val packet = BleFileProtocol.seal(alice, b, header(), byteArrayOf(1))
        for (size in 0 until BleFileProtocol.HEADER_BYTES + 16) {
            assertThrows(Exception::class.java) { BleFileProtocol.header(packet.payload.copyOf(size)) }
        }
        assertThrows(Exception::class.java) { BleFileProtocol.header(packet.payload + 0) }
        assertThrows(Exception::class.java) { BleFileProtocol.header(packet.payload.copyOf().also { it[4] = 99 }) }
        assertThrows(Exception::class.java) { BleFileProtocol.open(bob, a, packet, System.currentTimeMillis() + 700_000) }
        val wrong = PacketCodec.createSigned(PacketType.FILE_CHUNK, alice.peerId, bob.peerId, packet.payload, alice.signingPrivateKey, ttl = 0)
        assertThrows(Exception::class.java) { BleFileProtocol.open(bob, a, wrong, System.currentTimeMillis()) }
    }
    @Test fun manifestAndCapabilityBoundsExcludeWifiAndOversizedMetadata() {
        val h = header()
        assertEquals(h.binding, BleFileProtocol.binding(bob, a, manifest, TransferProfile.DIRECT_BLE))
        assertNotEquals(h.binding, BleFileProtocol.binding(alice, BleFileProtocol.peer(testIdentity("third")), manifest, TransferProfile.DIRECT_BLE))
        assertEquals(manifest, BleFileProtocol.manifest(h, BleFileProtocol.manifestBytes(manifest), bob, a))
        assertThrows(Exception::class.java) { BleFileProtocol.manifest(h, ByteArray(45), bob, a) }
        assertThrows(Exception::class.java) { BleFileProtocol.binding(alice, b, manifest, TransferProfile.WIFI_DIRECT) }
        assertThrows(Exception::class.java) { BleFileProtocol.seal(alice, b, h, ByteArray(1201)) }
        assertThrows(Exception::class.java) { BleFileProtocol.fingerprint(a.copy(verified = false)) }
        assertThrows(Exception::class.java) { BleFileProtocol.fingerprint(a.copy(signingPublicKey = b.signingPublicKey)) }
    }
    @Test fun repeatedEncryptionUsesFreshIvAndFullFingerprintBindsBothPublicKeys() {
        val h = header(); val body = byteArrayOf(0, -1, 1)
        val first = BleFileProtocol.seal(alice, b, h, body); val second = BleFileProtocol.seal(alice, b, h, body)
        assertFalse(first.payload.contentEquals(second.payload))
        assertEquals(64, BleFileProtocol.fingerprint(a).length)
        assertNotEquals(BleFileProtocol.fingerprint(a), BleFileProtocol.fingerprint(b))
        assertTrue(IdentityManager.verify(a.signingPublicKey, PacketCodec.signaturePreimage(first.copy(flags = first.flags and 2.inv())), first.signature!!))
    }
}
