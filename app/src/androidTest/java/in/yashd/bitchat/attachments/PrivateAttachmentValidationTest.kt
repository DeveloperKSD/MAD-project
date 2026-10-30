package `in`.yashd.bitchat.attachments

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PrivateAttachmentValidationTest {
    private val files = PrivateAttachments(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun actualImageDecoderRejectsHeaderSpoofingAndBoundsLargePreviews() {
        val file = files.temporary()
        try {
            file.writeBytes(byteArrayOf(-119,80,78,71,13,10,26,10))
            assertThrows(Exception::class.java) { files.validate(file) }
            val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
            assertEquals(AttachmentKind.PNG, files.validate(file))
            files.preview(file).let { assertTrue(it.width <= 512 && it.height <= 512); it.recycle() }
        } finally { file.delete() }
    }
    @Test fun incompleteAndUnsupportedFilesCannotBeSavedAsSupportedAttachments() {
        val file = files.temporary()
        try {
            file.writeText("%PDF-1.7\nunfinished")
            assertThrows(Exception::class.java) { files.validate(file) }
            file.writeText("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF")
            assertEquals(AttachmentKind.PDF, files.validate(file))
            file.writeText("GIF89a-not-a-supported-image")
            assertThrows(Exception::class.java) { files.validate(file) }
        } finally { file.delete() }
    }
}
