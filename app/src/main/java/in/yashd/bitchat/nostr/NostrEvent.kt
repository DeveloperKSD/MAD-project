package `in`.yashd.bitchat.nostr

/** The six fields hashed by NIP-01. createdAtSeconds is Unix time, not milliseconds. */
data class UnsignedNostrEvent(
    val pubkey: String,
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>> = emptyList(),
    val content: String
)

/** A signed event. The codec flattens these fields into the standard Nostr JSON object. */
data class NostrEvent(
    val id: String,
    val unsignedEvent: UnsignedNostrEvent,
    val sig: String
)
