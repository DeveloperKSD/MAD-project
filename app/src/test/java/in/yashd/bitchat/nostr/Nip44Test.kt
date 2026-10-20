package `in`.yashd.bitchat.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.*
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class Nip44Test {
    private val nip = Nip44()
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/nostr/nip44.vectors.json")!!.readText()).jsonObject["v2"]!!.jsonObject
    private val valid = vectors["valid"]!!.jsonObject
    private val invalid = vectors["invalid"]!!.jsonObject
    private fun JsonElement.field(name: String) = jsonObject[name]!!.jsonPrimitive.content
    private fun JsonElement.hex(name: String) = field(name).hexBytes()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).lowercaseHex()
    private fun reject(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun publishedConversationKeys() {
        valid["get_conversation_key"]!!.jsonArray.forEach {
            assertEquals(it.field("conversation_key"), nip.conversationKey(it.hex("sec1"), it.hex("pub2")).lowercaseHex())
        }
        invalid["get_conversation_key"]!!.jsonArray.forEach { reject { nip.conversationKey(it.hex("sec1"), it.hex("pub2")) } }
    }

    @Test fun publishedMessageKeysAndPadding() {
        val group = valid["get_message_keys"]!!
        group.jsonObject["keys"]!!.jsonArray.forEach {
            val keys = nip.messageKeys(group.hex("conversation_key"), it.hex("nonce"))
            assertEquals(it.field("chacha_key"), keys.copyOfRange(0, 32).lowercaseHex())
            assertEquals(it.field("chacha_nonce"), keys.copyOfRange(32, 44).lowercaseHex())
            assertEquals(it.field("hmac_key"), keys.copyOfRange(44, 76).lowercaseHex())
        }
        valid["calc_padded_len"]!!.jsonArray.forEach {
            assertEquals(it.jsonArray[1].jsonPrimitive.long, Nip44.paddedLength(it.jsonArray[0].jsonPrimitive.long))
        }
        assertEquals(4294967296L, Nip44.paddedLength(4294967295L))
        reject { Nip44.paddedLength(0) }; reject { Nip44.paddedLength(4294967296L) }
        reject { nip.messageKeys(ByteArray(31), ByteArray(32)) }
        reject { nip.messageKeys(ByteArray(32), ByteArray(31)) }
    }

    @Test fun publishedEncryptionAndDecryption() {
        valid["encrypt_decrypt"]!!.jsonArray.forEach {
            val key = nip.conversationKey(it.hex("sec1"), SchnorrSignature().publicKey(it.hex("sec2")))
            assertEquals(it.field("conversation_key"), key.lowercaseHex())
            val payload = nip.encryptWithNonce(it.field("plaintext"), key, it.hex("nonce"))
            assertEquals(it.field("payload"), payload)
            val reverse = nip.conversationKey(it.hex("sec2"), SchnorrSignature().publicKey(it.hex("sec1")))
            assertEquals(it.field("plaintext"), nip.decryptWithKey(payload, reverse))
        }
        valid["encrypt_decrypt_long_msg"]!!.jsonArray.forEach {
            val plaintext = it.field("pattern").repeat(it.field("repeat").toInt())
            assertEquals(it.field("plaintext_sha256"), hash(plaintext))
            val payload = nip.encryptWithNonce(plaintext, it.hex("conversation_key"), it.hex("nonce"))
            assertEquals(it.field("payload_sha256"), hash(payload))
            assertEquals(plaintext, nip.decryptWithKey(payload, it.hex("conversation_key")))
        }
    }

    @Test fun publishedInvalidPayloads() {
        invalid["decrypt"]!!.jsonArray.forEach { reject { nip.decryptWithKey(it.field("payload"), it.hex("conversation_key")) } }
        // Historical vectors reject >65535. Current NIP-44 explicitly permits extended lengths.
        invalid["encrypt_msg_lengths"]!!.jsonArray.forEach {
            val length = it.jsonPrimitive.int
            if (length == 0 || length > Nip44.MAX_PLAINTEXT_BYTES) reject {
                nip.encryptWithNonce("a".repeat(length), ByteArray(32), ByteArray(32))
            }
        }
    }

    @Test fun currentSpecificationExtendedLengthChecksums() {
        val key = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexBytes()
        val nonce = ByteArray(32).also { it[31] = 1 }
        mapOf(65535 to "6d8c2810d1e870fbaa1f0a0937126cca837a15f9260e27060c331d70a3c0bc84",
            65536 to "b7b4edb36ba92e267d322d56d9aebc22e7fa96ff52e3c12adc07f07a43cbc616",
            65537 to "eeb7c7c5373894ea2c1547cfd3ccb15d5a0b2d619da852e5c79df792dcc9e435").forEach { (size, checksum) ->
            val plaintext = "a".repeat(size)
            val payload = nip.encryptWithNonce(plaintext, key, nonce)
            assertEquals(checksum, hash(payload))
            assertEquals(plaintext, nip.decryptWithKey(payload, key))
        }
    }

    private fun identity(): ByteArray = ByteArray(32).also {
        do { SecureRandom().nextBytes(it) } while (!Secp256k1.secKeyVerify(it))
    }

    @Test fun independentIdentitiesUnicodeRandomnessAndTampering() {
        val a = identity(); val b = identity(); val c = identity()
        val ap = SchnorrSignature().publicKey(a); val bp = SchnorrSignature().publicKey(b)
        val text = "नमस्ते 👋 café 日本語\u0000"
        val first = nip.encrypt(text, a, bp)
        val second = nip.encrypt(text, a, bp)
        assertNotEquals(first, second)
        assertEquals(text, nip.decrypt(first, b, ap))
        assertEquals(text, nip.decrypt(nip.encrypt(text, b, ap), a, bp))
        reject { nip.decrypt(first, c, ap) }
        reject { nip.decrypt(first, b, SchnorrSignature().publicKey(c)) }
        for (index in listOf(0, 1, 33, Base64.decode(first).lastIndex)) {
            val modified = Base64.decode(first).also { it[index] = (it[index].toInt() xor 1).toByte() }
            reject { nip.decrypt(Base64.toBase64String(modified), b, ap) }
        }
        for (bad in listOf("", "#future", "Ag==", first + "\n", first.dropLast(1), " ".repeat(132), "A".repeat(Nip44.MAX_PAYLOAD_CHARS + 1))) {
            reject { nip.decrypt(bad, b, ap) }
        }
        assertThrows(UnsupportedNip44VersionException::class.java) { nip.decrypt("#future", b, ap) }
        reject { nip.encrypt("\uD800", a, bp) }
    }

    /** Valid MACs deliberately exercise unpadding/UTF-8 checks rather than MAC rejection. */
    @Test fun authenticatedMalformedContent() {
        val key = ByteArray(32)
        val nonce = ByteArray(32)
        fun payload(padded: ByteArray): String {
            val keys = nip.messageKeys(key, nonce)
            val cipher = org.bouncycastle.crypto.engines.ChaCha7539Engine()
            cipher.init(true, org.bouncycastle.crypto.params.ParametersWithIV(
                org.bouncycastle.crypto.params.KeyParameter(keys.copyOfRange(0, 32)), keys.copyOfRange(32, 44)))
            val ciphertext = ByteArray(padded.size)
            cipher.processBytes(padded, 0, padded.size, ciphertext, 0)
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(keys.copyOfRange(44, 76), "HmacSHA256"))
            mac.update(nonce)
            return Base64.toBase64String(byteArrayOf(2) + nonce + ciphertext + mac.doFinal(ciphertext))
        }
        for (length in listOf(0, 1, 65535, Nip44.MAX_PLAINTEXT_BYTES + 1, -1)) {
            val padded = ByteArray(38)
            java.nio.ByteBuffer.wrap(padded).putShort(0).putInt(length)
            assertThrows(IllegalArgumentException::class.java) { nip.decryptWithKey(payload(padded), key) }
        }
        for (size in listOf(34, 65, 67)) { // length 33 needs exactly 66 bytes including prefix
            val padded = ByteArray(size).also { it[1] = 33 }
            assertThrows(IllegalArgumentException::class.java) { nip.decryptWithKey(payload(padded), key) }
        }
        val invalidUtf8 = ByteArray(34).also { it[1] = 1; it[2] = 0xff.toByte() }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            nip.decryptWithKey(payload(invalidUtf8), key)
        }
    }

    @Test fun malformedKeyLengthsAndVersions() {
        val secret = ByteArray(32).also { it[31] = 1 }
        val publicKey = SchnorrSignature().publicKey(secret)
        for (length in listOf(0, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) { nip.encrypt("a", ByteArray(length), publicKey) }
            assertThrows(IllegalArgumentException::class.java) { nip.encrypt("a", secret, ByteArray(length)) }
            assertThrows(IllegalArgumentException::class.java) {
                nip.encryptWithNonce("a", ByteArray(length), ByteArray(32))
            }
            assertThrows(IllegalArgumentException::class.java) {
                nip.encryptWithNonce("a", ByteArray(32), ByteArray(length))
            }
        }
        val unknown = Base64.decode(nip.encrypt("a", secret, publicKey)).also { it[0] = 3 }
        assertThrows(UnsupportedNip44VersionException::class.java) {
            nip.decrypt(Base64.toBase64String(unknown), secret, publicKey)
        }
        val bytes = javaClass.getResource("/nostr/nip44.vectors.json")!!.readBytes()
        assertEquals("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040",
            MessageDigest.getInstance("SHA-256").digest(bytes).lowercaseHex())
    }

    @Test fun plaintextByteBoundaries() {
        val key = ByteArray(32); val nonce = ByteArray(32)
        for (size in listOf(1, 32, 33, 255, 256, 257, 65535, 65536, 65537, 100000, Nip44.MAX_PLAINTEXT_BYTES)) {
            val text = "a".repeat(size)
            assertEquals(text, nip.decryptWithKey(nip.encryptWithNonce(text, key, nonce), key))
        }
        reject { nip.encryptWithNonce("", key, nonce) }
        reject { nip.encryptWithNonce("a".repeat(Nip44.MAX_PLAINTEXT_BYTES + 1), key, nonce) }
        reject { nip.encryptWithNonce("🦄".repeat(Nip44.MAX_PLAINTEXT_BYTES / 4 + 1), key, nonce) }
    }
}
