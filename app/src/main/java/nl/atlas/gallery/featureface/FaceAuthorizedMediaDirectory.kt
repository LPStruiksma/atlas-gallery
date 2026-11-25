package nl.atlas.gallery.featureface

import java.util.Arrays
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Memory-bounded read-only resolver from a hashed native face key to an
 * actually permission-visible MediaStore content URI.
 *
 * Holds a frozen pointer snapshot for ordinary mutable sources. For the
 * immutable MediaRecordFaceView it shares the gallery list with ONE LongArray
 * index (8 bytes/admitted item), plus an IntArray only if the source has more
 * than 20-bit addressable rows. It stores a 44-bit
 * SHA-256 *search prefix* plus 20-bit offset into the admitted rows. Collisions are NOT
 * authority: every candidate is compared against the full 256-bit canonical
 * key AND exact source revision before returning any URI.
 *
 * I supplies an immutable, complete, permission-authorized MediaRecord list,
 * a pure projection via MediaRecord.toFaceVisibleMedia(), fresh epoch/provider
 * checks, and a cancellation callback. No permission request, media read,
 * network access, biometric embedding or V4 People write happens here.
 *
 * VS-V1b (#468): videos are resolvable too, under their own "video" key. A
 * video whose provenance is incomplete is left out here exactly as
 * FaceCatalogPublisher leaves it out (same [durableFaceKeyOrNull] rule).
 * Photos with incomplete optional revision metadata remain admitted.
 *
 * VS-V1c (#468): both apply one [FaceCatalogAdmission] in inventory order, so
 * videos past [FaceCatalogLimits.MAX_VIDEOS] are left out of both, and a
 * video-heavy inventory never fails the build that photos depend on.
 */
class FaceAuthorizedMediaDirectory<T> private constructor(
    private val permissionEpoch: String,
    private val sources: List<T>,
    private val projection: (T) -> FaceVisibleMedia,
    private val packedIndex: LongArray,
    private val sourceOffsets: IntArray?,
    private val activeEpoch: () -> String?,
    private val providerSnapshotCurrent: () -> Boolean,
    /**
     * #538: admitted rows that repeated an earlier row's canonical key and
     * revision (one MediaStore item listed twice), resolved through the first.
     */
    val repeatedRows: Int = 0
) {
    companion object {
        /** 20 bits: room for every admitted photo and video (600k). */
        private const val OFFSET_BITS = 20
        private const val MAX_OFFSET = (1 shl OFFSET_BITS) - 1
        private const val OFFSET_MASK = (1L shl OFFSET_BITS) - 1L
        private const val PREFIX = "atlas-face-media-v1:"

        fun <T> build(
            permissionEpoch: String,
            sourceRows: List<T>,
            projection: (T) -> FaceVisibleMedia,
            activeEpoch: () -> String?,
            providerSnapshotCurrent: () -> Boolean,
            checkActive: () -> Unit = {},
            /** The publisher's photo cap (#508): the same rows on both sides. */
            maxPhotoRows: Int = FaceCatalogLimits.MAX_PHOTOS
        ): FaceAuthorizedMediaDirectory<T> {
            require(permissionEpoch.isNotBlank())
            fun validate() {
                checkActive()
                check(activeEpoch() == permissionEpoch && providerSnapshotCurrent()) {
                    "Expired or changed media permission inventory"
                }
            }
            validate()
            // PERF-6b: a MediaRecordFaceView is an immutable snapshot. Index
            // directly into it instead of freezing all the temporary projected
            // FaceVisibleMedia objects (and their freshly rebuilt URI Strings).
            // All other callers keep the original copied-pointer semantics,
            // including protection against a later mutation of their input.
            val sharedGallery = sourceRows is MediaRecordFaceView
            val frozen = if (sharedGallery) null else ArrayList<T>()
            val capacity = minOf(sourceRows.size,
                maxPhotoRows + FaceCatalogLimits.MAX_VIDEOS)
            val keys = LongArray(capacity)
            // Direct 20-bit offsets suffice for <= 1,048,576 source rows.
            // An exceptionally large gallery needs a compact admitted->source
            // offset table, not another full-library object list.
            val sourceOffsets = if (sharedGallery && sourceRows.size > MAX_OFFSET + 1)
                IntArray(capacity) else null
            val admission = FaceCatalogAdmission(maxPhotoRows = maxPhotoRows)
            var admittedCount = 0
            for ((i, item) in sourceRows.withIndex()) {
                if ((i and 127) == 0) validate()
                val media = admission.admit(projection(item)) ?: continue
                val offset = if (sharedGallery) {
                    if (sourceOffsets == null) i
                    else {
                        sourceOffsets[admittedCount] = i
                        admittedCount
                    }
                } else frozen!!.size
                if (offset > MAX_OFFSET) {
                    throw IllegalArgumentException("Media resolver exceeds native index cap")
                }
                keys[admittedCount++] =
                    (fingerprint(media.canonicalMediaKey) shl OFFSET_BITS) or offset.toLong()
                frozen?.add(item)
            }
            validate()
            val packed = if (admittedCount == keys.size) keys else keys.copyOf(admittedCount)
            Arrays.sort(packed)
            // #538: duplicate canonical keys resolve through the first row.
            // A conflicting source revision refuses the index. Full key
            // comparisons also distinguish unrelated 44-bit prefix collisions.
            // Compact in place; do not allocate a second full-sized LongArray.
            var keptCount = 0
            var repeated = 0
            var runStart = 0
            while (runStart < packed.size) {
                val prefix = packed[runStart] ushr OFFSET_BITS
                var runEnd = runStart + 1
                while (runEnd < packed.size && packed[runEnd] ushr OFFSET_BITS == prefix) runEnd++
                if (runEnd - runStart == 1) {
                    packed[keptCount++] = packed[runStart]
                } else {
                    val seen = ArrayList<FaceMediaKey>(runEnd - runStart)
                    for (i in runStart until runEnd) {
                        val offset = (packed[i] and OFFSET_MASK).toInt()
                        val sourceIndex = sourceOffsets?.get(offset) ?: offset
                        val row = (frozen ?: sourceRows)[sourceIndex]
                        val key = projection(row).toDurableFaceKey()
                        val same = seen.firstOrNull { it.canonicalMediaKey == key.canonicalMediaKey }
                        when {
                            same == null -> { seen.add(key); packed[keptCount++] = packed[i] }
                            same.sourceRevision == key.sourceRevision -> repeated++
                            else -> throw IllegalArgumentException(
                                "Contradictory duplicate canonical media identity")
                        }
                    }
                }
                runStart = runEnd
            }
            val sorted = if (keptCount == packed.size) packed else packed.copyOf(keptCount)
            validate()
            return FaceAuthorizedMediaDirectory(
                permissionEpoch, frozen ?: sourceRows, projection, sorted,
                sourceOffsets?.copyOf(admittedCount), activeEpoch,
                providerSnapshotCurrent, repeated
            )
        }

        private fun fingerprint(canonicalKey: String): Long {
            require(canonicalKey.startsWith(PREFIX) && canonicalKey.length == PREFIX.length + 64)
            // Twelve exact hex chars = 48 bits; drop the low four => 44 bits,
            // leaving 20 bits for up to 1,048,576 admitted-row offsets.
            val prefix = canonicalKey.substring(PREFIX.length, PREFIX.length + 12)
            require(prefix.all { it in '0'..'9' || it in 'a'..'f' })
            return prefix.toLong(16) ushr 4
        }
    }

    /** Resolvable photos and videos (admitted rows only, each source once). */
    val mediaCount: Int get() = packedIndex.size

    private fun validScope() {
        val active = try {
            activeEpoch() == permissionEpoch && providerSnapshotCurrent()
        } catch (stopped: CancellationException) {
            // Worker stop is NOT a permission-denial retry; preserve cancellation.
            throw stopped
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            // Provider generation and version IPC may fail while media unmounts.
            // Such a source is stale, not a failed on-device face inference.
            false
        }
        if (!active) throw SecurityException("Face media source is outside the active grant")
    }

    /**
     * O(log N + tiny collision group), source-grant gated URI lookup.
     * Never return an old URI on epoch, model-source revision or grant change.
     */
    fun authorizedUri(media: FaceMediaKey): String = authorizedSource(media).uri

    /**
     * The same gated lookup, returning the whole visible row: a video scan
     * needs its duration and provider revision, never more than the grant.
     */
    fun authorizedSource(media: FaceMediaKey): FaceVisibleMedia {
        validScope()
        val prefix = try { fingerprint(media.canonicalMediaKey) }
        catch (_: IllegalArgumentException) {
            throw SecurityException("Malformed face media key")
        }
        val target = prefix shl OFFSET_BITS
        var pos = Arrays.binarySearch(packedIndex, target)
        if (pos < 0) pos = -pos - 1
        else {
            while (pos > 0 && packedIndex[pos - 1] ushr OFFSET_BITS == prefix) pos--
        }
        while (pos < packedIndex.size && packedIndex[pos] ushr OFFSET_BITS == prefix) {
            val offset = (packedIndex[pos] and OFFSET_MASK).toInt()
            val source = projection(sources[sourceOffsets?.get(offset) ?: offset])
            val actual = try { source.toDurableFaceKey() }
            catch (_: IllegalArgumentException) {
                throw SecurityException("Media source revision unavailable")
            }
            if (actual.canonicalMediaKey == media.canonicalMediaKey &&
                actual.sourceRevision == media.sourceRevision) {
                validScope()
                return source
            }
            pos++
        }
        throw SecurityException("Media source is absent, stale or not permitted")
    }

    /** P's FaceScanEngine sourceRevisionCurrent callback. */
    fun sourceRevisionCurrent(media: FaceMediaKey): Boolean =
        try { authorizedUri(media); true }
        catch (_: SecurityException) { false }
}

fun FaceVisibleMedia.toDurableFaceKey(): FaceMediaKey =
    if (isVideo) FaceMediaSourceKey.fromVisibleVideo(
        uri, volumeName, id, providerVersion, generationModified,
        sizeBytes, modifiedAtSeconds, editorRevision,
        widthPixels, heightPixels, orientationDegrees, durationMs
    ) else FaceMediaSourceKey.fromVisiblePhoto(
        uri, volumeName, isVideo, id, providerVersion, generationModified,
        sizeBytes, modifiedAtSeconds, editorRevision,
        widthPixels, heightPixels, orientationDegrees
    )

/**
 * The catalogue rule shared by FaceCatalogPublisher and the directory: a
 * photo with incomplete optional provenance still has a durable weak revision.
 * A photo without even a canonical volume and ID is omitted, while a video
 * with incomplete provenance is omitted too. Both sides use this rule.
 */
fun FaceVisibleMedia.durableFaceKeyOrNull(): FaceMediaKey? =
    try { toDurableFaceKey() } catch (_: IllegalArgumentException) { null }

/**
 * VS-V1c (#468): the face catalogue's separate, named budgets. Photos have
 * their own cap; videos have theirs. Videos past [MAX_VIDEOS] are deferred
 * (left out of this publication), never a reason to abort the photo scan.
 * Together they fit the directory's 20-bit offset (600k <= 1,048,576).
 */
object FaceCatalogLimits {
    const val MAX_PHOTOS = 500_000
    const val MAX_VIDEOS = 100_000
}

/**
 * One single-pass admission over an inventory, shared by FaceCatalogPublisher
 * and FaceAuthorizedMediaDirectory so both keep exactly the same rows when fed
 * the same inventory in the same order (newest first, #508: the first
 * [maxPhotoRows] photos and [maxVideoRows] complete videos win; a later
 * publication re-evaluates).
 *
 * [admit] returns the durable key to catalogue, or null for a row left out: a
 * video with incomplete provenance ([ignoredVideoRows]), a video past its
 * budget ([deferredVideoRows]) or, since #508, a photo past [maxPhotoRows]
 * ([deferredPhotoRows]): a library above the cap scans its newest photos
 * instead of refusing. Faces of a photo that leaves the window (the library
 * grew past the cap) are pruned like those of a deleted photo. An admitted
 * photo with missing canonical identity is omitted, while incomplete optional
 * fields use explicit unknown revision markers. Deferred rows are not inspected.
 */
class FaceCatalogAdmission(
    private val maxPhotoRows: Int = FaceCatalogLimits.MAX_PHOTOS,
    private val maxVideoRows: Int = FaceCatalogLimits.MAX_VIDEOS
) {
    init {
        require(maxPhotoRows in 1..FaceCatalogLimits.MAX_PHOTOS)
        require(maxVideoRows in 0..FaceCatalogLimits.MAX_VIDEOS)
    }

    var photoRows = 0; private set
    var ignoredPhotoRows = 0; private set
    var weakPhotoRows = 0; private set
    var observedPhotoRows = 0; private set
    val missingPhotoFields: Map<String, Int> get() = photoFieldCounts.toMap()
    private val photoFieldCounts = LinkedHashMap<String, Int>()
    var videoRows = 0; private set
    var ignoredVideoRows = 0; private set
    var deferredVideoRows = 0; private set
    var deferredPhotoRows = 0; private set
    val leftOutVideoRows: Int get() = ignoredVideoRows + deferredVideoRows
    val leftOutRows: Int get() = leftOutVideoRows + ignoredPhotoRows + deferredPhotoRows

    fun admit(row: FaceVisibleMedia): FaceMediaKey? {
        if (!row.isVideo) {
            // A deferred photo is never catalogued, so its provenance is not
            // checked: an odd old photo must not refuse the newest window.
            if (photoRows >= maxPhotoRows) {
                deferredPhotoRows++
                return null
            }
            observedPhotoRows++
            val key = row.durableFaceKeyOrNull()
            if (key == null) {
                ignoredPhotoRows++
                return null
            }
            val missing = FaceMediaSourceKey.missingPhotoFields(
                row.providerVersion, row.generationModified, row.sizeBytes,
                row.modifiedAtSeconds, row.editorRevision, row.widthPixels,
                row.heightPixels, row.orientationDegrees)
            if (missing.isNotEmpty()) weakPhotoRows++
            for (field in missing) photoFieldCounts[field] = (photoFieldCounts[field] ?: 0) + 1
            photoRows++
            return key
        }
        val key = row.durableFaceKeyOrNull()
        if (key == null) {
            ignoredVideoRows++
            return null
        }
        if (videoRows >= maxVideoRows) {
            deferredVideoRows++
            return null
        }
        videoRows++
        return key
    }
}
