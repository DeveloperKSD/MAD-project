package `in`.yashd.bitchat.nostr

import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.Secp256k1Exception
import java.security.SecureRandom

/** Small boundary around ACINQ's Bitcoin Core secp256k1 bindings. No custom cryptography. */
class SchnorrSignature {
    private val random = SecureRandom()

    fun publicKey(secretKey: ByteArray): ByteArray {
        validateSecretKey(secretKey)
        // libsecp256k1 returns 04 || x || y; BIP-340 uses the 32-byte x coordinate.
        return Secp256k1.pubkeyCreate(secretKey).copyOfRange(1, 33)
    }

    fun sign(
        hash: ByteArray,
        secretKey: ByteArray,
        auxiliaryRandomness: ByteArray = ByteArray(32).also(random::nextBytes)
    ): ByteArray {
        require(hash.size == 32) { "Nostr signs a 32-byte event hash" }
        require(auxiliaryRandomness.size == 32) { "Auxiliary randomness must be 32 bytes" }
        validateSecretKey(secretKey)
        return Secp256k1.signSchnorr(hash, secretKey, auxiliaryRandomness)
    }

    fun verify(hash: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (hash.size != 32 || publicKey.size != 32 || signature.size != 64) return false
        return try {
            Secp256k1.verifySchnorr(signature, hash, publicKey)
        } catch (_: Secp256k1Exception) {
            false
        }
    }

    private fun validateSecretKey(secretKey: ByteArray) {
        require(secretKey.size == 32 && Secp256k1.secKeyVerify(secretKey)) {
            "Invalid secp256k1 secret key"
        }
    }
}
