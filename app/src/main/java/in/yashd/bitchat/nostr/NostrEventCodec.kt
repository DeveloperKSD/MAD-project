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
        return JsonObject(fields(event.id, event.unsignedEvent) + ("sig" to JsonPrimitive(event.sig))).toString()
    }

    /** Decode structure strictly; callers must verify authenticity before accepting the event. */
    fun decode(text: String): NostrEvent {
        val obj = parseObject(text)
        val event = NostrEvent(NostrRelayProtocol.string(obj.getValue("id")),
            decodeFields(obj), NostrRelayProtocol.string(obj.getValue("sig")))
        encode(event) // Apply the same field constraints as outgoing events.
        return event
    }

    /** NIP-59 rumor: includes its content hash, but never a signature. */
    fun encodeRumor(event: UnsignedNostrEvent): String = fields(calculateId(event), event).toString()

    fun decodeRumor(text: String): UnsignedNostrEvent {
        val obj = parseObject(text)
        require("sig" !in obj) { "Rumor must be unsigned" }
        val event = decodeFields(obj)
        require(NostrRelayProtocol.string(obj.getValue("id")) == calculateId(event)) { "Invalid rumor ID" }
        return event
    }

    private fun parseObject(text: String): JsonObject {
        // Encrypted JSON bypasses the relay decoder's nesting limit. Bound it before
        // the recursive tree parser; even a correctly signed sender can send hostile JSON.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> { depth++; require(depth <= 32) { "Event JSON nesting limit exceeded" } }
                ']', '}' -> depth--
            }
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun fields(id: String, data: UnsignedNostrEvent) = buildJsonObject {
        put("id", id)
        put("pubkey", data.pubkey)
        put("created_at", data.createdAtSeconds)
        put("kind", data.kind)
        put("tags", tagsJson(data.tags))
        put("content", data.content)
    }

    private fun decodeFields(obj: JsonObject): UnsignedNostrEvent {
        fun str(key: String) = NostrRelayProtocol.string(obj.getValue(key))
        fun number(key: String): Long {
            val value = obj.getValue(key).jsonPrimitive
            // Reject non-JSON integer spellings accepted by the JSON library.
            require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")))
            return requireNotNull(value.longOrNull)
        }
        val kind = number("kind")
        require(kind in 0..65535)
        return UnsignedNostrEvent(
            str("pubkey"), number("created_at"), kind.toInt(),
            obj.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map(NostrRelayProtocol::string) },
            str("content")
        ).also(::validate)
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
