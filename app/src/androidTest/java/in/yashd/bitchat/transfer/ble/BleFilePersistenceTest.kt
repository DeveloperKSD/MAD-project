package `in`.yashd.bitchat.transfer.ble

import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.*
import `in`.yashd.bitchat.transfer.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Test-only directory and alias: never touch the application's identity/history/transfer files. */
class BleFilePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val directory = File(context.noBackupFilesDir, "test-ble-files-$token").apply { mkdirs() }
    private val file = File(directory, "bindings.enc")
    private val alias = "relay.test.ble.files.$token"
    private fun cipher() = HistoryCipher(alias)
    private fun store() = AndroidBleFileBindingStore(file, cipher())
    private fun identity(name: String): LocalIdentity {
        fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val enc = pair(); val sign = pair()
        return LocalIdentity(IdentityManager.derivePeerId(enc.public.encoded, sign.public.encoded), name,
            enc.public.encoded, enc.private.encoded, sign.public.encoded, sign.private.encoded, 0)
    }
    private val alice = identity("Alice"); private val bob = identity("Bob")
    private fun row(): BleFileBinding {
        val manifest = TransferManifest(UUID.randomUUID(), 123, 1024, "12".repeat(32))
        val remote = BleFileProtocol.peer(bob)
        return BleFileBinding(manifest, TransferProfile.DIRECT_BLE, remote,
            BleFileProtocol.fingerprint(BleFileProtocol.peer(alice)),
            BleFileProtocol.binding(alice, remote, manifest, TransferProfile.DIRECT_BLE), true,
            BleFileProtocol.nonce(), BleFileProtocol.nonce(), BleFilePhase.SENDING)
    }
    @After fun cleanup() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    @Test fun metadataIsEncryptedAndCommittedStateSurvivesReopenAndInterruptedWrite() {
        val expected = row()
        assertTrue(store().load().isEmpty()); store().save(listOf(expected))
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(expected.manifest.id.toString()))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(expected.remote.encryptionPublicKey.hex()))
        File(file.path + ".new").writeText("interrupted update")
        val reopened = store().load().single()
        assertEquals(expected.manifest, reopened.manifest)
        assertEquals(expected.phase, reopened.phase); assertEquals(expected.acceptance, reopened.acceptance)
        assertEquals(expected.binding, reopened.binding)
        assertArrayEquals(expected.remote.signingPublicKey, reopened.remote.signingPublicKey)
        assertArrayEquals(bytes, file.readBytes())
        File(file.path + ".bak").writeBytes(bytes)
        file.writeText("interrupted legacy replacement")
        assertEquals(expected.manifest, store().load().single().manifest)
        assertArrayEquals(bytes, file.readBytes())
        store().save(emptyList()); assertTrue(store().load().isEmpty())
    }
    @Test fun corruptedCiphertextCannotBeLoadedOrOverwritten() {
        store().save(listOf(row()))
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(Exception::class.java) { store().load() }
        assertThrows(Exception::class.java) { store().save(emptyList()) }
        assertArrayEquals(damaged, file.readBytes())
    }
    @Test fun missingKeyPreservesCiphertextAndNeverGeneratesReplacement() {
        store().save(listOf(row())); val bytes = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(Exception::class.java) { store().load() }
        assertThrows(Exception::class.java) { store().save(emptyList()) }
        assertFalse(cipher().exists()); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun missingMetadataWithExistingKeyFailsClosed() {
        store().save(listOf(row())); assertTrue(file.delete())
        assertThrows(Exception::class.java) { store().load() }
        assertThrows(Exception::class.java) { store().save(emptyList()) }
        assertFalse(file.exists()); assertTrue(cipher().exists())
    }
    @Test fun controllerRecreationRestoresPausedWithoutTransmittingOrReusingAcceptance() {
        val sent = mutableListOf<`in`.yashd.bitchat.mesh.MeshPacket>()
        fun controller() = BleFileController(alice, FileTransferEngine(directory), store(),
            { if (it == bob.peerId) BleFileProtocol.peer(bob) else null }, { true },
            { packet, _ -> sent.add(packet); true })
        val id: UUID
        controller().use { first ->
            id = first.offer(bob.peerId, ByteArrayInputStream(ByteArray(2000) { 3 }), TransferProfile.DIRECT_BLE)
        }
        val count = sent.size
        controller().use { reopened ->
            assertEquals(id, reopened.state.value.single().id)
            assertEquals(BleFilePhase.PAUSED, reopened.state.value.single().phase)
            assertEquals(BleFileProtocol.ZERO_NONCE, store().load().single().acceptance)
            reopened.tick(); assertEquals(count, sent.size)
            reopened.resume(id); assertEquals(count + 1, sent.size)
            assertEquals(BleFilePhase.OFFERED, reopened.state.value.single().phase)
        }
    }
}
