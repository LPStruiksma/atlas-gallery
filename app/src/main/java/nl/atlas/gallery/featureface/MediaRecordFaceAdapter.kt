package nl.atlas.gallery.featureface

import nl.atlas.gallery.MediaRecord

/** Direct handoff from Lane M's authorized, version-aware media catalogue.
 *
 * I MUST call this only for rows from a completed, grant-valid MediaStore
 * snapshot, and provide its current editor revision instead of relying on
 * zero after an in-app edit. This does not check Android photo permission.
 *
 * FaceCatalogPublisher catalogues photos and (VS-V1b) videos, rejects
 * incomplete optional image provenance with explicit unknown revision markers,
 * leaves out a video with incomplete provenance, and invalidates its transaction
 * if the grant/provider drifts.
 */
fun MediaRecord.toFaceVisibleMedia(editorRevision: Long): FaceVisibleMedia =
    FaceVisibleMedia(
        uri = uri,
        volumeName = volumeName,
        isVideo = isVideo,
        id = id,
        providerVersion = providerVersion,
        generationModified = generationModified,
        sizeBytes = sizeBytes,
        modifiedAtSeconds = modifiedAtSeconds,
        editorRevision = editorRevision,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        orientationDegrees = orientationDegrees,
        durationMs = if (isVideo) durationMillis.coerceAtLeast(0L) else 0L,
        takenAtMillis = takenAtMillis
    )

/**
 * PERF-6b: immutable face view of the gallery's permission-scoped snapshot.
 * Never retain FaceVisibleMedia values (or reconstructed per-row URI Strings).
 * The only optional retained storage is an IntArray of sorted source offsets.
 *
 * The caller owns the immutable MediaRecord snapshot for the view's lifetime:
 * publishing a different gallery snapshot creates a new view and epoch.
 */
internal class MediaRecordFaceView(
    private val records: List<MediaRecord>,
    private val order: IntArray? = null
) : AbstractList<FaceVisibleMedia>(), java.util.RandomAccess {
    override val size: Int get() = records.size

    override fun get(index: Int): FaceVisibleMedia {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Face media index $index")
        return records[order?.get(index) ?: index].toFaceVisibleMedia(editorRevision = 0L)
    }

    /** One primitive index when unsorted, or no index for an already-newest gallery. */
    internal val retainedIndexBytes: Long get() = (order?.size ?: 0).toLong() * Int.SIZE_BYTES

    /**
     * Stable newest-first order: capture descending, id descending, then the
     * original list position. In-place heapsort uses no boxed Int list and no
     * second full-library reference array during each publication.
     */
    internal fun newestFirstView(): MediaRecordFaceView {
        if (order != null || size < 2) return this
        fun compare(a: Int, b: Int): Int {
            val left = records[a]
            val right = records[b]
            val byTime = right.takenAtMillis.compareTo(left.takenAtMillis)
            if (byTime != 0) return byTime
            val byId = right.id.compareTo(left.id)
            return if (byId != 0) byId else a.compareTo(b)
        }
        var sorted = true
        for (i in 1 until size) {
            if (compare(i - 1, i) > 0) { sorted = false; break }
        }
        if (sorted) return this

        val positions = IntArray(size) { it }
        fun swap(a: Int, b: Int) {
            val x = positions[a]
            positions[a] = positions[b]
            positions[b] = x
        }
        fun siftDown(start: Int, end: Int) {
            var root = start
            while (root * 2 + 1 <= end) {
                var child = root * 2 + 1
                if (child < end && compare(positions[child], positions[child + 1]) < 0)
                    child++
                if (compare(positions[root], positions[child]) >= 0) return
                swap(root, child)
                root = child
            }
        }
        for (start in (size / 2 - 1) downTo 0) siftDown(start, size - 1)
        for (end in (size - 1) downTo 1) {
            swap(0, end)
            siftDown(0, end - 1)
        }
        return MediaRecordFaceView(records, positions)
    }
}
