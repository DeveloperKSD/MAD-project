package `in`.yashd.bitchat.data.internet

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

@Entity(tableName = "contacts", primaryKeys = ["owner", "peer"])
data class ContactRow(val owner: String, val peer: String, val encryptedName: ByteArray, val createdAtMs: Long)
@Entity(tableName = "conversations", primaryKeys = ["owner", "peer"], foreignKeys = [
    ForeignKey(entity = ContactRow::class, parentColumns = ["owner", "peer"], childColumns = ["owner", "peer"], onDelete = ForeignKey.RESTRICT)])
data class ConversationRow(val owner: String, val peer: String, val transport: String = "INTERNET", val updatedAtMs: Long)
@Entity(tableName = "messages", primaryKeys = ["owner", "recordId"], indices = [Index(value = ["owner", "rumorId"], unique = true), Index(value = ["owner", "peer"])], foreignKeys = [
    ForeignKey(entity = ConversationRow::class, parentColumns = ["owner", "peer"], childColumns = ["owner", "peer"], onDelete = ForeignKey.RESTRICT)])
data class MessageRow(val owner: String, val recordId: String, val rumorId: String?, val peer: String,
    val encryptedBody: ByteArray, val outgoing: Boolean, val createdAtMs: Long, val status: String, val transport: String = "INTERNET")
@Entity(tableName = "publications", primaryKeys = ["owner", "recordId", "target", "position"], foreignKeys = [
    ForeignKey(entity = MessageRow::class, parentColumns = ["owner", "recordId"], childColumns = ["owner", "recordId"], onDelete = ForeignKey.CASCADE)])
data class PublicationRow(val owner: String, val recordId: String, val target: String, val position: Int,
    val eventId: String, val encryptedRelay: ByteArray, val stage: String)
@Entity(tableName = "storage_metadata")
data class StorageMetadata(@PrimaryKey val id: Int = 1, val keyCheck: ByteArray)
data class ConversationTime(val peer: String, val lastMessageAtMs: Long)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM storage_metadata WHERE id = 1") suspend fun metadata(): StorageMetadata?
    @Insert suspend fun metadata(value: StorageMetadata)
    @Query("SELECT * FROM contacts WHERE owner = :owner ORDER BY createdAtMs, peer") suspend fun contacts(owner: String): List<ContactRow>
    @Query("SELECT * FROM contacts WHERE owner = :owner AND peer = :peer") suspend fun contact(owner: String, peer: String): ContactRow?
    @Upsert suspend fun put(value: ContactRow)
    @Upsert suspend fun put(value: ConversationRow)
    @Query("SELECT * FROM conversations WHERE owner = :owner") suspend fun conversations(owner: String): List<ConversationRow>
    @Query("SELECT * FROM publications WHERE owner = :owner") suspend fun allPublications(owner: String): List<PublicationRow>
    @Query("SELECT * FROM conversations WHERE owner = :owner AND peer = :peer") suspend fun conversation(owner: String, peer: String): ConversationRow?
    @Query("SELECT * FROM messages WHERE owner = :owner ORDER BY createdAtMs, recordId") suspend fun messages(owner: String): List<MessageRow>
    @Query("SELECT * FROM messages WHERE owner = :owner ORDER BY createdAtMs DESC, recordId DESC LIMIT 200") suspend fun recent(owner: String): List<MessageRow>
    @Query("SELECT peer, MAX(createdAtMs) AS lastMessageAtMs FROM messages WHERE owner = :owner GROUP BY peer") suspend fun conversationTimes(owner: String): List<ConversationTime>
    @Query("SELECT * FROM messages WHERE owner = :owner AND status = 'QUEUED'") suspend fun pending(owner: String): List<MessageRow>
    @Query("SELECT * FROM messages WHERE owner = :owner AND peer = :peer AND (:beforeTime IS NULL OR createdAtMs < :beforeTime OR (createdAtMs = :beforeTime AND recordId < :beforeId)) ORDER BY createdAtMs DESC, recordId DESC LIMIT 100")
    suspend fun page(owner: String, peer: String, beforeTime: Long?, beforeId: String): List<MessageRow>
    @Query("SELECT * FROM messages WHERE owner = :owner AND recordId = :id") suspend fun message(owner: String, id: String): MessageRow?
    @Query("SELECT * FROM messages WHERE owner = :owner AND rumorId = :rumor") suspend fun rumor(owner: String, rumor: String): MessageRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: MessageRow)
    @Update suspend fun update(value: MessageRow)
    @Delete suspend fun delete(value: MessageRow)
    @Query("SELECT * FROM publications WHERE owner = :owner AND recordId = :id ORDER BY target, position") suspend fun publications(owner: String, id: String): List<PublicationRow>
    @Query("SELECT * FROM publications WHERE owner = :owner AND recordId IN (:ids)") suspend fun publicationsForMessages(owner: String, ids: List<String>): List<PublicationRow>
    @Insert suspend fun insert(values: List<PublicationRow>)
}
@Database(entities = [ContactRow::class, ConversationRow::class, MessageRow::class, PublicationRow::class, StorageMetadata::class], version = 1, exportSchema = true)
abstract class InternetHistoryDatabase : RoomDatabase() { abstract fun history(): HistoryDao }

/** SQLite's default corruption callback deletes files. Preserve them for every failure instead. */
internal class PreserveHistoryOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val delegate = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(delegate.version) {
            override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
            override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
            override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) { throw LocalHistoryException() }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).allowDataLossOnRecovery(false).build())
    }
}
