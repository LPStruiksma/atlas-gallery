package nl.atlas.gallery.featureidentity.local

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * IDS-9 (#475): model manifest and on-phone model store. Binding design: the
 * #475 Strategy comment (issuecomment-6990190659). The public repository holds
 * code and this manifest format only; weights are never in git, APK assets,
 * releases, CI artifacts or caches. A model is fetched at run time from its
 * original host, only after the user taps setup, straight into app-private,
 * no-backup storage, hash-verified, and removable. Nothing is re-hosted or
 * exported, and no model is fetched while generating.
 */

/** What a model file is for in the local pipeline. */
enum class ModelRole { SWAP_NETWORK, IDENTITY_ENCODER, FACE_SEGMENTER }

/** Licence register tier (docs/identity-studio/LICENCES.md). C and E need a recorded owner decision. */
enum class LicenceTier { A, B, C, D, E }

/**
 * One downloadable model. [registerRow] names its row in LICENCES.md; [ownerDecision]
 * links the recorded owner decision (for example OD-1 on #467) for a tier C or E row.
 */
data class ModelEntry(
    val id: String,
    val role: ModelRole,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val registerRow: String,
    val tier: LicenceTier,
    val ownerDecision: String? = null
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid model id" }
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && ".." !in fileName) { "Invalid model file name" }
        require(url.startsWith("https://")) { "Models download over HTTPS only" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        require(sizeBytes in 1..4_000_000_000L) { "Invalid model size" }
        require(registerRow.isNotBlank()) { "A model needs its licence register row" }
        require(ownerDecision == null || ownerDecision.startsWith("https://")) { "An owner decision is a link" }
    }
}

/** Why an entry may or may not be downloaded. */
sealed interface ModelGate {
    data object Allowed : ModelGate
    /** Tier C or E without a recorded owner decision. */
    data object NeedsOwnerDecision : ModelGate
    /** Tier D: never shipped or downloaded by the app. */
    data object Forbidden : ModelGate
}

object ModelGatePolicy {
    fun gate(entry: ModelEntry): ModelGate = when (entry.tier) {
        LicenceTier.A, LicenceTier.B -> ModelGate.Allowed
        LicenceTier.C, LicenceTier.E -> if (entry.ownerDecision.isNullOrBlank()) ModelGate.NeedsOwnerDecision else ModelGate.Allowed
        LicenceTier.D -> ModelGate.Forbidden
    }
}

/** Install state, which the studio turns into its setup sheet when not installed. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Partial(val bytes: Long) : ModelState
    data class Installed(val file: File) : ModelState
    data class Blocked(val gate: ModelGate) : ModelState
}

class ModelGateException(val gate: ModelGate) : IllegalStateException("Model download not allowed: $gate")
class ModelVerificationException(message: String) : IllegalStateException(message)

/** One ranged read from the model's host. [resumed] is false when the host ignored the range. */
class FetchResponse(val body: InputStream, val resumed: Boolean)

fun interface ModelFetcher {
    fun open(url: String, offset: Long): FetchResponse
}

/** Plain HTTPS fetcher with a Range header for resume; no cookies, no credentials. */
object HttpsModelFetcher : ModelFetcher {
    override fun open(url: String, offset: Long): FetchResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.useCaches = false
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        val code = connection.responseCode
        if (code != 200 && code != 206) {
            connection.disconnect()
            throw java.io.IOException("Model host answered HTTP $code")
        }
        return FetchResponse(connection.inputStream, resumed = code == 206)
    }
}

/**
 * Model files under `<noBackupFilesDir>/identity_models/`. Blocking; call from an IO
 * worker. [fetcher] is replaceable for tests; production uses [HttpsModelFetcher].
 */
class IdentityModelStore(root: File, private val fetcher: ModelFetcher = HttpsModelFetcher) {
    private val dir = File(root, DIR)
    private val lock = Any()

    companion object {
        const val DIR = "identity_models"
        private const val PART = ".part"
        private const val VERIFIED = ".sha256"
    }

    private fun file(entry: ModelEntry) = File(dir, entry.fileName)
    private fun part(entry: ModelEntry) = File(dir, entry.fileName + PART)
    private fun marker(entry: ModelEntry) = File(dir, entry.fileName + VERIFIED)

    fun state(entry: ModelEntry): ModelState = synchronized(lock) {
        installedFile(entry)?.let { return ModelState.Installed(it) }
        val gate = ModelGatePolicy.gate(entry)
        if (gate != ModelGate.Allowed) return ModelState.Blocked(gate)
        val p = part(entry)
        if (p.isFile && p.length() > 0) ModelState.Partial(p.length()) else ModelState.NotInstalled
    }

    /**
     * The verified file, or null. The file counts as installed only if its size matches and
     * the hash recorded at install time is the manifest's hash. A blocked entry is never
     * installed, even if a file with its name exists.
     */
    fun installedFile(entry: ModelEntry): File? {
        if (ModelGatePolicy.gate(entry) != ModelGate.Allowed) return null
        val f = file(entry)
        val m = marker(entry)
        return if (f.isFile && f.length() == entry.sizeBytes && m.isFile && m.readText().trim() == entry.sha256) f else null
    }

    /**
     * One-time install after the user taps setup. Resumes a partial download, verifies
     * size and SHA-256, then moves the file into place. Throws [ModelGateException]
     * before any network access when the gate is closed.
     */
    fun install(entry: ModelEntry, isCancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): File {
        val gate = ModelGatePolicy.gate(entry)
        if (gate != ModelGate.Allowed) throw ModelGateException(gate)
        synchronized(lock) {
            installedFile(entry)?.let { return it }
            check(dir.isDirectory || dir.mkdirs()) { "Could not create the model folder" }
            val p = part(entry)
            var offset = if (p.isFile) p.length() else 0L
            if (offset > entry.sizeBytes) { p.delete(); offset = 0 }
            if (offset < entry.sizeBytes) {
                val response = fetcher.open(entry.url, offset)
                if (!response.resumed) offset = 0
                RandomAccessFile(p, "rw").use { out ->
                    out.setLength(offset)
                    out.seek(offset)
                    response.body.use { body ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Model download cancelled")
                            val n = body.read(buffer)
                            if (n < 0) break
                            offset += n
                            if (offset > entry.sizeBytes) {
                                out.close(); p.delete()
                                throw ModelVerificationException("Model is larger than its manifest says")
                            }
                            out.write(buffer, 0, n)
                            progress(offset)
                        }
                    }
                }
            }
            if (p.length() != entry.sizeBytes) throw ModelVerificationException("Model download incomplete")
            val actual = sha256(p)
            if (actual != entry.sha256) {
                p.delete()
                throw ModelVerificationException("Model hash does not match its manifest")
            }
            val target = file(entry)
            target.delete()
            check(p.renameTo(target)) { "Could not move the model into place" }
            marker(entry).writeText(entry.sha256)
            return target
        }
    }

    /** Removes the model, its marker and any partial download. */
    fun remove(entry: ModelEntry): Boolean = synchronized(lock) {
        listOf(file(entry), part(entry), marker(entry)).forEach { it.delete() }
        !file(entry).exists() && !part(entry).exists() && !marker(entry).exists()
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The model manifest. Empty of enabled third-party entries on purpose: the
 * HyperSwap 1a and InsightFace `w600k_r50` entries allowed by OD-1 arrive with
 * their network adapters in IDS-17 (#516). Every entry added here must name its
 * LICENCES.md row and, for tier C or E, the owner decision.
 */
object IdentityModelManifest {
    val entries: List<ModelEntry> = emptyList()
}
