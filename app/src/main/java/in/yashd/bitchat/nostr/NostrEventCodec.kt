package `in`.yashd.bitchat.nostr

import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** NIP-01 serialization only: no Android, BLE, network, or key storage dependencies. */
object NostrEventCodec {
    /** Exact compact JSON array used for hashing; tags retain their original order. */
    fun canonicalJson(event: UnsignedNostrEvent): String {
        validate(event)
        return buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(event.pubkey))
            add(JsonPrimitive(event.createdAtSeconds))
            add(JsonPrimitive(event.kind))
            add(tagsJson(event.tags))
            add(JsonPrimitive(event.content))
        }.toString()
    }

    fun calculateId(event: UnsignedNostrEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(event).toByteArray(Charsets.UTF_8))
            .lowercaseHex()

    /** Serializes an event, but does not authenticate it; use NostrEventSigner.verify for that. */
    fun encode(event: NostrEvent): String {
        validate(event.unsignedEvent)
        require(event.id.isLowercaseHex(32)) { "Event ID must be 32 bytes of lowercase hex" }
        require(event.sig.isLowercaseHex(64)) { "Signature must be 64 bytes of lowercase hex" }
        val data = event.unsignedEvent
        return buildJsonObject {
            put("id", event.id)
            put("pubkey", data.pubkey)
            put("created_at", data.createdAtSeconds)
            put("kind", data.kind)
            put("tags", tagsJson(data.tags))
            put("content", data.content)
            put("sig", event.sig)
        }.toString()
    }

    /** Decode structure strictly; callers must verify authenticity before accepting the event. */
    fun decode(text: String): NostrEvent {
        val obj = Json.parseToJsonElement(text).jsonObject
        fun str(key: String) = NostrRelayProtocol.string(obj.getValue(key))
        fun number(key: String): Long {
            val value = obj.getValue(key).jsonPrimitive
            // The JSON library accepts bare tokens such as +100 and 0100; neither
            // is a valid JSON integer. Reject them rather than normalizing before hashing.
            require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")))
            return requireNotNull(value.longOrNull)
        }
        val kind = number("kind")
        require(kind in 0..65535)
        val event = NostrEvent(str("id"), UnsignedNostrEvent(
            str("pubkey"), number("created_at"), kind.toInt(),
            obj.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map(NostrRelayProtocol::string) },
            str("content")
        ), str("sig"))
        encode(event) // Apply the same field constraints as outgoing events.
        return event
    }

    private fun tagsJson(tags: List<List<String>>) =
        JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) })

    private fun validate(event: UnsignedNostrEvent) {
        require(event.pubkey.isLowercaseHex(32)) { "Public key must be 32 bytes of lowercase hex" }
        require(event.createdAtSeconds >= 0) { "Unix timestamp must be nonnegative seconds" }
        require(event.kind in 0..65535) { "Event kind must be between 0 and 65535" }
        require(event.tags.all { it.isNotEmpty() }) { "Tags must contain at least a tag name" }
        requireValidUnicode(event.content)
        event.tags.flatten().forEach(::requireValidUnicode)
    }

    // Reject broken UTF-16 rather than hashing replacement characters during UTF-8 conversion.
    private fun requireValidUnicode(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                require(index < text.length && text[index].isLowSurrogate()) { "Unpaired surrogate" }
                index++
            } else {
                require(!char.isLowSurrogate()) { "Unpaired surrogate" }
            }
        }
    }
}
