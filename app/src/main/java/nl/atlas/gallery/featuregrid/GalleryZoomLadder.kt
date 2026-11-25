package nl.atlas.gallery.featuregrid

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/**
 * Six phone zoom stops, from original-ratio rows through 3/4/7-column
 * thumbnails to 12-column month and 28-column year overview layouts.
 * The overview group headers are planned by TimelineIndex, not the gesture.
 */
enum class GalleryZoomStop {
    ORIGINAL, LARGE, MEDIUM, SMALL, MONTH, YEAR;

    fun squareDensityOrNull(): GridDensity? = when (this) {
        ORIGINAL -> null
        LARGE -> GridDensity.LARGE
        MEDIUM -> GridDensity.MEDIUM
        SMALL -> GridDensity.SMALL
        MONTH -> GridDensity.MONTH
        YEAR -> GridDensity.YEAR
    }

    companion object {
        fun forDensity(density: GridDensity): GalleryZoomStop = when (density) {
            GridDensity.LARGE -> LARGE
            GridDensity.MEDIUM -> MEDIUM
            GridDensity.SMALL -> SMALL
            GridDensity.MONTH -> MONTH
            GridDensity.YEAR -> YEAR
        }
    }
}

/**
 * UI-owned handoff: the host must validate its current authorized catalogue,
 * epoch and index/URI before using the anchor in another grid. Null focus
 * means a layout is still changing; switching may proceed without guessing.
 */
data class GalleryZoomRequest(
    val focal: GridFocalAnchor?,
    val focusYPx: Float,
    val catalogueEpoch: Long,
    /** Which discrete stop the gesture actually selected. */
    val destination: GalleryZoomStop? = null
)

/**
 * One continuous pinch can traverse multiple neighbouring stops. It consumes
 * only scale, never triggers image decoding or MediaStore work during gesture.
 * The source screen decides whether an original-proportion destination exists.
 */
class GalleryZoomGesture(
    private val initial: GalleryZoomStop,
    private val allowOriginal: Boolean = true,
    /** Plain Search/People grids keep their historical three square sizes. */
    private val allowOverview: Boolean = true
) {
    var progress: Float = 0f
        private set

    /** Sign of the last non-zero step: +1 toward smaller tiles, −1 toward larger. */
    private var lastDirection: Int = 0

    fun addZoomFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val maxStep = if (factor < 0.5f || factor > 2f) 0.2f else 0.5f
        val step = (-ln(factor.toDouble()) / ln(1.5)).toFloat().coerceIn(-maxStep, maxStep)
        val minimum = if (allowOriginal) 0 else GalleryZoomStop.LARGE.ordinal
        val maximum = if (allowOverview) GalleryZoomStop.entries.lastIndex else GalleryZoomStop.SMALL.ordinal
        val before = progress
        progress = (progress + step).coerceIn(
            (minimum - initial.ordinal).toFloat(),
            (maximum - initial.ordinal).toFloat()
        )
        if (progress != before) lastDirection = (progress - before).sign.toInt()
    }

    /**
     * Samsung's release rule (parity research GRID:G3): the stop being entered
     * is committed once the gesture is more than [COMMIT_FRACTION] of the way
     * into it (one capped 0.2 sample alone never switches), unless the fingers were last moving back, in which case it
     * needs [COMMIT_FRACTION_BACK]. Otherwise the gesture returns to the last
     * stop it fully reached.
     */
    fun settle(): GalleryZoomStop {
        val direction = progress.sign.toInt()
        val whole = abs(progress).toInt()
        val fraction = abs(progress) - whole
        val pinchingBack = direction != 0 && lastDirection == -direction
        val commit = fraction > COMMIT_FRACTION &&
            !(pinchingBack && fraction < COMMIT_FRACTION_BACK)
        val steps = (whole + if (commit) 1 else 0) * direction
        val minimum = if (allowOriginal) 0 else GalleryZoomStop.LARGE.ordinal
        val maximum = if (allowOverview) GalleryZoomStop.entries.lastIndex else GalleryZoomStop.SMALL.ordinal
        return GalleryZoomStop.entries[(initial.ordinal + steps).coerceIn(minimum, maximum)]
    }

    companion object {
        const val COMMIT_FRACTION = 0.2f
        const val COMMIT_FRACTION_BACK = 0.8f
    }
}

/**
 * The integrator must call this immediately before changing the destination.
 * A sort can reorder the *same* authorized media within one grant epoch; an
 * O(1) URI-at-index check rejects that stale pinch without scanning the list.
 * Null focus is valid only when the publication itself is still current.
 */
fun GalleryZoomRequest.isCurrentFor(
    currentCatalogueEpoch: Long,
    currentMediaCount: Int,
    currentMediaKeyAtIndex: (Int) -> String?
): Boolean {
    if (catalogueEpoch != currentCatalogueEpoch || currentMediaCount < 0 ||
        !focusYPx.isFinite()) return false
    val anchor = focal ?: return true
    return currentFocalIndexOrNull(
        anchor.itemIndex, anchor.mediaKey, currentMediaCount, currentMediaKeyAtIndex
    ) == anchor.itemIndex
}
