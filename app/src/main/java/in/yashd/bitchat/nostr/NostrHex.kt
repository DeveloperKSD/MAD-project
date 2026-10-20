package `in`.yashd.bitchat.nostr

internal fun String.isLowercaseHex(bytes: Int): Boolean =
    length == bytes * 2 && all { it in '0'..'9' || it in 'a'..'f' }

internal fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' })
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal fun ByteArray.lowercaseHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@lowercaseHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }
}
