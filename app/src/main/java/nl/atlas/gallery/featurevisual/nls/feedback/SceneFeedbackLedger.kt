package nl.atlas.gallery.featurevisual.nls.feedback

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A deliberately small, opt-in feedback store, NOT a retrieval index.
 *
 * Callers must provide the current media permission grant AND its monotonically
 * changing epoch on every read and write. A denial or epoch mismatch erases the
 * prior records and consent before any record can become visible. The UI must
 * never cache records across permission changes.
 *
 * Only a per-install keyed SHA-256 digest of canonical query text is persisted;
 * query text, paths, embeddings and result lists are never stored. The media ID
 * is recorded only for an explicitly marked incorrect hit. No automatic capture,
 * network transport or logging exists in this component.
 */
class SceneFeedbackLedger(
    private val persistence: SceneFeedbackPersistence,
    private val newKey: () -> ByteArray = {
        ByteArray(32).also { SecureRandom().nextBytes(it) }
    },
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val MAX_ENTRIES = 256
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_QUERY_LENGTH = 4096
        private const val MAGIC = 0x53464C31 // SFL1
        private const val VERSION = 1
        private const val DIGEST_BYTES = 32
        private const val MAX_FILE_BYTES = 32 * 1024
        private val HEX = "0123456789abcdef".toCharArray()
        private val SPACES = Regex("\\s+")
    }

    data class Permission(val granted: Boolean, val epoch: Long) {
        init { require(epoch >= 0) { "Permission epoch must be nonnegative" } }
    }

    enum class Kind { INCORRECT_HIT, MISSING_MOMENT }

    /** A digest, not the query. Missing-moment feedback never includes a media ID. */
    data class Feedback(
        val kind: Kind,
        val queryDigest: String,
        val mediaId: Long?,
        val recordedAtMillis: Long
    )

    private data class Record(
        val kind: Kind,
        val digest: ByteArray,
        val mediaId: Long, // zero is reserved for missing-moment feedback
        val recordedAtMillis: Long
    )

    private data class State(val epoch: Long, val key: ByteArray, val rows: List<Record>)

    private var loaded = false
    private var state: State? = null

    /** Consent is separate from Android permission; only a granted epoch can enable it. */
    @Synchronized
    fun optIn(permission: Permission): Boolean {
        val existing = active(permission)
        if (!permission.granted) return false
        if (existing != null) return true
        val key = newKey().copyOf()
        require(key.size == DIGEST_BYTES) { "Invalid digest key length" }
        commit(State(permission.epoch, key, emptyList()))
        return true
    }

    /** Declining consent deletes entries and the per-install digest key. */
    @Synchronized
    fun optOut() = clearAll()

    /** Full erase: deletes entries, consent and digest key, even without permission. */
    @Synchronized
    fun clearAll() {
        persistence.erase()
        state = null
        loaded = true
    }

    /** Erase feedback without revoking consent, if the current epoch remains valid. */
    @Synchronized
    fun clearFeedback(permission: Permission) {
        val current = active(permission) ?: return
        if (current.rows.isNotEmpty()) commit(current.copy(rows = emptyList()))
    }

    @Synchronized
    fun isOptedIn(permission: Permission): Boolean = active(permission) != null

    /**
     * Returns true only when a NEW row was persisted. Repeating the same
     * explicit feedback does not count it twice or refresh its timestamp.
     * The ID is the positive opaque MediaStore media ID, never a file path.
     */
    @Synchronized
    fun markIncorrectHit(query: String, mediaId: Long, permission: Permission): Boolean {
        val current = active(permission) ?: return false
        if (mediaId <= 0L) return false
        return add(current, query, Kind.INCORRECT_HIT, mediaId)
    }

    /** Explicit "the sought moment is missing", with NO associated media ID. */
    @Synchronized
    fun markMissingMoment(query: String, permission: Permission): Boolean {
        val current = active(permission) ?: return false
        return add(current, query, Kind.MISSING_MOMENT, 0L)
    }

    /** Always re-fences visibility against current grant and epoch. */
    @Synchronized
    fun feedback(permission: Permission): List<Feedback> =
        active(permission)?.rows?.map { row ->
            Feedback(
                row.kind,
                buildString(DIGEST_BYTES * 2) {
                    for (byte in row.digest) {
                        append(HEX[(byte.toInt() ushr 4) and 15])
                        append(HEX[byte.toInt() and 15])
                    }
                },
                if (row.kind == Kind.INCORRECT_HIT) row.mediaId else null,
                row.recordedAtMillis
            )
        } ?: emptyList()

    private fun add(current: State, query: String, kind: Kind, mediaId: Long): Boolean {
        val canonical = canonicalQuery(query) ?: return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(current.key, "HmacSHA256"))
        val digest = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        if (current.rows.any {
                it.kind == kind && it.mediaId == mediaId && it.digest.contentEquals(digest)
            }) return false

        val updated = current.rows + Record(kind, digest, mediaId, now())
        commit(current.copy(rows = updated.takeLast(MAX_ENTRIES)))
        return true
    }

    private fun canonicalQuery(raw: String): String? {
        if (raw.length > MAX_QUERY_LENGTH ||
            raw.any { it == '\u0000' || (Character.isISOControl(it) && !it.isWhitespace()) } ||
            !Charsets.UTF_8.newEncoder().canEncode(raw)
        ) return null
        val canonical = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(SPACES, " ")
        return canonical.takeIf { it.isNotBlank() && it.length <= MAX_QUERY_LENGTH }
    }

    private fun now(): Long = clockMillis().also {
        require(it >= 0L) { "Invalid clock value" }
    }

    private fun active(permission: Permission): State? {
        // Denial is destructive, not merely a filter that can reveal stale data later.
        if (!permission.granted) {
            clearAll()
            return null
        }
        if (!loaded) {
            val bytes = persistence.read()
            val decoded = bytes?.let(::decode)
            if (bytes != null && decoded == null) persistence.erase()
            state = decoded
            loaded = true
        }
        val current = state ?: return null
        if (current.epoch != permission.epoch) {
            clearAll()
            return null
        }
        val instant = now()
        val cutoff = (instant - RETENTION_MILLIS).coerceAtLeast(0L)
        val retained = current.rows.filter {
            it.recordedAtMillis >= cutoff && it.recordedAtMillis <= instant
        }
        if (retained.size != current.rows.size) {
            commit(current.copy(rows = retained))
        }
        return state
    }

    private fun commit(next: State) {
        // Publish in-memory changes only after persistence succeeds.
        persistence.write(encode(next))
        state = next
        loaded = true
    }

    private fun encode(next: State): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(next.epoch)
                out.write(next.key)
                out.writeInt(next.rows.size)
                for (row in next.rows) {
                    out.writeByte(if (row.kind == Kind.INCORRECT_HIT) 1 else 2)
                    out.write(row.digest)
                    out.writeLong(row.mediaId)
                    out.writeLong(row.recordedAtMillis)
                }
            }
        }.toByteArray()

    private fun decode(bytes: ByteArray): State? {
        if (bytes.size > MAX_FILE_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val epoch = input.readLong()
                if (epoch < 0L) return null
                val key = ByteArray(DIGEST_BYTES).also(input::readFully)
                val count = input.readInt()
                if (count !in 0..MAX_ENTRIES) return null
                val rows = ArrayList<Record>(count)
                repeat(count) {
                    val kind = when (input.readUnsignedByte()) {
                        1 -> Kind.INCORRECT_HIT
                        2 -> Kind.MISSING_MOMENT
                        else -> return null
                    }
                    val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                    val mediaId = input.readLong()
                    val timestamp = input.readLong()
                    if (timestamp < 0L || (kind == Kind.INCORRECT_HIT && mediaId <= 0L) ||
                        (kind == Kind.MISSING_MOMENT && mediaId != 0L) ||
                        rows.any { it.kind == kind && it.mediaId == mediaId &&
                            it.digest.contentEquals(digest) }
                    ) return null
                    rows.add(Record(kind, digest, mediaId, timestamp))
                }
                if (input.available() != 0) return null
                State(epoch, key, rows)
            }
        } catch (_: IOException) {
            null // malformed/truncated data: fail closed and erase on read
        }
    }
}

/** Raw persistence is injectable; implementations must be app-private and atomic. */
interface SceneFeedbackPersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun erase()
}

/**
 * Atomic, bounded local-file adapter. Only pass an app-private directory.
 * The Android wrapper below uses noBackupFilesDir, so Android Auto Backup
 * cannot silently copy feedback to a cloud backup.
 */
class FileSceneFeedbackPersistence internal constructor(
    private val file: File,
    private val atomicReplace: (File, File) -> Unit
) : SceneFeedbackPersistence {
    /** Production writes require atomic replacement; the injectable seam tests failure paths. */
    constructor(file: File) : this(file, { from, to ->
        Files.move(from.toPath(), to.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })
    override fun read(): ByteArray? {
        if (!file.exists()) return null
        if (file.length() > 32 * 1024) return ByteArray(0) // decoded as malformed, then erased
        return file.readBytes()
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= 32 * 1024) { "Feedback exceeds storage bound" }
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Private directory unavailable")
        val temp = File(parent, file.name + ".tmp")
        try {
            FileOutputStream(temp).use {
                it.write(bytes)
                it.fd.sync()
            }
            // Never fall back to a non-atomic move: preserve the prior file on failure.
            atomicReplace(temp, file)
        } finally {
            temp.delete()
        }
    }

    override fun erase() {
        if (file.exists() && !file.delete()) throw IOException("Feedback erase failed")
        val temp = File(file.parentFile, file.name + ".tmp")
        if (temp.exists() && !temp.delete()) throw IOException("Feedback temp erase failed")
    }
}

/** Android adapter: private, non-backed-up local storage; no permissions or deps added. */
class AndroidPrivateSceneFeedbackPersistence(context: Context) : SceneFeedbackPersistence by
    FileSceneFeedbackPersistence(File(context.noBackupFilesDir, "scene-feedback-ledger-v1.bin"))
