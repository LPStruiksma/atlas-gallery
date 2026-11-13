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
 * Plans a copy or move of [records] into [target]. [takenNames] are the
 * display names already in the target folder; each planned name is added so
 * two selected "IMG.jpg" from different albums never collide with each other.
 */
fun planAlbumTransfer(
    mode: AlbumTransferMode,
    target: AlbumTransferTarget,
    records: List<MediaRecord>,
    takenNames: Set<String>
): AlbumTransferPlan {
    val taken = takenNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val steps = ArrayList<AlbumTransferStep>(records.size)
    val alreadyThere = ArrayList<MediaRecord>()
    val otherVolume = ArrayList<MediaRecord>()
    for (record in records) {
        val sameVolume = sameStorageVolume(record.volumeName, target.volumeName)
        if (mode == AlbumTransferMode.MOVE) {
            if (!sameVolume) { otherVolume += record; continue }
            if (record.relativePath == target.relativePath) { alreadyThere += record; continue }
        }
        val name = freeName(record.name, taken)
        taken += name.lowercase(Locale.ROOT)
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
