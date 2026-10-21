package `in`.yashd.bitchat.nostr

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test

class NostrMessagingCoordinatorTest {
    private val alice = ByteArray(32).apply { this[31] = 1 }
    private val bob = ByteArray(32).apply { this[31] = 2 }
    private fun pub(key: ByteArray) = SchnorrSignature().publicKey(key).lowercaseHex()
    private fun manager() = NostrIdentityManager(object : NostrIdentityStorage {
        override fun read() = alice.copyOf()
        override fun write(secret: ByteArray) = error("Unexpected creation")
    })
    private fun list(key: ByteArray, urls: List<String>, time: Long = System.currentTimeMillis()/1000) =
        NostrEventSigner().createSignedEvent(key, 10050, "", urls.map { listOf("relay", it) }, time)

    private class Network : AutoCloseable {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val servers = mutableListOf<MockWebServer>()
        fun relay(action: (WebSocket, String) -> Unit): String {
            val server = MockWebServer()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) = action(webSocket, text)
                })
            }
            server.start(); servers.add(server)
            return server.url("/").toString().replace("https://", "wss://")
        }
        override fun close() {
            servers.forEach { it.close() }; http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
    private fun fields(text: String) = Json.parseToJsonElement(text).jsonArray
    private fun ack(ws: WebSocket, event: NostrEvent, accepted: Boolean = true) {
        ws.send("[\"OK\",\"${event.id}\",$accepted,\"\"]")
    }
    @Test fun validationAndNewestSelection() {
        val policy = NostrInbox({ 4_000_000 })
        val old = list(bob, listOf("wss://old.example"), 3_999_000)
        val fresh = list(bob, listOf("wss://new.example"), 4_000_000)
        val invalid = fresh.copy(sig = "0".repeat(128))
        assertEquals(fresh, policy.newest(listOf(old, fresh, invalid), pub(bob)))
        assertNull(policy.relays(list(alice, listOf("wss://a.example"), 4_000_000), pub(bob)))
        listOf("ws://bad.example", "wss://user:pass@bad.example", "wss://bad.example/#fragment",
            "wss://bad.example:65536", "wss://").forEach { url ->
            assertNull(url, policy.relays(list(bob, listOf(url), 4_000_000), pub(bob)))
        }
        assertNull(policy.relays(list(bob, listOf("wss://a.example"), 1), pub(bob)))
        assertNull(policy.relays(list(bob, listOf("wss://a.example"), 4_000_301), pub(bob)))
        assertEquals(emptyList<String>(), policy.relays(list(bob, emptyList(), 4_000_000), pub(bob)))
        val tied = list(bob, listOf("wss://other.example"), 4_000_000)
        assertEquals(listOf(fresh, tied).minBy { it.id }, policy.newest(listOf(tied, fresh), pub(bob)))
    }
    @Test fun discoveryRoutingCopiesAndRejection() = Network().use { n ->
        val toBob = LinkedBlockingQueue<NostrEvent>()
        val toAlice = LinkedBlockingQueue<NostrEvent>()
        val b = n.relay { ws, text -> val a = fields(text); if (a[0].jsonPrimitive.content == "EVENT") {
            val e = NostrEventCodec.decode(a[1].toString()); toBob.add(e); ack(ws, e)
        } }
        val a = n.relay { ws, text -> val f = fields(text); if (f[0].jsonPrimitive.content == "EVENT") {
            val e = NostrEventCodec.decode(f[1].toString()); toAlice.add(e); ack(ws, e, false)
        } }
        val discoveryPublications = LinkedBlockingQueue<NostrEvent>()
        val d = n.relay { ws, text -> val f = fields(text)
            when (f[0].jsonPrimitive.content) {
                "REQ" -> {
                    val owner = f[2].jsonObject["authors"]!!.jsonArray[0].jsonPrimitive.content
                    val e = if (owner == pub(bob)) list(bob, listOf(b)) else list(alice, listOf(a))
                    val stale = e.copy(unsignedEvent = e.unsignedEvent.copy(createdAtSeconds = 1))
                    ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(stale)}]")
                    val outdated = list(if (owner == pub(bob)) bob else alice, listOf("wss://unused.invalid"), 1)
                    ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(outdated)}]")
                    val older = list(if (owner == pub(bob)) bob else alice, listOf("wss://unused.invalid"), System.currentTimeMillis()/1000 - 60)
                    ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(older)}]")
                    ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(e)}]")
                    ws.send("[\"EOSE\",${f[1]}]")
                }
                "EVENT" -> { val e = NostrEventCodec.decode(f[1].toString()); discoveryPublications.add(e); ack(ws,e) }
            }
        }
        val stages = LinkedBlockingQueue<NostrMessagingCoordinator.Publication>()
        NostrMessagingCoordinator(manager(), n.http, listOf(d), onPublication = { stages.add(it) }).use { c ->
            val result = c.send(pub(bob), "private").get(15, TimeUnit.SECONDS)
            assertEquals(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED, result.recipient.single().stage)
            assertEquals(NostrMessagingCoordinator.Stage.RELAY_REJECTED, result.sender.single().stage)
            val recipient = toBob.poll(2, TimeUnit.SECONDS)!!
            val sender = toAlice.poll(2, TimeUnit.SECONDS)!!
            assertEquals("private", Nip17().decodeIncoming(recipient, bob).content)
            assertEquals("private", Nip17().decodeIncoming(sender, alice).content)
            assertEquals(pub(bob), recipient.unsignedEvent.tags.single()[1])
            assertEquals(pub(alice), sender.unsignedEvent.tags.single()[1])
            assertNull(discoveryPublications.poll())
            assertEquals(listOf(NostrMessagingCoordinator.Stage.QUEUED, NostrMessagingCoordinator.Stage.SUBMITTED,
                NostrMessagingCoordinator.Stage.RELAY_ACCEPTED, NostrMessagingCoordinator.Stage.QUEUED,
                NostrMessagingCoordinator.Stage.SUBMITTED, NostrMessagingCoordinator.Stage.RELAY_REJECTED), stages.map { it.stage })
            assertEquals(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED, c.announce(listOf(a)).get(10, TimeUnit.SECONDS).single().stage)
            val firstAnnouncement = discoveryPublications.poll(2, TimeUnit.SECONDS)!!
            assertEquals(10050, firstAnnouncement.unsignedEvent.kind)
            c.announce(emptyList(), firstAnnouncement.unsignedEvent.createdAtSeconds + 1).get(10, TimeUnit.SECONDS)
            val update = discoveryPublications.poll(2, TimeUnit.SECONDS)!!
            assertTrue(update.unsignedEvent.createdAtSeconds > firstAnnouncement.unsignedEvent.createdAtSeconds)
            assertTrue(update.unsignedEvent.tags.isEmpty())
            assertNull(toBob.poll()); assertNull(toAlice.poll())
        }
    }
    @Test fun storagePreparationFailureAbortsBeforeAnyPublication() = Network().use { n ->
        val published = LinkedBlockingQueue<String>()
        val inbox = n.relay { _, text -> if (fields(text)[0].jsonPrimitive.content == "EVENT") published.add(text) }
        val discovery = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "REQ") {
                val owner = f[2].jsonObject["authors"]!!.jsonArray[0].jsonPrimitive.content
                val event = list(if (owner == pub(bob)) bob else alice, listOf(inbox))
                ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(event)}]")
                ws.send("[\"EOSE\",${f[1]}]")
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(discovery)).use { c ->
            assertThrows(ExecutionException::class.java) {
                c.send(pub(bob), "not published", beforePublish = { throw IllegalStateException("local storage unavailable") }).get(15, TimeUnit.SECONDS)
            }
            assertNull(published.poll())
        }
    }
    @Test fun incomingDuplicateSuppressionAndReconnect(): Unit = Network().use { n ->
        val received = LinkedBlockingQueue<UnsignedNostrEvent>()
        val requests = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val wrap = Nip17().createMessage(bob, pub(alice), "incoming").recipientCopy
        val url = n.relay { ws, text ->
            if (fields(text)[0].jsonPrimitive.content == "REQ") {
            requests.add(text); sockets.add(ws)
            ws.send("garbage")
            val invalid = NostrEventSigner().createSignedEvent(bob, 1059, "invalid ciphertext",
                listOf(listOf("p", pub(alice))))
            ws.send("[\"EVENT\",\"inbox\",${NostrEventCodec.encode(invalid)}]")
            ws.send("[\"EVENT\",\"inbox\",${NostrEventCodec.encode(wrap)}]")
            ws.send("[\"EVENT\",\"inbox\",${NostrEventCodec.encode(wrap)}]")
        } }
        NostrMessagingCoordinator(manager(), n.http, listOf(url), onReceived = { received.add(it) }).use { c ->
            c.startReceiving(listOf(url)).get(5, TimeUnit.SECONDS)
            assertEquals("incoming", received.poll(5, TimeUnit.SECONDS)!!.content)
            val req = requests.poll(5, TimeUnit.SECONDS)!!
            assertTrue(req.contains(pub(alice))); assertTrue(req.contains("1059"))
            sockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "restart")
            assertEquals(req, requests.poll(5, TimeUnit.SECONDS))
            assertNull(received.poll(300, TimeUnit.MILLISECONDS))
            c.startReceiving(emptyList()).get(5, TimeUnit.SECONDS)
        }
    }
    @Test fun missingListsNeverPublish() = Network().use { n ->
        val publications = LinkedBlockingQueue<String>()
        val d = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "REQ") ws.send("[\"EOSE\",${f[1]}]")
            if (f[0].jsonPrimitive.content == "EVENT") publications.add(text)
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(d)).use { c ->
            val e = assertThrows(ExecutionException::class.java) { c.send(pub(bob), "secret").get(5, TimeUnit.SECONDS) }
            assertTrue(e.cause is NostrMessagingCoordinator.MissingInbox)
            assertNull(publications.poll())
        }
    }
    @Test fun connectionFailureIsNotAcceptance() = Network().use { n ->
        val url = n.relay { _, _ -> }
        n.servers.last().dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url), timeoutMillis = 200).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.FAILED,
                c.announce(listOf(url)).get(5, TimeUnit.SECONDS).single().stage)
        }
    }
    @Test fun authenticationAndUnknownPublicationOutcome() = Network().use { n ->
        val issues = LinkedBlockingQueue<NostrMessagingCoordinator.Issue>()
        val authSent = LinkedBlockingQueue<String>()
        val url = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "EVENT") ws.send("[\"AUTH\",\"challenge\"]")
            if (f[0].jsonPrimitive.content == "AUTH") authSent.add(text)
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url), timeoutMillis = 500,
            onRelayIssue = { _, issue -> issues.add(issue) }).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.UNKNOWN, c.announce(listOf(url)).get(5, TimeUnit.SECONDS).single().stage)
            assertEquals(NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED, issues.poll(2, TimeUnit.SECONDS))
            assertNull(authSent.poll())
        }
    }
    @Test fun discoveryConsidersEventsBeyondTheRequestedLimitAndLongerLists() = Network().use { n ->
        val old = list(bob, listOf("wss://old.example"), System.currentTimeMillis()/1000 - 60)
        val latest = list(bob, (1..4).map { "wss://inbox$it.example" })
        val url = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "REQ") {
                repeat(64) { ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(old)}]") }
                ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(latest)}]")
                ws.send("[\"EOSE\",${f[1]}]")
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url)).use { c ->
            assertEquals(latest, c.discover(pub(bob)).get(10, TimeUnit.SECONDS))
        }
    }

    @Test fun queuedRelayConfigurationIsSnapshotted(): Unit = Network().use { n ->
        val waiting = LinkedBlockingQueue<Pair<WebSocket, String>>()
        val announced = LinkedBlockingQueue<NostrEvent>()
        val requests = LinkedBlockingQueue<String>()
        val inbox = n.relay { _, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") requests.add(text) }
        val url = n.relay { ws, text -> val f = fields(text)
            when (f[0].jsonPrimitive.content) {
                "REQ" -> waiting.add(ws to f[1].toString())
                "EVENT" -> { val e = NostrEventCodec.decode(f[1].toString()); announced.add(e); ack(ws, e) }
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url)).use { c ->
            val blocked = c.discover(pub(bob))
            val (ws, sub) = waiting.poll(5, TimeUnit.SECONDS)!!
            val mutable = mutableListOf(inbox)
            val announcement = c.announce(mutable)
            val receiving = c.startReceiving(mutable)
            mutable.clear()
            ws.send("[\"EOSE\",$sub]")
            blocked.get(5, TimeUnit.SECONDS)
            announcement.get(5, TimeUnit.SECONDS)
            receiving.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(listOf("relay", inbox)), announced.poll(5, TimeUnit.SECONDS)!!.unsignedEvent.tags)
            assertNotNull(requests.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun boundedWorkQueueAndCloseCancelRunningAndQueuedFutures() = Network().use { n ->
        val requested = CountDownLatch(1)
        val url = n.relay { _, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") requested.countDown() }
        val c = NostrMessagingCoordinator(manager(), n.http, listOf(url), timeoutMillis = 60_000)
        try {
            val running = c.discover(pub(bob))
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            val pending = (1..32).map { c.discover(pub(bob)) }
            assertThrows(RejectedExecutionException::class.java) { c.discover(pub(bob)) }
            c.close()
            assertTrue(running.isCancelled)
            assertTrue(pending.all { it.isCancelled })
            assertThrows(IllegalStateException::class.java) { c.discover(pub(bob)) }
            assertEquals(1, n.servers.single().requestCount)
        } finally { c.close() }
    }

    @Test fun shutdownSuppressesReceiptAfterNonInterruptibleIdentityRead(): Unit = Network().use { n ->
        val block = AtomicBoolean()
        val entered = CountDownLatch(1)
        val permit = Semaphore(0)
        val leftStorage = CountDownLatch(1)
        val readsOnWorker = LinkedBlockingQueue<Boolean>()
        val id = NostrIdentityManager(object : NostrIdentityStorage {
            override fun read(): ByteArray {
                readsOnWorker.add(Thread.currentThread().name == "nostr-messaging")
                if (block.get()) {
                    entered.countDown()
                    permit.acquireUninterruptibly()
                    leftStorage.countDown()
                }
                return alice.copyOf()
            }
            override fun write(secret: ByteArray) = error("Unexpected write")
        })
        val sockets = LinkedBlockingQueue<WebSocket>()
        val url = n.relay { ws, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") sockets.add(ws) }
        val received = LinkedBlockingQueue<UnsignedNostrEvent>()
        val c = NostrMessagingCoordinator(id, n.http, listOf(url), onReceived = { received.add(it) })
        try {
            c.startReceiving(listOf(url)).get(5, TimeUnit.SECONDS)
            val ws = sockets.poll(5, TimeUnit.SECONDS)!!
            block.set(true)
            val wrap = Nip17().createMessage(bob, pub(alice), "must not arrive after close").recipientCopy
            ws.send("[\"EVENT\",\"inbox\",${NostrEventCodec.encode(wrap)}]")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.close()
            block.set(false)
            permit.release(8)
            assertTrue(leftStorage.await(5, TimeUnit.SECONDS))
            assertNull(received.poll(500, TimeUnit.MILLISECONDS))
            assertTrue(readsOnWorker.all { it })
        } finally { block.set(false); permit.release(8); c.close() }
    }

    @Test fun relayIssueCallbackCanCloseCoordinatorWithoutTransportLocks(): Unit = Network().use { n ->
        val callback = CountDownLatch(1)
        val threadNames = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val url = n.relay { ws, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") sockets.add(ws) }
        lateinit var c: NostrMessagingCoordinator
        c = NostrMessagingCoordinator(manager(), n.http, listOf(url), onRelayIssue = { _, _ ->
            threadNames.add(Thread.currentThread().name)
            c.close()
            callback.countDown()
        })
        try {
            c.startReceiving(listOf(url)).get(5, TimeUnit.SECONDS)
            sockets.poll(5, TimeUnit.SECONDS)!!.send("[\"AUTH\",\"challenge\"]")
            assertTrue(callback.await(5, TimeUnit.SECONDS))
            assertEquals("nostr-messaging", threadNames.poll())
        } finally { c.close() }
    }

    @Test fun redirectsCannotMovePublicationToUnadvertisedRelay() = Network().use { n ->
        val unexpected = LinkedBlockingQueue<String>()
        val target = n.relay { _, text -> unexpected.add(text) }
        val source = n.relay { _, _ -> error("Should not upgrade") }
        n.servers.last().dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302)
                .setHeader("Location", target.replace("wss://", "https://"))
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(source), timeoutMillis = 500).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.FAILED,
                c.announce(listOf(source)).get(5, TimeUnit.SECONDS).single().stage)
            assertEquals(0, n.servers.first().requestCount)
            assertNull(unexpected.poll())
        }
    }

    @Test fun authRequiredRejectionAndClosedSubscriptionNeverSendCredentials(): Unit = Network().use { n ->
        val issues = LinkedBlockingQueue<NostrMessagingCoordinator.Issue>()
        val requestCount = AtomicInteger()
        val credentials = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val url = n.relay { ws, text -> val f = fields(text)
            when (f[0].jsonPrimitive.content) {
                "REQ" -> {
                    requestCount.incrementAndGet(); sockets.add(ws)
                    ws.send("[\"CLOSED\",${f[1]},\"auth-required: sign in\"]")
                }
                "EVENT" -> {
                    val e = NostrEventCodec.decode(f[1].toString())
                    ws.send("[\"OK\",\"${e.id}\",false,\"auth-required: sign in\"]")
                }
                "AUTH" -> credentials.add(text)
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url),
            onRelayIssue = { _, issue -> issues.add(issue) }).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.RELAY_REJECTED,
                c.announce(listOf(url)).get(5, TimeUnit.SECONDS).single().stage)
            c.startReceiving(listOf(url)).get(5, TimeUnit.SECONDS)
            assertEquals(NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED, issues.poll(5, TimeUnit.SECONDS))
            // Wait until CLOSED was consumed, then disconnect. It must not be restored.
            val observed = (1..3).map { issues.poll(2, TimeUnit.SECONDS) }
            assertTrue(observed.contains(NostrMessagingCoordinator.Issue.SUBSCRIPTION_CLOSED))
            sockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "restart")
            Thread.sleep(1300)
            assertEquals(1, requestCount.get())
            assertNull(credentials.poll())
        }
    }

    @Test fun publicationDisconnectHasUnknownOutcomeAndIsNeverReplayed() = Network().use { n ->
        val writes = AtomicInteger()
        val url = n.relay { ws, text -> if (fields(text)[0].jsonPrimitive.content == "EVENT") {
            writes.incrementAndGet()
            ws.close(1001, "lost acknowledgment")
        } }
        NostrMessagingCoordinator(manager(), n.http, listOf(url), timeoutMillis = 400).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.UNKNOWN,
                c.announce(listOf(url)).get(5, TimeUnit.SECONDS).single().stage)
            Thread.sleep(1200)
            assertEquals(1, writes.get())
            assertEquals(1, n.servers.single().requestCount)
        }
    }

    @Test fun threeInboxBoundAndRepeatedConfigurationDoNotDuplicateSubscriptions(): Unit = Network().use { n ->
        val requests = LinkedBlockingQueue<String>()
        val urls = (1..3).map { n.relay { _, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") requests.add(text) } }
        NostrMessagingCoordinator(manager(), n.http, listOf(urls.first())).use { c ->
            c.startReceiving(listOf(urls.first(), urls.first())).get(5, TimeUnit.SECONDS)
            assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
            c.startReceiving(urls).get(5, TimeUnit.SECONDS)
            repeat(3) { assertNotNull(requests.poll(5, TimeUnit.SECONDS)) }
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
            assertThrows(ExecutionException::class.java) {
                c.startReceiving(urls + urls.first()).get(5, TimeUnit.SECONDS)
            }
            c.startReceiving(emptyList()).get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun receivingOverflowIsReportedAndRetriesRemainDeduplicated(): Unit = Network().use { n ->
        val entered = CountDownLatch(1)
        val permit = Semaphore(0)
        val block = AtomicBoolean()
        val id = NostrIdentityManager(object : NostrIdentityStorage {
            override fun read(): ByteArray {
                if (block.compareAndSet(true, false)) { entered.countDown(); permit.acquireUninterruptibly() }
                return alice.copyOf()
            }
            override fun write(secret: ByteArray) = error("Unexpected write")
        })
        val sockets = LinkedBlockingQueue<WebSocket>()
        val issues = LinkedBlockingQueue<NostrMessagingCoordinator.Issue>()
        val received = LinkedBlockingQueue<UnsignedNostrEvent>()
        val url = n.relay { ws, text -> if (fields(text)[0].jsonPrimitive.content == "REQ") sockets.add(ws) }
        NostrMessagingCoordinator(id, n.http, listOf(url), onReceived = { received.add(it) },
            onRelayIssue = { _, issue -> issues.add(issue) }).use { c ->
            try {
                c.startReceiving(listOf(url)).get(5, TimeUnit.SECONDS)
                val ws = sockets.poll(5, TimeUnit.SECONDS)!!
                val wrap = Nip17().createMessage(bob, pub(alice), "once").recipientCopy
                val wire = "[\"EVENT\",\"inbox\",${NostrEventCodec.encode(wrap)}]"
                block.set(true); ws.send(wire)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(80) { ws.send(wire) }
                ws.send("[\"AUTH\",\"challenge while full\"]")
                Thread.sleep(700)
                permit.release()
                val observed = (1..2).map { issues.poll(5, TimeUnit.SECONDS) }
                assertTrue(observed.contains(NostrMessagingCoordinator.Issue.RECEIVE_QUEUE_FULL))
                assertTrue(observed.contains(NostrMessagingCoordinator.Issue.AUTHENTICATION_REQUIRED))
                assertEquals("once", received.poll(5, TimeUnit.SECONDS)!!.content)
                assertNull(received.poll(300, TimeUnit.MILLISECONDS))
            } finally { permit.release() }
        }
    }

    @Test fun discoveryDiscardsTimedOutPartialAnnouncements() = Network().use { n ->
        val valid = list(bob, listOf("wss://unused.example"))
        val url = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "REQ") {
                ws.send("[\"EVENT\",${f[1]},${NostrEventCodec.encode(valid)}]")
                // Intentionally no EOSE: this is not a complete discovery response.
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url), timeoutMillis = 300).use { c ->
            assertNull(c.discover(pub(bob)).get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun unrelatedAcceptanceCannotOverrideMatchingRejection() = Network().use { n ->
        val unrelated = "00".repeat(32)
        val url = n.relay { ws, text -> val f = fields(text)
            if (f[0].jsonPrimitive.content == "EVENT") {
                ws.send("[\"OK\",\"$unrelated\",true,\"\"]")
                ack(ws, NostrEventCodec.decode(f[1].toString()), false)
            }
        }
        NostrMessagingCoordinator(manager(), n.http, listOf(url)).use { c ->
            assertEquals(NostrMessagingCoordinator.Stage.RELAY_REJECTED,
                c.announce(listOf(url)).get(5, TimeUnit.SECONDS).single().stage)
        }
    }

}
