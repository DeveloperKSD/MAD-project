package `in`.yashd.bitchat.nostr

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live verification against the preset public relays. Not part of CI expectations; evidence is written
 * to /tmp/roundtrip-evidence.txt. Uses two throwaway identities, synthetic payload only. */
class NostrLiveRoundtripTest {

    private class MemStorage(var secret: ByteArray?) : NostrIdentityStorage {
        // Manager zeroizes buffers after use; never share our reference.
        override fun read(): ByteArray? = secret?.copyOf()
        override fun write(secret: ByteArray) { this.secret = secret.copyOf() }
    }

    @Test(timeout = 180_000)
    fun presetRelays_announceDiscoverGiftwrapRoundtrip() {
        // Live network verification: run explicitly with NOSTR_LIVE_TEST=1.
        org.junit.Assume.assumeTrue(System.getenv("NOSTR_LIVE_TEST") == "1")
        val log = StringBuilder()
        fun ev(msg: String) { log.appendLine(msg); println(msg) }

        val discovery = listOf("wss://relay.damus.io", "wss://nos.lol")
        val inbox = listOf("wss://nos.lol") // damus demands NIP-42 auth on subscriptions
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val aliceId = NostrIdentityManager(MemStorage(null)); val alice = aliceId.loadOrCreate()
        val bobId = NostrIdentityManager(MemStorage(null)); val bob = bobId.loadOrCreate()
        val issues = ConcurrentLinkedQueue<String>()
        val receivedByBob = ConcurrentLinkedQueue<UnsignedNostrEvent>()
        val receivedByAlice = ConcurrentLinkedQueue<UnsignedNostrEvent>()

        val aliceCoord = NostrMessagingCoordinator(aliceId, http, discovery, 15_000,
            onReceived = { receivedByAlice += it },
            onRelayIssue = { url, issue -> issues += "alice $url $issue" })
        val bobCoord = NostrMessagingCoordinator(bobId, http, discovery, 15_000,
            onReceived = { receivedByBob += it },
            onRelayIssue = { url, issue -> issues += "bob $url $issue" })
        try {
            ev("alice npub=${alice.npub}")
            ev("bob npub=${bob.npub}")

            val pubA = aliceCoord.announce(inbox).get(90, TimeUnit.SECONDS)
            pubA.forEach { ev("announce alice -> ${it.relay}: ${it.stage}") }
            val pubB = bobCoord.announce(inbox).get(90, TimeUnit.SECONDS)
            pubB.forEach { ev("announce bob -> ${it.relay}: ${it.stage}") }

            val foundA = bobCoord.discover(alice.publicKeyHex).get(90, TimeUnit.SECONDS)
            ev("discover alice from bob side: ${if (foundA != null) "found kind=${foundA.unsignedEvent.kind}" else "NOT FOUND"}")
            val foundB = aliceCoord.discover(bob.publicKeyHex).get(90, TimeUnit.SECONDS)
            ev("discover bob from alice side: ${if (foundB != null) "found kind=${foundB.unsignedEvent.kind}" else "NOT FOUND"}")

            bobCoord.startReceiving(inbox).get(90, TimeUnit.SECONDS)
            aliceCoord.startReceiving(inbox).get(90, TimeUnit.SECONDS)

            val token1 = "roundtrip-alice-bob-" + UUID.randomUUID()
            val send1 = aliceCoord.send(bob.publicKeyHex, token1).get(90, TimeUnit.SECONDS)
            send1.recipient.forEach { ev("send a->b recipient ${it.relay}: ${it.stage}") }
            send1.sender.forEach { ev("send a->b sendercopy ${it.relay}: ${it.stage}") }

            val token2 = "roundtrip-bob-alice-" + UUID.randomUUID()
            val send2 = bobCoord.send(alice.publicKeyHex, token2).get(90, TimeUnit.SECONDS)
            send2.recipient.forEach { ev("send b->a recipient ${it.relay}: ${it.stage}") }
            send2.sender.forEach { ev("send b->a sendercopy ${it.relay}: ${it.stage}") }

            val deadline = System.currentTimeMillis() + 60_000
            var bobGot = false; var aliceGot = false
            while (System.currentTimeMillis() < deadline && (!bobGot || !aliceGot)) {
                if (!bobGot) bobGot = receivedByBob.any { it.content == token1 }
                if (!aliceGot) aliceGot = receivedByAlice.any { it.content == token2 }
                if (!bobGot || !aliceGot) Thread.sleep(500)
            }
            ev("bob received a->b decrypted=$bobGot")
            ev("alice received b->a decrypted=$aliceGot")
            issues.forEach { ev("issue: $it") }

            assertTrue(pubA.any { it.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED })
            assertTrue(pubB.any { it.stage == NostrMessagingCoordinator.Stage.RELAY_ACCEPTED })
            assertTrue(foundA != null && foundB != null)
            assertTrue(bobGot); assertTrue(aliceGot)
        } finally {
            File("/tmp/roundtrip-evidence.txt").writeText(log.toString())
            aliceCoord.close(); bobCoord.close()
        }
    }
}
