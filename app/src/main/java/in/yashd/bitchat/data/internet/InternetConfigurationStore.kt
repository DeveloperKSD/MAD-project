package `in`.yashd.bitchat.data.internet

import android.content.Context
import android.util.AtomicFile
import `in`.yashd.bitchat.nostr.NostrInbox
import kotlinx.serialization.json.*
import java.io.File

/** URLs only. Connection and publication results are deliberately never persisted. */
data class InternetConfiguration(val discovery: List<String>, val inboxes: List<String>) {
    fun validated(): InternetConfiguration {
        listOf(discovery, inboxes).forEach { urls ->
            require(urls.size in 1..3 && urls.distinct().size == urls.size)
            urls.forEach(NostrInbox::validateUrl)
        }
        return this
    }
}

interface InternetConfigurationStore {
    /** Blocking storage operations; callers must use a worker thread. */
    fun load(owner: String): InternetConfiguration?
    fun save(owner: String, configuration: InternetConfiguration)
}

class LocalConfigurationException : RuntimeException(
    "Saved Internet servers could not be opened or saved. Existing data was preserved. Restart Relay to retry.")

/** Independent Keystore key, identity-bound AAD, and no backup/device transfer. */
class EncryptedInternetConfigurationStore(
    context: Context,
    private val file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
    private val cipher: HistoryCipher = HistoryCipher(KEY_ALIAS),
    // Injection permits testing a failure after AtomicFile has staged the new ciphertext.
    private val beforeCommit: () -> Unit = {}
) : InternetConfigurationStore {
    private val atomic = AtomicFile(file)
    private var failed = false

    private fun binding(owner: String): String {
        require(owner.matches(Regex("[0-9a-f]{64}")))
        return "internet-configuration-v1:$owner"
    }
    private fun read(owner: String): InternetConfiguration? {
        val aad = binding(owner)
        val bytes = try { atomic.readFully() } catch (e: java.io.FileNotFoundException) {
            // A surviving key or file fragment is evidence of existing data, never a fresh install.
            check(!file.exists() && !File(file.path + ".bak").exists() &&
                !File(file.path + ".new").exists() && !cipher.exists())
            return null
        }
        require(bytes.size <= 64 * 1024)
        val json = Json.parseToJsonElement(cipher.decrypt(bytes, aad)).jsonObject
        require(json["version"]?.jsonPrimitive?.int == 1)
        fun urls(name: String) = json.getValue(name).jsonArray.map { it.jsonPrimitive.content }
        return InternetConfiguration(urls("discovery"), urls("inboxes")).validated()
    }
    private fun <T> access(action: () -> T): T = synchronized(LOCK) {
        check(!failed) { "Configuration storage unavailable" }
        try { action() } catch (_: Exception) { failed = true; throw LocalConfigurationException() }
    }
    override fun load(owner: String): InternetConfiguration? = access { read(owner) }

    override fun save(owner: String, configuration: InternetConfiguration) {
        // Invalid caller input cannot poison or modify a healthy store.
        configuration.validated()
        binding(owner)
        access {
            read(owner) // Authenticate existing data before permitting replacement, including across instances.
            if (!cipher.exists()) cipher.create()
            val payload = buildJsonObject {
                put("version", 1)
                put("discovery", JsonArray(configuration.discovery.map(::JsonPrimitive)))
                put("inboxes", JsonArray(configuration.inboxes.map(::JsonPrimitive)))
            }.toString()
            val encrypted = cipher.encrypt(payload, binding(owner))
            val output = atomic.startWrite()
            try {
                output.write(encrypted)
                beforeCommit()
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
            // AtomicFile logs some commit failures rather than throwing; never report a false save.
            check(atomic.readFully().contentEquals(encrypted))
        }
    }
    companion object {
        const val FILE_NAME = "nostr_configuration.enc"
        const val KEY_ALIAS = "relay.nostr.configuration.aes.v1"
        private val LOCK = Any()
    }
}
