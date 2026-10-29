package `in`.yashd.bitchat.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile

/** Only app-private, no-backup UI staging. The transfer engine remains the source of truth. */
class PrivateAttachments(private val context: Context) {
    val directory = File(context.noBackupFilesDir, "nearby-attachment-ui").apply { check(mkdirs() || isDirectory) }
    fun temporary() = File.createTempFile("attachment-", ".private", directory)
    fun import(uri: Uri, multiHop: Boolean): PickedAttachment {
        require(uri.scheme == "content") { "Choose a file using the system picker." }
        val maximum = AttachmentPolicy.maximum(multiHop)
        AttachmentPolicy.checkSpace(directory.usableSpace, maximum)
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = it.getString(index)
                val size = it.getColumnIndex(OpenableColumns.SIZE)
                if (size >= 0 && !it.isNull(size) && it.getLong(size) >= 0) require(it.getLong(size) in 1..maximum) { "File exceeds the BLE size limit or is empty." }
            }
        }
        val file = temporary()
        try {
            val size = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read selected file." }.use { input ->
                file.outputStream().use { AttachmentPolicy.copyBounded(input, it, maximum) }
            }
            val kind = validate(file)
            return PickedAttachment(file, AttachmentPolicy.safeName(name, kind), size, kind, multiHop)
        } catch (e: Exception) { file.delete(); throw e }
    }
    fun validate(file: File): AttachmentKind {
        val kind = file.inputStream().use { AttachmentPolicy.kind(it.readNBytesCompat(8)) }
            ?: error("Only JPEG, PNG and PDF files are supported. The content did not match.")
        if (kind == AttachmentKind.PDF) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(maxOf(0, input.length() - 1024)); val tail = ByteArray((input.length() - input.filePointer).toInt()); input.readFully(tail)
                require(String(tail, Charsets.ISO_8859_1).contains("%%EOF")) { "PDF appears incomplete." }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= AttachmentPolicy.MAX_IMAGE_PIXELS && bounds.outMimeType == kind.mime) {
                "Invalid image or image dimensions exceed the safe preview limit."
            }
            // Exercise the actual decoder, bounded to a thumbnail; headers alone are insufficient.
            preview(file).recycle()
        }
        return kind
    }
    fun preview(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= AttachmentPolicy.MAX_IMAGE_PIXELS)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AttachmentPolicy.PREVIEW_EDGE) sample *= 2
        return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "Image could not be decoded." }
    }
    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val result = ByteArray(count); var at = 0
        while (at < count) { val n = read(result, at, count - at); if (n < 0) break; if (n == 0) { val v = read(); if (v < 0) break; result[at++] = v.toByte() } else at += n }
        return result.copyOf(at)
    }
}
data class PickedAttachment(val file: File, val name: String, val size: Long, val kind: AttachmentKind, val multiHop: Boolean)
