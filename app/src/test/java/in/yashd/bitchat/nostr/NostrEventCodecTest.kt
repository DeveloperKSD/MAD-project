package `in`.yashd.bitchat.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NostrEventCodecTest {
    private val event = UnsignedNostrEvent("00".repeat(32), 1700000000, 1, content = "Hello, Relay!")

    @Test fun canonicalArrayAndIndependentHashFixture() {
        assertEquals(
            """[0,"${event.pubkey}",1700000000,1,[],"Hello, Relay!"]""",
            NostrEventCodec.canonicalJson(event)
        )
        // Independently calculated with Python hashlib.sha256 over the literal UTF-8 array above.
        assertEquals(
            "24736f2d42223c9018db2e293cb02eaed5d52e9fe6c4df0b39dfd5db763b84d0",
            NostrEventCodec.calculateId(event)
        )
    }

    @Test fun escapesJsonWithoutEscapingSlashesOrUnicode() {
        val data = event.copy(content = "\"\\\n\r\t\b\u000c / café 😀 <>& \u2028")
        assertEquals(
            """[0,"${event.pubkey}",1700000000,1,[],"\"\\\n\r\t\b\f / café 😀 <>& """ +
                "\u2028" + "\"]",
            NostrEventCodec.canonicalJson(data)
        )
    }

    @Test fun preservesTagOrderAndEmptyStrings() {
        val data = event.copy(tags = listOf(listOf("p", "abc", ""), listOf("t", "Relay")), content = "")
        assertEquals(
            """[0,"${event.pubkey}",1700000000,1,[["p","abc",""],["t","Relay"]],""]""",
            NostrEventCodec.canonicalJson(data)
        )
        assertNotEquals(
            NostrEventCodec.calculateId(data),
            NostrEventCodec.calculateId(data.copy(tags = data.tags.reversed()))
        )
    }

    @Test fun wireObjectHasExactlyTheStandardFieldsAndNumericTimestamp() {
        val signed = NostrEvent("ab".repeat(32), event, "cd".repeat(64))
        val json = Json.parseToJsonElement(NostrEventCodec.encode(signed)).jsonObject
        assertEquals(setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"), json.keys)
        assertFalse(json.getValue("created_at").jsonPrimitive.isString)
        assertEquals("1700000000", json.getValue("created_at").toString())
        assertEquals(event.content, json.getValue("content").jsonPrimitive.content)
    }

    @Test fun rejectsMalformedFieldsAndBrokenUnicode() {
        val invalid = listOf(
            event.copy(pubkey = "AB".repeat(32)),
            event.copy(pubkey = "00"),
            event.copy(createdAtSeconds = -1),
            event.copy(kind = -1),
            event.copy(kind = 65536),
            event.copy(tags = listOf(emptyList())),
            event.copy(content = "\uD800"),
            event.copy(tags = listOf(listOf("t", "\uDC00")))
        )
        invalid.forEach { data ->
            assertThrows(IllegalArgumentException::class.java) { NostrEventCodec.canonicalJson(data) }
        }
        // Both ends of the NIP-01 kind range and the Unix epoch are valid.
        NostrEventCodec.canonicalJson(event.copy(kind = 0, createdAtSeconds = 0))
        NostrEventCodec.canonicalJson(event.copy(kind = 65535))
    }
}
