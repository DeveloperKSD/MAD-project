package `in`.yashd.bitchat.background

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File

/** Opt-in is not backed up. Atomic commit survives process death; unknown/corrupt versions
 * fail closed and are preserved. Configuration and identity remain in their encrypted stores.
 */
class FileBackgroundSyncStore(context: Context,
    private val file: File = File(context.noBackupFilesDir, "nostr_background_sync.json")) : BackgroundSyncStore {
    private val atomic = AtomicFile(file)
    override fun read(): BackgroundSyncDecision = synchronized(LOCK) {
        val bytes = try { atomic.readFully() } catch (_: java.io.FileNotFoundException) {
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
            return@synchronized BackgroundSyncDecision()
        }
        require(bytes.size <= 4096)
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(json.getValue("version").jsonPrimitive.int == 1)
        val enabled = json.getValue("enabled").jsonPrimitive.boolean
        val binding = json.getValue("binding").jsonPrimitive.content
        val approval = json.getValue("approval").jsonPrimitive.content
        val last = json.getValue("lastSuccessSeconds").jsonPrimitive.long
        require(last >= 0)
        if (enabled) require(binding.matches(Regex("[0-9a-f]{64}")) && approval.length in 1..64)
        BackgroundSyncDecision(enabled, binding, approval, last)
    }
    override fun write(value: BackgroundSyncDecision) = synchronized(LOCK) {
        read() // Do not overwrite corrupt/unsupported existing state.
        val bytes = buildJsonObject {
            put("version", 1); put("enabled", value.enabled); put("binding", value.binding)
            put("approval", value.approval); put("lastSuccessSeconds", value.lastSuccessSeconds)
        }.toString().toByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        check(atomic.readFully().contentEquals(bytes))
    }
    private companion object { val LOCK = Any() }
}
