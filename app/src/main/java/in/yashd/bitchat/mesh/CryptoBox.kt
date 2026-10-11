package `in`.yashd.bitchat.mesh

import `in`.yashd.bitchat.identity.IdentityManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoBox {
    private val random = SecureRandom()

    fun encrypt(localPrivateKey: ByteArray, remotePublicKey: ByteArray, plaintext: ByteArray): ByteArray {
        val key = sharedKey(localPrivateKey, remotePublicKey)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(localPrivateKey: ByteArray, remotePublicKey: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 12)
        val key = sharedKey(localPrivateKey, remotePublicKey)
        val iv = ciphertext.copyOfRange(0, 12)
        val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun sharedKey(localPrivateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(IdentityManager.decodePrivateKey(localPrivateKey))
        agreement.doPhase(IdentityManager.decodePublicKey(remotePublicKey), true)
        return MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret())
    }
}
