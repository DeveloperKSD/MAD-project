package `in`.yashd.bitchat.data.internet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent of the Nostr identity wrapping key. Missing keys are never recreated on read. */
class HistoryCipher(private val alias: String = KEY_ALIAS) {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun exists(): Boolean = store().containsAlias(alias)
    fun create() = synchronized(KEY_CREATION_LOCK) {
        check(!exists())
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        Unit
    }
    private fun key() = store().getKey(alias, null) as? SecretKey ?: throw LocalHistoryException()
    fun encrypt(value: String, binding: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad(binding))
            byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
        } finally { bytes.fill(0) }
    }
    fun decrypt(envelope: ByteArray, binding: String): String {
        require(envelope.size >= 29 && envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(aad(binding))
        val bytes = cipher.doFinal(envelope, 13, envelope.size - 13)
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }
    private fun aad(binding: String) = "relay.local-history.aes-gcm.v1\u0000$binding".toByteArray(Charsets.UTF_8)
    companion object { private val KEY_CREATION_LOCK = Any(); const val KEY_ALIAS = "relay.internet.history.aes.v1" }
}
