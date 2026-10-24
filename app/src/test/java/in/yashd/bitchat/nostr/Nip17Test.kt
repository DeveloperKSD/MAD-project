package `in`.yashd.bitchat.nostr

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Nip17Test {
    private val alice = ByteArray(32).also { it[31] = 3 }
    private val bob = ByteArray(32).also { it[31] = 4 }
    private val eve = ByteArray(32).also { it[31] = 5 }
    private val temporary = ByteArray(32).also { it[31] = 6 }
    private val signer = NostrEventSigner()
    private val cipher = Nip44()
    private val nip17 = Nip17()
    private fun pub(key: ByteArray) = SchnorrSignature().publicKey(key).lowercaseHex()
    private fun rumor() = UnsignedNostrEvent(pub(alice), 1700000000, 14,
        listOf(listOf("p", pub(bob))), "Hello 🌍")
    private fun decode(event: NostrEvent, key: ByteArray = bob) =
        nip17.decodeMessage(event, key, pub(alice), pub(bob))
    private fun seal(text: String) = signer.createSignedEvent(alice, 13,
        cipher.encrypt(text, alice, pub(bob).hexBytes()))
    private fun wrap(seal: NostrEvent) = signer.createSignedEvent(temporary, 1059,
        cipher.encrypt(NostrEventCodec.encode(seal), temporary, pub(bob).hexBytes()),
        listOf(listOf("p", pub(bob))))
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun roundTripAndSenderCopyHaveSameUnsignedRumor() {
        val copies = nip17.createMessage(alice, pub(bob), rumor().content, 1700000000)
        assertEquals(rumor(), decode(copies.recipientCopy))
        assertEquals(rumor(), decode(copies.senderCopy, alice))
        assertNotEquals(copies.recipientCopy.unsignedEvent.pubkey, copies.senderCopy.unsignedEvent.pubkey)
        val again = nip17.createMessage(alice, pub(bob), "again", 1700000000)
        assertNotEquals(copies.recipientCopy.unsignedEvent.pubkey, again.recipientCopy.unsignedEvent.pubkey)
        listOf(copies.recipientCopy to bob, copies.senderCopy to alice).forEach { (outer, key) ->
            assertTrue(signer.verify(outer))
            assertEquals(1059, outer.unsignedEvent.kind)
            assertTrue(outer.unsignedEvent.createdAtSeconds in 1699827200L..1700000000L)
            val inner = NostrEventCodec.decode(cipher.decrypt(outer.unsignedEvent.content,
                key, outer.unsignedEvent.pubkey.hexBytes()))
            assertTrue(signer.verify(inner))
            assertEquals(13, inner.unsignedEvent.kind)
            assertTrue(inner.unsignedEvent.tags.isEmpty())
            assertTrue(inner.unsignedEvent.createdAtSeconds in 1699827200L..1700000000L)
            val text = cipher.decrypt(inner.unsignedEvent.content, key, pub(alice).hexBytes())
            assertFalse(Json.parseToJsonElement(text).jsonObject.containsKey("sig"))
            assertEquals(rumor(), NostrEventCodec.decodeRumor(text))
        }
    }

    @Test fun rejectsWrongRecipientSenderAndOuterTampering() {
        val outer = nip17.createMessage(alice, pub(bob), "hello").recipientCopy
        rejects { decode(outer, eve) }
        rejects { decode(outer, alice) }
        rejects { nip17.decodeMessage(outer, bob, pub(eve), pub(bob)) }
        rejects { nip17.decodeMessage(outer, bob, pub(alice), pub(eve)) }
        rejects { decode(signer.createSignedEvent(temporary, 1059,
            outer.unsignedEvent.content.reversed(), listOf(listOf("p", pub(bob))))) }
        rejects { decode(signer.createSignedEvent(temporary, 21059,
            outer.unsignedEvent.content, listOf(listOf("p", pub(bob))))) }
        rejects { decode(outer.copy(sig = "00".repeat(64))) }
        rejects { decode(outer.copy(unsignedEvent = outer.unsignedEvent.copy(content = "tampered"))) }
        rejects { decode(signer.createSignedEvent(temporary, 1059,
            outer.unsignedEvent.content, listOf(listOf("p", pub(eve))))) }
    }

    @Test fun rejectsAuthenticatedButInvalidInnerEvents() {
        val valid = seal(NostrEventCodec.encodeRumor(rumor()))
        rejects { decode(wrap(valid.copy(sig = "00".repeat(64)))) }
        rejects { decode(wrap(signer.sign(valid.unsignedEvent.copy(kind = 1), alice))) }
        rejects { decode(wrap(signer.sign(valid.unsignedEvent.copy(tags = listOf(listOf("p", pub(bob)))), alice))) }
        rejects { decode(wrap(signer.sign(valid.unsignedEvent.copy(content = valid.unsignedEvent.content.reversed()), alice))) }
        val badRumors = listOf(
            NostrEventCodec.encodeRumor(rumor().copy(pubkey = pub(eve))),
            NostrEventCodec.encodeRumor(rumor().copy(kind = 15)),
            NostrEventCodec.encodeRumor(rumor().copy(tags = listOf(listOf("p", pub(eve))))),
            NostrEventCodec.encodeRumor(rumor().copy(tags = emptyList())),
            NostrEventCodec.encodeRumor(rumor().copy(tags = listOf(listOf("p", pub(bob)), listOf("p", pub(eve))))),
            NostrEventCodec.encode(signer.sign(rumor(), alice)),
            NostrEventCodec.encodeRumor(rumor()).replace("Hello", "Changed"),
            NostrEventCodec.encodeRumor(rumor()).replace("1700000000", "\"1700000000\""),
            NostrEventCodec.encodeRumor(rumor()).replace("\"content\":\"Hello 🌍\"", "\"content\":14"),
            "{}", "[]", "not json"
        )
        badRumors.forEach { rejects { decode(wrap(seal(it))) } }
    }

    @Test fun rejectsDeeplyNestedDecryptedJsonWithoutLeakingIt() {
        val marker = "private-message-parser-marker"
        val nested = "[".repeat(10000) + "\"$marker\"" + "]".repeat(10000)
        val maliciousRumor = NostrEventCodec.encodeRumor(rumor()).dropLast(1) + ",\"extra\":$nested}"
        val maliciousSeal = NostrEventCodec.encode(seal(NostrEventCodec.encodeRumor(rumor())))
            .dropLast(1) + ",\"extra\":$nested}"
        val outerWithMaliciousSeal = signer.createSignedEvent(temporary, 1059,
            cipher.encrypt(maliciousSeal, temporary, pub(bob).hexBytes()),
            listOf(listOf("p", pub(bob))))
        listOf(wrap(seal(maliciousRumor)), outerWithMaliciousSeal).forEach { event ->
            val error = assertThrows(IllegalArgumentException::class.java) { decode(event) }
            assertEquals("Invalid NIP-17 private message", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains(marker))
        }
    }

    @Test fun senderCopyRejectsRecipientThatIsNotACurvePoint() {
        val invalidRecipient = "ff".repeat(32)
        val text = NostrEventCodec.encodeRumor(rumor().copy(tags = listOf(listOf("p", invalidRecipient))))
        val selfSeal = signer.createSignedEvent(alice, 13, cipher.encrypt(text, alice, pub(alice).hexBytes()))
        val selfWrap = signer.createSignedEvent(temporary, 1059,
            cipher.encrypt(NostrEventCodec.encode(selfSeal), temporary, pub(alice).hexBytes()),
            listOf(listOf("p", pub(alice))))
        rejects { nip17.decodeMessage(selfWrap, alice, pub(alice), invalidRecipient) }
    }

    @Test fun publishedNip59ExampleAuthenticatesAndDecryptsBothLayers() {
        val examples = Json.parseToJsonElement(javaClass.getResource("/nostr/nip59-example.json")!!.readText()).jsonArray
        val publishedSeal = NostrEventCodec.decode(examples[0].toString())
        val outer = NostrEventCodec.decode(examples[1].toString())
        val receiver = "e108399bd8424357a710b606ae0c13166d853d327e47a6e5e038197346bdbf45".hexBytes()
        assertTrue(signer.verify(outer))
        val inner = NostrEventCodec.decode(cipher.decrypt(outer.unsignedEvent.content, receiver, outer.unsignedEvent.pubkey.hexBytes()))
        assertEquals(publishedSeal, inner)
        assertTrue(signer.verify(inner))
        val rumor = NostrEventCodec.decodeRumor(cipher.decrypt(inner.unsignedEvent.content, receiver, inner.unsignedEvent.pubkey.hexBytes()))
        assertEquals("Are you going to the party tonight?", rumor.content)
        assertEquals(inner.unsignedEvent.pubkey, rumor.pubkey)
        assertEquals(1, rumor.kind)
        // NIP-59's generic kind-1 example is deliberately not an accepted NIP-17 text DM.
        rejects { nip17.decodeMessage(outer, receiver, rumor.pubkey, pub(receiver)) }
    }
    @Test fun incomingEntryPointRejectsImpersonationAndInvalidLayersWithoutLeakingPlaintext() {
        val originals = nip17.createMessage(alice, pub(bob), rumor().content, 1700000000)
        assertEquals(rumor(), nip17.decodeIncoming(originals.recipientCopy, bob))
        assertEquals(rumor(), nip17.decodeIncoming(originals.senderCopy, alice))
        val validSeal = seal(NostrEventCodec.encodeRumor(rumor()))
        val invalidEvents = listOf(
            originals.recipientCopy.copy(sig = "00".repeat(64)),
            wrap(validSeal.copy(sig = "00".repeat(64))),
            wrap(seal(NostrEventCodec.encodeRumor(rumor().copy(pubkey = pub(eve))))),
            wrap(seal(NostrEventCodec.encode(signer.sign(rumor(), alice)))),
            wrap(seal(NostrEventCodec.encodeRumor(rumor().copy(tags = listOf(listOf("p", pub(eve))))))),
            wrap(signer.sign(validSeal.unsignedEvent.copy(tags = listOf(listOf("p", pub(bob)))), alice))
        )
        invalidEvents.forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { nip17.decodeIncoming(it, bob) }
            assertEquals("Invalid NIP-17 private message", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains(rumor().content))
        }
    }

}
