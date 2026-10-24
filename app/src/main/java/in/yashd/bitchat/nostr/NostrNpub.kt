package `in`.yashd.bitchat.nostr

/** NIP-19 public keys only, using Bech32 (not Bech32m). */
object NostrNpub {
    private const val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val prefix = intArrayOf(3, 3, 3, 3, 0, 14, 16, 21, 2) // HRP expansion of npub
    private val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Public key must be 32 bytes" }
        val words = convert(publicKey.map { it.toInt() and 255 }, 8, 5, true)
        val checksum = polymod(prefix.toList() + words + List(6) { 0 }) xor 1
        return "npub1" + (words + List(6) { (checksum ushr (5 * (5 - it))) and 31 })
            .joinToString("") { alphabet[it].toString() }
    }

    fun decode(npub: String): ByteArray {
        require(npub.length == 63 && (npub == npub.lowercase() || npub == npub.uppercase())) {
            "Invalid npub length or case"
        }
        val normalized = npub.lowercase()
        require(normalized.startsWith("npub1")) { "Expected npub" }
        val words = normalized.drop(5).map { alphabet.indexOf(it).also { n -> require(n >= 0) } }
        require(polymod(prefix.toList() + words) == 1) { "Invalid npub checksum" }
        return convert(words.dropLast(6), 5, 8, false).map { it.toByte() }.toByteArray()
            .also { require(it.size == 32) }
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0..4) if (((top ushr i) and 1) != 0) checksum = checksum xor generators[i]
        }
        return checksum
    }

    private fun convert(values: List<Int>, from: Int, to: Int, pad: Boolean): List<Int> {
        var accumulator = 0
        var bits = 0
        val result = mutableListOf<Int>()
        for (value in values) {
            accumulator = ((accumulator shl from) or value) and ((1 shl (from + to - 1)) - 1)
            bits += from
            while (bits >= to) {
                bits -= to
                result += (accumulator ushr bits) and ((1 shl to) - 1)
            }
        }
        if (pad && bits > 0) result += (accumulator shl (to - bits)) and ((1 shl to) - 1)
        else require(bits < from && ((accumulator shl (to - bits)) and ((1 shl to) - 1)) == 0) {
            "Invalid npub padding"
        }
        return result
    }
}
