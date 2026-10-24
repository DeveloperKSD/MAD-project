package `in`.yashd.bitchat.nostr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Version || 12-byte IV || authenticated ciphertext. Only encrypted bytes reach disk. */
internal class KeystoreNostrIdentityStorage(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
    fileName: String = FILE_NAME
) : NostrIdentityStorage {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun read(): ByteArray? {
        val store = keystore()
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            // A remaining wrapping key is evidence of a previous identity. Do not replace it.
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !store.containsAlias(keyAlias)) { "Identity data is missing" }
            return null
        }
        require(bytes.size == 61 && bytes[0] == 1.toByte()) { "Invalid identity envelope" }
        val key = store.getKey(keyAlias, null) as? SecretKey
            ?: error("Identity encryption key is missing")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(AAD)
            doFinal(bytes, 13, bytes.size - 13)
        }
    }

    override fun write(secret: ByteArray) {
        require(secret.size == 32)
        check(!file.baseFile.exists()) { "Identity already exists" }
        val store = keystore()
        check(!store.containsAlias(keyAlias)) { "Identity encryption key already exists" }
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val envelope = byteArrayOf(1) + cipher.iv + cipher.doFinal(secret)
        val output = file.startWrite()
        try {
            output.write(envelope)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    companion object {
        const val FILE_NAME = "nostr_identity.enc"
        const val KEY_ALIAS = "relay.nostr.identity.aes.v1"
        private val AAD = "relay.nostr.identity.v1".toByteArray(Charsets.US_ASCII)
    }
}
