package nl.atlas.gallery.featureidentity.local

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

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
 * A model that its original host publishes only inside a ZIP archive (InsightFace's
 * `buffalo_l.zip`). The app fetches just this member's bytes with one HTTP range
 * (`Range: bytes=<rangeStart>-`, read for [rangeBytes]): its local file header plus the
 * deflated data, pinned by [rangeSha256]. The rest of the archive is never downloaded.
 * After the range verifies, the member is inflated and checked against the entry's
 * own SHA-256 and size.
 */
data class ArchiveMember(
    val member: String,
    val rangeStart: Long,
    val rangeBytes: Long,
    val rangeSha256: String,
    /** Size of the deflated data inside the range (the local header may leave it as 0). */
    val compressedBytes: Long
) {
    init {
        require(member.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")) && ".." !in member) { "Invalid archive member" }
        require(rangeStart >= 0 && rangeBytes in 31..4_000_000_000L) { "Invalid archive range" }
        require(compressedBytes in 1 until rangeBytes) { "Invalid compressed size" }
        require(rangeSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid range SHA-256" }
    }
}

/**
 * One downloadable model. [registerRow] names its row in LICENCES.md; [ownerDecision]
 * links the recorded owner decision (for example OD-1 on #467) for a tier C or E row.
 * [archive] is set when [url] is an archive and only one member of it is the model.
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
    val ownerDecision: String? = null,
    val archive: ArchiveMember? = null
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
    /** The URL is not one of the original hosts in [ModelHostPolicy] (no mirrors). */
    data object HostNotAllowed : ModelGate
}

/**
 * Original hosts only (OD-1, #516 "Not this"): a model is fetched from the release
 * page of the project that published it, never from a mirror. Each prefix is the
 * publisher's own GitHub release-download path. A fetcher may follow GitHub's
 * redirect from these URLs to its release-asset storage; the manifest URL itself
 * must match.
 */
object ModelHostPolicy {
    val originalHostPrefixes: List<String> = listOf(
        // FaceFusion's own model host (HyperSwap 1a 256).
        "https://github.com/facefusion/facefusion-assets/releases/download/",
        // InsightFace's own release (buffalo_l, of which only w600k_r50.onnx is fetched).
        "https://github.com/deepinsight/insightface/releases/download/"
    )

    fun allowed(url: String): Boolean {
        if (!url.startsWith("https://") || '?' in url || '#' in url || '@' in url || "/../" in url || "\\" in url) return false
        return originalHostPrefixes.any { url.startsWith(it) && url.length > it.length }
    }
}

object ModelGatePolicy {
    fun gate(entry: ModelEntry): ModelGate = when (entry.tier) {
        LicenceTier.D -> ModelGate.Forbidden
        LicenceTier.C, LicenceTier.E ->
            if (entry.ownerDecision.isNullOrBlank()) ModelGate.NeedsOwnerDecision else hostGate(entry)
        LicenceTier.A, LicenceTier.B -> hostGate(entry)
    }

    private fun hostGate(entry: ModelEntry) =
        if (ModelHostPolicy.allowed(entry.url)) ModelGate.Allowed else ModelGate.HostNotAllowed
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

/**
 * One ranged read from the model's original host (`Range: bytes=<offset>-`).
 * [resumed] is false when the host ignored the range and sent the whole file.
 */
class FetchResponse(val body: InputStream, val resumed: Boolean)

fun interface ModelFetcher {
    fun open(url: String, offset: Long): FetchResponse
}

/**
 * Model files under `<noBackupFilesDir>/identity_models/`. Blocking; call from an IO
 * worker. This package holds no network code (the #191 fence keeps network use inside
 * featureaiedit/): the host passes the HTTPS [fetcher] that performs the one-time,
 * user-started download. See LOCAL_ENGINE.md, "Open points".
 */
class IdentityModelStore(root: File, private val fetcher: ModelFetcher) {
    private val dir = File(root, DIR)
    private val lock = Any()

    companion object {
        const val DIR = "identity_models"
        private const val PART = ".part"
        private const val VERIFIED = ".sha256"
        private const val INFLATE = ".inflate"
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
     * size and SHA-256, then moves the file into place. For an [ArchiveMember] entry only
     * the member's byte range is fetched and verified, then inflated and verified again.
     * Throws [ModelGateException] before any network access when the gate is closed.
     */
    fun install(entry: ModelEntry, isCancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): File {
        val gate = ModelGatePolicy.gate(entry)
        if (gate != ModelGate.Allowed) throw ModelGateException(gate)
        synchronized(lock) {
            installedFile(entry)?.let { return it }
            check(dir.isDirectory || dir.mkdirs()) { "Could not create the model folder" }
            val archive = entry.archive
            val start = archive?.rangeStart ?: 0L
            val length = archive?.rangeBytes ?: entry.sizeBytes
            val p = part(entry)
            var offset = if (p.isFile) p.length() else 0L
            if (offset > length) { p.delete(); offset = 0 }
            if (offset < length) {
                val response = fetcher.open(entry.url, start + offset)
                var skip = 0L
                if (!response.resumed) {
                    // The host sent the file from byte 0: restart, and skip to the range.
                    offset = 0
                    skip = start
                }
                RandomAccessFile(p, "rw").use { out ->
                    out.setLength(offset)
                    out.seek(offset)
                    response.body.use { body ->
                        val buffer = ByteArray(64 * 1024)
                        while (skip > 0) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Model download cancelled")
                            val n = body.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
                            if (n < 0) break
                            skip -= n
                        }
                        while (offset < length) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Model download cancelled")
                            val n = body.read(buffer, 0, minOf(buffer.size.toLong(), length - offset).toInt())
                            if (n < 0) break
                            offset += n
                            out.write(buffer, 0, n)
                            progress(offset)
                        }
                        // A plain file must end exactly at its size; an archive range simply stops there.
                        if (archive == null && offset == length && body.read() >= 0) {
                            out.close(); p.delete()
                            throw ModelVerificationException("Model is larger than its manifest says")
                        }
                    }
                }
            }
            if (p.length() != length) throw ModelVerificationException("Model download incomplete")
            val actual = sha256(p)
            if (actual != (archive?.rangeSha256 ?: entry.sha256)) {
                p.delete()
                throw ModelVerificationException("Model hash does not match its manifest")
            }
            val target = file(entry)
            target.delete()
            if (archive == null) {
                check(p.renameTo(target)) { "Could not move the model into place" }
            } else {
                val inflated = File(dir, entry.fileName + INFLATE)
                try {
                    ZipMember.extract(p, archive, inflated)
                    if (inflated.length() != entry.sizeBytes) throw ModelVerificationException("Extracted model has the wrong size")
                    if (sha256(inflated) != entry.sha256) throw ModelVerificationException("Extracted model hash does not match its manifest")
                    check(inflated.renameTo(target)) { "Could not move the model into place" }
                } finally {
                    inflated.delete()
                    p.delete()
                }
            }
            marker(entry).writeText(entry.sha256)
            return target
        }
    }

    /** Removes the model, its marker and any partial download. */
    fun remove(entry: ModelEntry): Boolean = synchronized(lock) {
        File(dir, entry.fileName + INFLATE).delete()
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
 * Reads one ZIP local file entry (header + data, as fetched for an [ArchiveMember]) and
 * writes the member's bytes. Only stored (0) and deflate (8) are accepted. The data size
 * comes from the manifest, because a streamed archive leaves it 0 in the local header.
 */
internal object ZipMember {
    private const val LOCAL_HEADER = 0x04034b50

    fun extract(range: File, archive: ArchiveMember, out: File) {
        RandomAccessFile(range, "r").use { raf ->
            val head = ByteArray(30)
            raf.readFully(head)
            fun u16(i: Int) = (head[i].toInt() and 255) or ((head[i + 1].toInt() and 255) shl 8)
            fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
            if (u32(0) != LOCAL_HEADER.toLong()) throw ModelVerificationException("Archive range is not a ZIP entry")
            val method = u16(8)
            val nameLength = u16(26)
            val extraLength = u16(28)
            val name = ByteArray(nameLength).also { raf.readFully(it) }.toString(Charsets.UTF_8)
            if (name != archive.member) throw ModelVerificationException("Archive range holds another file")
            val dataStart = 30L + nameLength + extraLength
            if (dataStart + archive.compressedBytes != archive.rangeBytes) throw ModelVerificationException("Archive range has the wrong layout")
            raf.seek(dataStart)
            out.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var left = archive.compressedBytes
                when (method) {
                    0 -> while (left > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw ModelVerificationException("Archive range ends early")
                        sink.write(buffer, 0, n); left -= n
                    }
                    8 -> {
                        val inflater = Inflater(true)
                        val outBuf = ByteArray(256 * 1024)
                        try {
                            while (!inflater.finished()) {
                                if (inflater.needsInput()) {
                                    if (left <= 0) throw ModelVerificationException("Archive member is truncated")
                                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                    if (n < 0) throw ModelVerificationException("Archive range ends early")
                                    left -= n
                                    inflater.setInput(buffer, 0, n)
                                }
                                val m = try { inflater.inflate(outBuf) } catch (e: java.util.zip.DataFormatException) {
                                    throw ModelVerificationException("Archive member is corrupt")
                                }
                                if (m == 0 && inflater.needsDictionary()) throw ModelVerificationException("Archive member is corrupt")
                                sink.write(outBuf, 0, m)
                            }
                        } finally { inflater.end() }
                    }
                    else -> throw ModelVerificationException("Archive member uses an unsupported compression")
                }
            }
        }
    }
}

/**
 * The model manifest. Every entry names its LICENCES.md row and, for tier C or E, the
 * owner decision. The two entries below are allowed by OD-1 (private use, downloaded
 * after install from the original hosts) and were re-read on 2026-11-19: URL, size and
 * SHA-256 below are what those hosts served that day (LICENCES.md, LOCAL_ENGINE.md).
 */
object IdentityModelManifest {
    const val OD1 = "https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396"

    /** HyperSwap 1a 256, FaceFusion's own model host (402.7 MB). */
    val HYPERSWAP_1A_256 = ModelEntry(
        id = "hyperswap-1a-256",
        role = ModelRole.SWAP_NETWORK,
        fileName = "hyperswap_1a_256.onnx",
        url = "https://github.com/facefusion/facefusion-assets/releases/download/models-3.3.0/hyperswap_1a_256.onnx",
        sha256 = "c0e98a8a03a238f461ed3d2570e426b49f46745ee400854a60dceeb70c246add",
        sizeBytes = 402_742_682L,
        registerRow = "HyperSwap 1a / 1b / 1c",
        tier = LicenceTier.C,
        ownerDecision = OD1
    )

    /**
     * InsightFace `w600k_r50` (ArcFace R50, WebFace600K) from InsightFace's own v0.7
     * release. Only `w600k_r50.onnx` is fetched out of `buffalo_l.zip` (288.6 MB): one
     * 160.3 MB range, inflated to 174.4 MB. Same network as the bench's FaceFusion copy
     * (identical outputs; only the tensor names differ).
     */
    val INSIGHTFACE_W600K_R50 = ModelEntry(
        id = "insightface-w600k-r50",
        role = ModelRole.IDENTITY_ENCODER,
        fileName = "w600k_r50.onnx",
        url = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip",
        sha256 = "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43",
        sizeBytes = 174_383_860L,
        registerRow = "InsightFace ArcFace w600k_r50 (buffalo_l)",
        tier = LicenceTier.C,
        ownerDecision = OD1,
        archive = ArchiveMember(
            member = "w600k_r50.onnx",
            rangeStart = 128_359_988L,
            rangeBytes = 160_260_974L,
            rangeSha256 = "53f2e2a47b96326a218c093f00655ad8d7b73088b2f591c12010d41bd3730efc",
            compressedBytes = 160_260_914L
        )
    )

    val entries: List<ModelEntry> = listOf(HYPERSWAP_1A_256, INSIGHTFACE_W600K_R50)

    /** Bytes the one-time setup downloads, and bytes the installed models occupy. */
    val downloadBytes: Long get() = entries.sumOf { it.archive?.rangeBytes ?: it.sizeBytes }
    val installedBytes: Long get() = entries.sumOf { it.sizeBytes }
}
