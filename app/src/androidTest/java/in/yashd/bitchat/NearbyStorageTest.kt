package `in`.yashd.bitchat

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.*
import `in`.yashd.bitchat.mesh.*
import java.security.KeyStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NearbyStorageTest {
    private val token = UUID.randomUUID().toString()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("test-$token-$name", mode)
    }
    private val alias = "relay.test.nearby.$token"
    private fun cipher() = HistoryCipher(alias)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After fun cleanup() {
        listOf("identity", "messages").forEach { base.deleteSharedPreferences("test-$token-$it") }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun privateHistoryEncryptsAndOnlyTheRecipientCanAcknowledge() {
        val store = MessageStore(context, cipher())
        val sent = store.createOutbound("alice", "bob", "PRIVATE_NEARBY_BODY", true)
        store.updateStatus(sent.messageId, "packet", MessageStatus.SentToTransport)
        store.markPacketDelivered("packet", "eve")
        assertEquals(MessageStatus.SentToTransport, store.conversation.value.single().status)
        store.markPacketDelivered("packet", "bob")
        assertEquals(MessageStatus.Delivered, MessageStore(context, cipher()).conversation.value.single().status)
        assertFalse(prefs("messages").all.toString().contains("PRIVATE_NEARBY_BODY"))
        assertFalse(prefs("messages").contains("items"))
    }

    @Test fun corruptLegacyHistoryBlocksWritesAndPreservesEveryOriginalByte() {
        val raw = """[{"messageId":"one","senderId":"alice","recipientId":null,"body":"KEEP","createdAtMs":1,"receivedAtMs":null,"isPrivate":false,"status":"Pending","direction":"Outbound"},{"messageId":"broken","status":"invalid"}]"""
        prefs("messages").edit().putString("items", raw).commit()
        val store = MessageStore(context, cipher())
        assertTrue(store.storageError)
        assertThrows(IllegalStateException::class.java) { store.createOutbound("alice", null, "new", false) }
        assertEquals(raw, prefs("messages").getString("items", null))
        assertFalse(prefs("messages").contains("items.enc"))
    }

    @Test fun legacyHistoryMigratesAfterSuccessfulWholeArrayValidation() {
        val item = JSONObject().put("messageId", "legacy").put("senderId", "alice").put("body", "OLD_PRIVATE_TEXT")
            .put("createdAtMs", 1).put("isPrivate", true).put("status", "Delivered").put("direction", "Inbound")
        prefs("messages").edit().putString("items", JSONArray().put(item).toString()).commit()
        val store = MessageStore(context, cipher())
        assertFalse(store.storageError)
        assertEquals("OLD_PRIVATE_TEXT", store.conversation.value.single().body)
        assertFalse(prefs("messages").contains("items"))
        assertFalse(prefs("messages").all.toString().contains("OLD_PRIVATE_TEXT"))
    }

    @Test fun nearbyRetentionIsBoundedAcrossRestart() {
        val store = MessageStore(context, cipher())
        repeat(205) { store.createOutbound("alice", null, "message-$it", false) }
        val raw = cipher().decrypt(prefs("messages").getString("items.enc", null)!!.decode64(), "nearby-messages-v1")
        assertEquals(200, JSONArray(raw).length())
        assertFalse(raw.contains("\"message-0\""))
        assertEquals(100, MessageStore(context, cipher()).conversation.value.size)
    }

    @Test fun interruptedPendingSendRestoresAsFailedWithoutAutomaticResend() {
        val original = MessageStore(context, cipher()).createOutbound("alice", "bob", "preserve pending body", true)
        val reopened = MessageStore(context, cipher()).conversation.value.single()
        assertEquals(original.messageId, reopened.messageId)
        assertEquals("preserve pending body", reopened.body)
        assertEquals(MessageStatus.Failed, reopened.status)
        assertEquals(MessageStatus.Failed, MessageStore(context, cipher()).conversation.value.single().status)
    }

    @Test fun identityMigrationKeepsKeysBindsBothAndEncryptsSecrets() {
        val original = IdentityManager(context, cipher()).loadOrCreate { "Alice" }
        prefs("identity").edit().putString("peer_id", "old-id")
            .putString("enc_private", original.encryptionPrivateKey.encode64())
            .putString("sign_private", original.signingPrivateKey.encode64()).commit()
        val manager = IdentityManager(context, cipher())
        val migrated = manager.loadOrCreate { "unused" }
        assertEquals("old-id", manager.migratedFromPeerId)
        assertArrayEquals(original.encryptionPrivateKey, migrated.encryptionPrivateKey)
        assertArrayEquals(original.signingPrivateKey, migrated.signingPrivateKey)
        assertEquals(IdentityManager.derivePeerId(original.encryptionPublicKey, original.signingPublicKey), migrated.peerId)
        assertTrue(prefs("identity").getString("enc_private", "")!!.startsWith("encrypted:"))
        assertFalse(prefs("identity").all.toString().contains(original.encryptionPrivateKey.encode64()))
    }

    @Test fun missingHistoryKeyNeverCreatesReplacementOrOverwritesCiphertext() {
        val store = MessageStore(context, cipher())
        store.createOutbound("alice", null, "preserve", false)
        val raw = prefs("messages").getString("items.enc", null)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val reopened = MessageStore(context, cipher())
        assertTrue(reopened.storageError)
        assertThrows(IllegalStateException::class.java) { reopened.createOutbound("alice", null, "new", false) }
        assertEquals(raw, prefs("messages").getString("items.enc", null))
        assertFalse(cipher().exists())
    }
}
