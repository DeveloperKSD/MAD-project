package `in`.yashd.bitchat.nostr

import org.junit.Assert.*
import org.junit.Test

class NostrIdentityManagerTest {
    private class MemoryStorage : NostrIdentityStorage {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(secret: ByteArray) { bytes = secret.copyOf(); writes++ }
    }

    @Test fun createsValidIdentityAndReloadsAcrossInstances() {
        val storage = MemoryStorage()
        val first = NostrIdentityManager(storage).loadOrCreate()
        assertEquals(first, NostrIdentityManager(storage).loadOrCreate())
        assertEquals(1, storage.writes)
        assertEquals(SchnorrSignature().publicKey(storage.bytes!!).lowercaseHex(), first.publicKeyHex)
        assertArrayEquals(first.publicKeyHex.hexBytes(), NostrNpub.decode(first.npub))
        assertNotEquals(first, NostrIdentityManager(MemoryStorage()).loadOrCreate())
    }

    @Test fun corruptIdentityIsPreserved() {
        val storage = MemoryStorage().apply { bytes = ByteArray(32) }
        repeat(2) {
            assertThrows(NostrIdentityException::class.java) { NostrIdentityManager(storage).loadOrCreate() }
        }
        assertEquals(0, storage.writes)
        assertArrayEquals(ByteArray(32), storage.bytes)
    }

    @Test fun storageFailureDoesNotGenerateReplacement() {
        var writes = 0
        val storage = object : NostrIdentityStorage {
            override fun read(): ByteArray? = error("Unavailable")
            override fun write(secret: ByteArray) { writes++ }
        }
        assertThrows(NostrIdentityException::class.java) { NostrIdentityManager(storage).loadOrCreate() }
        assertEquals(0, writes)
    }

    @Test fun writeFailureIsReportedAndTemporarySecretIsCleared() {
        var captured: ByteArray? = null
        val storage = object : NostrIdentityStorage {
            override fun read(): ByteArray? = null
            override fun write(secret: ByteArray) { captured = secret; error("Unavailable") }
        }
        assertThrows(NostrIdentityException::class.java) { NostrIdentityManager(storage).loadOrCreate() }
        assertArrayEquals(ByteArray(32), captured)
    }
    @Test fun scopedSecretIsClearedOnSuccessAndFailure() {
        val storage = MemoryStorage()
        val manager = NostrIdentityManager(storage)
        var captured: ByteArray? = null
        val publicKey = manager.withSecret { captured = it; SchnorrSignature().publicKey(it).lowercaseHex() }
        assertEquals(manager.loadOrCreate().publicKeyHex, publicKey)
        assertArrayEquals(ByteArray(32), captured)
        assertThrows(IllegalStateException::class.java) {
            manager.withSecret { captured = it; error("Consumer failure") }
        }
        assertArrayEquals(ByteArray(32), captured)
        assertEquals(1, storage.writes)
    }

    @Test fun backgroundReadsNeverCreateIdentityAndClearSecretBuffers() {
        val storage = MemoryStorage()
        val manager = NostrIdentityManager(storage)
        assertNull(manager.loadExisting())
        assertEquals(0, storage.writes)
        assertThrows(IllegalStateException::class.java) { manager.withExistingSecret { fail("No saved key") } }
        assertEquals(0, storage.writes)
        val identity = manager.loadOrCreate()
        assertEquals(identity, NostrIdentityManager(storage).loadExisting())
        var captured: ByteArray? = null
        manager.withExistingSecret { captured = it }
        assertArrayEquals(ByteArray(32), captured)
        storage.bytes = ByteArray(32)
        assertThrows(NostrIdentityException::class.java) { manager.loadExisting() }
        assertEquals(1, storage.writes)
    }

}
