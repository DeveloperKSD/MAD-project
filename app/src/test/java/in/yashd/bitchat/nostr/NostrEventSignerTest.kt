package `in`.yashd.bitchat.nostr

import org.junit.Assert.*
import org.junit.Test

class NostrEventSignerTest {
    private val signer = NostrEventSigner()
    private val secret = ByteArray(32).also { it[31] = 3 }
    private fun signed() = signer.createSignedEvent(
        secret, 1, "Hello, Relay!", listOf(listOf("t", "relay")), 1700000000
    )

    @Test fun createsVerifiableEventWithKnownPublicKey() {
        val event = signed()
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            event.unsignedEvent.pubkey
        )
        assertEquals(1700000000L, event.unsignedEvent.createdAtSeconds)
        assertEquals(64, event.id.length)
        assertEquals(128, event.sig.length)
        assertTrue(signer.verify(event))
        assertEquals(NostrEventCodec.calculateId(event.unsignedEvent), event.id)
    }

    @Test fun defaultTimestampUsesSeconds() {
        val before = System.currentTimeMillis() / 1000
        val event = signer.createSignedEvent(secret, 1, "")
        val after = System.currentTimeMillis() / 1000
        assertTrue(event.unsignedEvent.createdAtSeconds in before..after)
    }

    @Test fun everySignedFieldIsAuthenticatedEvenIfAttackerRecalculatesId() {
        val event = signed()
        val data = event.unsignedEvent
        val changed = listOf(
            data.copy(content = "tampered"),
            data.copy(createdAtSeconds = data.createdAtSeconds + 1),
            data.copy(kind = 2),
            data.copy(tags = listOf(listOf("t", "other"))),
            data.copy(pubkey = SchnorrSignature().publicKey(ByteArray(32).also { it[31] = 4 }).lowercaseHex())
        )
        changed.forEach {
            assertFalse(signer.verify(event.copy(unsignedEvent = it)))
            assertFalse(signer.verify(event.copy(unsignedEvent = it, id = NostrEventCodec.calculateId(it))))
        }
    }

    @Test fun rejectsInvalidIdsAndSignaturesWithoutThrowing() {
        val event = signed()
        listOf("", "00", "gg".repeat(64), "00".repeat(64), event.sig.uppercase()).forEach {
            assertFalse(signer.verify(event.copy(sig = it)))
        }
        listOf("", "00", "00".repeat(32), event.id.uppercase()).forEach {
            assertFalse(signer.verify(event.copy(id = it)))
        }
        assertFalse(signer.verify(event.copy(unsignedEvent = event.unsignedEvent.copy(kind = -1))))
    }

    @Test fun signingRejectsMismatchedPublicKey() {
        assertThrows(IllegalArgumentException::class.java) {
            signer.sign(signed().unsignedEvent, ByteArray(32).also { it[31] = 4 })
        }
    }

    @Test fun signingSnapshotsCallerOwnedTags() {
        val tag = mutableListOf("t", "relay")
        val tags = mutableListOf<List<String>>(tag)
        val event = signer.createSignedEvent(secret, 1, "hello", tags, 1700000000)
        tag[1] = "changed"
        tags.clear()
        assertEquals(listOf(listOf("t", "relay")), event.unsignedEvent.tags)
        assertTrue(signer.verify(event))
    }
}
