package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurealbums.AlbumBucketKey
import java.util.Locale

/** Samsung's selection More (#568): copy keeps the original, move relocates it. */
enum class AlbumTransferMode { COPY, MOVE }

/** The album a selection is copied or moved into: an existing folder or a new one. */
data class AlbumTransferTarget(val volumeName: String, val relativePath: String, val title: String) {
    val bucket: AlbumBucketKey get() = AlbumBucketKey(volumeName, relativePath)
}

/** One planned file operation: [source] keeps its bytes; [targetName] is free in the target folder. */
data class AlbumTransferStep(val source: MediaRecord, val targetName: String)

/**
 * What a copy or move will do before anything is written. [alreadyThere] are
 * items a move leaves alone because they already live in the target album;
 * [otherVolume] are items a move cannot reach (MediaStore moves stay on one
 * storage volume, so they are reported, never silently copied).
 */
data class AlbumTransferPlan(
    val mode: AlbumTransferMode,
    val target: AlbumTransferTarget,
    val steps: List<AlbumTransferStep>,
    val alreadyThere: List<MediaRecord>,
    val otherVolume: List<MediaRecord>
)

/** Samsung's default new-album parent, under the primary Pictures folder. */
const val NEW_ALBUM_PARENT = "Pictures/"

/** Longest album name accepted for a new folder (FAT/ext4 limit is 255 bytes). */
const val MAX_ALBUM_NAME_CHARS = 100

/** Why a typed album name can't become a folder; null when it can. */
enum class AlbumNameProblem { BLANK, TOO_LONG, BAD_CHARACTER, DOT_NAME, EXISTS }

private val forbiddenNameChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * Checks a typed new-album name. Existing folders under [NEW_ALBUM_PARENT]
 * on the same volume are compared case-insensitively, as Android's FUSE
 * layer treats them as the same folder.
 */
fun albumNameProblem(raw: String, existing: Collection<AlbumBucketKey>, volumeName: String): AlbumNameProblem? {
    val name = raw.trim()
    if (name.isEmpty()) return AlbumNameProblem.BLANK
    if (name.length > MAX_ALBUM_NAME_CHARS) return AlbumNameProblem.TOO_LONG
    if (name.any { it in forbiddenNameChars || it.isISOControl() }) return AlbumNameProblem.BAD_CHARACTER
    if (name.startsWith('.')) return AlbumNameProblem.DOT_NAME
    val path = newAlbumRelativePath(name).lowercase(Locale.ROOT)
    if (existing.any { sameStorageVolume(it.volumeName, volumeName) && it.relativePath.lowercase(Locale.ROOT) == path })
        return AlbumNameProblem.EXISTS
    return null
}

fun albumNameProblemText(problem: AlbumNameProblem): String = when (problem) {
    AlbumNameProblem.BLANK -> "Enter a name"
    AlbumNameProblem.TOO_LONG -> "Use $MAX_ALBUM_NAME_CHARS characters or fewer"
    AlbumNameProblem.BAD_CHARACTER -> "Names can't contain / \\ : * ? \" < > |"
    AlbumNameProblem.DOT_NAME -> "Names can't start with a dot"
    AlbumNameProblem.EXISTS -> "An album with this name already exists"
}

fun newAlbumRelativePath(name: String): String = NEW_ALBUM_PARENT + name.trim() + "/"

private val numberedName = Regex("""^(.*?)\s?\((\d+)\)$""")

/**
 * Samsung's collision-safe auto-numbering (parity research DI1): "IMG.jpg" becomes
 * "IMG(1).jpg", "IMG(1).jpg" becomes "IMG(2).jpg", until the name is free.
 * [taken] holds names already in the target folder, compared case-insensitively.
 */
fun freeDisplayName(name: String, taken: Set<String>): String =
    freeName(name, taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) })

/** [lowerTaken] holds lower-case names. */
private fun freeName(name: String, lowerTaken: Set<String>): String {
    if (name.lowercase(Locale.ROOT) !in lowerTaken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    val match = numberedName.matchEntire(stem)
    val base = match?.groupValues?.get(1) ?: stem
    var n = (match?.groupValues?.get(2)?.toLongOrNull() ?: 0L) + 1
    while (true) {
        val candidate = "$base($n)$extension"
        if (candidate.lowercase(Locale.ROOT) !in lowerTaken) return candidate
        n++
    }
}

/**
 * Indexed successor slots for collision suffixes. Each occupied suffix points
 * to the next number to try; path compression skips long consecutive runs in
 * near-constant amortized work instead of rescanning 1..N for each photo.
 *
 * Families are case-insensitive (stem + final extension). A preexisting name
 * is indexed only when it is a canonical generated suffix: "IMG(1).jpg"
 * occupies suffix 1, while "IMG(001).jpg" and "IMG (1).jpg" do not.
 * The latter can still be an exact source-name collision, handled by [taken].
 */
private class BatchDisplayNames(
    takenNames: Set<String>,
    private val onCandidateLookup: (() -> Unit)?
) {
    private data class Family(val base: String, val extension: String)

    private val taken = takenNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    private val occupied = HashMap<Family, MutableMap<Long, Long>>()

    init {
        for (name in taken) indexCanonicalSuffix(name)
    }

    private fun family(base: String, extension: String) =
        Family(base.lowercase(Locale.ROOT), extension.lowercase(Locale.ROOT))

    private fun indexCanonicalSuffix(lowerName: String) {
        val dot = lowerName.lastIndexOf('.').takeIf { it > 0 } ?: lowerName.length
        val stem = lowerName.substring(0, dot)
        val match = canonicalSuffix.matchEntire(stem) ?: return
        val digits = match.groupValues[2]
        val n = digits.toLongOrNull() ?: return
        // Leading zeros are part of the filename, not a generated collision index.
        if (digits != n.toString()) return
        val key = family(match.groupValues[1], lowerName.substring(dot))
        occupied.getOrPut(key) { HashMap() }[n] = n + 1
    }

    private fun nextUnused(slots: MutableMap<Long, Long>, start: Long): Long {
        var n = start
        while (true) {
            n = slots[n] ?: break
        }
        var position = start
        while (position != n) {
            val next = slots[position] ?: break
            slots[position] = n
            position = next
        }
        return n
    }

    fun reserve(name: String): String {
        onCandidateLookup?.invoke()
        if (taken.add(name.lowercase(Locale.ROOT))) {
            indexCanonicalSuffix(name.lowercase(Locale.ROOT))
            return name
        }

        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        val match = numberedName.matchEntire(stem)
        val base = match?.groupValues?.get(1) ?: stem
        var n = (match?.groupValues?.get(2)?.toLongOrNull() ?: 0L) + 1
        val slots = occupied.getOrPut(family(base, extension)) { HashMap() }

        while (true) {
            n = nextUnused(slots, n)
            val candidate = "$base($n)$extension"
            onCandidateLookup?.invoke()
            val free = taken.add(candidate.lowercase(Locale.ROOT))
            slots[n] = n + 1
            if (free) return candidate
            n++
        }
    }

    companion object {
        // Greedy base preserves whitespace before '(' as a distinct family.
        private val canonicalSuffix = Regex("""^(.*)\\((\\d+)\\)$""")
    }
}

/**
 * A test-only observation seam counts exact name-membership probes, including
 * the original filename. Production passes null and does not invoke callbacks.
 */
internal fun planAlbumTransferWithLookupCounter(
    mode: AlbumTransferMode,
    target: AlbumTransferTarget,
    records: List<MediaRecord>,
    takenNames: Set<String>,
    onCandidateLookup: () -> Unit
): AlbumTransferPlan =
    buildAlbumTransfer(mode, target, records, takenNames, onCandidateLookup)

/**
 * Plans a copy or move of [records] into [target]. [takenNames] are the
 * display names already in the target folder; each planned name is added so
 * two selected "IMG.jpg" from different albums never collide with each other.
 */
fun planAlbumTransfer(
    mode: AlbumTransferMode,
    target: AlbumTransferTarget,
    records: List<MediaRecord>,
    takenNames: Set<String>
): AlbumTransferPlan = buildAlbumTransfer(mode, target, records, takenNames, null)

private fun buildAlbumTransfer(
    mode: AlbumTransferMode,
    target: AlbumTransferTarget,
    records: List<MediaRecord>,
    takenNames: Set<String>,
    onCandidateLookup: (() -> Unit)?
): AlbumTransferPlan {
    val names = BatchDisplayNames(takenNames, onCandidateLookup)
    val steps = ArrayList<AlbumTransferStep>(records.size)
    val alreadyThere = ArrayList<MediaRecord>()
    val otherVolume = ArrayList<MediaRecord>()
    for (record in records) {
        val sameVolume = sameStorageVolume(record.volumeName, target.volumeName)
        if (mode == AlbumTransferMode.MOVE) {
            if (!sameVolume) { otherVolume += record; continue }
            if (record.relativePath == target.relativePath) { alreadyThere += record; continue }
        }
        val name = names.reserve(record.name)
        steps += AlbumTransferStep(record, name)
    }
    return AlbumTransferPlan(mode, target, steps, alreadyThere, otherVolume)
}

/** "external" is MediaStore's alias for the primary volume. */
fun sameStorageVolume(a: String, b: String): Boolean {
    fun canonical(v: String) = if (v == "external") "external_primary" else v
    return canonical(a) == canonical(b)
}

/** Outcome counts of a finished copy or move, for one short Samsung-style toast. */
data class AlbumTransferResult(val done: Int, val failed: Int, val skipped: Int)

fun albumTransferMessage(mode: AlbumTransferMode, target: String, result: AlbumTransferResult): String {
    val verb = if (mode == AlbumTransferMode.COPY) "Copied" else "Moved"
    val noun = if (result.done == 1) "item" else "items"
    val head = if (result.done > 0) "$verb ${result.done} $noun to $target" else
        if (mode == AlbumTransferMode.COPY) "Nothing was copied" else "Nothing was moved"
    val tail = buildList {
        if (result.failed > 0) add("${result.failed} couldn't be ${if (mode == AlbumTransferMode.COPY) "copied" else "moved"}")
        if (result.skipped > 0) add("${result.skipped} already in $target")
    }
    return if (tail.isEmpty()) head else head + " · " + tail.joinToString(" · ")
}
