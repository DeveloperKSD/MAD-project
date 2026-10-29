package `in`.yashd.bitchat.attachments

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = 29)
class AttachmentPickerImportTest {
    @Test fun providerMimeAndNameCannotOverrideContentAndActualStreamLimit() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "test-picker-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "fixture")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("../../invoice.exe", 10)) }
            override fun getType(uri: Uri) = "image/png" // Deliberately lies.
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(base, ProviderInfo().apply { authority = "fixture"; exported = true })
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = root
            override fun getContentResolver() = ContentResolver.wrap(provider)
        }
        try {
            val files = PrivateAttachments(context)
            source.writeText("%PDF-1.7\n%%EOF")
            val picked = files.import(Uri.parse("content://fixture/test"), false)
            assertEquals(AttachmentKind.PDF, picked.kind); assertEquals("invoice.pdf", picked.name)
            assertTrue(picked.file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            picked.file.delete()
            source.writeBytes(ByteArray(250 * 1024 + 1) { 3 })
            assertThrows(Exception::class.java) { files.import(Uri.parse("content://fixture/test"), true) }
            assertEquals(0, files.directory.listFiles()!!.size)
            assertThrows(Exception::class.java) { files.import(Uri.parse("file:///sdcard/secret"), false) }
        } finally { root.deleteRecursively() }
    }
}
