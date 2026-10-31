package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.transfer.TransferManifest
import `in`.yashd.bitchat.transfer.TransferProfile
import java.util.UUID

enum class BleFilePhase { OFFERED, AWAITING_ACCEPTANCE, SENDING, RECEIVING, PAUSED, COMPLETED, CANCELLED, REJECTED, FAILED }
data class BleFileStatus(val id: UUID, val peer: String, val phase: BleFilePhase, val outgoing: Boolean,
                         val confirmedBytes: Long, val totalBytes: Long, val profile: TransferProfile = TransferProfile.DIRECT_BLE,
                         val createdAtMs: Long = 0L)

/** No private identity keys. Full public keys pin the endpoint across resume/reconnect.
 * Epoch/acceptance challenges are never inferred from untrusted file DATA.
 */
data class BleFileBinding(val manifest: TransferManifest, val profile: TransferProfile, val remote: PeerIdentity,
    val localFingerprint: String, val binding: String, val outgoing: Boolean, var epoch: String,
    var acceptance: String = BleFileProtocol.ZERO_NONCE, var phase: BleFilePhase = BleFilePhase.OFFERED,
    var createdAtMs: Long = 0L)

interface BleFileBindingStore {
    fun load(): List<BleFileBinding>
    /** Must commit the complete bounded list before returning; failure must propagate. */
    fun save(bindings: List<BleFileBinding>)
}

/** UI operations deliberately exclude terminal retry: it requires a newly selected file/UUID. */
enum class BleFileAction { ACCEPT, REJECT, PAUSE, RESUME, CANCEL, DISCARD }
