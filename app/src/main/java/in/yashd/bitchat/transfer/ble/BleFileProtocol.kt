package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.identity.IdentityManager
import `in`.yashd.bitchat.identity.LocalIdentity
import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.math.BigInteger
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Separate v1 extension; neither chat plaintext encoding nor CryptoBox is changed. */
enum class BleFileCode(val wire: Int) {
    OFFER(1), ACCEPT(2), REJECT(3), CHUNK(4), ACK(5), RESUME(6), CANCEL(7), COMPLETE(8), PAUSE(9)
}

data class BleFileHeader(val code: BleFileCode, val id: UUID, val sender: String, val recipient: String,
    val epoch: String, val acceptance: String, val binding: String, val profile: TransferProfile)
data class OpenBleFileFrame(val header: BleFileHeader, val body: ByteArray)

object BleFileProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 120
    const val MAX_BODY_BYTES = 1200
    const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_BODY_BYTES + 16
    const val ZERO_NONCE = "00000000000000000000000000000000"
    private const val MAGIC = 0x52424631 // RBF1
    private val random = SecureRandom()
    private val p256Order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    fun nonce(): String = ByteArray(16).also(random::nextBytes).hex()
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun manifestBytes(manifest: TransferManifest): ByteArray = ByteBuffer.allocate(44).apply {
        putLong(manifest.sizeBytes); putInt(manifest.chunkBytes); put(manifest.sha256.unhex())
    }.array()
    fun manifest(header: BleFileHeader, bytes: ByteArray, local: LocalIdentity, remote: PeerIdentity): TransferManifest {
        require(bytes.size == 44)
        val input = ByteBuffer.wrap(bytes)
        val result = TransferManifest(header.id, input.long, input.int, ByteArray(32).also(input::get).hex())
        require(result.chunkBytes == TransferLimits.DEFAULT_CHUNK_BYTES)
        require(binding(local, remote, result, header.profile) == header.binding)
        return result
    }
    /** Keyed binding prevents an observer from testing known-file hashes against the header. */
    fun binding(local: LocalIdentity, remote: PeerIdentity, manifest: TransferManifest, profile: TransferProfile): String {
        val fingerprints = listOf(fingerprint(peer(local)), fingerprint(remote)).sorted()
        val secret = agreement(local, remote)
        val key = hmac("relay-ble-file-manifest-v1".toByteArray(), secret)
        return try {
            hmac(key, fingerprints[0].unhex() + fingerprints[1].unhex() +
                ByteBuffer.allocate(17).putLong(manifest.id.mostSignificantBits).putLong(manifest.id.leastSignificantBits)
                    .put(profileByte(profile)).array() + manifestBytes(manifest)).hex()
        } finally { secret.fill(0); key.fill(0) }
    }


    /** A complete signed announcement is self-certifying, not human identity verification. */
    fun fingerprint(peer: PeerIdentity): String {
        require(peer.verified && IdentityManager.derivePeerId(peer.encryptionPublicKey, peer.signingPublicKey) == peer.peerId)
        listOf(peer.encryptionPublicKey, peer.signingPublicKey).forEach {
            val key = IdentityManager.decodePublicKey(it) as ECPublicKey
            require(key.params.order == p256Order && key.params.cofactor == 1 && key.params.curve.field.fieldSize == 256)
        }
        return digest(ByteBuffer.allocate(4).putShort(peer.encryptionPublicKey.size.toShort())
            .putShort(peer.signingPublicKey.size.toShort()).array() + peer.encryptionPublicKey + peer.signingPublicKey)
    }
    fun peer(local: LocalIdentity) = PeerIdentity(local.peerId, local.displayName, local.encryptionPublicKey.copyOf(),
        local.signingPublicKey.copyOf(), 0, 0, true)

    /** Header is visible to relays but authenticated by BOTH AEAD and the mesh signature.
     * Full endpoint key fingerprints are in the KDF/AAD, not merely 64-bit routing IDs.
     */
    fun seal(local: LocalIdentity, remote: PeerIdentity, header: BleFileHeader, body: ByteArray): MeshPacket {
        require(header.sender == local.peerId && header.recipient == remote.peerId && body.size <= MAX_BODY_BYTES)
        val iv = ByteArray(12).also(random::nextBytes)
        val encoded = encodeHeader(header, iv, body.size + 16)
        val senderFingerprint = fingerprint(peer(local)); val recipientFingerprint = fingerprint(remote)
        val key = derive(local, remote, header, senderFingerprint, recipientFingerprint)
        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(encoded + senderFingerprint.unhex() + recipientFingerprint.unhex()); doFinal(body)
            }
        } finally { key.fill(0) }
        return PacketCodec.createSigned(if (header.code == BleFileCode.CHUNK) PacketType.FILE_CHUNK else PacketType.FILE_CONTROL,
            local.peerId, remote.peerId, encoded + ciphertext, local.signingPrivateKey,
            ttl = if (header.profile == TransferProfile.DIRECT_BLE) 0 else 5)
    }

    fun open(local: LocalIdentity, remote: PeerIdentity, packet: MeshPacket, now: Long): OpenBleFileFrame {
        require(packet.senderId == remote.peerId && packet.recipientId == local.peerId)
        require(packet.timestampMs in now - 600_000..now + 60_000)
        require(PacketCodec.verify(packet, remote.signingPublicKey))
        val header = header(packet.payload)
        require(header.sender == packet.senderId && header.recipient == packet.recipientId)
        require((header.code == BleFileCode.CHUNK) == (packet.type == PacketType.FILE_CHUNK))
        require(packet.type == PacketType.FILE_CHUNK || packet.type == PacketType.FILE_CONTROL)
        require(header.profile != TransferProfile.DIRECT_BLE || packet.ttl == 0)
        val senderFingerprint = fingerprint(remote); val recipientFingerprint = fingerprint(peer(local))
        val key = derive(local, remote, header, senderFingerprint, recipientFingerprint)
        val body = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.payload.copyOfRange(104, 116)))
                updateAAD(packet.payload.copyOfRange(0, HEADER_BYTES) + senderFingerprint.unhex() + recipientFingerprint.unhex())
                doFinal(packet.payload, HEADER_BYTES, packet.payload.size - HEADER_BYTES)
            }
        } finally { key.fill(0) }
        return OpenBleFileFrame(header, body)
    }

    /** Strict bounded header parsing is also used by forwarders, without decrypting files. */
    fun header(bytes: ByteArray): BleFileHeader {
        require(bytes.size in HEADER_BYTES + 16..MAX_FRAME_BYTES)
        val input = ByteBuffer.wrap(bytes)
        require(input.int == MAGIC && input.get().toInt() == VERSION)
        val codeValue = input.get().toInt()
        val code = BleFileCode.entries.firstOrNull { it.wire == codeValue } ?: error("Unknown file code")
        val profile = when (input.get().toInt()) { 0 -> TransferProfile.DIRECT_BLE; 1 -> TransferProfile.MULTI_HOP_BLE; else -> error("Invalid file profile") }
        require(input.get().toInt() == HEADER_BYTES)
        val id = UUID(input.long, input.long)
        fun hex(count: Int) = ByteArray(count).also(input::get).hex()
        val sender = hex(8); val recipient = hex(8); val epoch = hex(16); val acceptance = hex(16); val binding = hex(32)
        require(sender != recipient && epoch != ZERO_NONCE)
        if (code == BleFileCode.OFFER) require(acceptance == ZERO_NONCE)
        input.position(116)
        val length = input.int
        require(length in 16..MAX_BODY_BYTES + 16 && bytes.size == HEADER_BYTES + length)
        return BleFileHeader(code, id, sender, recipient, epoch, acceptance, binding, profile)
    }
    private fun encodeHeader(header: BleFileHeader, iv: ByteArray, length: Int): ByteArray {
        require(header.sender.matches(Regex("[0-9a-f]{16}")) && header.recipient.matches(Regex("[0-9a-f]{16}")))
        require(listOf(header.epoch, header.acceptance).all { it.matches(Regex("[0-9a-f]{32}")) })
        require(header.binding.matches(Regex("[0-9a-f]{64}")))
        return ByteBuffer.allocate(HEADER_BYTES).apply {
            putInt(MAGIC); put(VERSION.toByte()); put(header.code.wire.toByte()); put(profileByte(header.profile)); put(HEADER_BYTES.toByte())
            putLong(header.id.mostSignificantBits); putLong(header.id.leastSignificantBits)
            put(header.sender.unhex()); put(header.recipient.unhex()); put(header.epoch.unhex()); put(header.acceptance.unhex())
            put(header.binding.unhex()); put(iv); putInt(length)
        }.array().also { BleFileProtocol.header(it + ByteArray(length)) }
    }
    private fun derive(local: LocalIdentity, remote: PeerIdentity, header: BleFileHeader, sender: String, recipient: String): ByteArray {
        val secret = agreement(local, remote)
        // RFC 5869 extract + one 32-byte expand block. Direction and file context separated from chat keys.
        val prk = hmac(header.epoch.unhex(), secret)
        return try {
            hmac(prk, "relay-ble-file-v1".toByteArray() + sender.unhex() + recipient.unhex() +
                header.id.toString().toByteArray() + header.binding.unhex() + header.acceptance.unhex() + byteArrayOf(1))
        } finally { secret.fill(0); prk.fill(0) }
    }
    private fun agreement(local: LocalIdentity, remote: PeerIdentity): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(IdentityManager.decodePrivateKey(local.encryptionPrivateKey))
        doPhase(IdentityManager.decodePublicKey(remote.encryptionPublicKey), true); generateSecret()
    }
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    private fun profileByte(profile: TransferProfile): Byte = when (profile) {
        TransferProfile.DIRECT_BLE -> 0; TransferProfile.MULTI_HOP_BLE -> 1
        TransferProfile.WIFI_DIRECT -> error("Wi-Fi Direct is not a BLE file profile")
    }
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
