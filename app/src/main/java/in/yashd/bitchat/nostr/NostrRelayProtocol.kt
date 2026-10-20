package `in`.yashd.bitchat.nostr

import kotlinx.serialization.json.*

/** Wire responses. Acceptance means relay acceptance only, never recipient delivery. */
sealed interface RelayResponse {
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayResponse
    data class Acceptance(val eventId: String, val accepted: Boolean, val message: String) : RelayResponse
    data class EndOfStoredEvents(val subscriptionId: String) : RelayResponse
    data class Closed(val subscriptionId: String, val message: String) : RelayResponse
    data class Notice(val message: String) : RelayResponse
}

object NostrRelayProtocol {
    private val json = Json { isLenient = false }
    internal fun string(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Expected string")
    fun validateSubscriptionId(id: String) { require(id.isNotEmpty() && id.length <= 64) }
    private fun array(vararg fields: JsonElement) = JsonArray(fields.toList()).toString()
    fun publish(event: NostrEvent): String = array(JsonPrimitive("EVENT"), json.parseToJsonElement(NostrEventCodec.encode(event)))
    fun request(id: String, filters: List<JsonObject>): String {
        validateSubscriptionId(id)
        require(filters.isNotEmpty())
        return JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(id)) + filters).toString()
    }
    fun close(id: String): String {
        validateSubscriptionId(id)
        return array(JsonPrimitive("CLOSE"), JsonPrimitive(id))
    }

    /** Untrusted events are authenticated before they can leave this decoder. */
    fun decode(text: String, maxBytes: Int = 256 * 1024): RelayResponse? = try {
        require(maxBytes > 0 && text.length <= maxBytes && text.toByteArray(Charsets.UTF_8).size <= maxBytes)
        // Bound nesting before invoking the JSON parser, including malicious arrays/objects.
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> { depth++; require(depth <= 32) }
                ']', '}' -> depth--
            }
        }
        val a = json.parseToJsonElement(text) as JsonArray
        when (string(a[0])) {
            "EVENT" -> {
                require(a.size == 3)
                val id = string(a[1]); validateSubscriptionId(id)
                val event = NostrEventCodec.decode(a[2].toString())
                require(NostrEventSigner().verify(event))
                RelayResponse.Event(id, event)
            }
            "OK" -> {
                require(a.size == 4)
                val id = string(a[1]); require(id.isLowercaseHex(32))
                val accepted = a[2] as JsonPrimitive
                require(!accepted.isString)
                RelayResponse.Acceptance(id, requireNotNull(accepted.booleanOrNull), string(a[3]))
            }
            "EOSE" -> { require(a.size == 2); val id = string(a[1]); validateSubscriptionId(id); RelayResponse.EndOfStoredEvents(id) }
            "CLOSED" -> { require(a.size == 3); val id = string(a[1]); validateSubscriptionId(id); RelayResponse.Closed(id, string(a[2])) }
            "NOTICE" -> { require(a.size == 2); RelayResponse.Notice(string(a[1])) }
            else -> null
        }
    } catch (_: Exception) { null }
}
