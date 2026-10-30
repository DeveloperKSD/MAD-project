package `in`.yashd.bitchat.transfer.ble

import android.content.Context
import android.util.AtomicFile
import `in`.yashd.bitchat.data.internet.HistoryCipher
import `in`.yashd.bitchat.identity.PeerIdentity
import `in`.yashd.bitchat.transfer.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Separate no-backup authenticated metadata. Missing halves/corruption never reset consent. */
class AndroidBleFileBindingStore internal constructor(
    private val file: File, private val cipher: HistoryCipher
) : BleFileBindingStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ble_file_bindings.enc"),
        HistoryCipher("relay.ble.files.bindings.aes.v1"))
    private val atomic = AtomicFile(file)
    @Synchronized override fun load(): List<BleFileBinding> {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { require(it.length() <= 32 * 1024) }
        val bytes = try { atomic.readFully() } catch (_: java.io.FileNotFoundException) {
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists() && !cipher.exists())
            return emptyList()
        }
        require(bytes.size <= 32 * 1024)
        val root = Json.parseToJsonElement(cipher.decrypt(bytes, "ble-file-bindings-v1")).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        val rows = root.getValue("items").jsonArray
        require(rows.size <= 4)
        return rows.map { value ->
            val row = value.jsonObject
            fun str(key: String) = row.getValue(key).jsonPrimitive.content
            val id = UUID.fromString(str("id"))
            val profile = TransferProfile.valueOf(str("profile")); require(profile != TransferProfile.WIFI_DIRECT)
            val remote = PeerIdentity(str("peer"), "", str("enc").unhex(), str("sign").unhex(), 0, 0, true)
            BleFileProtocol.fingerprint(remote)
            val manifest = TransferManifest(id, row.getValue("size").jsonPrimitive.long, row.getValue("chunk").jsonPrimitive.int, str("sha"))
            manifest.validate(profile, TransferLimits())
            require(manifest.chunkBytes == TransferLimits.DEFAULT_CHUNK_BYTES)
            require(str("epoch").matches(Regex("[0-9a-f]{32}")) && str("epoch") != BleFileProtocol.ZERO_NONCE)
            require(str("acceptance").matches(Regex("[0-9a-f]{32}")) && str("local").matches(Regex("[0-9a-f]{64}")))
            require(str("binding").matches(Regex("[0-9a-f]{64}")))
            BleFileBinding(manifest, profile, remote, str("local"), str("binding"), row.getValue("outgoing").jsonPrimitive.boolean,
                str("epoch"), str("acceptance"), BleFilePhase.valueOf(str("phase")))
        }.also { require(it.map { row -> row.manifest.id }.distinct().size == it.size) }
    }
    @Synchronized override fun save(bindings: List<BleFileBinding>) {
        require(bindings.size <= 4)
        load() // Authenticate any prior state before allowing replacement.
        if (!cipher.exists()) cipher.create()
        val json = buildJsonObject {
            put("version", 1)
            put("items", JsonArray(bindings.map { binding -> buildJsonObject {
                put("id", binding.manifest.id.toString()); put("size", binding.manifest.sizeBytes)
                put("chunk", binding.manifest.chunkBytes); put("sha", binding.manifest.sha256)
                put("profile", binding.profile.name); put("peer", binding.remote.peerId)
                put("enc", binding.remote.encryptionPublicKey.hex()); put("sign", binding.remote.signingPublicKey.hex())
                put("local", binding.localFingerprint); put("binding", binding.binding); put("outgoing", binding.outgoing)
                put("epoch", binding.epoch); put("acceptance", binding.acceptance); put("phase", binding.phase.name)
            } }))
        }.toString()
        val encrypted = cipher.encrypt(json, "ble-file-bindings-v1")
        require(encrypted.size <= 32 * 1024)
        val out = atomic.startWrite()
        try { out.write(encrypted); out.fd.sync(); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        check(atomic.readFully().contentEquals(encrypted))
    }
}
