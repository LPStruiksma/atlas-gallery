package nl.atlas.gallery.featureinsights

import nl.atlas.gallery.MediaRecord
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * INSIGHT-1 (#587): pure, Android-free aggregation of the catalogue already in
 * memory into size/count rows by folder, type and year. It reads each record
 * once, keeps one small counter per bucket (no per-bucket item lists), and makes
 * no MediaStore query, permission request or network call.
 *
 * Every row carries an [InsightsScope] and [matches] is the single definition of
 * that scope, so the grid a row opens holds exactly the records the row counted.
 */
enum class InsightsType(val key: String, val label: String) {
    PHOTOS("photos", "Photos"),
    VIDEOS("videos", "Videos"),

    /** A subgroup of [PHOTOS]: it overlaps Photos and is never added to a total. */
    SCREENSHOTS("screenshots", "Screenshots"),

    /** A subgroup of [PHOTOS]: it overlaps Photos and is never added to a total. */
    RAW("raw", "RAW");

    companion object {
        fun fromKey(key: String): InsightsType? = entries.firstOrNull { it.key == key }
    }
}

sealed interface InsightsScope {
    /** The exact MediaStore folder; a same-named folder on another volume is a different folder. */
    data class Folder(val volumeName: String, val relativePath: String) : InsightsScope

    data class Type(val type: InsightsType) : InsightsScope

    /** [year] null is the "Unknown date" bucket: never an invented year. */
    data class Year(val year: Int?) : InsightsScope

    /** Stable text for saved instance state; [decode] is its inverse. */
    fun encode(): String = when (this) {
        is Folder -> "f:$volumeName$SEP$relativePath"
        is Type -> "t:${type.key}"
        is Year -> "y:${year ?: "unknown"}"
    }

    companion object {
        private const val SEP = '\u001F'

        fun decode(text: String): InsightsScope? {
            val body = text.drop(2)
            return when {
                text.startsWith("f:") -> body.indexOf(SEP).takeIf { it >= 0 }?.let {
                    Folder(body.substring(0, it), body.substring(it + 1))
                }
                text.startsWith("t:") -> InsightsType.fromKey(body)?.let(::Type)
                text.startsWith("y:") ->
                    if (body == "unknown") Year(null) else body.toIntOrNull()?.let(::Year)
                else -> null
            }
        }
    }
}

/** One tappable row: what it counted, and the scope the grid it opens must show. */
data class InsightsRow(
    val scope: InsightsScope,
    val label: String,
    /** Full folder path for TalkBack; null for type and year rows. */
    val fullPath: String?,
    val bytes: Long,
    val count: Int
)

class LibraryInsights(
    val totalBytes: Long,
    val totalCount: Int,
    val videoDurationMillis: Long,
    /** False when some videos have no valid duration: the sum is then "at least". */
    val videoDurationExact: Boolean,
    /** Every folder, ranked bytes desc, count desc, normalised path asc. */
    val folders: List<InsightsRow>,
    /** Photos, Videos, then the overlapping Screenshots and RAW subgroups; empty ones left out. */
    val types: List<InsightsRow>,
    /** Newest year first, Unknown date last. */
    val years: List<InsightsRow>
) {
    val isEmpty: Boolean get() = totalCount == 0
}

private val RAW_EXTENSIONS = hashSetOf(
    "dng", "arw", "cr2", "cr3", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "raw"
)

fun isRawFile(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return false
    return name.substring(dot + 1).lowercase(Locale.ROOT) in RAW_EXTENSIONS
}

/** MediaStore puts screenshots in a folder called Screenshots; a video there is a recording. */
fun isScreenshot(record: MediaRecord): Boolean {
    if (record.isVideo) return false
    val path = record.relativePath
    if (path.isEmpty()) return false
    val trimmed = path.trimEnd('/')
    val last = trimmed.substringAfterLast('/')
    return last.equals("Screenshots", ignoreCase = true)
}

/** Same rule as the Pictures timeline: 0 or an unrepresentable instant is "unknown". */
fun insightsYearOrNull(takenAtMillis: Long, zone: ZoneId): Int? {
    if (takenAtMillis == 0L) return null
    return try {
        Instant.ofEpochMilli(takenAtMillis).atZone(zone).year
    } catch (_: DateTimeException) {
        null
    }
}

fun InsightsScope.matches(record: MediaRecord, zone: ZoneId): Boolean = when (this) {
    is InsightsScope.Folder ->
        record.volumeName == volumeName && record.relativePath == relativePath
    is InsightsScope.Type -> when (type) {
        InsightsType.PHOTOS -> !record.isVideo
        InsightsType.VIDEOS -> record.isVideo
        InsightsType.SCREENSHOTS -> isScreenshot(record)
        InsightsType.RAW -> !record.isVideo && isRawFile(record.name)
    }
    is InsightsScope.Year -> insightsYearOrNull(record.takenAtMillis, zone) == year
}

private class Counter {
    var bytes = 0L
    var count = 0
    fun add(size: Long) {
        // 64-bit sums; a negative size from a broken provider row counts as 0 bytes.
        if (size > 0L) bytes += size
        count++
    }
}

fun folderLabel(relativePath: String): String =
    relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Unknown folder" }

/**
 * One deterministic pass over [records]. [checkpoint] is called every 1024
 * records so a caller can cancel; it must throw to abort.
 */
fun computeLibraryInsights(
    records: List<MediaRecord>,
    zone: ZoneId = ZoneId.systemDefault(),
    checkpoint: () -> Unit = {}
): LibraryInsights {
    var totalBytes = 0L
    var durationMillis = 0L
    var durationExact = true
    // volume -> path -> counter: no per-record key object is allocated.
    val folders = HashMap<String, HashMap<String, Counter>>()
    val typeCounters = Array(InsightsType.entries.size) { Counter() }
    val years = HashMap<Int, Counter>()
    val unknownYear = Counter()

    for (index in records.indices) {
        if ((index and 1023) == 0) checkpoint()
        val record = records[index]
        val size = record.sizeBytes
        if (size > 0L) totalBytes += size
        folders.getOrPut(record.volumeName) { HashMap() }
            .getOrPut(record.relativePath) { Counter() }.add(size)
        if (record.isVideo) {
            typeCounters[InsightsType.VIDEOS.ordinal].add(size)
            if (record.durationMillis > 0L) durationMillis += record.durationMillis
            else durationExact = false
        } else {
            typeCounters[InsightsType.PHOTOS.ordinal].add(size)
            if (isScreenshot(record)) typeCounters[InsightsType.SCREENSHOTS.ordinal].add(size)
            if (isRawFile(record.name)) typeCounters[InsightsType.RAW.ordinal].add(size)
        }
        val year = insightsYearOrNull(record.takenAtMillis, zone)
        if (year == null) unknownYear.add(size) else years.getOrPut(year) { Counter() }.add(size)
    }
    checkpoint()

    val folderRows = ArrayList<InsightsRow>()
    for ((volume, paths) in folders) {
        for ((path, counter) in paths) {
            folderRows += InsightsRow(InsightsScope.Folder(volume, path), folderLabel(path),
                path.ifBlank { null }, counter.bytes, counter.count)
        }
    }
    folderRows.sortWith(
        compareByDescending<InsightsRow> { it.bytes }
            .thenByDescending { it.count }
            .thenBy { normalisedPath((it.scope as InsightsScope.Folder).relativePath) }
            .thenBy { (it.scope as InsightsScope.Folder).volumeName }
            .thenBy { (it.scope as InsightsScope.Folder).relativePath }
    )

    val typeRows = InsightsType.entries.mapNotNull { type ->
        val counter = typeCounters[type.ordinal]
        if (counter.count == 0) null
        else InsightsRow(InsightsScope.Type(type), type.label, null, counter.bytes, counter.count)
    }

    val yearRows = ArrayList<InsightsRow>()
    years.entries.sortedByDescending { it.key }.forEach { (year, counter) ->
        yearRows += InsightsRow(InsightsScope.Year(year), year.toString(), null,
            counter.bytes, counter.count)
    }
    if (unknownYear.count > 0) {
        yearRows += InsightsRow(InsightsScope.Year(null), "Unknown date", null,
            unknownYear.bytes, unknownYear.count)
    }

    return LibraryInsights(
        totalBytes = totalBytes,
        totalCount = records.size,
        videoDurationMillis = durationMillis,
        videoDurationExact = durationExact,
        folders = folderRows,
        types = typeRows,
        years = yearRows
    )
}

private fun normalisedPath(path: String): String =
    java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** Row bar width as a share of the largest row in its own section; a positive row is never empty. */
fun insightsBarFraction(bytes: Long, largest: Long): Float {
    if (bytes <= 0L || largest <= 0L) return 0f
    return (bytes.toDouble() / largest.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** "19 h 24 min" for a whole-minute duration; null when there is none. */
fun insightsDuration(millis: Long): String? {
    if (millis <= 0L) return null
    val minutes = (millis / 60_000L).coerceAtLeast(1L)
    val hours = minutes / 60L
    val rest = minutes % 60L
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** Grid header for a Library insights type or year row; [encoded] is [InsightsScope.encode]. */
fun insightsScopeTitle(encoded: String): String = when (val scope = InsightsScope.decode(encoded)) {
    is InsightsScope.Type -> scope.type.label
    is InsightsScope.Year -> scope.year?.toString() ?: "Unknown date"
    is InsightsScope.Folder -> folderLabel(scope.relativePath)
    null -> "Library insights"
}
