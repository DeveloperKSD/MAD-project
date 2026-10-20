package `in`.yashd.bitchat.nostr

/** Creates and verifies NIP-01 events without retaining the caller's private key. */
class NostrEventSigner(private val schnorr: SchnorrSignature = SchnorrSignature()) {
    fun createSignedEvent(
        secretKey: ByteArray,
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAtSeconds: Long = System.currentTimeMillis() / 1000
    ): NostrEvent = sign(
        UnsignedNostrEvent(
            pubkey = schnorr.publicKey(secretKey).lowercaseHex(),
            createdAtSeconds = createdAtSeconds,
            kind = kind,
            tags = tags,
            content = content
        ),
        secretKey
    )

    fun sign(event: UnsignedNostrEvent, secretKey: ByteArray): NostrEvent {
        require(event.pubkey == schnorr.publicKey(secretKey).lowercaseHex()) {
            "Event public key does not match the signing key"
        }
        // Snapshot caller-owned lists so editing them later cannot change the signed event.
        val snapshot = event.copy(tags = event.tags.map { it.toList() })
        val id = NostrEventCodec.calculateId(snapshot)
        return NostrEvent(id, snapshot, schnorr.sign(id.hexBytes(), secretKey).lowercaseHex())
    }

    /** Both the content hash and the signature must match. Malformed event values return false. */
    fun verify(event: NostrEvent): Boolean {
        if (!event.id.isLowercaseHex(32) || !event.sig.isLowercaseHex(64)) return false
        return try {
            val calculatedId = NostrEventCodec.calculateId(event.unsignedEvent)
            calculatedId == event.id && schnorr.verify(
                calculatedId.hexBytes(),
                event.unsignedEvent.pubkey.hexBytes(),
                event.sig.hexBytes()
            )
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
