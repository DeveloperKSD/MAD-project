package `in`.yashd.bitchat.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/** Only encrypted events leave createMessage; neither keys nor plaintext are retained here. */
data class Nip17GiftWraps(val recipientCopy: NostrEvent, val senderCopy: NostrEvent, val rumorId: String)

/**
 * Blocking one-to-one kind-14 text packaging; call on a worker thread.
 * Keys are caller-owned. No identity storage, transport, logging, or persistence.
 */
class Nip17 {
    private val signer = NostrEventSigner()
    private val schnorr = SchnorrSignature()
    private val cipher = Nip44()
    private val random = SecureRandom()

    fun createMessage(
        senderSecret: ByteArray,
        recipientPublicKey: String,
        content: String,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000
    ): Nip17GiftWraps {
        require(recipientPublicKey.isLowercaseHex(32)) { "Invalid recipient" }
        val sender = schnorr.publicKey(senderSecret).lowercaseHex()
        val rumor = UnsignedNostrEvent(sender, createdAtSeconds, 14,
            listOf(listOf("p", recipientPublicKey)), content)
        val json = NostrEventCodec.encodeRumor(rumor)
        return Nip17GiftWraps(
            wrap(json, senderSecret, recipientPublicKey, createdAtSeconds),
            wrap(json, senderSecret, sender, createdAtSeconds),
            NostrEventCodec.calculateId(rumor)
        )
    }

    /** Empty-bodied kind-14 receipt rumor with the same authenticated gift-wrap pipeline. */
    fun createReceipt(
        senderSecret: ByteArray,
        recipientPublicKey: String,
        originalRumorId: String,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000
    ): Nip17GiftWraps {
        require(recipientPublicKey.isLowercaseHex(32)) { "Invalid recipient" }
        require(originalRumorId.matches(Regex("[0-9a-f]{64}"))) { "Invalid original rumor id" }
        val sender = schnorr.publicKey(senderSecret).lowercaseHex()
        val rumor = UnsignedNostrEvent(sender, createdAtSeconds, 14,
            listOf(listOf("p", recipientPublicKey),
                listOf(DeliveryReceipts.TAG, DeliveryReceipts.DELIVERED, originalRumorId)), "")
        val json = NostrEventCodec.encodeRumor(rumor)
        return Nip17GiftWraps(
            wrap(json, senderSecret, recipientPublicKey, createdAtSeconds),
            wrap(json, senderSecret, sender, createdAtSeconds),
            NostrEventCodec.calculateId(rumor)
        )
    }

    /**
     * expectedRecipient is the original kind-14 recipient, including for a sender copy.
     * expectedSender is the conversation peer who authored the message. Neither is inferred
     * from an untrusted rumor. Returns plaintext only after all authentication succeeds.
     */
    fun decodeMessage(
        giftWrap: NostrEvent,
        recipientSecret: ByteArray,
        expectedSender: String,
        expectedRecipient: String
    ): UnsignedNostrEvent = try {
        require(expectedSender.isLowercaseHex(32) && expectedRecipient.isLowercaseHex(32)) {
            "Invalid expected identity"
        }
        val local = schnorr.publicKey(recipientSecret).lowercaseHex()
        require(local == expectedRecipient || local == expectedSender) { "Incorrect recipient" }
        val outer = giftWrap.unsignedEvent
        require(outer.kind == 1059 && signer.verify(giftWrap)) { "Invalid gift wrap" }
        require(singleRecipient(outer) == local) { "Incorrect gift wrap recipient" }
        val seal = NostrEventCodec.decode(cipher.decrypt(outer.content, recipientSecret, outer.pubkey.hexBytes()))
        require(seal.unsignedEvent.kind == 13 && seal.unsignedEvent.tags.isEmpty() && signer.verify(seal)) {
            "Invalid seal"
        }
        require(seal.unsignedEvent.pubkey == expectedSender) { "Unexpected sender" }
        val rumor = NostrEventCodec.decodeRumor(cipher.decrypt(
            seal.unsignedEvent.content, recipientSecret, seal.unsignedEvent.pubkey.hexBytes()
        ))
        require(rumor.kind == 14) { "Unsupported message kind" }
        require(rumor.pubkey == seal.unsignedEvent.pubkey) { "Rumor sender does not match seal" }
        require(singleRecipient(rumor) == expectedRecipient) { "Incorrect rumor recipient" }
        // Recipient copies validate this point during ECDH, but sender copies use
        // self-ECDH and must also validate the original recipient independently.
        Secp256k1.pubkeyParse(byteArrayOf(2) + expectedRecipient.hexBytes())
        rumor
    } catch (error: UnsupportedNip44VersionException) {
        throw error
    } catch (_: Exception) {
        // JSON parser/provider errors can contain decrypted input. Do not expose their causes.
        throw IllegalArgumentException("Invalid NIP-17 private message")
    }

    /** Discover participants only inside authenticated layers, then reuse strict decoding. */
    fun decodeIncoming(giftWrap: NostrEvent, recipientSecret: ByteArray): UnsignedNostrEvent = try {
        val local = schnorr.publicKey(recipientSecret).lowercaseHex()
        require(giftWrap.unsignedEvent.kind == 1059 && signer.verify(giftWrap))
        require(singleRecipient(giftWrap.unsignedEvent) == local)
        val seal = NostrEventCodec.decode(cipher.decrypt(giftWrap.unsignedEvent.content,
            recipientSecret, giftWrap.unsignedEvent.pubkey.hexBytes()))
        require(seal.unsignedEvent.kind == 13 && seal.unsignedEvent.tags.isEmpty() && signer.verify(seal))
        val rumor = NostrEventCodec.decodeRumor(cipher.decrypt(seal.unsignedEvent.content,
            recipientSecret, seal.unsignedEvent.pubkey.hexBytes()))
        decodeMessage(giftWrap, recipientSecret, seal.unsignedEvent.pubkey, singleRecipient(rumor))
    } catch (_: Exception) { throw IllegalArgumentException("Invalid NIP-17 private message") }

    private fun singleRecipient(event: UnsignedNostrEvent): String {
        val recipients = event.tags.filter { it.firstOrNull() == "p" }
        require(recipients.size == 1 && recipients.single().size >= 2) { "Expected one recipient" }
        return recipients.single()[1]
    }

    private fun wrap(rumor: String, senderSecret: ByteArray, recipient: String, now: Long): NostrEvent {
        val seal = signer.createSignedEvent(senderSecret, 13,
            cipher.encrypt(rumor, senderSecret, recipient.hexBytes()), createdAtSeconds = randomizedTime(now))
        val temporary = ByteArray(32)
        try {
            do { random.nextBytes(temporary) } while (!Secp256k1.secKeyVerify(temporary))
            return signer.createSignedEvent(temporary, 1059,
                cipher.encrypt(NostrEventCodec.encode(seal), temporary, recipient.hexBytes()),
                listOf(listOf("p", recipient)), randomizedTime(now))
        } finally { temporary.fill(0) }
    }

    private fun randomizedTime(now: Long): Long {
        require(now >= 0) { "Invalid timestamp" }
        val window = minOf(now, 172800L).toInt()
        return now - random.nextInt(window + 1)
    }
}
