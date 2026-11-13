package nl.atlas.gallery

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Timeline placement provenance, not an EXIF write or certainty claim. */
enum class MediaDateSource { TAKEN, FILENAME_TIMESTAMP, FILENAME_DATE, MODIFIED, ADDED, UNKNOWN }

data class ResolvedMediaDate(val millis: Long, val source: MediaDateSource)

// Compile once instead of allocating regex objects for 150k records.
private val TIMESTAMP_HINTS = listOf(
    Regex("^(?:IMG|VID|PXL)_([0-9]{8})_([0-9]{6})(?:[0-9]{1,3})?(?:[^0-9].*|$)", RegexOption.IGNORE_CASE),
    Regex("^Screenshot[_-]([0-9]{8})[-_]([0-9]{6})(?:[^0-9].*|$)", RegexOption.IGNORE_CASE),
    // Samsung screen recordings embed the time before the recording app name.
    Regex("^Screen_Recording_([0-9]{8})_([0-9]{6})(?:[^0-9].*|$)", RegexOption.IGNORE_CASE),
    // Stock Galaxy Camera filenames often omit IMG_/VID_: yyyyMMdd_HHmmss.jpg/mp4.
    // Anchoring avoids interpreting arbitrary embedded dates as capture metadata.
    Regex("^([0-9]{8})_([0-9]{6})(?:[0-9]{1,3})?(?:[^0-9].*|$)")
)
private val DATE_ONLY_HINT =
    Regex("^(?:IMG|VID)-([0-9]{8})-WA[0-9]+(?:[^0-9].*|$)", RegexOption.IGNORE_CASE)

/** Filename dates are strictly validated hints, not identities. */
fun resolveMediaDate(
    takenMillis: Long,
    filename: String,
    modifiedSeconds: Long,
    addedSeconds: Long,
    zone: ZoneId = ZoneId.systemDefault()
): ResolvedMediaDate {
    if (isPlausibleMediaTakenMillis(takenMillis))
        return ResolvedMediaDate(takenMillis, MediaDateSource.TAKEN)
    val timestamp = TIMESTAMP_HINTS.firstNotNullOfOrNull { it.find(filename) }
    if (timestamp != null) {
        parseDateHint(timestamp.groupValues[1], timestamp.groupValues[2], zone)?.let {
            return ResolvedMediaDate(it, MediaDateSource.FILENAME_TIMESTAMP)
        }
    }
    val dateOnly = DATE_ONLY_HINT.find(filename)
    if (dateOnly != null) {
        // Local noon is a stable representative of a date-only filename.
        parseDateHint(dateOnly.groupValues[1], "120000", zone)?.let {
            return ResolvedMediaDate(it, MediaDateSource.FILENAME_DATE)
        }
    }
    if (isPlausibleMediaEpochSeconds(modifiedSeconds))
        return ResolvedMediaDate(modifiedSeconds * 1000L, MediaDateSource.MODIFIED)
    if (isPlausibleMediaEpochSeconds(addedSeconds))
        return ResolvedMediaDate(addedSeconds * 1000L, MediaDateSource.ADDED)
    return ResolvedMediaDate(0L, MediaDateSource.UNKNOWN)
}

private fun parseDateHint(day: String, clock: String, zone: ZoneId): Long? = try {
    val year = day.substring(0, 4).toInt()
    if (year !in 1970..2100) null else {
        val date = LocalDate.of(year, day.substring(4, 6).toInt(), day.substring(6, 8).toInt())
        val local = LocalDateTime.of(date.year, date.month, date.dayOfMonth,
            clock.substring(0, 2).toInt(), clock.substring(2, 4).toInt(), clock.substring(4, 6).toInt())
        // Don't silently shift a nonexistent local time across a DST gap.
        zone.rules.getValidOffsets(local).firstOrNull()?.let { local.toInstant(it).toEpochMilli() }
    }
} catch (_: DateTimeException) { null } catch (_: NumberFormatException) { null }

/**
 * MediaStore's aggregate "external" cursor reports specific per-row volumes.
 * An absent, blank or newly unmounted volume is a failed catalogue snapshot,
 * not permission to substitute an ambiguous aggregate item URI or silently
 * drop already-reviewed photos. Keep the exception non-identifying.
 *
 * Mounted volumes are captured before the scan; the caller rechecks them
 * after the scan and preserves its last successfully published inventory on
 * failure. O(1) hash-set lookup, no per-item collection map allocations.
 */
/**
 * ContentResolver.query() returning null means no cursor was supplied; it
 * does NOT prove there are zero images. Fail the scan instead of committing
 * an incomplete inventory and hiding confirmed People photos.
 * SecurityException is handled separately by the established per-kind
 * access-denial rollback.
 */
internal fun <T : Any> requireMediaCatalogueCursor(cursor: T?): T =
    cursor ?: throw IllegalStateException("Media provider returned no catalogue cursor")

internal fun requireMountedMediaVolume(
    reportedVolume: String?, mountedVolumes: Set<String>
): String {
    if (reportedVolume.isNullOrBlank() || reportedVolume !in mountedVolumes) {
        throw IllegalStateException("Media volume unavailable during catalogue scan")
    }
    return reportedVolume
}

/** Photo and video numeric IDs overlap; volume and URI are essential. */
data class MediaIdentity(val uri: String, val volumeName: String, val isVideo: Boolean, val id: Long)

fun MediaRecord.mediaIdentity(): MediaIdentity = MediaIdentity(uri, volumeName, isVideo, id)

/**
 * Cursor numeric conversion must not wrap an out-of-range 64-bit rotation into
 * an apparently valid zero/90/180/270. MediaStore describes orientation as
 * integer degrees; invalid Long input gets a non-quarter-turn sentinel.
 *
 * Missing orientation metadata is handled separately at the cursor boundary;
 * this helper only processes values that the provider actually returned.
 */
internal fun mediaOrientationDegreesOrInvalid(raw: Long): Int =
    if (raw < Int.MIN_VALUE.toLong() || raw > Int.MAX_VALUE.toLong())
        Int.MIN_VALUE
    else raw.toInt()

/**
 * Unknown, negative, zero or out-of-range provider dimensions are NOT a
 * synthetic 2.1-billion-pixel image. Return zero (unknown), so G's justified
 * grid uses its bounded unknown-ratio fallback instead of fabricating a
 * panorama from corrupted MediaStore metadata.
 */
/** Missing/null orientation is not the same as a measured zero-degree rotation. */
internal fun mediaOrientationDegreesOrUnknown(raw: Long, present: Boolean): Int =
    if (present) mediaOrientationDegreesOrInvalid(raw) else Int.MIN_VALUE

internal fun mediaPixelDimensionOrUnknown(raw: Long): Int =
    if (raw in 1L..Int.MAX_VALUE.toLong()) raw.toInt() else 0

/**
 * For justified rows: MediaStore dimensions with a quarter-turn applied.
 *
 * #504: a missing/NULL ORIENTATION (the [Int.MIN_VALUE] sentinel) means
 * "no rotation recorded", which is how MediaStore reports every PNG,
 * screenshot, download and EXIF-less JPEG. Treating it as unknown turned most
 * of a real library into neutral squares with letterbox bars. The ratio grid
 * now crops inside each cell, so a rare wrong guess costs a slight crop, never
 * a bar. Malformed non-quarter turns (45, 135, ...) still abstain.
 */
fun MediaRecord.displayAspectRatioOrNull(): Float? {
    if (widthPixels <= 0 || heightPixels <= 0) return null
    val degrees = if (orientationDegrees == Int.MIN_VALUE) 0 else orientationDegrees
    val normalized = ((degrees % 360) + 360) % 360
    val swapAxes = when (normalized) {
        0, 180 -> false
        90, 270 -> true
        else -> return null
    }
    val w = if (swapAxes) heightPixels else widthPixels
    val h = if (swapAxes) widthPixels else heightPixels
    return w.toFloat() / h.toFloat()
}

/**
 * Version-aware image key. Compute only for visible items. The catalogue
 * epoch changes whenever media permissions/scope or inventory is re-read.
 * A key is not permission: callers must still check the live grant.
 * Unknown provider version/generation => no durable cache reuse.
 */
fun MediaRecord.presentationCacheKeyOrNull(
    requestedPixels: Int, catalogueEpoch: Long, editorRevision: Long = 0L
): String? {
    if (requestedPixels !in 1..8192 || catalogueEpoch < 0L || editorRevision < 0L) return null
    val version = providerVersion?.takeIf { it.isNotBlank() } ?: return null
    if (generationModified <= 0L) return null
    return buildString(160) {
        append("atlas-media-v2|")
        for (part in listOf(uri, volumeName, isVideo.toString(), id.toString(), version)) {
            append(part.length).append(':').append(part).append('|')
        }
        append(generationModified).append('|').append(sizeBytes).append('|')
        // MediaStore geometry can change independently of item generation
        // on some providers. Never reuse a decoded/cropped image with an old
        // width, height or EXIF-equivalent orientation interpretation.
        append(widthPixels).append('|').append(heightPixels).append('|')
        append(orientationDegrees).append('|')
        append(modifiedAtSeconds).append('|').append(catalogueEpoch).append('|')
        append(editorRevision).append('|').append(requestedPixels)
    }
}

/**
 * Keep the established primary-storage RANDOM permutation unchanged while
 * differentiating secondary volumes that may reuse the same numeric ID.
 * A volume salt is computed once per item, never inside the comparator.
 * A rare 64-bit hash collision still has an exact URI tie-break in sortMedia.
 */
internal fun MediaRecord.randomShuffleDiscriminator(): Long {
    val legacy = (id shl 1) or (if (isVideo) 1L else 0L)
    if (volumeName == "external" || volumeName == "external_primary") return legacy
    var salt = -3750763034362895579L // FNV-1a 64-bit offset, signed
    for (character in volumeName) {
        salt = (salt xor character.code.toLong()) * 1099511628211L
    }
    return legacy xor (if (salt == 0L) Long.MIN_VALUE else salt)
}

/**
 * Verify that a MediaStore inventory was assembled under one coherent
 * provider version AND generation snapshot for every mounted volume.
 *
 * getVersion alone is insufficient: routine additions/edits increment the
 * generation without resetting the version. A null/blank version or
 * null/negative generation can be equal across reads but never proves a
 * usable mounted-volume snapshot; reject them before publication.
 *
 * API29 has no getGeneration and therefore validates version+mount only.
 * Even with generation values this is not an atomic database transaction.
 * Recheck mounted volumes separately via getExternalVolumeNames: version
 * maps must include exactly the same device set on both sides.
 */
internal fun sameMediaProviderScanSnapshot(
    versionsAtStart: Map<String, String?>,
    versionsAtEnd: Map<String, String?>,
    generationsAtStart: Map<String, Long?>,
    generationsAtEnd: Map<String, Long?>
): Boolean =
    versionsAtStart.isNotEmpty() &&
        // getVersion may return null when a volume is unmounted. Repeating
        // the same unavailable value cannot establish a valid snapshot.
        versionsAtStart.values.all { !it.isNullOrBlank() } &&
        versionsAtStart == versionsAtEnd &&
        generationsAtStart == generationsAtEnd &&
        // API 29 legitimately lacks generation observations. On API 30+,
        // reject an unavailable/invalid value even when both scans agree.
        (generationsAtStart.isEmpty() ||
            (generationsAtStart.keys == versionsAtStart.keys &&
                generationsAtStart.values.all { it != null && it >= 0L }))

/**
 * Old decoded data may be reused only when the exact per-item generation
 * is unchanged, the provider version is unchanged, and the permission
 * scope is known identical. Generation *advance* supports incremental
 * synchronization, not reuse of stale decoded pixels or identity evidence.
 * This helper is a necessary check, never sufficient authorization: callers
 * must also revalidate URI, access grant, size/editor revision and cache epoch.
 */
fun canReuseMediaGeneration(
    oldProviderVersion: String?, newProviderVersion: String?,
    oldGeneration: Long, newGeneration: Long, sameAccessScope: Boolean
): Boolean = sameAccessScope && !oldProviderVersion.isNullOrBlank() &&
    oldProviderVersion == newProviderVersion && oldGeneration > 0L &&
    newGeneration == oldGeneration
