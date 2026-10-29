package `in`.yashd.bitchat.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/** Public information only. No private-key export or rotation API. */
data class NostrIdentity(val publicKeyHex: String, val npub: String)

class NostrIdentityException(message: String) : Exception(message)

internal interface NostrIdentityStorage {
    fun read(): ByteArray?
    fun write(secret: ByteArray)
}

/** Blocking API: call from a worker thread. Independent of the BLE identity lifecycle. */
class NostrIdentityManager internal constructor(private val storage: NostrIdentityStorage) {
    constructor(context: android.content.Context) : this(KeystoreNostrIdentityStorage(context))

    /** Creates only on first use. Storage failures never trigger identity replacement. */
    fun loadOrCreate(): NostrIdentity = synchronized(lock) {
        var secret: ByteArray? = null
        try {
            secret = storage.read()
            if (secret == null) {
                secret = ByteArray(32)
                val random = SecureRandom()
                do { random.nextBytes(secret) } while (!Secp256k1.secKeyVerify(secret))
                storage.write(secret)
            }
            val publicKey = SchnorrSignature().publicKey(secret)
            NostrIdentity(publicKey.lowercaseHex(), NostrNpub.encode(publicKey))
        } catch (_: Exception) {
            // Do not include provider messages or key material in errors.
            throw NostrIdentityException("Nostr identity could not be loaded or saved; existing data was preserved")
        } finally {
            secret?.fill(0)
        }
    }

    /** Background work may read an existing identity, but must never create one. */
    fun loadExisting(): NostrIdentity? = synchronized(lock) {
        var secret: ByteArray? = null
        try {
            secret = storage.read() ?: return@synchronized null
            require(Secp256k1.secKeyVerify(secret))
            val publicKey = SchnorrSignature().publicKey(secret)
            NostrIdentity(publicKey.lowercaseHex(), NostrNpub.encode(publicKey))
        } catch (_: Exception) {
            throw NostrIdentityException("Saved Nostr identity is unavailable; existing data was preserved")
        } finally { secret?.fill(0) }
    }

    /** Internal scoped key access; never export or retain the storage buffer. */
    internal fun <T> withSecret(action: (ByteArray) -> T): T = synchronized(lock) {
        loadOrCreate()
        val secret = try { requireNotNull(storage.read()) } catch (_: Exception) {
            throw NostrIdentityException("Nostr identity could not be loaded")
        }
        try { action(secret) } finally { secret.fill(0) }
    }

    internal fun <T> withExistingSecret(action: (ByteArray) -> T): T = synchronized(lock) {
        checkNotNull(loadExisting())
        val secret = requireNotNull(storage.read())
        try { action(secret) } finally { secret.fill(0) }
    }

    private companion object {
        // Serialize creation across manager instances in the application process.
        val lock = Any()
    }
}
