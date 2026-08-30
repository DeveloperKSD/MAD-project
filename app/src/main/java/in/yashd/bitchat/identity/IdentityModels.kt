package `in`.yashd.bitchat.identity

data class LocalIdentity(
    val peerId: String,
    val displayName: String,
    val encryptionPublicKey: ByteArray,
    val encryptionPrivateKey: ByteArray,
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val createdAtMs: Long
)

data class PeerIdentity(
    val peerId: String,
    val displayName: String,
    val encryptionPublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val verified: Boolean
)
