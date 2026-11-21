package nl.atlas.gallery.featureaiedit.engine

import android.content.SharedPreferences
import android.util.Base64
import nl.atlas.gallery.featureaiedit.provider.RemoteHandle
import nl.atlas.gallery.featureaiedit.provider.RemoteRecovery
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * Durable, atomic store for the session's one [AiEditRequestRecord] (`request.json` in the session's
 * private folder). Each write goes to a temp file, is fsynced and renamed, so a process death leaves
 * either the old or the new record. No key, prompt-free beyond the session's own text, no image.
 */
class AndroidRequestJournal(private val directory: File) {
    private val file get() = File(directory, "request.json")

    @Synchronized fun read(): AiEditRequestRecord? = try {
        if (!file.isFile || file.length() > 32_000) null else decode(JSONObject(file.readText()))
    } catch (_: Exception) { null }

    @Synchronized fun write(record: AiEditRequestRecord) {
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile(".request-", ".tmp", directory)
        try {
            FileOutputStream(temp).use {
                it.write(encode(record).toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(temp.renameTo(file)) { "Could not commit request journal" }
        } finally { temp.delete() }
    }

    @Synchronized fun clear() { file.delete() }

    private fun encode(r: AiEditRequestRecord) = JSONObject().apply {
        put("generation", r.generation)
        put("profile", r.profileId)
        put("provider", r.providerId)
        put("service", r.serviceName)
        put("recovery", r.recovery.name)
        put("stage", r.stage.name)
        put("prompt", r.prompt.take(16_384))
        r.handle?.let { h ->
            put("handle", JSONObject().apply {
                put("provider", h.providerId); put("endpoint", h.endpoint); put("id", h.remoteId)
                put("candidates", h.candidates); put("fingerprint", h.keyFingerprint ?: "")
            })
        }
    }

    private fun decode(j: JSONObject): AiEditRequestRecord? {
        val handle = j.optJSONObject("handle")?.let {
            RemoteHandle(it.getString("provider"), it.getString("endpoint"), it.getString("id"),
                it.optInt("candidates", 1), it.optString("fingerprint").ifBlank { null })
        }
        return AiEditRequestRecord(
            generation = j.getLong("generation"),
            profileId = j.getString("profile"),
            providerId = j.getString("provider"),
            serviceName = j.optString("service"),
            recovery = RemoteRecovery.entries.firstOrNull { it.name == j.optString("recovery") } ?: RemoteRecovery.NONE,
            // An unreadable stage is treated as "may have been sent", never as done.
            stage = AiEditRequestRecord.Stage.entries.firstOrNull { it.name == j.optString("stage") }
                ?: AiEditRequestRecord.Stage.SENT,
            handle = handle,
            prompt = j.optString("prompt")
        )
    }

    companion object {
        /** Per-install random salt for [KeyFingerprint]; created once, never leaves the device. */
        fun salt(preferences: SharedPreferences): ByteArray {
            preferences.getString("fingerprintSalt", null)?.let { stored ->
                runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
                    ?.takeIf { it.size >= KeyFingerprint.SALT_BYTES }?.let { return it }
            }
            val fresh = ByteArray(KeyFingerprint.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            preferences.edit().putString("fingerprintSalt", Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()
            return fresh
        }
    }
}
