package `in`.yashd.bitchat.nostr

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class NostrRelayTest {
    private fun event() = NostrEventSigner().createSignedEvent(ByteArray(32).apply { this[31] = 1 }, 1, "hello", createdAtSeconds = 100)
    private val filters = listOf(buildJsonObject { put("kinds", JsonArray(listOf(JsonPrimitive(1)))) })

    @Test fun clientEncoding() {
        assertEquals("[\"REQ\",\"sub\",{\"kinds\":[1]}]", NostrRelayProtocol.request("sub", filters))
        assertEquals("[\"CLOSE\",\"sub\"]", NostrRelayProtocol.close("sub"))
        val signed = event()
        assertEquals("[\"EVENT\",${NostrEventCodec.encode(signed)}]", NostrRelayProtocol.publish(signed))
    }
    @Test fun eventDecodingAndAuthentication() {
        val e = event()
        assertEquals(e, NostrEventCodec.decode(NostrEventCodec.encode(e)))
        val wire = "[\"EVENT\",\"sub\",${NostrEventCodec.encode(e)}]"
        assertEquals(RelayResponse.Event("sub", e), NostrRelayProtocol.decode(wire))
        assertNull(NostrRelayProtocol.decode(wire.replace("hello", "tampered")))
        assertNull(NostrRelayProtocol.decode(wire.replace(e.sig, "0".repeat(128))))
        assertNull(NostrRelayProtocol.decode(wire.replace(e.id, "0".repeat(64))))
    }
    @Test fun rejectsMalformedEventNumbers() {
        val wire = "[\"EVENT\",\"s\",${NostrEventCodec.encode(event())}]"
        listOf("0100", "+100", "\"100\"", "100.0", "1e2").forEach { number ->
            assertNull(number, NostrRelayProtocol.decode(wire.replace("\"created_at\":100", "\"created_at\":$number")))
        }
    }

    @Test fun responsesAndMalformedInput() {
        val id = event().id
        assertEquals(RelayResponse.Acceptance(id, true, ""), NostrRelayProtocol.decode("[\"OK\",\"$id\",true,\"\"]"))
        assertEquals(RelayResponse.Acceptance(id, false, "blocked: no"), NostrRelayProtocol.decode("[\"OK\",\"$id\",false,\"blocked: no\"]"))
        assertEquals(RelayResponse.EndOfStoredEvents("s"), NostrRelayProtocol.decode("[\"EOSE\",\"s\"]"))
        assertEquals(RelayResponse.Closed("s", "error: no"), NostrRelayProtocol.decode("[\"CLOSED\",\"s\",\"error: no\"]"))
        assertEquals(RelayResponse.Notice("hi"), NostrRelayProtocol.decode("[\"NOTICE\",\"hi\"]"))
        listOf("", "{", "[]", "null", "[1]", "[\"EVENT\",\"s\",{}]", "[\"OK\",\"$id\",\"true\",\"\"]", "[\"EOSE\",1]", "[\"NOTICE\",null]", "[\"UNKNOWN\"]", "[\"EOSE\",\"s\",0]").forEach { assertNull(it, NostrRelayProtocol.decode(it)) }
        assertNull(NostrRelayProtocol.decode("[\"NOTICE\",\"ééé\"]", 18))
        assertNull(NostrRelayProtocol.decode("[".repeat(1000)))
    }
    @Test fun rejectsInsecureUrlsAndInvalidSubscriptions() {
        val http = OkHttpClient()
        try {
            listOf("ws://localhost", "https://localhost", "wss://user:pass@localhost").forEach {
                assertThrows(IllegalArgumentException::class.java) { NostrRelayClient(it, http) }
            }
            listOf("", "s".repeat(65)).forEach {
                assertThrows(IllegalArgumentException::class.java) { NostrRelayProtocol.request(it, filters) }
            }
            assertThrows(IllegalArgumentException::class.java) { NostrRelayProtocol.request("s", emptyList()) }
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    private class Fixture(
        private val backoffMillis: Long = 20,
        failFirstHandshake: Boolean = false
    ) : AutoCloseable {
        @Volatile var stateAction: (NostrRelayClient.State) -> Unit = {}
        val server = MockWebServer()
        val opened = LinkedBlockingQueue<WebSocket>()
        val messages = LinkedBlockingQueue<String>()
        val responses = LinkedBlockingQueue<RelayResponse>()
        val states = LinkedBlockingQueue<NostrRelayClient.State>()
        val invalid = LinkedBlockingQueue<Boolean>()
        val http: OkHttpClient
        val client: NostrRelayClient
        init {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            if (failFirstHandshake) server.enqueue(MockResponse().setResponseCode(503))
            repeat(4) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { messages.add(text) }
            })) }
            server.start()
            client = NostrRelayClient(server.url("/").toString().replace("https://", "wss://"), http,
                maxMessageBytes = 4096, maxReconnectAttempts = 2, initialBackoffMillis = backoffMillis, maxBackoffMillis = backoffMillis * 2,
                onState = { states.add(it); stateAction(it) }, onResponse = { responses.add(it) }, onInvalidMessage = { invalid.add(true) })
        }
        fun connected(): WebSocket {
            val ws = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
            var next = states.poll(5, TimeUnit.SECONDS)
            while (next != NostrRelayClient.State.CONNECTED) {
                check(next != null) { "Timed out waiting for connected" }
                next = states.poll(5, TimeUnit.SECONDS)
            }
            return ws
        }
        fun message() = requireNotNull(messages.poll(5, TimeUnit.SECONDS))
        fun response() = requireNotNull(responses.poll(5, TimeUnit.SECONDS))
        override fun close() { client.close(); server.close(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    @Test fun tlsPublishingAcceptanceRejectionAndSubscriptions() = Fixture().use { f ->
        f.client.subscribe("s", filters)
        f.client.connect()
        val ws = f.connected()
        assertEquals(NostrRelayProtocol.request("s", filters), f.message())
        val e = event()
        assertTrue(f.client.publish(e))
        assertEquals(NostrRelayProtocol.publish(e), f.message())
        ws.send("[\"OK\",\"${e.id}\",true,\"\"]")
        assertEquals(RelayResponse.Acceptance(e.id, true, ""), f.response())
        ws.send("[\"OK\",\"${e.id}\",false,\"blocked: test\"]")
        assertEquals(RelayResponse.Acceptance(e.id, false, "blocked: test"), f.response())
        ws.send("[\"EVENT\",\"s\",${NostrEventCodec.encode(e)}]")
        assertEquals(RelayResponse.Event("s", e), f.response())
        ws.send("[\"EOSE\",\"s\"]")
        assertEquals(RelayResponse.EndOfStoredEvents("s"), f.response())
        ws.send("broken")
        assertEquals(true, f.invalid.poll(5, TimeUnit.SECONDS))
        ws.send("[\"NOTICE\",\"still alive\"]")
        assertEquals(RelayResponse.Notice("still alive"), f.response())
        f.client.cancelSubscription("s")
        assertEquals(NostrRelayProtocol.close("s"), f.message())
        ws.send("[\"EVENT\",\"s\",${NostrEventCodec.encode(e)}]")
        ws.send("[\"NOTICE\",\"barrier\"]")
        assertEquals(RelayResponse.Notice("barrier"), f.response())
    }
    @Test fun restoresOnlyActiveSubscriptionsAndStopsAfterClose(): Unit = Fixture().use { f ->
        f.client.subscribe("keep", filters)
        f.client.subscribe("cancel", filters)
        f.client.cancelSubscription("cancel")
        f.client.subscribe("closed", filters)
        f.client.connect()
        val first = f.connected()
        assertEquals(NostrRelayProtocol.request("keep", filters), f.message())
        assertEquals(NostrRelayProtocol.request("closed", filters), f.message())
        first.send("[\"CLOSED\",\"closed\",\"blocked: test\"]")
        assertEquals(RelayResponse.Closed("closed", "blocked: test"), f.response())
        first.close(1001, "restart")
        val second = f.connected()
        assertEquals(NostrRelayProtocol.request("keep", filters), f.message())
        second.close(1001, "restart")
        val third = f.connected()
        assertEquals(NostrRelayProtocol.request("keep", filters), f.message())
        third.close(1001, "budget exhausted")
        assertEquals(NostrRelayClient.State.ERROR, f.states.poll(5, TimeUnit.SECONDS))
        assertNull(f.opened.poll(200, TimeUnit.MILLISECONDS))
        f.client.close()
        assertEquals(NostrRelayClient.State.DISCONNECTED, f.client.state)
        assertThrows(IllegalStateException::class.java) { f.client.connect() }
    }
    @Test fun oversizedMessageTriggersReconnect() = Fixture().use { f ->
        f.client.connect()
        f.connected().send("x".repeat(4097))
        assertEquals(true, f.invalid.poll(5, TimeUnit.SECONDS))
        f.connected()
        f.client.close()
        assertNull(f.opened.poll(100, TimeUnit.MILLISECONDS))
    }
    @Test fun errorCallbackCannotStartDuplicateConnections() = Fixture(backoffMillis = 150).use { f ->
        f.stateAction = { if (it == NostrRelayClient.State.ERROR) f.client.connect() }
        f.client.subscribe("s", filters)
        f.client.connect()
        f.connected().close(1001, "restart")
        assertEquals(NostrRelayProtocol.request("s", filters), f.message())
        f.connected()
        assertEquals(NostrRelayProtocol.request("s", filters), f.message())
        assertNull("An ERROR callback must not bypass the pending retry", f.opened.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(2, f.server.requestCount)
    }

    @Test fun connectedCallbackDoesNotDuplicateSubscription() = Fixture().use { f ->
        f.stateAction = { if (it == NostrRelayClient.State.CONNECTED) f.client.subscribe("s", filters) }
        f.client.connect()
        f.connected()
        assertEquals(NostrRelayProtocol.request("s", filters), f.message())
        assertNull("The callback subscription must be sent once", f.messages.poll(200, TimeUnit.MILLISECONDS))
        // Repeated connect calls must also reuse this socket.
        repeat(3) { f.client.connect() }
        assertEquals(1, f.server.requestCount)
    }

    @Test fun closeCancelsPendingRetry(): Unit = Fixture(backoffMillis = 150).use { f ->
        f.stateAction = { if (it == NostrRelayClient.State.ERROR) f.client.close() }
        f.client.connect()
        f.connected().close(1001, "restart")
        assertEquals(NostrRelayClient.State.ERROR, f.states.poll(5, TimeUnit.SECONDS))
        assertEquals(NostrRelayClient.State.DISCONNECTED, f.states.poll(5, TimeUnit.SECONDS))
        assertNull(f.opened.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(1, f.server.requestCount)
        f.client.close()
        assertThrows(IllegalStateException::class.java) { f.client.publish(event()) }
    }

    @Test fun failedHandshakeReconnectsAndRestoresSubscription() = Fixture(failFirstHandshake = true).use { f ->
        f.client.subscribe("s", filters)
        f.client.connect()
        f.connected()
        assertEquals(NostrRelayProtocol.request("s", filters), f.message())
        assertEquals(2, f.server.requestCount)
    }

}
