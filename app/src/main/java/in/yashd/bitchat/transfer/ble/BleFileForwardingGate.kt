package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.TransferProfile
import java.util.UUID

/** One bounded, authenticated file route per forwarding device. It never decrypts file data.
 * After a process/link reset, DATA is refused until a new offer/accept exchange traverses it.
 */
class BleFileForwardingGate(private val peer: (String) -> PeerIdentity?, private val drop: (UUID) -> Unit = {}) {
    private data class Route(val id: UUID, val sender: String, val receiver: String, val binding: String,
        val epoch: String, var acceptance: String = BleFileProtocol.ZERO_NONCE,
        var expires: Long, var bytes: Long = 0, var completed: Boolean = false)
    private var route: Route? = null
    private val retired = linkedMapOf<String, Long>()
    private var offerAt = Long.MIN_VALUE
    @Synchronized fun busy(now: Long): Boolean {
        route?.let { if (it.expires < now) retire(it, now) }
        return route != null
    }
    @Synchronized fun allow(packet: MeshPacket, now: Long): Boolean = try {
        val remote = requireNotNull(peer(packet.senderId)); BleFileProtocol.fingerprint(remote)
        require(PacketCodec.verify(packet, remote.signingPublicKey))
        require(packet.timestampMs in now - 600_000..now + 60_000 && packet.ttl > 0)
        val h = BleFileProtocol.header(packet.payload)
        require(h.profile == TransferProfile.MULTI_HOP_BLE && h.sender == packet.senderId && h.recipient == packet.recipientId)
        require((h.code == BleFileCode.CHUNK) == (packet.type == PacketType.FILE_CHUNK))
        require(packet.type == PacketType.FILE_CONTROL || packet.type == PacketType.FILE_CHUNK)
        route?.let { if (it.expires < now) retire(it, now) }
        retired.entries.removeAll { it.value < now }
        if (h.code == BleFileCode.OFFER || h.code == BleFileCode.RESUME && h.acceptance == BleFileProtocol.ZERO_NONCE) {
            val key = "${h.id}:${h.epoch}"
            require(key !in retired)
            val current = route
            if (current == null || current.completed && current.sender == h.sender) {
                require(offerAt == Long.MIN_VALUE || now - offerAt >= 1000)
                current?.let { retire(it, now) }
                route = Route(h.id, h.sender, h.recipient, h.binding, h.epoch, expires = now + 30_000)
                offerAt = now
            }
            val r = requireNotNull(route)
            require(r.id == h.id && r.epoch == h.epoch && r.sender == h.sender && r.receiver == h.recipient && r.binding == h.binding)
            true
        } else {
            val r = requireNotNull(route)
            require(r.id == h.id && r.epoch == h.epoch && r.binding == h.binding)
            val forward = h.sender == r.sender && h.recipient == r.receiver
            val backward = h.sender == r.receiver && h.recipient == r.sender
            require(forward || backward)
            if (h.code == BleFileCode.ACCEPT || h.code == BleFileCode.COMPLETE && backward && r.acceptance == BleFileProtocol.ZERO_NONCE) {
                require(backward && h.acceptance != BleFileProtocol.ZERO_NONCE)
                require(r.acceptance == BleFileProtocol.ZERO_NONCE || r.acceptance == h.acceptance)
                r.acceptance = h.acceptance
            } else require(h.acceptance == r.acceptance)
            require(h.code != BleFileCode.CHUNK || forward && r.acceptance != BleFileProtocol.ZERO_NONCE)
            require(h.code != BleFileCode.ACK || backward)
            r.bytes += packet.payload.size
            require(r.bytes <= 2L * 1024 * 1024) // Includes retransmits; no unlimited forwarding history.
            r.expires = now + 60_000
            if (h.code == BleFileCode.COMPLETE && backward) r.completed = true
            if (h.code in setOf(BleFileCode.CANCEL, BleFileCode.REJECT, BleFileCode.PAUSE)) retire(r, now)
            true
        }
    } catch (_: Exception) { false }
    private fun retire(r: Route, now: Long) {
        drop(r.id); retired["${r.id}:${r.epoch}"] = now + 660_000
        while (retired.size > 32) retired.remove(retired.keys.first())
        route = null
    }
    @Synchronized fun reset(now: Long) { route?.let { retire(it, now) } }
}
