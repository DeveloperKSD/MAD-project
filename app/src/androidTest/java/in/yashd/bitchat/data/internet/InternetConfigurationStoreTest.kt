package `in`.yashd.bitchat.data.internet

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.security.KeyStore

class InternetConfigurationStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.noBackupFilesDir, "configuration_test.enc")
    private val alias = "relay.configuration.instrumentation.test"
    private val owner = "01".repeat(32)
    private val configuration = InternetConfiguration(listOf("wss://discovery.example/path"), listOf("wss://inbox.example"))
    private fun storage(beforeCommit: () -> Unit = {}) =
        EncryptedInternetConfigurationStore(context, file, HistoryCipher(alias), beforeCommit)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Before fun prepare() = clean()
    @After fun clean() {
        listOf("", ".bak", ".new").forEach { File(file.path + it).delete() }
        keys().deleteEntry(alias)
    }
    private fun fails(action: () -> Unit) {
        try { action(); fail("Expected safe storage failure") } catch (_: LocalConfigurationException) { }
    }
    @Test fun newStoreRestoresEncryptedValidatedSettings() {
        assertNull(storage().load(owner))
        storage().save(owner, configuration)
        assertEquals(configuration, storage().load(owner))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("discovery.example"))
        assertNull(keys().getKey(alias, null).encoded)
    }
    @Test fun viewModelRecreationRestoresSettingsFromDiskAndOnlyConnectsWhenStarted() {
        val stores = mutableListOf<androidx.lifecycle.ViewModelStore>()
        var opened = 0
        var announced = 0
        val backend = object : `in`.yashd.bitchat.ui.relay.InternetBackend {
            override fun identity() = `in`.yashd.bitchat.nostr.NostrIdentity(owner,
                `in`.yashd.bitchat.nostr.NostrNpub.encode(ByteArray(32) { 1 }))
            override fun open(discovery: List<String>, received: (`in`.yashd.bitchat.nostr.UnsignedNostrEvent) -> Unit,
                connection: (String, String) -> Unit, issue: (String) -> Unit): `in`.yashd.bitchat.ui.relay.InternetSession {
                opened++
                return object : `in`.yashd.bitchat.ui.relay.InternetSession {
                    override fun receive(inboxes: List<String>) = java.util.concurrent.CompletableFuture.completedFuture(Unit)
                    override fun announce(inboxes: List<String>): java.util.concurrent.Future<List<`in`.yashd.bitchat.nostr.NostrMessagingCoordinator.Publication>> {
                        announced++
                        return java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                    }
                    override fun send(recipient: String, body: String, prepared: (String) -> Unit): java.util.concurrent.Future<`in`.yashd.bitchat.nostr.NostrMessagingCoordinator.SendResult> = error("Unexpected send")
                    override fun close() {}
                }
            }
            override fun close() {}
        }
        fun model() = `in`.yashd.bitchat.ui.relay.RelayViewModel(backend, kotlinx.coroutines.Dispatchers.Unconfined,
            TestHistoryRepository(), configurationStore = storage()).also { model ->
            stores.add(androidx.lifecycle.ViewModelStore().apply { put("relay", model) })
        }
        try {
            val first = model()
            first.configure(configuration.discovery.joinToString("\n"), configuration.inboxes.joinToString("\n"))
            stores.first().clear()
            val second = model()
            assertEquals(configuration.discovery, second.state.value.discovery)
            assertEquals(configuration.inboxes, second.state.value.inboxes)
            assertTrue(second.state.value.connections.isEmpty())
            assertEquals(0, opened)
            second.start()
            assertEquals(1, opened)
            assertFalse(second.state.value.inboxesConnected)
            assertEquals(0, announced)
        } finally { stores.forEach { it.clear() } }
    }
    @Test fun failedFirstWriteDoesNotBecomeSuccessfulOrReplaceSurvivingKey() {
        fails { storage { throw IOException("test failure") }.save(owner, configuration) }
        assertFalse(file.exists())
        val key = keys().getKey(alias, null)
        assertNotNull(key)
        fails { storage().load(owner) }
        fails { storage().save(owner, configuration) }
        assertEquals(key, keys().getKey(alias, null))
    }
    @Test fun anotherIdentityCannotReadOrOverwrite() {
        storage().save(owner, configuration)
        val bytes = file.readBytes()
        val other = "02".repeat(32)
        fails { storage().load(other) }
        fails { storage().save(other, configuration) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(configuration, storage().load(owner))
    }
    @Test fun corruptCiphertextCannotBeOverwrittenEvenByNewStore() {
        storage().save(owner, configuration)
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        fails { storage().load(owner) }
        fails { storage().save(owner, configuration) }
        assertArrayEquals(damaged, file.readBytes())
    }
    @Test fun missingKeyDoesNotCreateReplacement() {
        storage().save(owner, configuration)
        val bytes = file.readBytes()
        keys().deleteEntry(alias)
        fails { storage().load(owner) }
        fails { storage().save(owner, configuration) }
        assertFalse(keys().containsAlias(alias))
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun missingFileWithSurvivingKeyIsNotTreatedAsFreshInstall() {
        storage().save(owner, configuration)
        assertTrue(file.delete())
        fails { storage().load(owner) }
        fails { storage().save(owner, configuration) }
        assertFalse(file.exists())
    }
    @Test fun interruptedReplacementKeepsPreviousSettings() {
        storage().save(owner, configuration)
        val bytes = file.readBytes()
        fails { storage { throw IOException("test write failure") }.save(owner,
            InternetConfiguration(listOf("wss://new.example"), configuration.inboxes)) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(configuration, storage().load(owner))
    }
    @Test fun authenticatedButInvalidUrlsAreRejectedAndPreserved() {
        HistoryCipher(alias).create()
        val payload = """{"version":1,"discovery":["ws://unsafe.example"],"inboxes":["wss://inbox.example"]}"""
        val bytes = HistoryCipher(alias).encrypt(payload, "internet-configuration-v1:$owner")
        file.writeBytes(bytes)
        fails { storage().load(owner) }
        fails { storage().save(owner, configuration) }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun validationFailureDoesNotTouchSavedData() {
        storage().save(owner, configuration)
        val bytes = file.readBytes()
        try {
            storage().save(owner, InternetConfiguration(listOf("ws://unsafe.example"), configuration.inboxes))
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(configuration, storage().load(owner))
    }
}
