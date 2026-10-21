package `in`.yashd.bitchat.nostr

import java.net.URI

/** Application policy: TLS only, 30-day freshness, five-minute clock skew.
 * NIP-17 itself does not impose an expiry. Empty lists explicitly withdraw an inbox.
 */
class NostrInbox(private val now: () -> Long = { System.currentTimeMillis() / 1000 },
                 private val maxAgeSeconds: Long = 30 * 86400L) {
    init { require(maxAgeSeconds > 0) }
    fun relays(event: NostrEvent, owner: String): List<String>? = try {
        val e = event.unsignedEvent
        require(e.kind == 10050 && e.pubkey == owner && NostrEventSigner().verify(event))
        require(e.createdAtSeconds >= now() - maxAgeSeconds && e.createdAtSeconds <= now() + 300)
        val tags = e.tags.filter { it.firstOrNull() == "relay" }
        tags.map { require(it.size >= 2); validateUrl(it[1]) }.distinct()
    } catch (_: Exception) { null }

    fun newest(events: List<NostrEvent>, owner: String): NostrEvent? = events
        .filter { relays(it, owner) != null }
        .sortedWith(compareByDescending<NostrEvent> { it.unsignedEvent.createdAtSeconds }.thenBy { it.id })
        .firstOrNull()

    companion object {
        fun validateUrl(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "wss" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            require(value.length <= 2048)
            return value
        }
    }
}
