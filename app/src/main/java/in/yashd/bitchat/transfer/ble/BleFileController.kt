package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.identity.LocalIdentity
import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.mesh.MeshPacket
import `in`.yashd.bitchat.transfer.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Serialized on a bounded IO dispatcher, never a Bluetooth/main callback. send() must only
 * enqueue, not synchronously deliver/reenter a remote controller. Explicit API consent; no UI.
 */
class BleFileController(
    private val local: LocalIdentity,
    private val engine: FileTransferEngine,
    private val store: BleFileBindingStore,
    private val peer: (String) -> PeerIdentity?,
    private val direct: (String) -> Boolean,
    private val send: (MeshPacket, Boolean) -> Boolean,
    private val dropQueued: (UUID) -> Unit = {},
    private val limits: TransferLimits = TransferLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private data class Attempt(var at: Long, var tries: Int)
    private val bindings = linkedMapOf<UUID, BleFileBinding>()
    private val flights = linkedMapOf<Int, Attempt>()
    private val requested = linkedSetOf<Int>()
    private var controlAttempt = Attempt(0, 0)
    private var handshakeDeadline = 0L
    private var receivedAt = 0L
    private var rateAt = 0L
    private var rateCount = 0
    private var closed = false
    private var offerAt = Long.MIN_VALUE
    private val denied = linkedMapOf<UUID, Long>()
    private var storageHealthy = true
    private val mutable = MutableStateFlow<List<BleFileStatus>>(emptyList())
    val state = mutable.asStateFlow()
    init {
        check(engine.recoveryIssues.isEmpty()) { "File storage requires recovery; data preserved" }
        val loaded = store.load(); require(loaded.size <= limits.maxStoredTransfers)
        loaded.forEach { binding ->
            require(binding.localFingerprint == BleFileProtocol.fingerprint(BleFileProtocol.peer(local))) { "File identity changed; data preserved" }
            require(bindings.put(binding.manifest.id, binding) == null)
            binding.manifest.validate(binding.profile, limits)
            require(binding.binding == BleFileProtocol.binding(local, binding.remote, binding.manifest, binding.profile))
            val recovered = snapshotOrNull(binding.manifest.id)
            if (recovered != null) {
                require(recovered.manifest == binding.manifest)
                require((recovered.direction == TransferDirection.OUTGOING) == binding.outgoing)
                if (recovered.state == TransferState.FAILED) binding.phase = BleFilePhase.FAILED
                else if (recovered.state == TransferState.CANCELLED) binding.phase = BleFilePhase.CANCELLED
                else if (binding.outgoing && recovered.state == TransferState.COMPLETED && !terminal(binding.phase))
                    binding.phase = BleFilePhase.COMPLETED // Durable authenticated remote completion won the crash race.
            }
            if (binding.phase == BleFilePhase.COMPLETED) require(recovered?.state == TransferState.COMPLETED)
            if (!terminal(binding.phase)) { binding.phase = BleFilePhase.PAUSED; binding.acceptance = BleFileProtocol.ZERO_NONCE }
        }
        require(bindings.values.count { !terminal(it.phase) } <= 1)
        persist()
    }

    @Synchronized fun hasActiveTransfer(): Boolean = bindings.values.any { !terminal(it.phase) }

    @Synchronized fun offer(remoteId: String, input: InputStream, profile: TransferProfile): UUID {
        healthy(); require(profile != TransferProfile.WIFI_DIRECT); require(remoteId != local.peerId)
        require(profile != TransferProfile.DIRECT_BLE || direct(remoteId)) { "No direct route; choose multi-hop explicitly" }
        check(bindings.values.none { !terminal(it.phase) }) { "Another file owns this device" }
        require(bindings.size < limits.maxStoredTransfers)
        val remote = known(remoteId)
        val manifest = engine.prepareOutgoing(input, profile).manifest
        val binding = BleFileBinding(manifest, profile, remote,
            BleFileProtocol.fingerprint(BleFileProtocol.peer(local)), BleFileProtocol.binding(local, remote, manifest, profile), true, BleFileProtocol.nonce())
        binding.createdAtMs = clock()
        bindings[manifest.id] = binding
        persist(); handshakeDeadline = clock() + 30_000; controlAttempt = Attempt(clock(), 0); emitOffer(binding, false)
        return manifest.id
    }

    /** Only this explicit receiver API grants permission to allocate and receive file bytes. */
    @Synchronized fun accept(id: UUID) {
        healthy(); val binding = binding(id)
        check(!binding.outgoing && binding.phase == BleFilePhase.AWAITING_ACCEPTANCE)
        if (clock() >= handshakeDeadline) { reject(id); return }
        pinned(binding)
        val effective = if (binding.profile == TransferProfile.MULTI_HOP_BLE || !direct(binding.remote.peerId))
            TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE
        binding.manifest.validate(effective, limits)
        receivedAt = clock()
        binding.acceptance = BleFileProtocol.nonce()
        engine.beginIncoming(binding.manifest, effective)
        if (engine.snapshot(id).state != TransferState.COMPLETED) engine.start(id)
        binding.phase = if (engine.snapshot(id).state == TransferState.COMPLETED) BleFilePhase.COMPLETED else BleFilePhase.RECEIVING
        persist()
        emit(binding, if (binding.phase == BleFilePhase.COMPLETED) BleFileCode.COMPLETE else BleFileCode.ACCEPT,
            if (binding.phase == BleFilePhase.COMPLETED) binding.manifest.sha256.unhex() else missingBody(binding))
    }
    @Synchronized fun reject(id: UUID) {
        healthy(); val binding = binding(id)
        check(!binding.outgoing && binding.phase == BleFilePhase.AWAITING_ACCEPTANCE)
        binding.phase = BleFilePhase.REJECTED
        emit(binding, BleFileCode.REJECT, byteArrayOf())
        denied[id] = clock() + 660_000
        while (denied.size > 64) denied.remove(denied.keys.first())
        val partial = snapshotOrNull(id)
        if (partial == null) bindings.remove(id) // A first offer allocated no payload.
        else if (partial.state !in setOf(TransferState.CANCELLED, TransferState.FAILED, TransferState.COMPLETED)) engine.cancel(id)
        persist() // A rejected resume retains a terminal record so its checkpoint can be discarded.
    }
    @Synchronized fun pause(id: UUID) { healthy(); pauseBinding(binding(id), true) }
    @Synchronized fun cancel(id: UUID) {
        healthy(); val binding = binding(id); check(!terminal(binding.phase))
        snapshotOrNull(id)?.let { if (it.state != TransferState.COMPLETED && it.state != TransferState.CANCELLED && it.state != TransferState.FAILED) engine.cancel(id) }
        binding.phase = BleFilePhase.CANCELLED; flights.clear(); requested.clear(); dropQueued(id)
        persist(); emit(binding, BleFileCode.CANCEL, byteArrayOf())
    }
    @Synchronized fun resume(id: UUID) {
        healthy(); val binding = binding(id)
        check(binding.outgoing && binding.phase == BleFilePhase.PAUSED)
        pinned(binding)
        if (binding.profile == TransferProfile.DIRECT_BLE) require(direct(binding.remote.peerId)) { "No silent direct-to-multi-hop downgrade" }
        binding.epoch = BleFileProtocol.nonce(); binding.acceptance = BleFileProtocol.ZERO_NONCE
        binding.phase = BleFilePhase.OFFERED; flights.clear(); requested.clear(); dropQueued(id)
        persist(); handshakeDeadline = clock() + 30_000; controlAttempt = Attempt(clock(), 0); emitOffer(binding, true)
    }
    @Synchronized fun discard(id: UUID) {
        healthy(); val binding = binding(id); check(terminal(binding.phase))
        snapshotOrNull(id)?.let { engine.discard(id) }
        bindings.remove(id); denied[id] = clock() + 660_000
        while (denied.size > 64) denied.remove(denied.keys.first())
        persist()
    }
    @Synchronized fun copyCompletedTo(id: UUID, output: OutputStream) {
        healthy()
        try { engine.copyCompletedTo(id, output) }
        catch (e: Exception) {
            if (snapshotOrNull(id)?.state == TransferState.FAILED) {
                binding(id).phase = BleFilePhase.FAILED
                try { persist() } catch (storage: Exception) { e.addSuppressed(storage) }
            }
            throw e
        }
    }
    @Synchronized fun linkLost() { bindings.values.filter { !terminal(it.phase) }.forEach { pauseBinding(it, false) } }

    /** Invalid or unauthenticated packets have no state, file, ACK, or queue side effects. */
    @Synchronized fun receive(packet: MeshPacket) {
        if (closed || !storageHealthy) return
        val now = clock()
        if (now - rateAt >= 1000 || now < rateAt) { rateAt = now; rateCount = 0 }
        if (++rateCount > 64) return
        val frame = try { BleFileProtocol.open(local, known(packet.senderId), packet, now) } catch (_: Exception) { return }
        val header = frame.header
        try {
            if (header.code == BleFileCode.OFFER || header.code == BleFileCode.RESUME && header.acceptance == BleFileProtocol.ZERO_NONCE) {
                receiveOffer(frame); return
            }
            val binding = bindings[header.id] ?: return
            pinned(binding)
            if (header.binding != binding.binding || header.profile != binding.profile || header.epoch != binding.epoch) return
            if (header.sender != binding.remote.peerId) return
            if (binding.outgoing && binding.phase == BleFilePhase.OFFERED && header.code in setOf(BleFileCode.ACCEPT, BleFileCode.COMPLETE)) {
                if (header.acceptance == BleFileProtocol.ZERO_NONCE) return
                // Commit this challenge only after validating the control body below.
            } else if (header.acceptance != binding.acceptance) return
            if (binding.phase == BleFilePhase.OFFERED && now >= handshakeDeadline ||
                binding.phase == BleFilePhase.RECEIVING && now - receivedAt >= 60_000) {
                pauseBinding(binding, true); return
            }
            receivedAt = clock()
            when (header.code) {
                BleFileCode.ACCEPT -> {
                    if (!binding.outgoing || binding.phase != BleFilePhase.OFFERED) return
                    val missing = indices(frame.body, binding)
                    binding.acceptance = header.acceptance
                    engine.start(header.id); binding.phase = BleFilePhase.SENDING
                    requested.addAll(missing); flights.clear(); controlAttempt = Attempt(clock(), 0); persist(); pump(binding)
                }
                BleFileCode.REJECT -> {
                    require(frame.body.isEmpty())
                    if (binding.outgoing && binding.phase == BleFilePhase.OFFERED) {
                        engine.cancel(header.id); binding.phase = BleFilePhase.REJECTED; persist()
                    }
                }
                BleFileCode.CHUNK -> {
                    if (binding.outgoing || binding.phase !in setOf(BleFilePhase.RECEIVING, BleFilePhase.COMPLETED)) return
                    val chunk = ChunkProtocol.decode(frame.body)
                    require(chunk.transferId == header.id && chunk.totalChunks == binding.manifest.totalChunks)
                    engine.accept(frame.body) // Durable fsync/checkpoint before ACK.
                    emit(binding, BleFileCode.ACK, ByteBuffer.allocate(4).putInt(chunk.index).array())
                    if (engine.snapshot(header.id).state == TransferState.COMPLETED) {
                        binding.phase = BleFilePhase.COMPLETED; persist()
                        emit(binding, BleFileCode.COMPLETE, binding.manifest.sha256.unhex())
                    } else publish()
                }
                BleFileCode.ACK -> {
                    if (!binding.outgoing || binding.phase != BleFilePhase.SENDING) return
                    require(frame.body.size == 4); val index = ByteBuffer.wrap(frame.body).int
                    if (index !in flights) return
                    engine.acknowledge(header.id, index); flights.remove(index); controlAttempt = Attempt(clock(), 0)
                    publish(); pump(binding)
                }
                BleFileCode.RESUME -> {
                    if (binding.outgoing && binding.phase == BleFilePhase.SENDING) {
                        val missing = indices(frame.body, binding)
                        require((requested + missing).size <= 128)
                        requested.addAll(missing)
                        controlAttempt = Attempt(clock(), 0); pump(binding)
                    }
                }
                BleFileCode.COMPLETE -> {
                    require(frame.body.contentEquals(binding.manifest.sha256.unhex()))
                    if (binding.outgoing && binding.phase in setOf(BleFilePhase.OFFERED, BleFilePhase.SENDING)) {
                        binding.acceptance = header.acceptance
                        if (engine.snapshot(header.id).state != TransferState.TRANSFERRING) engine.start(header.id)
                        var missing = engine.missingChunks(header.id)
                        while (missing.isNotEmpty()) { missing.forEach { engine.acknowledge(header.id, it) }; missing = engine.missingChunks(header.id) }
                        engine.confirmRemoteCompletion(header.id, binding.manifest.sha256)
                        binding.phase = BleFilePhase.COMPLETED; flights.clear(); requested.clear(); dropQueued(header.id); persist()
                    } else if (!binding.outgoing && binding.phase == BleFilePhase.COMPLETED) emit(binding, BleFileCode.COMPLETE, frame.body)
                    else if (!binding.outgoing && binding.phase == BleFilePhase.RECEIVING) emit(binding, BleFileCode.RESUME, missingBody(binding))
                }
                BleFileCode.PAUSE -> { require(frame.body.isEmpty()); if (!terminal(binding.phase)) pauseBinding(binding, false) }
                BleFileCode.CANCEL -> {
                    require(frame.body.isEmpty())
                    if (!terminal(binding.phase)) {
                        snapshotOrNull(header.id)?.let { engine.cancel(header.id) }
                        binding.phase = BleFilePhase.CANCELLED; flights.clear(); requested.clear(); dropQueued(header.id); persist()
                    }
                }
                else -> Unit
            }
        } catch (_: IllegalArgumentException) { /* Authenticated malformed control rejected; no payload admitted. */ }
        catch (_: Exception) {
            bindings[header.id]?.let { binding ->
                binding.phase = if (snapshotOrNull(header.id)?.state == TransferState.FAILED) BleFilePhase.FAILED else BleFilePhase.PAUSED
                snapshotOrNull(header.id)?.let { if (it.state == TransferState.TRANSFERRING) engine.pause(header.id) }
                flights.clear(); requested.clear(); dropQueued(header.id)
                try { persist() } catch (_: Exception) { storageHealthy = false }
            }
        }
    }

    private fun receiveOffer(frame: OpenBleFileFrame) {
        val header = frame.header
        denied.entries.removeAll { it.value < clock() }
        if (header.id in denied) return
        val manifest = BleFileProtocol.manifest(header, frame.body, local, known(header.sender))
        val effective = if (header.profile == TransferProfile.MULTI_HOP_BLE || !direct(header.sender)) TransferProfile.MULTI_HOP_BLE else TransferProfile.DIRECT_BLE
        manifest.validate(effective, limits)
        val remote = known(header.sender)
        val old = bindings[header.id]
        if (old != null) {
            pinned(old); require(!old.outgoing && old.remote.peerId == remote.peerId && old.manifest == manifest && old.profile == header.profile)
            if (old.phase == BleFilePhase.COMPLETED) {
                old.epoch = header.epoch; old.acceptance = BleFileProtocol.nonce(); persist()
                emit(old, BleFileCode.COMPLETE, old.manifest.sha256.unhex()); return
            }
            if (terminal(old.phase)) return
            if (old.epoch == header.epoch) {
                if (old.phase == BleFilePhase.RECEIVING) emit(old, BleFileCode.ACCEPT, missingBody(old))
                return // A stale/replayed OFFER cannot wake a paused transfer.
            }
            if (old.phase != BleFilePhase.PAUSED) return
            old.epoch = header.epoch; old.acceptance = BleFileProtocol.ZERO_NONCE; old.phase = BleFilePhase.AWAITING_ACCEPTANCE
            handshakeDeadline = clock() + 30_000; controlAttempt = Attempt(clock(), 0)
            persist(); return
        }
        if (bindings.size >= limits.maxStoredTransfers || bindings.values.any { !terminal(it.phase) }) return
        if (offerAt != Long.MIN_VALUE && clock() - offerAt < 10_000) return
        offerAt = clock(); handshakeDeadline = clock() + 30_000; controlAttempt = Attempt(clock(), 0)
        bindings[header.id] = BleFileBinding(manifest, header.profile, remote,
            BleFileProtocol.fingerprint(BleFileProtocol.peer(local)), header.binding, false, header.epoch,
            phase = BleFilePhase.AWAITING_ACCEPTANCE, createdAtMs = clock())
        persist() // No incoming payload allocation until explicit authenticated acceptance.
    }

    @Synchronized fun tick() {
        if (closed || !storageHealthy) return
        val binding = bindings.values.firstOrNull { !terminal(it.phase) } ?: return
        try {
            pinned(binding)
            if (binding.phase == BleFilePhase.RECEIVING && clock() - receivedAt >= 60_000) {
                pauseBinding(binding, true); return
            }
            if (binding.phase == BleFilePhase.AWAITING_ACCEPTANCE && clock() >= handshakeDeadline) {
                reject(binding.manifest.id); return
            }
            if (binding.phase == BleFilePhase.OFFERED && clock() >= handshakeDeadline) { pauseBinding(binding, true); return }
            if (binding.phase == BleFilePhase.OFFERED && clock() - controlAttempt.at >= RETRY_MS) {
                if (controlAttempt.tries >= MAX_ATTEMPTS) { pauseBinding(binding, false); return }
                emitOffer(binding, true)
            } else if (binding.phase == BleFilePhase.SENDING) {
                flights.toMap().forEach { (index, attempt) ->
                    if (clock() - attempt.at >= RETRY_MS) {
                        if (attempt.tries >= MAX_ATTEMPTS) { pauseBinding(binding, true); return }
                        sendChunk(binding, index, attempt)
                    }
                }
                pump(binding)
                if (flights.isEmpty() && requested.isEmpty() && engine.missingChunks(binding.manifest.id).isEmpty() && clock() - controlAttempt.at >= RETRY_MS) {
                    if (controlAttempt.tries >= MAX_ATTEMPTS) { pauseBinding(binding, true); return }
                    controlAttempt.at = clock(); controlAttempt.tries++
                    emit(binding, BleFileCode.COMPLETE, binding.manifest.sha256.unhex()) // Probe, NOT a delivery assertion.
                }
            }
        } catch (_: Exception) { pauseBinding(binding, false) }
    }
    private fun pump(binding: BleFileBinding) {
        val candidates = (requested.toList() + engine.missingChunks(binding.manifest.id)).distinct()
        for (index in candidates) {
            if (flights.size >= WINDOW) return
            if (index in flights) continue
            val attempt = Attempt(clock(), 0)
            val admitted = sendChunk(binding, index, attempt)
            requested.remove(index); flights[index] = attempt
            if (!admitted) return
        }
    }
    private fun sendChunk(binding: BleFileBinding, index: Int, attempt: Attempt): Boolean {
        attempt.at = clock(); attempt.tries++
        return emit(binding, BleFileCode.CHUNK, engine.readChunk(binding.manifest.id, index))
    }
    private fun emitOffer(binding: BleFileBinding, resume: Boolean) {
        controlAttempt.at = clock(); controlAttempt.tries++
        emit(binding, if (resume) BleFileCode.RESUME else BleFileCode.OFFER, BleFileProtocol.manifestBytes(binding.manifest))
    }
    private fun emit(binding: BleFileBinding, code: BleFileCode, body: ByteArray): Boolean {
        val remote = pinned(binding)
        val header = BleFileHeader(code, binding.manifest.id, local.peerId, remote.peerId, binding.epoch,
            binding.acceptance, binding.binding, binding.profile)
        return send(BleFileProtocol.seal(local, remote, header, body), code == BleFileCode.CHUNK)
    }
    private fun missingBody(binding: BleFileBinding): ByteArray {
        val missing = engine.missingChunks(binding.manifest.id)
        return ByteBuffer.allocate(4 + missing.size * 4).apply { putInt(missing.size); missing.forEach { putInt(it) } }.array()
    }
    private fun indices(body: ByteArray, binding: BleFileBinding): List<Int> {
        require(body.size in 4..516 && body.size % 4 == 0)
        val input = ByteBuffer.wrap(body); val count = input.int
        require(count in 0..128 && body.size == 4 + count * 4)
        return List(count) { input.int.also { require(it in 0 until binding.manifest.totalChunks) } }.also { require(it.distinct().size == count) }
    }
    private fun pauseBinding(binding: BleFileBinding, notify: Boolean) {
        if (terminal(binding.phase)) return
        snapshotOrNull(binding.manifest.id)?.let { if (it.state in setOf(TransferState.PENDING, TransferState.TRANSFERRING)) engine.pause(it.manifest.id) }
        binding.phase = if (snapshotOrNull(binding.manifest.id)?.state == TransferState.FAILED) BleFilePhase.FAILED else BleFilePhase.PAUSED
        flights.clear(); requested.clear(); dropQueued(binding.manifest.id)
        persist(); if (notify) emit(binding, BleFileCode.PAUSE, byteArrayOf())
    }
    private fun known(id: String): PeerIdentity = requireNotNull(peer(id)).also { BleFileProtocol.fingerprint(it) }.let {
        it.copy(encryptionPublicKey = it.encryptionPublicKey.copyOf(), signingPublicKey = it.signingPublicKey.copyOf())
    }
    private fun pinned(binding: BleFileBinding): PeerIdentity = known(binding.remote.peerId).also {
        require(BleFileProtocol.fingerprint(it) == BleFileProtocol.fingerprint(binding.remote)) { "File endpoint keys changed" }
    }
    private fun binding(id: UUID) = requireNotNull(bindings[id])
    private fun snapshotOrNull(id: UUID) = engine.snapshots().firstOrNull { it.manifest.id == id }
    private fun terminal(phase: BleFilePhase) = phase in setOf(BleFilePhase.COMPLETED, BleFilePhase.CANCELLED, BleFilePhase.REJECTED, BleFilePhase.FAILED)
    private fun healthy() { check(!closed && storageHealthy) }
    private fun persist() {
        try { store.save(bindings.values.toList()); publish() }
        catch (e: Exception) { storageHealthy = false; throw e }
    }
    private fun publish() {
        mutable.value = bindings.values.map { BleFileStatus(it.manifest.id, it.remote.peerId, it.phase, it.outgoing,
            snapshotOrNull(it.manifest.id)?.confirmedBytes ?: 0, it.manifest.sizeBytes, it.profile, it.createdAtMs) }
    }
    @Synchronized override fun close() {
        if (closed) return
        try { if (storageHealthy) linkLost() } finally { closed = true; engine.close() }
    }
    companion object { const val WINDOW = 2; const val MAX_ATTEMPTS = 4; const val RETRY_MS = 5000L }
}
