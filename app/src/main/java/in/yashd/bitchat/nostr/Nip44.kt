package `in`.yashd.bitchat.nostr

import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.Secp256k1Exception
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.encoders.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class UnsupportedNip44VersionException : IllegalArgumentException("Unsupported NIP-44 version")

/**
 * NIP-44 v2 payload encryption. Call on a worker thread. Keys are caller-owned, never retained.
 * The caller MUST verify the containing NIP-01 event before calling decrypt; this API only
 * authenticates the encrypted payload. No NIP-17 packaging or transport is performed here.
 * A 1 MiB UTF-8 limit bounds mobile memory usage, including the extended length prefix.
 */
class Nip44 {
    private val random = SecureRandom()

    fun encrypt(plaintext: String, secretKey: ByteArray, recipientPublicKey: ByteArray): String {
        val key = conversationKey(secretKey, recipientPublicKey)
        return try {
            encryptWithNonce(plaintext, key, ByteArray(32).also(random::nextBytes))
        } finally { key.fill(0) }
    }

    fun decrypt(payload: String, secretKey: ByteArray, senderPublicKey: ByteArray): String {
        // Reject oversized/unsupported input before doing elliptic-curve work.
        validatePayloadSize(payload)
        val key = conversationKey(secretKey, senderPublicKey)
        return try { decryptWithKey(payload, key) } finally { key.fill(0) }
    }

    internal fun conversationKey(secret: ByteArray, publicKey: ByteArray): ByteArray {
        require(secret.size == 32 && Secp256k1.secKeyVerify(secret)) { "Invalid Nostr secret key" }
        require(publicKey.size == 32) { "Invalid Nostr public key" }
        val point = try {
            // Even-y lift of the BIP-340 x-only key. ecdh() hashes its output, so use tweak-mul.
            Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(byteArrayOf(2) + publicKey), secret)
        } catch (_: Secp256k1Exception) {
            throw IllegalArgumentException("Invalid Nostr public key")
        }
        val sharedX = point.copyOfRange(1, 33)
        return try { hmac("nip44-v2".toByteArray(Charsets.UTF_8), sharedX) }
        finally { sharedX.fill(0); point.fill(0) }
    }

    internal fun messageKeys(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 32) { "Invalid NIP-44 key or nonce length" }
        return ByteArray(76).also {
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters.skipExtractParameters(key, nonce))
                generateBytes(it, 0, it.size)
            }
        }
    }

    // Deterministic entry point is internal solely for published test vectors.
    internal fun encryptWithNonce(plaintext: String, key: ByteArray, nonce: ByteArray): String {
        require(key.size == 32 && nonce.size == 32) { "Invalid NIP-44 key or nonce length" }
        require(plaintext.length <= MAX_PLAINTEXT_BYTES) { "Invalid plaintext length" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(plaintext))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        try {
            require(bytes.size in 1..MAX_PLAINTEXT_BYTES) { "Invalid plaintext length" }
            val prefix = if (bytes.size < 65536) 2 else 6
            val padded = ByteArray(prefix + paddedLength(bytes.size.toLong()).toInt())
            try {
                val buffer = ByteBuffer.wrap(padded)
                if (prefix == 6) buffer.putShort(0).putInt(bytes.size) else buffer.putShort(bytes.size.toShort())
                buffer.put(bytes)
                val keys = messageKeys(key, nonce)
                try {
                    val ciphertext = crypt(keys, padded)
                    return Base64.toBase64String(byteArrayOf(2) + nonce + ciphertext + authentication(keys, nonce, ciphertext))
                } finally { keys.fill(0) }
            } finally { padded.fill(0) }
        } finally { bytes.fill(0); encoded.clear(); while (encoded.hasRemaining()) encoded.put(0) }
    }

    internal fun decryptWithKey(payload: String, key: ByteArray): String {
        validatePayloadSize(payload)
        val data = try { Base64.decode(payload) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid NIP-44 base64")
        }
        require(data.size >= 99 && Base64.toBase64String(data) == payload) { "Invalid NIP-44 payload" }
        if (data[0] != 2.toByte()) throw UnsupportedNip44VersionException()
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val keys = messageKeys(key, nonce)
        try {
            require(MessageDigest.isEqual(authentication(keys, nonce, ciphertext), data.copyOfRange(data.size - 32, data.size))) {
                "Invalid NIP-44 authentication"
            }
            val padded = crypt(keys, ciphertext)
            try {
                val buffer = ByteBuffer.wrap(padded)
                var length = (buffer.short.toInt() and 65535).toLong()
                val prefix = if (length == 0L) 6 else 2
                if (prefix == 6) {
                    length = buffer.int.toLong() and 0xffffffffL
                    require(length >= 65536) { "Invalid NIP-44 padding" }
                }
                require(length in 1..MAX_PLAINTEXT_BYTES.toLong() &&
                    padded.size.toLong() == prefix + paddedLength(length)) { "Invalid NIP-44 padding" }
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(padded, prefix, length.toInt())).toString()
            } finally { padded.fill(0) }
        } finally { keys.fill(0) }
    }

    private fun validatePayloadSize(payload: String) {
        if (payload.startsWith("#")) throw UnsupportedNip44VersionException()
        require(payload.length in 132..MAX_PAYLOAD_CHARS) { "Invalid NIP-44 payload length" }
    }

    private fun crypt(keys: ByteArray, input: ByteArray): ByteArray {
        val key = keys.copyOfRange(0, 32)
        try {
            val cipher = ChaCha7539Engine() // RFC 8439, 12-byte nonce, initial counter zero.
            cipher.init(true, ParametersWithIV(KeyParameter(key), keys.copyOfRange(32, 44)))
            return ByteArray(input.size).also { cipher.processBytes(input, 0, input.size, it, 0) }
        } finally { key.fill(0) }
    }

    private fun authentication(keys: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = keys.copyOfRange(44, 76)
        return try { hmac(key, nonce, ciphertext) } finally { key.fill(0) }
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    companion object {
        const val MAX_PLAINTEXT_BYTES = 1_048_576
        const val MAX_PAYLOAD_CHARS = 1_398_196 // base64(65 + 6 + 1 MiB), rounded up

        internal fun paddedLength(length: Long): Long {
            require(length in 1..0xffffffffL) { "Invalid plaintext length" }
            if (length <= 32) return 32
            val nextPower = java.lang.Long.highestOneBit(length - 1) shl 1
            val chunk = if (nextPower <= 256) 32 else nextPower / 8
            return chunk * ((length - 1) / chunk + 1)
        }
    }
}
