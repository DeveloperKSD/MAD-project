package `in`.yashd.bitchat

import `in`.yashd.bitchat.identity.IdentityManager
import `in`.yashd.bitchat.mesh.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.*
import org.junit.Test

class MeshProtocolTest {
    private fun keys() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun signedPacketRoundTripRejectsTamperingAndUnknownVersions() {
        val signer = keys()
        val packet = PacketCodec.createSigned(PacketType.MESSAGE, "01".repeat(8), null, "hello".toByteArray(), signer.private.encoded)
        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded)
        assertTrue(PacketCodec.verify(decoded, signer.public.encoded))
        assertFalse(PacketCodec.verify(decoded.copy(payload = "forged".toByteArray()), signer.public.encoded))
        assertFalse(PacketCodec.verify(decoded.copy(signature = null), signer.public.encoded))
        assertTrue(PacketCodec.verify(decoded.copy(ttl = 4), signer.public.encoded))
        assertThrows(IllegalArgumentException::class.java) { PacketCodec.decode(encoded.copyOf().apply { this[0] = 1 }) }
        assertThrows(IllegalArgumentException::class.java) { PacketCodec.decode(encoded + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            PacketCodec.createSigned(PacketType.MESSAGE, "01".repeat(8), null, ByteArray(4097), signer.private.encoded)
        }
    }

    @Test fun peerIdBindsBothKeysAndSubstitutedSigningKeyFails() {
        val encryption = keys()
        val signing = keys()
        val attacker = keys()
        val id = IdentityManager.derivePeerId(encryption.public.encoded, signing.public.encoded)
        assertNotEquals(id, IdentityManager.derivePeerId(encryption.public.encoded, attacker.public.encoded))
        fun tlv(type: Int, bytes: ByteArray) = byteArrayOf(type.toByte(), bytes.size.toByte()) + bytes
        val payload = tlv(1, "Alice".toByteArray()) + tlv(2, encryption.public.encoded) + tlv(3, attacker.public.encoded)
        val forged = PacketCodec.createSigned(PacketType.ANNOUNCE, id, null, payload, attacker.private.encoded)
        assertThrows(IllegalArgumentException::class.java) { AnnouncementCodec.decode(forged) }
    }
}
