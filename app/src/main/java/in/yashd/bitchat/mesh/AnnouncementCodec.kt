package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.LocalIdentity
import `in`.yashd.bitchat.identity.PeerIdentity
import java.io.ByteArrayOutputStream

object AnnouncementCodec {
    private const val TYPE_DISPLAY_NAME = 0x01
    private const val TYPE_ENCRYPTION_PUBLIC = 0x02
    private const val TYPE_SIGNING_PUBLIC = 0x03

    fun encode(identity: LocalIdentity): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeTlv(TYPE_DISPLAY_NAME, identity.displayName.encodeToByteArray())
        output.writeTlv(TYPE_ENCRYPTION_PUBLIC, identity.encryptionPublicKey)
        output.writeTlv(TYPE_SIGNING_PUBLIC, identity.signingPublicKey)
        return output.toByteArray()
    }

    fun decode(packet: MeshPacket): PeerIdentity {
        val fields = mutableMapOf<Int, ByteArray>()
        var index = 0
        while (index + 2 <= packet.payload.size) {
            val type = packet.payload[index].toInt() and 0xff
            val length = packet.payload[index + 1].toInt() and 0xff
            index += 2
            require(index + length <= packet.payload.size)
            fields[type] = packet.payload.copyOfRange(index, index + length)
            index += length
        }
        val displayName = fields.getValue(TYPE_DISPLAY_NAME).decodeToString()
        val encryptionPublic = fields.getValue(TYPE_ENCRYPTION_PUBLIC)
        val signingPublic = fields.getValue(TYPE_SIGNING_PUBLIC)
        require(index == packet.payload.size && packet.recipientId == null)
        val derivedPeerId = `in`.yashd.bitchat.identity.IdentityManager.derivePeerId(encryptionPublic, signingPublic)
        require(derivedPeerId == packet.senderId)
        require(PacketCodec.verify(packet, signingPublic))
        val now = System.currentTimeMillis()
        return PeerIdentity(
            peerId = derivedPeerId,
            displayName = displayName,
            encryptionPublicKey = encryptionPublic,
            signingPublicKey = signingPublic,
            firstSeenMs = now,
            lastSeenMs = now,
            verified = true
        )
    }

    private fun ByteArrayOutputStream.writeTlv(type: Int, value: ByteArray) {
        require(value.size <= 255)
        write(type)
        write(value.size)
        write(value)
    }
}
