package `in`.yashd.bitchat.nostr

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Uses a dedicated test file and Keystore alias, never the real identity. */
class NostrIdentityStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.noBackupFilesDir, "nostr_identity_test.enc")

    private fun storage() = KeystoreNostrIdentityStorage(context,
        "relay.nostr.identity.instrumentation.test", "nostr_identity_test.enc")

    @Before fun prepare() = clean()
    @After fun clean() {
        file.delete()
        File(file.path + ".bak").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("relay.nostr.identity.instrumentation.test")
    }

    @Test fun encryptedStorageSurvivesNewManagerAndStorageInstances() {
        val secret = ByteArray(32).also { it[31] = 3 }
        storage().write(secret)
        assertFalse(file.readBytes().toList().windowed(32).any { it == secret.toList() })
        assertEquals(61, file.length().toInt())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey("relay.nostr.identity.instrumentation.test", null).encoded)
        val expected = SchnorrSignature().publicKey(secret).lowercaseHex()
        repeat(2) { assertEquals(expected, NostrIdentityManager(storage()).loadOrCreate().publicKeyHex) }
        secret.fill(0)
    }

    @Test fun generatedIdentityPersists() {
        val first = NostrIdentityManager(storage()).loadOrCreate()
        assertEquals(first, NostrIdentityManager(storage()).loadOrCreate())
    }

    @Test fun tamperingIsReportedWithoutReplacement() {
        NostrIdentityManager(storage()).loadOrCreate()
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertFailure()
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun missingCiphertextOrKeystoreKeyDoesNotReplaceIdentity() {
        NostrIdentityManager(storage()).loadOrCreate()
        val encrypted = file.readBytes()
        assertTrue(file.delete())
        assertFailure()
        assertFalse(file.exists())
        file.writeBytes(encrypted)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("relay.nostr.identity.instrumentation.test")
        assertFailure()
        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test fun truncatedEnvelopeIsPreserved() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFailure()
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    private fun assertFailure() {
        try {
            NostrIdentityManager(storage()).loadOrCreate()
            fail("Expected identity storage failure")
        } catch (_: NostrIdentityException) { }
    }
}
