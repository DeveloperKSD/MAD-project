package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.bluetooth.BleFrameCodec
import `in`.yashd.bitchat.identity.*
import `in`.yashd.bitchat.mesh.*
import `in`.yashd.bitchat.transfer.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.ArrayDeque

internal fun testIdentity(name: String): LocalIdentity {
    fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val encryption = pair(); val signing = pair()
    return LocalIdentity(IdentityManager.derivePeerId(encryption.public.encoded, signing.public.encoded), name,
        encryption.public.encoded, encryption.private.encoded, signing.public.encoded, signing.private.encoded, 0)
}
internal class MemoryBindings : BleFileBindingStore {
    var rows = emptyList<BleFileBinding>()
    var fail = false
    override fun load() = rows.map { it.copy() }
    override fun save(bindings: List<BleFileBinding>) { check(!fail); rows = bindings.map { it.copy() } }
}

/** Virtual endpoints + optional forwarding device. The real PacketCodec, signed encryption,
 * BLE fragmentation/reassembly and Stage 1 disk engine execute; no radio is simulated as proven.
 */
internal class BleFileTestNetwork(private val root: File, val multiHop: Boolean = false) : AutoCloseable {
    val alice = testIdentity("Alice"); val bob = testIdentity("Bob")
    val known = linkedMapOf(alice.peerId to BleFileProtocol.peer(alice), bob.peerId to BleFileProtocol.peer(bob))
    val stores = mapOf(alice.peerId to MemoryBindings(), bob.peerId to MemoryBindings())
    val nodes = linkedMapOf<String, BleFileController>()
    val wire = ArrayDeque<MeshPacket>()
    val sent = mutableListOf<MeshPacket>()
    var now = System.currentTimeMillis()
    var admit = true
    var dropped = 0
    var shouldDrop: (MeshPacket) -> Boolean = { false }
    val gate = BleFileForwardingGate({ known[it] })
    init { restart(alice); restart(bob) }
    fun restart(identity: LocalIdentity) {
        nodes.remove(identity.peerId)?.close()
        val directory = File(root, identity.peerId).apply { mkdirs() }
        nodes[identity.peerId] = BleFileController(identity, FileTransferEngine(directory), stores.getValue(identity.peerId),
            { known[it] }, { !multiHop }, { packet, _ ->
                sent.add(packet)
                if (admit) { wire.add(packet); true } else false
            }, clock = { now })
    }
    fun payload(identity: LocalIdentity, id: java.util.UUID) = File(root, "${identity.peerId}/relay-transfers-v1/$id/payload.bin")
    val a get() = nodes.getValue(alice.peerId)
    val b get() = nodes.getValue(bob.peerId)
    fun deliverOne() {
        val original = wire.removeFirst()
        now += 40 // Virtual time, no sleeping or claims about real radio latency.
        if (shouldDrop(original)) { dropped++; return }
        val packet = if (multiHop) {
            if (!gate.allow(original, now)) return
            original.copy(ttl = original.ttl - 1)
        } else original
        val encoded = PacketCodec.encode(packet)
        val frames = BleFrameCodec.encode(encoded, 180, 7)
        val assembler = BleFrameCodec.Reassembler()
        var assembled: ByteArray? = null
        frames.reversed().forEach { frame -> assembler.accept("test-link", frame, now)?.let { assembled = it } }
        nodes[packet.recipientId]?.receive(PacketCodec.decode(requireNotNull(assembled)))
    }
    fun drain(maximum: Int = 10000) {
        var count = 0
        while (wire.isNotEmpty()) { check(count++ < maximum) { "Unbounded test traffic" }; deliverOne() }
    }
    fun tick(milliseconds: Long = 5100) { now += milliseconds; nodes.values.forEach { it.tick() }; drain() }
    override fun close() { nodes.values.forEach { it.close() } }
}
