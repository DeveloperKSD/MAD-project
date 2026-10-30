package `in`.yashd.bitchat.attachments

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class AttachmentPolicyTest {
    @Test fun contentSignaturesIgnoreSpoofedExtensionsAndRejectUnknownTypes() {
        assertEquals(AttachmentKind.JPEG, AttachmentPolicy.kind(byteArrayOf(-1, -40, -1, 0)))
        assertEquals(AttachmentKind.PNG, AttachmentPolicy.kind(byteArrayOf(-119,80,78,71,13,10,26,10)))
        assertEquals(AttachmentKind.PDF, AttachmentPolicy.kind("%PDF-1.7".toByteArray()))
        listOf(byteArrayOf(), "GIF89a".toByteArray(), "%PDF-x.y".toByteArray(), "<script>".toByteArray()).forEach {
            assertNull(AttachmentPolicy.kind(it))
        }
    }
    @Test fun filenamesCannotEscapeDirectoriesOrKeepMisleadingExtensions() {
        assertEquals("invoice.pdf", AttachmentPolicy.safeName("../../invoice.exe", AttachmentKind.PDF))
        assertEquals("image.png", AttachmentPolicy.safeName("C:\\private\\image.jpg", AttachmentKind.PNG))
        assertEquals("attachment.jpg", AttachmentPolicy.safeName("..", AttachmentKind.JPEG))
        assertTrue(AttachmentPolicy.safeName("a".repeat(500), AttachmentKind.PDF).length <= 52)
        assertFalse(AttachmentPolicy.safeName("ab\u202E\n/cd.pdf", AttachmentKind.PDF).contains('/'))
    }
    @Test fun exactDirectAndMultiHopLimitsAreEnforcedEvenWithUnknownStreamLength() {
        listOf(false, true).forEach { multi ->
            val max = AttachmentPolicy.maximum(multi)
            assertEquals(if (multi) 250L * 1024 else 1024L * 1024, max)
            val output = ByteArrayOutputStream()
            assertEquals(max, AttachmentPolicy.copyBounded(ByteArrayInputStream(ByteArray(max.toInt())), output, max))
            val rejected = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.copyBounded(ByteArrayInputStream(ByteArray(max.toInt() + 1)), rejected, max) }
            assertTrue(rejected.size() <= max)
        }
    }
    @Test fun zeroProgressStreamsAreFiniteAndEmptyFilesAreRejected() {
        val stream = object : InputStream() {
            var left = 2
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int = if (left-- > 0) 7 else -1
        }
        assertEquals(2L, AttachmentPolicy.copyBounded(stream, ByteArrayOutputStream(), 10))
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 10) }
    }
    @Test fun spaceReservesPrivateCopiesAndOverhead() {
        val needed = 3 * 1024L + AttachmentPolicy.STORAGE_MARGIN
        AttachmentPolicy.checkSpace(needed, 1024)
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.checkSpace(needed - 1, 1024) }
    }
}
