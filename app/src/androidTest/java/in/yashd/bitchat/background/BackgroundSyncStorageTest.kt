package `in`.yashd.bitchat.background

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BackgroundSyncStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun optInAndSuccessSurviveReopenAndDisablePersists() {
        val file = File(context.noBackupFilesDir, "sync-test-${UUID.randomUUID()}.json")
        try {
            fun store() = FileBackgroundSyncStore(context, file)
            assertFalse(store().read().enabled)
            val value = BackgroundSyncDecision(true, "01".repeat(32), "explicit-approval", 123456L)
            store().write(value)
            assertEquals(value, store().read())
            // AtomicFile recovers the previous committed state after an interrupted write.
            File(file.path + ".new").writeText("interrupted")
            assertEquals(value, store().read())
            store().write(BackgroundSyncDecision())
            assertFalse(store().read().enabled)
        } finally {
            file.delete(); File(file.path + ".new").delete(); File(file.path + ".bak").delete()
        }
    }
    @Test fun corruptAndUnknownVersionDecisionsArePreservedAndFailClosed() {
        val file = File(context.noBackupFilesDir, "sync-test-${UUID.randomUUID()}.json")
        try {
            listOf("corrupt", """{"version":99,"enabled":true}""").forEach { damaged ->
                file.writeText(damaged)
                val store = FileBackgroundSyncStore(context, file)
                try { store.read(); fail("Expected read failure") } catch (_: IllegalArgumentException) {}
                try { store.write(BackgroundSyncDecision()); fail("Expected preservation") } catch (_: IllegalArgumentException) {}
                assertEquals(damaged, file.readText())
            }
        } finally { file.delete() }
    }
}
