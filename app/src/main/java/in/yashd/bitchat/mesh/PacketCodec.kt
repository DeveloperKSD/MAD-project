package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.IdentityManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

object PacketCodec {
    private const val VERSION = 2
    private const val FLAG_HAS_RECIPIENT = 0x01
    private const val FLAG_HAS_SIGNATURE = 0x02
    const val MAX_PAYLOAD_BYTES = 4096
    private val random = SecureRandom()

    fun createSigned(
        type: PacketType,
        senderId: String,
        recipientId: String?,
        payload: ByteArray,
        signingPrivateKey: ByteArray,
        ttl: Int = 5
    ): MeshPacket {
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val packet = MeshPacket(
            version = VERSION,
            type = type,
            ttl = ttl,
            flags = if (recipientId == null) 0 else FLAG_HAS_RECIPIENT,
            timestampMs = System.currentTimeMillis(),
            packetId = randomId(),
            senderId = senderId,
            recipientId = recipientId,
            payload = payload,
            signature = null
        )
        val signature = IdentityManager.sign(signingPrivateKey, signaturePreimage(packet))
        return packet.copy(flags = packet.flags or FLAG_HAS_SIGNATURE, signature = signature)
    }

    fun encode(packet: MeshPacket): ByteArray {
        require(packet.version == VERSION && packet.ttl in 0..5 && packet.packetId.size == 16)
        require(packet.senderId.hexToBytes().size == 8 && (packet.recipientId == null || packet.recipientId.hexToBytes().size == 8))
        require(packet.payload.size <= MAX_PAYLOAD_BYTES && (packet.signature?.size ?: 0) <= 80)
        val recipientBytes = packet.recipientId?.hexToBytes()
        val signature = packet.signature
        val size = 1 + 1 + 1 + 1 + 8 + 16 + 8 +
            (recipientBytes?.size ?: 0) + 4 + packet.payload.size + 2 + (signature?.size ?: 0)
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(packet.version.toByte())
            put(packet.type.value.toByte())
            put(packet.ttl.toByte())
            put(packet.flags.toByte())
            putLong(packet.timestampMs)
            put(packet.packetId)
            put(packet.senderId.hexToBytes())
            if (recipientBytes != null) put(recipientBytes)
            putInt(packet.payload.size)
            put(packet.payload)
            putShort((signature?.size ?: 0).toShort())
            if (signature != null) put(signature)
        }.array()
    }

    fun decode(bytes: ByteArray): MeshPacket {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        require(version == VERSION)
        val type = PacketType.fromValue(buffer.get().toInt() and 0xff) ?: error("unknown packet type")
        val ttl = buffer.get().toInt() and 0xff
        require(ttl in 0..5)
        val flags = buffer.get().toInt() and 0xff
        require(flags and (FLAG_HAS_RECIPIENT or FLAG_HAS_SIGNATURE).inv() == 0)
        val timestampMs = buffer.long
        val packetId = ByteArray(16).also(buffer::get)
        val sender = ByteArray(8).also(buffer::get).toHex()
        val recipient = if (flags and FLAG_HAS_RECIPIENT != 0) {
            ByteArray(8).also(buffer::get).toHex()
        } else {
            null
        }
        val payloadLength = buffer.int
        require(payloadLength in 0..MAX_PAYLOAD_BYTES)
        val payload = ByteArray(payloadLength).also(buffer::get)
        val signatureLength = buffer.short.toInt() and 0xffff
        require(signatureLength <= 80 && (signatureLength > 0) == (flags and FLAG_HAS_SIGNATURE != 0))
        val signature = if (signatureLength > 0) ByteArray(signatureLength).also(buffer::get) else null
        require(!buffer.hasRemaining())
        return MeshPacket(version, type, ttl, flags, timestampMs, packetId, sender, recipient, payload, signature)
    }

    fun verify(packet: MeshPacket, signingPublicKey: ByteArray): Boolean {
        val signature = packet.signature ?: return false
        return IdentityManager.verify(signingPublicKey, signaturePreimage(packet.copy(flags = packet.flags and FLAG_HAS_SIGNATURE.inv())), signature)
    }

    fun signaturePreimage(packet: MeshPacket): ByteArray {
        val unsignedFlags = packet.flags and FLAG_HAS_SIGNATURE.inv()
        val recipientBytes = packet.recipientId?.hexToBytes()
        val size = 1 + 1 + 1 + 8 + 16 + 8 + (recipientBytes?.size ?: 0) + 4 + packet.payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(packet.version.toByte())
            put(packet.type.value.toByte())
            put(unsignedFlags.toByte())
            putLong(packet.timestampMs)
            put(packet.packetId)
            put(packet.senderId.hexToBytes())
            if (recipientBytes != null) put(recipientBytes)
            putInt(packet.payload.size)
            put(packet.payload)
        }.array()
    }

    fun randomId(): ByteArray = ByteArray(16).also(random::nextBytes)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
