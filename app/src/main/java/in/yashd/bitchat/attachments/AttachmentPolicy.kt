package `in`.yashd.bitchat.attachments

import `in`.yashd.bitchat.transfer.TransferLimits
import `in`.yashd.bitchat.transfer.TransferProfile
import java.io.InputStream
import java.io.OutputStream

/** Content signatures, never a provider's filename or advertised MIME type, choose the type. */
enum class AttachmentKind(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), PDF("application/pdf", "pdf")
}
object AttachmentPolicy {
    const val MAX_IMAGE_PIXELS = 16_000_000L
    const val PREVIEW_EDGE = 512
    const val STORAGE_MARGIN = 1024L * 1024
    fun kind(prefix: ByteArray): AttachmentKind? = when {
        prefix.size >= 8 && prefix.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() } -> AttachmentKind.PNG
        prefix.size >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte() -> AttachmentKind.JPEG
        prefix.size >= 8 && String(prefix.take(8).toByteArray(), Charsets.US_ASCII).matches(Regex("%PDF-[12]\\.[0-9]")) -> AttachmentKind.PDF
        else -> null
    }
    fun safeName(name: String?, kind: AttachmentKind): String {
        val segment = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val leaf = segment.substringBeforeLast('.', segment)
        val base = leaf.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(48).ifEmpty { "attachment" }
        return "$base.${kind.extension}"
    }
    fun maximum(multiHop: Boolean) = TransferLimits().maxBytes(if (multiHop) TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE)
    /** Space for an import, the Stage 1 snapshot and a private verified export, plus overhead. */
    fun checkSpace(available: Long, bytes: Long) { require(available >= bytes * 3 + STORAGE_MARGIN) { "Not enough private storage. Free space and try again." } }
    fun copyBounded(input: InputStream, output: OutputStream, maximum: Long): Long {
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maximum - total + 1).toInt())
            if (count < 0) break
            if (count == 0) { val one = input.read(); if (one < 0) break; require(total < maximum) { "File exceeds the BLE size limit." }; output.write(one); total++ }
            else { require(total + count <= maximum) { "File exceeds the BLE size limit." }; output.write(buffer, 0, count); total += count }
        }
        require(total > 0) { "Empty files are not supported by the attachment UI." }
        return total
    }
}
