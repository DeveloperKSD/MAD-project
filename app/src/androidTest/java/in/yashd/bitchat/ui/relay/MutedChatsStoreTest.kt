package `in`.yashd.bitchat.ui.relay

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.mesh.MessageStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class MutedChatsStoreTest {
    private val token = UUID.randomUUID().toString()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("test-$token-$name", mode)
    }
    private val alias = "relay.test.mutes.$token"
    private val key = MutedChatsStore.key("nearby", "alice", "bob")
    private fun store() = MutedChatsStore(context, HistoryCipher(alias))
    private fun prefs() = context.getSharedPreferences("mute_chats", Context.MODE_PRIVATE)

    @After fun cleanup() {
        listOf("mute_chats", "messages", "pinned_chats").forEach { base.deleteSharedPreferences("test-$token-$it") }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            listOf(alias, "$alias.messages", "$alias.pins").forEach { deleteEntry(it) }
        }
    }

    @Test fun togglesPersistEncryptedAcrossStoreRecreation() {
        assertTrue(store().toggle(key))
        assertEquals(setOf(key), store().mutes.value)
        assertFalse(prefs().all.toString().contains("alice"))
        assertFalse(prefs().all.toString().contains(key))
        assertTrue(store().toggle(key))
        assertTrue(store().mutes.value.isEmpty())
    }

    @Test fun transportsOwnersAndGroupCannotCollide() {
        val keys = setOf(key,
            MutedChatsStore.key("internet", "alice", "bob"),
            MutedChatsStore.key("nearby", "other-owner", "bob"),
            MutedChatsStore.key("nearby", "alice", null),
            MutedChatsStore.key("nearby", "alice", "group"))
        assertEquals(5, keys.size)
        val saved = store()
        keys.forEach { assertTrue(saved.toggle(it)) }
        assertEquals(keys, store().mutes.value)
    }

    @Test fun unfavoritingLeavesHistoryAndPinsUntouchedAndDeletionCleanupPersists() {
        val history = MessageStore(context, HistoryCipher("$alias.messages"))
        val message = history.createOutbound("alice", "bob", "keep this message", true)
        val pins = PinnedChatsStore(context, HistoryCipher("$alias.pins"))
        pins.toggle("nearby:bob")
        val saved = store()
        saved.toggle(key)
        saved.toggle(key)
        assertEquals(message.messageId, history.conversation.value.single().messageId)
        assertEquals(listOf("nearby:bob"), pins.pinned.value)
        saved.toggle(key)
        history.deleteConversation("bob")
        assertTrue(saved.remove(key))
        assertTrue(store().mutes.value.isEmpty())
    }

    @Test fun unreadablePreferencesBlockWritesAndPreserveOriginalBytes() {
        prefs().edit().putString("items.enc", "damaged saved ciphertext").commit()
        val saved = store()
        assertTrue(saved.storageError)
        assertFalse(saved.toggle(key))
        assertFalse(saved.remove(key))
        assertEquals("damaged saved ciphertext", prefs().getString("items.enc", null))
        assertFalse(HistoryCipher(alias).exists())
    }

    @Test fun overlappingUiInstancesDoNotOverwriteEachOthersChanges() {
        val first = store()
        val second = store()
        val internet = MutedChatsStore.key("internet", "alice", "bob")
        first.toggle(key)
        second.toggle(internet)
        assertEquals(setOf(key, internet), store().mutes.value)
        first.remove(key)
        assertEquals(setOf(internet), store().mutes.value)
    }

    @Test fun keyLossAfterLoadingPreservesCiphertextAndLastKnownState() {
        val saved = store()
        saved.toggle(key)
        val original = prefs().getString("items.enc", null)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertFalse(saved.toggle(key))
        assertEquals(setOf(key), saved.mutes.value)
        assertEquals(original, prefs().getString("items.enc", null))
        assertFalse(HistoryCipher(alias).exists())
    }

    @Test fun lostKeyNeverReplacesSavedCiphertext() {
        store().toggle(key)
        val original = prefs().getString("items.enc", null)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val saved = store()
        assertTrue(saved.storageError)
        assertFalse(saved.toggle(key))
        assertEquals(original, prefs().getString("items.enc", null))
        assertFalse(HistoryCipher(alias).exists())
    }
}
