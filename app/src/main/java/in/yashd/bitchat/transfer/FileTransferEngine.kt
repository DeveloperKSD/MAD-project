package `in`.yashd.bitchat.transfer

import java.io.*
import java.security.MessageDigest
import java.util.UUID

class TransferIntegrityException(val failure: TransferFailure) : IOException("Transfer integrity check failed: $failure")

/** Blocking, serialized disk engine. Call on a worker thread, never the Android main thread.
 * It owns no radios, network, routing, UI, encryption keys or coroutine/background jobs.
 * Sources are copied once into a private immutable snapshot; chunks are pulled on demand.
 * Incoming receipt data is fsynced before checkpoint/ACK admission. No whole-file RAM buffers.
 */
class FileTransferEngine(
    privateDirectory: File,
    private val limits: TransferLimits = TransferLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private val store = TransferStore(privateDirectory)
    private val records = linkedMapOf<UUID, TransferRecord>()
    private val issues = mutableListOf<TransferRecoveryIssue>()
    private var closed = false

    init {
        try {
            val directories = store.directories()
            require(directories.size <= limits.maxStoredTransfers) { "Stored transfer count exceeds configured limit; data preserved" }
            directories.forEach { directory ->
                val record = try { store.load(directory, limits) }
                catch (_: Exception) {
                    issues.add(TransferRecoveryIssue(directory.name, "Checkpoint unavailable or unsupported; files preserved"))
                    return@forEach
                }
                records[record.manifest.id] = record
                try { recover(record) }
                catch (e: InterruptedIOException) { throw e }
                catch (_: Exception) {
                    // A valid checkpoint with unavailable payload must never become a fresh transfer.
                    markFailed(record, TransferFailure.STORAGE)
                }
            }
        } catch (e: Exception) { store.close(); throw e }
    }

    val recoveryIssues: List<TransferRecoveryIssue> @Synchronized get() = issues.toList()
    @Synchronized fun snapshots(): List<TransferSnapshot> { ensureOpen(); return records.values.map { it.snapshot() } }
    @Synchronized fun snapshot(id: UUID): TransferSnapshot = record(id).snapshot()

    /** Caller retains ownership of input. Unknown-size streams are counted and bounded too.
     * Interrupted/oversized imports leave no registered record and remove only their own temp files.
     */
    @Synchronized fun prepareOutgoing(input: InputStream, profile: TransferProfile,
                                      chunkBytes: Int = TransferLimits.DEFAULT_CHUNK_BYTES): TransferSnapshot {
        ensureOpen()
        require(chunkBytes in 1..TransferLimits.MAX_CHUNK_BYTES)
        val maximum = minOf(limits.maxBytes(profile), chunkBytes.toLong() * TransferLimits.MAX_CHUNKS)
        store.reserve(maximum + TransferStore.CHECKPOINT_RESERVATION_BYTES, limits)
        val id = UUID.randomUUID()
        store.create(id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(store.data(id)).use { out ->
                TransferStore.ownerOnly(store.data(id))
                val buffer = ByteArray(TransferLimits.IO_BUFFER_BYTES)
                while (true) {
                    interrupted()
                    // Read at most one byte beyond the limit, and never write that byte.
                    var count = input.read(buffer, 0, minOf(buffer.size.toLong(), maximum - size + 1).toInt())
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        buffer[0] = one.toByte(); count = 1
                    }
                    require(size + count <= maximum) { "File exceeds transfer size or chunk-count limit" }
                    digest.update(buffer, 0, count); out.write(buffer, 0, count); size += count
                }
                out.fd.sync()
            }
            val manifest = TransferManifest(id, size, chunkBytes, digest.digest().hex())
            val record = TransferRecord(manifest, profile, TransferDirection.OUTGOING, TransferState.PENDING, updatedAt = now())
            store.save(record)
            records[id] = record
            return record.snapshot()
        } catch (e: Exception) {
            try { store.discard(id) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    /** Call only after future peer authentication and user consent. Profile is selected locally. */
    @Synchronized fun beginIncoming(manifest: TransferManifest, profile: TransferProfile): TransferSnapshot {
        ensureOpen()
        manifest.validate(profile, limits)
        records[manifest.id]?.let { existing ->
            require(existing.manifest == manifest && existing.profile == profile && existing.direction == TransferDirection.INCOMING)
            return existing.snapshot() // Idempotent offer; never resurrect a cancelled/failed ID.
        }
        store.reserve(manifest.sizeBytes + manifest.totalChunks.toLong() * 32 + TransferStore.CHECKPOINT_RESERVATION_BYTES, limits)
        store.create(manifest.id)
        try {
            store.allocate(store.data(manifest.id), manifest.sizeBytes)
            store.allocate(store.receipts(manifest.id), manifest.totalChunks.toLong() * 32)
            val record = TransferRecord(manifest, profile, TransferDirection.INCOMING, TransferState.PENDING, updatedAt = now())
            store.save(record)
            records[manifest.id] = record
            return record.snapshot()
        } catch (e: Exception) {
            try { store.discard(manifest.id) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    @Synchronized fun start(id: UUID): TransferSnapshot {
        val record = record(id)
        check(record.state == TransferState.PENDING || record.state == TransferState.PAUSED)
        try {
            if (!payloadExists(record)) return record.snapshot()
            if (record.direction == TransferDirection.INCOMING) validateReceipts(record)
            else if (TransferStore.hashFile(store.data(id), record.manifest.sizeBytes) != record.manifest.sha256) {
                failIntegrity(record, TransferFailure.HASH_MISMATCH)
            }
            record.state = TransferState.TRANSFERRING
            checkpoint(record)
            if (record.direction == TransferDirection.INCOMING && record.confirmed.cardinality() == record.manifest.totalChunks) finishIncoming(record)
            return record.snapshot()
        } catch (e: TransferIntegrityException) { throw e }
        catch (e: IOException) { storageFailure(record, e) }
    }

    @Synchronized fun pause(id: UUID): TransferSnapshot {
        val record = record(id)
        check(record.state in setOf(TransferState.PENDING, TransferState.TRANSFERRING, TransferState.PAUSED))
        record.state = TransferState.PAUSED
        persist(record)
        return record.snapshot()
    }

    /** One binary frame, not a List of all chunks. An adapter must respect queue backpressure. */
    @Synchronized fun readChunk(id: UUID, index: Int): ByteArray {
        val record = record(id)
        check(record.direction == TransferDirection.OUTGOING && record.state == TransferState.TRANSFERRING)
        val length = record.manifest.payloadBytes(index)
        try {
            interrupted()
            checkDataLength(record)
            val payload = ByteArray(length)
            RandomAccessFile(store.data(id), "r").use { input ->
                input.seek(index.toLong() * record.manifest.chunkBytes); input.readFully(payload)
            }
            return ChunkProtocol.encode(id, index, record.manifest.totalChunks, payload)
        } catch (e: IOException) { storageFailure(record, e) }
    }

    /** Invalid headers/geometry are rejected without mutating a transfer. Conflicting duplicate
     * bytes fail the transfer; equal duplicates do not rewrite disk or inflate progress.
     */
    @Synchronized fun accept(packet: ByteArray): ChunkReceipt {
        ensureOpen()
        val chunk = ChunkProtocol.decode(packet)
        val record = record(chunk.transferId)
        require(record.direction == TransferDirection.INCOMING)
        require(chunk.totalChunks == record.manifest.totalChunks && chunk.payload.size == record.manifest.payloadBytes(chunk.index))
        check(record.state == TransferState.TRANSFERRING || record.state == TransferState.COMPLETED)
        try {
            interrupted()
            checkDataLength(record)
            if (record.confirmed[chunk.index]) {
                val previous = ByteArray(chunk.payload.size)
                RandomAccessFile(store.data(chunk.transferId), "r").use { input ->
                    input.seek(chunk.index.toLong() * record.manifest.chunkBytes); input.readFully(previous)
                }
                if (!previous.contentEquals(chunk.payload)) {
                    require(record.state != TransferState.COMPLETED) { "Conflicting packet after completion" }
                    failIntegrity(record, TransferFailure.CONFLICTING_CHUNK)
                }
                return ChunkReceipt.DUPLICATE
            }
            check(record.state == TransferState.TRANSFERRING)
            val receipts = store.receipts(chunk.transferId)
            if (!receipts.isFile || receipts.length() != record.manifest.totalChunks.toLong() * 32)
                throw IOException("Transfer receipts are unavailable")
            RandomAccessFile(store.data(chunk.transferId), "rw").use { out ->
                out.seek(chunk.index.toLong() * record.manifest.chunkBytes); out.write(chunk.payload); out.fd.sync()
            }
            RandomAccessFile(store.receipts(chunk.transferId), "rw").use { out ->
                out.seek(chunk.index.toLong() * 32); out.write(TransferStore.sha256(chunk.payload)); out.fd.sync()
            }
            record.confirmed.set(chunk.index)
            checkpoint(record)
            if (record.confirmed.cardinality() == record.manifest.totalChunks) {
                finishIncoming(record)
                return ChunkReceipt.COMPLETED
            }
            return ChunkReceipt.STORED
        } catch (e: TransferIntegrityException) { throw e }
        catch (e: IOException) { storageFailure(record, e) }
    }

    /** Future adapter must authenticate this acknowledgement from the expected receiver. */
    @Synchronized fun acknowledge(id: UUID, index: Int): TransferSnapshot {
        val record = record(id)
        check(record.direction == TransferDirection.OUTGOING && record.state == TransferState.TRANSFERRING)
        record.manifest.payloadBytes(index)
        if (!record.confirmed[index]) { record.confirmed.set(index); persist(record) }
        return record.snapshot()
    }

    /** Chunk ACKs alone never mean outgoing completion. Receiver must verify the whole file. */
    @Synchronized fun confirmRemoteCompletion(id: UUID, sha256: String): TransferSnapshot {
        val record = record(id)
        check(record.direction == TransferDirection.OUTGOING)
        check(record.state == TransferState.TRANSFERRING || record.state == TransferState.COMPLETED)
        require(record.confirmed.cardinality() == record.manifest.totalChunks)
        if (record.state == TransferState.COMPLETED) {
            require(sha256 == record.manifest.sha256)
            return record.snapshot()
        }
        if (sha256 != record.manifest.sha256) failIntegrity(record, TransferFailure.HASH_MISMATCH)
        record.state = TransferState.COMPLETED
        persist(record)
        return record.snapshot()
    }

    /** Bounded, paged missing/ACK tracking for either direction. Next page starts after the
     * last returned index. Empty means no missing chunk at/after fromIndex, not completion.
     */
    @Synchronized fun missingChunks(id: UUID, fromIndex: Int = 0, limit: Int = TransferLimits.MAX_MISSING_PAGE): List<Int> {
        val record = record(id)
        require(fromIndex in 0..record.manifest.totalChunks && limit in 1..TransferLimits.MAX_MISSING_PAGE)
        val result = ArrayList<Int>(limit)
        var index = record.confirmed.nextClearBit(fromIndex)
        while (index < record.manifest.totalChunks && result.size < limit) {
            result.add(index); index = record.confirmed.nextClearBit(index + 1)
        }
        return result
    }

    @Synchronized fun cancel(id: UUID): TransferSnapshot {
        val record = record(id)
        check(record.state != TransferState.COMPLETED && record.state != TransferState.FAILED)
        record.state = TransferState.CANCELLED
        persist(record)
        store.deletePayload(id)
        return record.snapshot()
    }

    /** Explicitly release a terminal record, including its completed private file. No TTL or
     * automatic eviction; the caller decides when a completed file is no longer needed.
     */
    @Synchronized fun discard(id: UUID) {
        val record = record(id)
        check(record.state in setOf(TransferState.COMPLETED, TransferState.FAILED, TransferState.CANCELLED))
        store.discard(id)
        records.remove(id)
    }

    /** Avoid exposing temp paths or retaining whole files in RAM. Caller owns output. */
    @Synchronized fun copyCompletedTo(id: UUID, output: OutputStream) {
        val record = record(id)
        check(record.direction == TransferDirection.INCOMING && record.state == TransferState.COMPLETED)
        try {
            checkDataLength(record)
            if (TransferStore.hashFile(store.data(id), record.manifest.sizeBytes) != record.manifest.sha256) failIntegrity(record, TransferFailure.HASH_MISMATCH)
        } catch (e: TransferIntegrityException) { throw e }
        catch (e: IOException) { storageFailure(record, e) }
        // An export destination failure does not destroy a verified received file.
        FileInputStream(store.data(id)).use { input ->
            val buffer = ByteArray(TransferLimits.IO_BUFFER_BYTES)
            while (true) {
                interrupted()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    private fun recover(record: TransferRecord) {
        if (record.state in setOf(TransferState.CANCELLED, TransferState.FAILED)) {
            store.deletePayload(record.manifest.id); return
        }
        if (!payloadExists(record)) return
        if (record.direction == TransferDirection.OUTGOING || record.state == TransferState.COMPLETED) {
            if (TransferStore.hashFile(store.data(record.manifest.id), record.manifest.sizeBytes) != record.manifest.sha256) {
                markFailed(record, TransferFailure.HASH_MISMATCH); return
            }
        } else validateReceipts(record)
        if (record.state == TransferState.TRANSFERRING) record.state = TransferState.PAUSED
        checkpoint(record)
    }

    private fun payloadExists(record: TransferRecord): Boolean {
        if (!store.data(record.manifest.id).isFile || store.data(record.manifest.id).length() != record.manifest.sizeBytes) {
            markFailed(record, TransferFailure.MISSING_DATA); return false
        }
        return true
    }

    private fun checkDataLength(record: TransferRecord) {
        if (!store.data(record.manifest.id).isFile || store.data(record.manifest.id).length() != record.manifest.sizeBytes)
            throw IOException("Transfer data is unavailable")
    }

    private fun validateReceipts(record: TransferRecord) {
        val receiptFile = store.receipts(record.manifest.id)
        if (!receiptFile.isFile || receiptFile.length() != record.manifest.totalChunks.toLong() * 32) throw IOException("Receipts unavailable")
        RandomAccessFile(store.data(record.manifest.id), "r").use { data ->
            RandomAccessFile(receiptFile, "r").use { receipts ->
                var index = record.confirmed.nextSetBit(0)
                while (index >= 0) {
                    interrupted()
                    val payload = ByteArray(record.manifest.payloadBytes(index))
                    val expected = ByteArray(32)
                    data.seek(index.toLong() * record.manifest.chunkBytes); data.readFully(payload)
                    receipts.seek(index.toLong() * 32); receipts.readFully(expected)
                    // A torn/corrupt previously recorded chunk is missing again, never trusted.
                    if (!MessageDigest.isEqual(TransferStore.sha256(payload), expected)) record.confirmed.clear(index)
                    index = record.confirmed.nextSetBit(index + 1)
                }
            }
        }
    }

    private fun finishIncoming(record: TransferRecord) {
        if (TransferStore.hashFile(store.data(record.manifest.id), record.manifest.sizeBytes) != record.manifest.sha256) failIntegrity(record, TransferFailure.HASH_MISMATCH)
        record.state = TransferState.COMPLETED
        checkpoint(record)
    }

    private fun markFailed(record: TransferRecord, failure: TransferFailure) {
        record.state = TransferState.FAILED; record.failure = failure
        checkpoint(record)
        store.deletePayload(record.manifest.id)
    }

    private fun failIntegrity(record: TransferRecord, failure: TransferFailure): Nothing {
        markFailed(record, failure)
        throw TransferIntegrityException(failure)
    }

    private fun persist(record: TransferRecord) {
        try { checkpoint(record) } catch (e: IOException) { storageFailure(record, e) }
    }
    private fun checkpoint(record: TransferRecord) { record.updatedAt = now(); store.save(record) }
    private fun now(): Long = clock().also { require(it >= 0) }
    private fun record(id: UUID): TransferRecord { ensureOpen(); return requireNotNull(records[id]) { "Unknown transfer ID" } }
    private fun ensureOpen() { check(!closed) { "Transfer engine is closed" } }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Transfer operation interrupted") }
    private fun storageFailure(record: TransferRecord, cause: IOException): Nothing {
        if (cause is InterruptedIOException) {
            if (record.state == TransferState.TRANSFERRING) record.state = TransferState.PAUSED
            try { checkpoint(record) } catch (e: Exception) { cause.addSuppressed(e) }
            throw cause
        }
        try { markFailed(record, TransferFailure.STORAGE) } catch (e: Exception) { cause.addSuppressed(e) }
        throw cause
    }

    /** Releases the exclusive store lock; active transfers become resumable PAUSED records. */
    @Synchronized override fun close() {
        if (closed) return
        try {
            records.values.filter { it.state == TransferState.TRANSFERRING }.forEach {
                it.state = TransferState.PAUSED; checkpoint(it)
            }
        } finally { closed = true; store.close() }
    }
}
