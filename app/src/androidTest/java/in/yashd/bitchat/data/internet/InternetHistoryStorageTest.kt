package `in`.yashd.bitchat.data.internet

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.nostr.NostrMessagingCoordinator
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class InternetHistoryStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val alias = "relay.test.history.$token"
    private val file = File(context.noBackupFilesDir, "history-tests/$token/history.db")
    private val owner = "01".repeat(32)
    private val peer = "02".repeat(32)
    private val rumor = "03".repeat(32)
    private val repositories = mutableListOf<RoomInternetHistoryRepository>()
    private fun repo() = RoomInternetHistoryRepository(context, file, HistoryCipher(alias)).also { repositories.add(it) }
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private suspend fun raw(action: suspend (InternetHistoryDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, InternetHistoryDatabase::class.java, file.absolutePath)
            .openHelperFactory(PreserveHistoryOpenHelperFactory()).build()
        try { action(db) } finally { db.close() }
    }
    @After fun cleanup() = runBlocking(Dispatchers.IO) {
        repositories.forEach { it.close() }
        keystore().deleteEntry(alias)
        file.parentFile?.deleteRecursively()
        Unit
    }
    @Test fun backgroundCatchUpPreservesTombstonesAndSenderStatusAcrossRestart() = runBlocking(Dispatchers.IO) {
        val rumorEvent = `in`.yashd.bitchat.nostr.UnsignedNostrEvent(peer, 1000, 14,
            listOf(listOf("p", owner)), "deleted plaintext")
        val id = `in`.yashd.bitchat.nostr.NostrEventCodec.calculateId(rumorEvent)
        var repository = repo()
        repository.received(owner, id, peer, rumorEvent.content, false, 1_000_000)
        repository.deleteConversation(owner, peer)
        repository.close()
        repository = repo()
        val backend = object : `in`.yashd.bitchat.ui.relay.InternetBackend {
            override fun identity() = `in`.yashd.bitchat.nostr.NostrIdentity(owner,
                `in`.yashd.bitchat.nostr.NostrNpub.encode(ByteArray(32) { 1 }))
            override fun open(discovery: List<String>, received: (`in`.yashd.bitchat.nostr.UnsignedNostrEvent) -> Unit,
                connection: (String, String) -> Unit, issue: (String) -> Unit): `in`.yashd.bitchat.ui.relay.InternetSession = error("No live session")
            override fun close() {}
        }
        var alerts = 0
        val runtime = `in`.yashd.bitchat.ui.relay.InternetRuntime(backend, Dispatchers.IO, repository,
            onIncomingStored = { _, _, _ -> alerts++ })
        try {
            assertEquals(true, runtime.backgroundCatchUp(owner) { stored ->
                repeat(3) { stored(rumorEvent) }
                true
            })
            assertTrue(repository.load(owner).messages.isEmpty())
            assertTrue(repository.load(owner).contacts.isEmpty())
            val outgoing = rumorEvent.copy(pubkey = owner, tags = listOf(listOf("p", peer)), content = "sender copy")
            val outgoingId = `in`.yashd.bitchat.nostr.NostrEventCodec.calculateId(outgoing)
            repository.outgoing(owner, "sent", peer, outgoing.content, 1_000_000)
            repository.bindRumor(owner, "sent", outgoingId)
            repository.publication(owner, "sent", accepted())
            assertEquals(true, runtime.backgroundCatchUp(owner) { stored -> repeat(3) { stored(outgoing) }; true })
            assertEquals(1, repository.load(owner).messages.size)
            assertEquals(HistoryStatus.RELAY_ACCEPTED, repository.load(owner).messages.single().status)
            assertEquals(0, alerts)
        } finally { runtime.close(); repository.close() }
        val snapshot = repo().load(owner)
        assertEquals(1, snapshot.messages.size)
        assertEquals(HistoryStatus.RELAY_ACCEPTED, snapshot.messages.single().status)
    }

    @Test fun contactsAndBodiesSurviveRepositoryAndDatabaseReopen() = runBlocking(Dispatchers.IO) {
        val first = repo()
        first.contact(owner, peer, "Aarav")
        first.outgoing(owner, "local", peer, "private message", 1234)
        first.bindRumor(owner, "local", rumor)
        first.publication(owner, "local", accepted())
        first.close()
        val snapshot = repo().load(owner)
        assertEquals("Aarav", snapshot.contacts.single().name)
        assertEquals("private message", snapshot.messages.single().body)
        assertEquals(rumor, snapshot.messages.single().rumorId)
        assertEquals(1234L, snapshot.messages.single().createdAtMs)
        assertEquals(HistoryStatus.RELAY_ACCEPTED, snapshot.messages.single().status)
    }
    @Test fun contactAndConversationPrimaryKeysPreventDuplicatesAndPreserveName() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.contact(owner, peer, "First")
        repository.contact(owner, peer, "")
        assertEquals("First", repository.load(owner).contacts.single().name)
        repository.contact(owner, peer, "Updated")
        assertEquals("Updated", repository.load(owner).contacts.single().name)
        repository.close()
        raw { db ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM conversations").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }
    @Test fun repeatedRumorAcrossRestartDoesNotDuplicateMessage() = runBlocking(Dispatchers.IO) {
        var repository = repo()
        repeat(2) { repository.received(owner, rumor, peer, "received", false, 1000) }
        repository.close(); repository = repo()
        repository.received(owner, rumor, peer, "received", false, 1000)
        assertEquals(1, repository.load(owner).messages.size)
    }
    @Test fun senderCopyRetainsRecipientPublicationStatusAcrossRestart() = runBlocking(Dispatchers.IO) {
        var repository = repo()
        repository.outgoing(owner, "local", peer, "same rumor", 1000)
        repository.bindRumor(owner, "local", rumor)
        repository.publication(owner, "local", accepted())
        repository.close(); repository = repo()
        repository.received(owner, rumor, peer, "same rumor", true, 1000)
        val message = repository.load(owner).messages.single()
        assertEquals(HistoryStatus.RELAY_ACCEPTED, message.status)
        assertEquals("local", message.recordId)
    }
    @Test fun senderCopyArrivingBeforeBindingMergesAtomically() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.outgoing(owner, "local", peer, "body", 1000)
        repository.received(owner, rumor, peer, "body", true, 1000)
        repository.bindRumor(owner, "local", rumor)
        repository.publication(owner, "local", accepted())
        assertEquals(1, repository.load(owner).messages.size)
        assertEquals(HistoryStatus.RELAY_ACCEPTED, repository.load(owner).messages.single().status)
    }
    @Test fun senderCopyAloneNeverBecomesDeliveredOrRelayAccepted() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.received(owner, rumor, peer, "sender archive", true, 1000)
        assertEquals(HistoryStatus.SENDER_COPY_RECEIVED, repository.load(owner).messages.single().status)
        assertTrue(repository.load(owner).messages.single().status.label().contains("delivery unconfirmed"))
    }
    @Test fun restoredQueuedSendBecomesUnknownWithoutAutomaticResend() = runBlocking(Dispatchers.IO) {
        val first = repo()
        first.outgoing(owner, "pending", peer, "pending body", 1000)
        first.close()
        val next = repo()
        next.interruptPending(owner)
        assertEquals(HistoryStatus.UNKNOWN, next.load(owner).messages.single().status)
    }
    @Test fun publicationStagesAndEncryptedRelayMetadataAreStoredExactly() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.outgoing(owner, "local", peer, "body", 1)
        repository.publication(owner, "local", NostrMessagingCoordinator.SendResult(
            listOf(publication(NostrMessagingCoordinator.Stage.UNKNOWN)), listOf(publication(NostrMessagingCoordinator.Stage.RELAY_REJECTED))))
        repository.close()
        val reloaded = repo().load(owner)
        assertEquals(HistoryStatus.UNKNOWN, reloaded.messages.single().status)
        raw { db ->
            val rows = db.history().publications(owner, "local")
            assertEquals(setOf("recipient", "sender"), rows.map { it.target }.toSet())
            assertEquals(setOf("UNKNOWN", "RELAY_REJECTED"), rows.map { it.stage }.toSet())
            rows.forEach { assertEquals("wss://relay.example/?secret=relay-token", HistoryCipher(alias).decrypt(it.encryptedRelay, "relay:$owner:local:${it.target}:${it.position}:${it.eventId}:${it.stage}")) }
        }
    }
    @Test fun rawDatabaseAndSidecarsContainNoPlaintextBodiesOrNames() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.contact(owner, peer, "PRIVATE_CONTACT_LABEL")
        repository.outgoing(owner, "local", peer, "PRIVATE_MESSAGE_BODY_948127", 1)
        repository.publication(owner, "local", accepted())
        fun assertNoPlaintext() {
            file.parentFile!!.listFiles()!!.forEach {
                val bytes = it.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains("PRIVATE_CONTACT_LABEL"))
                assertFalse(bytes.contains("PRIVATE_MESSAGE_BODY_948127"))
                assertFalse(bytes.contains("relay-token"))
            }
        }
        // Closing the last connection checkpoints/removes WAL/SHM. Inspect live files too.
        assertTrue(File(file.path + "-wal").exists())
        assertTrue(File(file.path + "-shm").exists())
        assertNoPlaintext()
        repository.close()
        assertNoPlaintext()
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
    }
    @Test fun aesGcmRoundTripUsesRandomIvsAndRejectsTamperingAndWrongBinding() {
        val cipher = HistoryCipher(alias)
        cipher.create()
        val one = cipher.encrypt("confidential", "record-a")
        val two = cipher.encrypt("confidential", "record-a")
        assertFalse(one.contentEquals(two))
        assertEquals("confidential", cipher.decrypt(one, "record-a"))
        val tampered = one.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered, "record-a") }
        assertThrows(Exception::class.java) { cipher.decrypt(one, "record-b") }
    }
    @Test fun missingKeystoreKeyFailsWithoutCreatingReplacementOrDeletingHistory() = runBlocking(Dispatchers.IO) {
        val first = repo()
        first.outgoing(owner, "local", peer, "keep this", 1)
        first.close()
        val before = file.readBytes()
        keystore().deleteEntry(alias)
        try { repo().load(owner); fail("Missing key must fail") } catch (_: LocalHistoryException) {}
        assertFalse(keystore().containsAlias(alias))
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun replacementKeyCannotOpenExistingDataAndDoesNotResetIt() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.contact(owner, peer, "Keep name"); first.close()
        val before = file.readBytes()
        keystore().deleteEntry(alias); HistoryCipher(alias).create()
        try { repo().load(owner); fail("Wrong key must fail") } catch (_: LocalHistoryException) {}
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun missingDatabaseWithSurvivingKeyDoesNotSilentlyCreateEmptyHistory() = runBlocking(Dispatchers.IO) {
        HistoryCipher(alias).create()
        try { repo().load(owner); fail("Missing database must fail") } catch (_: LocalHistoryException) {}
        assertFalse(file.exists())
        assertTrue(keystore().containsAlias(alias))
    }
    @Test fun rowCiphertextSwapFailsAuthenticatedBindingAndPreservesRows() = runBlocking(Dispatchers.IO) {
        val first = repo()
        first.outgoing(owner, "one", peer, "first", 1)
        first.outgoing(owner, "two", peer, "second", 2)
        first.close()
        raw { db ->
            val bytes = db.history().message(owner, "one")!!.encryptedBody
            db.openHelper.writableDatabase.execSQL("UPDATE messages SET encryptedBody = ? WHERE recordId = 'two'", arrayOf(bytes))
        }
        try { repo().load(owner); fail("Swapped ciphertext must fail") } catch (_: LocalHistoryException) {}
        raw { db -> assertEquals(2, db.history().messages(owner).size) }
    }
    @Test fun tamperedKeyCheckBlocksWritesWithoutDiscardingContact() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.contact(owner, peer, "Keep"); first.close()
        raw { db -> db.openHelper.writableDatabase.execSQL("UPDATE storage_metadata SET keyCheck = ?", arrayOf(byteArrayOf(1,2,3))) }
        try { repo().contact(owner, peer, "Overwrite"); fail("Tampered marker must block writes") } catch (_: LocalHistoryException) {}
        raw { db -> assertEquals(1, db.history().contacts(owner).size) }
    }
    @Test fun corruptedDatabaseIsNotDeletedBySqliteRecovery() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.contact(owner, peer, "Keep"); first.close()
        val damaged = file.readBytes().apply { repeat(32) { this[it] = 0x55.toByte() } }
        file.writeBytes(damaged)
        try { repo().load(owner); fail("Corruption must fail") } catch (_: LocalHistoryException) {}
        assertTrue(file.exists())
        assertArrayEquals(damaged.copyOfRange(0,32), file.readBytes().copyOfRange(0,32))
    }
    @Test fun differentLocalIdentitiesCannotSeeEachOthersContactsOrMessages() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repository.outgoing(owner, "local", peer, "body", 1)
        val other = repository.load("04".repeat(32))
        assertTrue(other.contacts.isEmpty()); assertTrue(other.messages.isEmpty())
    }
    @Test fun unknownDeliveryStatusFailsClosedRatherThanShowingDelivered() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.outgoing(owner, "local", peer, "body", 1); first.close()
        raw { db -> db.openHelper.writableDatabase.execSQL("UPDATE messages SET status = 'DELIVERED'") }
        try { repo().load(owner); fail("Unsupported status must fail") } catch (_: LocalHistoryException) {}
        raw { db -> assertEquals(1, db.history().messages(owner).size) }
    }
    @Test fun backupRulesExcludeDatabaseAndCompanionsFromCloudAndTransfer() {
        listOf(`in`.yashd.bitchat.R.xml.backup_rules, `in`.yashd.bitchat.R.xml.data_extraction_rules).forEach { resource ->
            val paths = mutableListOf<String>()
            context.resources.getXml(resource).use { parser ->
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude")
                        paths.add(parser.getAttributeValue(null, "path"))
                    parser.next()
                }
            }
            listOf("relay_history/", "relay-internet.db", "relay-internet.db-wal", "relay-internet.db-shm", "relay-internet.db-journal").forEach {
                assertEquals(if (resource == `in`.yashd.bitchat.R.xml.backup_rules) 1 else 2, paths.count { path -> path == it })
            }
        }
    }
    @Test fun changingAValidPublicationStatusCannotForgeAcceptance() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.outgoing(owner, "local", peer, "body", 1); first.close()
        raw { db -> db.openHelper.writableDatabase.execSQL("UPDATE messages SET status = 'RELAY_ACCEPTED'") }
        try { repo().load(owner); fail("Changed authenticated status must fail") } catch (_: LocalHistoryException) {}
        assertTrue(file.exists())
    }
    @Test fun changedCanonicalRumorIdFailsAuthenticationWithoutRemovingMessage() = runBlocking(Dispatchers.IO) {
        val first = repo(); first.outgoing(owner, "local", peer, "body", 1); first.close()
        raw { db -> db.openHelper.writableDatabase.execSQL("UPDATE messages SET rumorId = ?", arrayOf(rumor)) }
        try { repo().load(owner); fail("Changed rumor ID must fail") } catch (_: LocalHistoryException) {}
        raw { db -> assertEquals(1, db.history().messages(owner).size) }
    }
    @Test fun contactNameCiphertextCannotBeMovedToAnotherPublicKey() = runBlocking(Dispatchers.IO) {
        val first = repo()
        first.contact(owner, peer, "First friend")
        first.contact(owner, rumor, "Second friend")
        first.close()
        raw { db ->
            val encrypted = db.history().contact(owner, peer)!!.encryptedName
            db.openHelper.writableDatabase.execSQL("UPDATE contacts SET encryptedName = ? WHERE peer = ?", arrayOf(encrypted, rumor))
        }
        try { repo().load(owner); fail("Moved name must fail") } catch (_: LocalHistoryException) {}
        raw { db -> assertEquals(2, db.history().contacts(owner).size) }
    }
    @Test fun concurrentFirstOpenUsesOneKeyAndOneDatabaseMarker() = runBlocking(Dispatchers.IO) {
        // Separate repository instances model overlapping ViewModels. Construct them before
        // launching so the test fixture's repository list itself is not accessed concurrently.
        val instances = List(6) { repo() }
        val start = CompletableDeferred<Unit>()
        val opens = instances.map { repository -> async { start.await(); repository.load(owner) } }
        start.complete(Unit)
        opens.awaitAll().forEach { assertTrue(it.contacts.isEmpty()) }
        instances.first().contact(owner, peer, "Preserved across instances")
        instances.forEach { assertEquals("Preserved across instances", it.load(owner).contacts.single().name) }
    }
    @Test fun historyLoadsBoundedPagesAndSingleRecordChanges() = runBlocking(Dispatchers.IO) {
        val repository = repo()
        repeat(230) { repository.outgoing(owner, "row-${it.toString().padStart(3, '0')}", peer, "message-$it", it.toLong()) }
        assertEquals(200, repository.load(owner).messages.size)
        assertEquals(230, run { var count = 0; raw { count = it.history().messages(owner).size }; count })
        val latest = repository.page(owner, peer)
        assertEquals(100, latest.size)
        assertEquals("message-130", latest.first().body)
        val older = repository.page(owner, peer, latest.first().createdAtMs, latest.first().recordId)
        assertEquals("message-30", older.first().body)
        assertEquals(100, older.size)
        val change = repository.changes(owner, "row-000", null)
        assertEquals("message-0", change.messages.single().body)
        assertEquals(1, change.contacts.size)
    }
    private fun publication(stage: NostrMessagingCoordinator.Stage) = NostrMessagingCoordinator.Publication("event-id", "wss://relay.example/?secret=relay-token", stage)
    private fun accepted() = NostrMessagingCoordinator.SendResult(listOf(publication(NostrMessagingCoordinator.Stage.RELAY_ACCEPTED)), emptyList())
}
