package `in`.yashd.bitchat.identity

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class IdentityManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)

    fun loadOrCreate(displayNameProvider: () -> String): LocalIdentity {
        val storedPeerId = prefs.getString(KEY_PEER_ID, null)
        val name = prefs.getString(KEY_DISPLAY_NAME, null)
        val encPub = prefs.getString(KEY_ENC_PUBLIC, null)
        val encPriv = prefs.getString(KEY_ENC_PRIVATE, null)
        val signPub = prefs.getString(KEY_SIGN_PUBLIC, null)
        val signPriv = prefs.getString(KEY_SIGN_PRIVATE, null)
        val createdAtMs = prefs.getLong(KEY_CREATED_AT, 0L)

        if (storedPeerId != null && name != null && encPub != null && encPriv != null && signPub != null && signPriv != null) {
            return LocalIdentity(
                peerId = storedPeerId,
                displayName = name,
                encryptionPublicKey = encPub.decode64(),
                encryptionPrivateKey = encPriv.decode64(),
                signingPublicKey = signPub.decode64(),
                signingPrivateKey = signPriv.decode64(),
                createdAtMs = createdAtMs
            )
        }

        return create(trimDisplayName(displayNameProvider()))
    }

    fun updateDisplayName(name: String): LocalIdentity {
        prefs.edit().putString(KEY_DISPLAY_NAME, trimDisplayName(name)).apply()
        return loadOrCreate { name }
    }

    fun rotateIdentity(displayName: String): LocalIdentity {
        clearIdentity()
        return create(trimDisplayName(displayName))
    }

    fun clearIdentity() {
        prefs.edit().clear().apply()
    }

    private fun create(displayName: String): LocalIdentity {
        val encryption = generateEcKeyPair()
        val signing = generateEcKeyPair()
        val createdAtMs = System.currentTimeMillis()
        val peerId = derivePeerId(encryption.public.encoded)
        prefs.edit()
            .putString(KEY_PEER_ID, peerId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_ENC_PUBLIC, encryption.public.encoded.encode64())
            .putString(KEY_ENC_PRIVATE, encryption.private.encoded.encode64())
            .putString(KEY_SIGN_PUBLIC, signing.public.encoded.encode64())
            .putString(KEY_SIGN_PRIVATE, signing.private.encoded.encode64())
            .putLong(KEY_CREATED_AT, createdAtMs)
            .apply()
        return LocalIdentity(
            peerId = peerId,
            displayName = displayName,
            encryptionPublicKey = encryption.public.encoded,
            encryptionPrivateKey = encryption.private.encoded,
            signingPublicKey = signing.public.encoded,
            signingPrivateKey = signing.private.encoded,
            createdAtMs = createdAtMs
        )
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    companion object {
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ENC_PUBLIC = "enc_public"
        private const val KEY_ENC_PRIVATE = "enc_private"
        private const val KEY_SIGN_PUBLIC = "sign_public"
        private const val KEY_SIGN_PRIVATE = "sign_private"
        private const val KEY_CREATED_AT = "created_at"

        fun trimDisplayName(value: String): String {
            val trimmed = value.trim().filterNot { it.isISOControl() }
            return trimmed.takeIf { it.isNotBlank() }?.take(32) ?: "nearby"
        }

        fun derivePeerId(publicKey: ByteArray): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return hash.take(8).joinToString("") { "%02x".format(it) }
        }

        fun sign(privateKeyBytes: ByteArray, payload: ByteArray): ByteArray {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(decodePrivateKey(privateKeyBytes))
            signature.update(payload)
            return signature.sign()
        }

        fun verify(publicKeyBytes: ByteArray, payload: ByteArray, signatureBytes: ByteArray): Boolean {
            return runCatching {
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initVerify(decodePublicKey(publicKeyBytes))
                signature.update(payload)
                signature.verify(signatureBytes)
            }.getOrDefault(false)
        }

        fun decodePublicKey(bytes: ByteArray): PublicKey {
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }

        fun decodePrivateKey(bytes: ByteArray): PrivateKey {
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }
    }
}

fun ByteArray.encode64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.decode64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
