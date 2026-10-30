package `in`.yashd.bitchat.transfer

import java.io.*
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID

internal data class TransferRecord(
    val manifest: TransferManifest,
    val profile: TransferProfile,
    val direction: TransferDirection,
    var state: TransferState,
    val confirmed: BitSet = BitSet(),
    var updatedAt: Long,
    var failure: TransferFailure? = null
) {
    fun snapshot(): TransferSnapshot {
        val count = confirmed.cardinality()
        val last = manifest.totalChunks - 1
        val bytes = count.toLong() * manifest.chunkBytes -
            if (last >= 0 && confirmed[last]) manifest.chunkBytes - manifest.payloadBytes(last) else 0
        return TransferSnapshot(manifest, profile, direction, state, count, bytes, updatedAt, failure)
    }
}

/** Sole owner of a dedicated subdirectory under a CALLER-SUPPLIED private directory.
 * No Android dependency: a future integration must pass context.noBackupFilesDir, never
 * external/shared storage. Only fixed filenames under UUID directories are accessed.
 */
internal class TransferStore(privateDirectory: File) : Closeable {
    val root = File(privateDirectory.canonicalFile, "relay-transfers-v1")
    private val lockFile: RandomAccessFile
    private val lock: FileLock

    init {
        check(privateDirectory.isDirectory) { "A private parent directory is required" }
        check(root.canonicalFile == root.absoluteFile) { "Transfer directory must not be a symbolic link" }
        if (!root.exists() && !root.mkdir()) throw IOException("Cannot create transfer directory")
        check(root.isDirectory)
        ownerOnly(root)
        val file = safeChild(root, "engine.lock")
        lockFile = RandomAccessFile(file, "rw")
        try {
            ownerOnly(file)
            lock = lockFile.channel.tryLock() ?: throw IOException("Transfer store is already open")
        } catch (e: Exception) { lockFile.close(); throw IOException("Transfer store is already open or unavailable", e) }
    }

    fun directories(): List<File> {
        val entries = root.listFiles() ?: throw IOException("Cannot list transfer directory")
        val directories = entries.filter { it.name != "engine.lock" }
        directories.forEach {
            safeChild(root, it.name)
            require(it.isDirectory && UUID.fromString(it.name).toString() == it.name) { "Unrecognized transfer directory" }
        }
        return directories
    }

    fun reserve(bytes: Long, limits: TransferLimits) {
        val directories = directories()
        require(directories.size < limits.maxStoredTransfers) { "Transfer record limit reached; discard old terminal records" }
        var used = 0L
        directories.forEach { directory ->
            val files = directory.listFiles() ?: throw IOException("Cannot inspect transfer storage")
            // Keep headroom for old + replacement checkpoint, even when the current
            // bitmap is small. Admission must not consume a later commit's disk budget.
            used = Math.addExact(used, CHECKPOINT_RESERVATION_BYTES.toLong())
            files.forEach {
                require(it.name in OWNED_FILES && safeChild(directory, it.name).isFile) { "Unrecognized transfer file" }
                if (it.name == "payload.bin" || it.name == "receipts.bin") used = Math.addExact(used, it.length())
                else require(it.length() <= MAX_CHECKPOINT_BYTES) { "Oversized recovery metadata" }
            }
        }
        require(bytes <= limits.maxReservedBytes && used <= limits.maxReservedBytes - bytes) { "Transfer disk budget exceeded" }
    }

    fun create(id: UUID): File {
        val directory = directory(id)
        require(!directory.exists()) { "Transfer ID already exists, including preserved recovery data" }
        if (!directory.mkdir()) throw IOException("Cannot create transfer record")
        ownerOnly(directory)
        return directory
    }

    fun directory(id: UUID): File = safeChild(root, id.toString())
    fun data(id: UUID): File = safeChild(directory(id), "payload.bin")
    fun receipts(id: UUID): File = safeChild(directory(id), "receipts.bin")

    fun allocate(file: File, size: Long) {
        require(!file.exists())
        RandomAccessFile(file, "rw").use { out ->
            ownerOnly(file)
            out.setLength(size)
            out.fd.sync()
        }
    }

    fun save(record: TransferRecord) {
        val manifest = record.manifest
        val bitmap = ByteArray((manifest.totalChunks + 7) / 8)
        record.confirmed.toByteArray().copyInto(bitmap)
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(CHECKPOINT_MAGIC); out.writeByte(1)
                out.writeByte(record.direction.ordinal); out.writeByte(record.state.ordinal)
                out.writeByte(record.failure?.let { it.ordinal + 1 } ?: 0)
                out.writeByte(record.profile.ordinal); out.writeByte(manifest.version)
                out.writeLong(manifest.id.mostSignificantBits); out.writeLong(manifest.id.leastSignificantBits)
                out.writeLong(manifest.sizeBytes); out.writeInt(manifest.chunkBytes)
                out.write(manifest.sha256.hexBytes()); out.writeLong(record.updatedAt)
                out.writeInt(bitmap.size); out.write(bitmap)
            }
        }.toByteArray()
        check(bytes.size + 32 <= MAX_CHECKPOINT_BYTES)
        val staging = safeChild(directory(manifest.id), "checkpoint.tmp")
        val target = safeChild(directory(manifest.id), "checkpoint.bin")
        FileOutputStream(staging).use { out ->
            ownerOnly(staging)
            out.write(bytes); out.write(sha256(bytes)); out.fd.sync()
        }
        // Same-directory rename is atomic on supported Android/Linux/macOS filesystems.
        // If a process dies before rename, the earlier checkpoint remains authoritative.
        if (!staging.renameTo(target)) throw IOException("Cannot commit transfer checkpoint")
    }

    fun load(directory: File, limits: TransferLimits): TransferRecord {
        val file = safeChild(directory, "checkpoint.bin")
        require(file.length() in 100..MAX_CHECKPOINT_BYTES.toLong()) { "Invalid checkpoint size" }
        val all = file.readBytes() // Strictly bounded, never reads the payload file into memory.
        val body = all.copyOfRange(0, all.size - 32)
        require(MessageDigest.isEqual(sha256(body), all.copyOfRange(all.size - 32, all.size))) { "Damaged checkpoint" }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == CHECKPOINT_MAGIC && input.readUnsignedByte() == 1)
            val direction = TransferDirection.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown direction")
            val state = TransferState.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown state")
            val reason = input.readUnsignedByte()
            val failure = if (reason == 0) null else TransferFailure.entries.getOrNull(reason - 1) ?: error("Unknown failure")
            require((failure != null) == (state == TransferState.FAILED))
            val profile = TransferProfile.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown profile")
            val version = input.readUnsignedByte()
            val id = UUID(input.readLong(), input.readLong())
            require(id.toString() == directory.name)
            val size = input.readLong()
            val chunkBytes = input.readInt()
            val hash = ByteArray(32).also(input::readFully).hex()
            val manifest = TransferManifest(id, size, chunkBytes, hash, version)
            manifest.validate(profile, limits)
            val updatedAt = input.readLong().also { require(it >= 0) }
            val length = input.readInt()
            require(length == (manifest.totalChunks + 7) / 8 && input.available() == length)
            val bits = BitSet.valueOf(ByteArray(length).also(input::readFully))
            require(bits.length() <= manifest.totalChunks)
            if (state == TransferState.COMPLETED) require(bits.cardinality() == manifest.totalChunks)
            TransferRecord(manifest, profile, direction, state, bits, updatedAt, failure)
        }
    }

    fun deletePayload(id: UUID) {
        listOf(data(id), receipts(id)).forEach { if (it.exists() && !it.delete()) throw IOException("Cannot clean transfer payload") }
    }

    fun discard(id: UUID) {
        val directory = directory(id)
        val files = directory.listFiles() ?: throw IOException("Cannot inspect transfer record")
        files.forEach { require(it.name in OWNED_FILES && safeChild(directory, it.name).isFile) }
        files.forEach { if (!it.delete()) throw IOException("Cannot remove transfer file") }
        if (!directory.delete()) throw IOException("Cannot remove transfer record")
    }

    override fun close() { try { lock.release() } finally { lockFile.close() } }

    companion object {
        const val MAX_CHECKPOINT_BYTES = 8192
        const val CHECKPOINT_RESERVATION_BYTES = 2 * MAX_CHECKPOINT_BYTES
        private const val CHECKPOINT_MAGIC = 0x52465331 // RFS1
        private val OWNED_FILES = setOf("payload.bin", "receipts.bin", "checkpoint.bin", "checkpoint.tmp")
        private fun safeChild(parent: File, name: String): File = File(parent, name).also {
            require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile) { "Unsafe transfer storage path" }
        }
        fun ownerOnly(file: File) {
            val ok = file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false) &&
                file.setReadable(true, true) && file.setWritable(true, true) && (!file.isDirectory || file.setExecutable(true, true))
            if (!ok) throw IOException("Cannot restrict transfer storage permissions")
        }
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun hashFile(file: File, expectedBytes: Long): String {
            require(expectedBytes in 0..TransferLimits.HARD_MAX_FILE_BYTES)
            if (file.length() != expectedBytes) throw IOException("Transfer size changed")
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(TransferLimits.IO_BUFFER_BYTES)
                var bytes = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Transfer verification interrupted")
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - bytes + 1).toInt())
                    if (count < 0) break
                    bytes += count
                    if (bytes > expectedBytes) throw IOException("Transfer size changed")
                    if (count > 0) digest.update(buffer, 0, count)
                }
                if (bytes != expectedBytes) throw IOException("Transfer size changed")
            }
            return digest.digest().hex()
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
