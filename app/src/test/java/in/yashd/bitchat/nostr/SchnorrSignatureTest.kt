package `in`.yashd.bitchat.nostr

import org.junit.Assert.*
import org.junit.Test

class SchnorrSignatureTest {
    private val schnorr = SchnorrSignature()

    @Test fun publishedBip340VectorsFor32ByteMessages() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/nostr/bip340-test-vectors.csv"))
        val rows = stream.bufferedReader().use { it.readLines() }.drop(1)
        var verificationCases = 0
        var signingCases = 0
        rows.filter { it.isNotBlank() }.forEach { row ->
            val fields = row.split(",", limit = 8)
            // NIP-01 always signs SHA-256 hashes. BIP-340 vectors 15–18 test other lengths.
            if (fields[4].length != 64) return@forEach
            val label = "BIP-340 vector ${fields[0]}: ${fields[7]}"
            val publicKey = fields[2].lowercase().hexBytes()
            val hash = fields[4].lowercase().hexBytes()
            val signature = fields[5].lowercase().hexBytes()
            assertEquals(label, fields[6] == "TRUE", schnorr.verify(hash, publicKey, signature))
            verificationCases++
            if (fields[1].isNotEmpty()) {
                val secret = fields[1].lowercase().hexBytes()
                assertArrayEquals(label, publicKey, schnorr.publicKey(secret))
                assertArrayEquals(
                    label, signature,
                    schnorr.sign(hash, secret, fields[3].lowercase().hexBytes())
                )
                signingCases++
            }
        }
        assertEquals(15, verificationCases)
        assertEquals(4, signingCases)
    }

    @Test fun invalidLengthsAndSecretsAreHandled() {
        assertFalse(schnorr.verify(ByteArray(31), ByteArray(32), ByteArray(64)))
        assertFalse(schnorr.verify(ByteArray(32), ByteArray(33), ByteArray(64)))
        assertFalse(schnorr.verify(ByteArray(32), ByteArray(32), ByteArray(63)))
        listOf(ByteArray(31), ByteArray(32), ByteArray(32) { 0xff.toByte() }).forEach {
            assertThrows(IllegalArgumentException::class.java) { schnorr.publicKey(it) }
        }
        val secret = ByteArray(32).also { it[31] = 3 }
        assertThrows(IllegalArgumentException::class.java) { schnorr.sign(ByteArray(31), secret) }
        assertThrows(IllegalArgumentException::class.java) {
            schnorr.sign(ByteArray(32), secret, ByteArray(31))
        }
    }
}
