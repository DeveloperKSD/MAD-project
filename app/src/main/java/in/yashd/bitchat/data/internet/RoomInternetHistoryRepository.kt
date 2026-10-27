package `in`.yashd.bitchat.data.internet

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import `in`.yashd.bitchat.nostr.NostrMessagingCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Owns a non-backed-up Room store. No destructive migration, key replacement, or automatic reset. */
class RoomInternetHistoryRepository(
    context: Context,
    private val file: File = File(context.noBackupFilesDir, "relay_history/relay-internet.db"),
    private val cipher: HistoryCipher = HistoryCipher()
) : InternetHistoryRepository {
    private val context = context.applicationContext
    private val gate = Mutex()
    private var database: InternetHistoryDatabase? = null
    private var failed = false
    private var closed = false

    private suspend fun db(): InternetHistoryDatabase = INITIALIZATION_GATE.withLock {
        check(!failed && !closed)
        database?.let { check(file.exists()); verify(it); return@withLock it }
        file.parentFile!!.mkdirs()
        val existed = file.exists()
        val fragments = listOf("-wal", "-shm", "-journal").any { File(file.path + it).exists() }
        // Either surviving half is evidence of earlier storage. Never replace a missing half.
        check(existed == cipher.exists() && (existed || !fragments))
        if (!existed) cipher.create()
        val opened = Room.databaseBuilder(context, InternetHistoryDatabase::class.java, file.absolutePath)
            .openHelperFactory(PreserveHistoryOpenHelperFactory()).addMigrations(MIGRATION_1_2).build()
        database = opened
        if (!existed) opened.history().metadata(StorageMetadata(keyCheck = cipher.encrypt(CHECK, "storage-check")))
        verify(opened)
        opened
    }
    private suspend fun verify(db: InternetHistoryDatabase) {
        val marker = requireNotNull(db.history().metadata())
        check(cipher.decrypt(marker.keyCheck, "storage-check") == CHECK)
    }
    private suspend fun <T> access(action: suspend (InternetHistoryDatabase, HistoryDao) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            try { val db = db(); action(db, db.history()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; throw LocalHistoryException() }
        }
    }
    override suspend fun load(owner: String): HistorySnapshot = access { db, dao ->
        validateKey(owner)
        db.withTransaction {
            val conversations = dao.conversations(owner)
            conversations.forEach { check(it.transport == "INTERNET") }
            val hidden = conversations.filter { it.hidden }.map { it.peer }.toSet()
            val rows = dao.recent(owner).asReversed()
            val times = dao.conversationTimes(owner).associate { it.peer to it.lastMessageAtMs }
            validatePublications(dao.publicationsForMessages(owner, rows.map { it.recordId }))
            HistorySnapshot(dao.contacts(owner).filter { it.peer !in hidden }.map {
                SavedContact(it.peer, cipher.decrypt(it.encryptedName, nameBinding(owner, it.peer)), times[it.peer])
            }, rows.map(::saved))
        }
    }
    override suspend fun changes(owner: String, id: String?, peer: String?): HistorySnapshot = access { db, dao ->
        validateKey(owner)
        db.withTransaction {
            val found = id?.let { dao.message(owner, it) ?: dao.rumor(owner, it) }
            // A deleted message must never be shown again, even when a relay redelivers it.
            val row = found?.takeIf { it.status != HistoryStatus.DELETED.name }
            if (row != null) validatePublications(dao.publications(owner, row.recordId))
            val contact = (peer ?: found?.peer)?.let { who ->
                dao.contact(owner, who)?.takeIf { dao.conversation(owner, who)?.hidden != true }
            }
            HistorySnapshot(contact?.let { listOf(SavedContact(it.peer, cipher.decrypt(it.encryptedName, nameBinding(owner, it.peer)), row?.createdAtMs)) }.orEmpty(),
                row?.let { listOf(saved(it)) }.orEmpty())
        }
    }
    override suspend fun page(owner: String, peer: String, beforeTime: Long?, beforeId: String): List<SavedMessage> = access { db, dao ->
        validateKey(owner); validateKey(peer)
        db.withTransaction {
            val rows = dao.page(owner, peer, beforeTime, beforeId).asReversed()
            validatePublications(dao.publicationsForMessages(owner, rows.map { it.recordId }))
            rows.map(::saved)
        }
    }
    private fun validatePublications(rows: List<PublicationRow>) {
        rows.forEach {
            check(it.target in listOf("recipient", "sender"))
            NostrMessagingCoordinator.Stage.valueOf(it.stage)
            cipher.decrypt(it.encryptedRelay, publicationBinding(it))
        }
    }
    private fun saved(row: MessageRow): SavedMessage {
        check(row.transport == "INTERNET")
        return SavedMessage(row.recordId, row.rumorId, row.peer, body(row), row.outgoing, row.createdAtMs, HistoryStatus.valueOf(row.status))
    }
    private suspend fun ensureConversation(dao: HistoryDao, owner: String, peer: String, name: String, now: Long) {
        validateKey(owner); validateKey(peer)
        val previous = dao.contact(owner, peer)
        // Decrypt existing metadata before updating; corrupt names must not be overwritten silently.
        val previousName = previous?.let { cipher.decrypt(it.encryptedName, nameBinding(owner, peer)) }.orEmpty()
        val chosen = name.ifBlank { previousName }
        dao.put(ContactRow(owner, peer, cipher.encrypt(chosen, nameBinding(owner, peer)), previous?.createdAtMs ?: now))
        val conversation = dao.conversation(owner, peer)
        check(conversation == null || conversation.transport == "INTERNET")
        dao.put(ConversationRow(owner, peer, updatedAtMs = maxOf(now, conversation?.updatedAtMs ?: 0)))
    }
    override suspend fun contact(owner: String, peer: String, name: String) = access { db, dao ->
        require(name.length <= 60 && name.none { it.isISOControl() })
        db.withTransaction { ensureConversation(dao, owner, peer, name, System.currentTimeMillis()) }
    }
    override suspend fun outgoing(owner: String, id: String, peer: String, body: String, time: Long) = access { db, dao ->
        db.withTransaction {
            ensureConversation(dao, owner, peer, "", time)
            check(dao.message(owner, id) == null)
            val row = MessageRow(owner, id, null, peer, byteArrayOf(), true, time, HistoryStatus.QUEUED.name)
            dao.insert(row.copy(encryptedBody = cipher.encrypt(body, bodyBinding(row))))
        }
    }
    override suspend fun bindRumor(owner: String, id: String, rumorId: String) = access { db, dao ->
        validateKey(rumorId)
        db.withTransaction {
            val local = requireNotNull(dao.message(owner, id))
            val duplicate = dao.rumor(owner, rumorId)
            if (duplicate != null && duplicate.recordId != id) {
                // Only remove a proven duplicate of the same authenticated rumor, never damaged data.
                check(duplicate.peer == local.peer && duplicate.outgoing == local.outgoing &&
                    (duplicate.status == HistoryStatus.DELETED.name || body(duplicate) == body(local)))
                val previousPublications = dao.publications(owner, duplicate.recordId).map { p ->
                    val relay = cipher.decrypt(p.encryptedRelay, publicationBinding(p))
                    val moved = p.copy(recordId = id)
                    moved.copy(encryptedRelay = cipher.encrypt(relay, publicationBinding(moved)))
                }
                dao.delete(duplicate)
                dao.insert(previousPublications)
            }
            check(local.rumorId == null || local.rumorId == rumorId)
            val duplicateStatus = duplicate?.status
            val updated = local.copy(rumorId = rumorId,
                status = if (duplicateStatus == HistoryStatus.RELAY_ACCEPTED.name || duplicateStatus == HistoryStatus.DELETED.name) (duplicateStatus ?: local.status) else local.status)
            dao.update(seal(updated, body(local)))
        }
    }
    override suspend fun received(owner: String, rumorId: String, peer: String, body: String, outgoing: Boolean, time: Long) = access { db, dao ->
        validateKey(rumorId)
        db.withTransaction {
            val existing = dao.rumor(owner, rumorId)
            if (existing != null) {
                // A deleted message stays deleted when a relay sends it again.
                if (existing.status != HistoryStatus.DELETED.name) check(existing.peer == peer && existing.outgoing == outgoing && body(existing) == body)
                // A sender copy proves neither recipient delivery nor relay publication acceptance.
            } else {
                ensureConversation(dao, owner, peer, "", time)
                val row = MessageRow(owner, rumorId, rumorId, peer, byteArrayOf(), outgoing, time,
                    (if (outgoing) HistoryStatus.SENDER_COPY_RECEIVED else HistoryStatus.RECEIVED).name)
                dao.insert(row.copy(encryptedBody = cipher.encrypt(body, bodyBinding(row))))
            }
        }
    }
    override suspend fun publication(owner: String, id: String, result: NostrMessagingCoordinator.SendResult) = access { db, dao ->
        db.withTransaction {
            val row = requireNotNull(dao.message(owner, id))
            if (row.status == HistoryStatus.DELETED.name) return@withTransaction
            val content = body(row)
            val updated = row.copy(status = if (row.status == HistoryStatus.RELAY_ACCEPTED.name) row.status else publicationStatus(result).name)
            dao.update(seal(updated, content))
            val previous = dao.publications(owner, id)
            val records = listOf("recipient" to result.recipient, "sender" to result.sender).flatMap { (target, publications) ->
                publications.mapIndexed { index, p ->
                    val position = index + (previous.filter { it.target == target }.maxOfOrNull { it.position }?.plus(1) ?: 0)
                    val row = PublicationRow(owner, id, target, position, p.eventId, byteArrayOf(), p.stage.name)
                    row.copy(encryptedRelay = cipher.encrypt(p.relay, publicationBinding(row))) }
            }
            dao.insert(records)
        }
    }
    override suspend fun failed(owner: String, id: String, unknown: Boolean) = access { db, dao ->
        db.withTransaction {
            val row = requireNotNull(dao.message(owner, id))
            if (row.status == HistoryStatus.DELETED.name) return@withTransaction
            val content = body(row)
            val updated = row.copy(status = if (row.status == HistoryStatus.RELAY_ACCEPTED.name) row.status else (if (unknown) HistoryStatus.UNKNOWN else HistoryStatus.FAILED).name)
            dao.update(seal(updated, content))
        }
    }
    override suspend fun interruptPending(owner: String) = access { db, dao ->
        db.withTransaction {
            // Verify all bodies first, so a failure does not mutate damaged history.
            val verified = dao.pending(owner).map { it to body(it) }
            verified.forEach { (row, content) ->
                dao.update(seal(row.copy(status = HistoryStatus.UNKNOWN.name), content))
            }
        }
    }
    override suspend fun deleteMessage(owner: String, id: String) = access { db, dao ->
        validateKey(owner)
        db.withTransaction {
            val row = dao.message(owner, id) ?: dao.rumor(owner, id)
            if (row != null) erase(dao, row)
            Unit
        }
    }
    override suspend fun deleteConversation(owner: String, peer: String) = access { db, dao ->
        validateKey(owner); validateKey(peer)
        db.withTransaction {
            dao.liveMessages(owner, peer).forEach { erase(dao, it) }
            val contact = dao.contact(owner, peer)
            if (contact != null) dao.put(contact.copy(encryptedName = cipher.encrypt("", nameBinding(owner, peer))))
            if (dao.conversation(owner, peer) != null) dao.setHidden(owner, peer, true)
            Unit
        }
    }
    /** Wipes the text and relay details but keeps the row, so a redelivered copy is recognised and ignored. */
    private suspend fun erase(dao: HistoryDao, row: MessageRow) {
        if (row.status == HistoryStatus.DELETED.name) return
        dao.deletePublications(row.owner, row.recordId)
        dao.update(seal(row.copy(status = HistoryStatus.DELETED.name), ""))
    }
    override suspend fun close() = withContext(Dispatchers.IO) {
        gate.withLock { closed = true; database?.close(); database = null }
    }
    private fun seal(row: MessageRow, content: String) = row.copy(encryptedBody = cipher.encrypt(content, bodyBinding(row)))
    private fun publicationBinding(row: PublicationRow) = "relay:${row.owner}:${row.recordId}:${row.target}:${row.position}:${row.eventId}:${row.stage}"
    private fun body(row: MessageRow) = cipher.decrypt(row.encryptedBody, bodyBinding(row))
    private fun bodyBinding(row: MessageRow) = "body:${row.owner}:${row.peer}:${row.recordId}:${row.outgoing}:${row.createdAtMs}:${row.rumorId ?: "-"}:${row.status}:INTERNET"
    private fun nameBinding(owner: String, peer: String) = "name:$owner:$peer"
    private fun validateKey(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    companion object {
        // Key creation and the first authenticated marker must appear as one initialization
        // to overlapping repository/ViewModel instances in this process.
        private val INITIALIZATION_GATE = Mutex()
        private const val CHECK = "relay-history-key-check-v1"
    }
}
