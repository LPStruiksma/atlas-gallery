package nl.atlas.gallery

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** One cheap, album-relative target; never walks the media list. */
internal data class ViewerStripPosition(val index: Int, val scrollOffsetPx: Int)

/**
 * Translate fractional pager motion into LazyRow's index + positive scroll offset.
 * scrollToItem(index, offset) puts that tile at the centre when offset is zero
 * because the strip supplies half-viewport horizontal content padding.
 */
internal fun viewerStripPosition(
    currentPage: Int, pageOffsetFraction: Float, count: Int, stridePx: Int
): ViewerStripPosition {
    require(count > 0 && stridePx > 0 && currentPage in 0 until count)
    val fraction = if (pageOffsetFraction.isFinite()) pageOffsetFraction.toDouble() else 0.0
    val position = (currentPage.toDouble() + fraction).coerceIn(0.0, (count - 1).toDouble())
    val index = floor(position).toInt()
    val offset = ((position - index) * stridePx).roundToInt().coerceIn(0, stridePx)
    return ViewerStripPosition(index, offset)
}

/** The tap-only strip ease starts at LazyRow's *rendered* first item + offset.
 * Double precision avoids losing sub-tile accuracy for a 150k-item library;
 * every frame remains O(1), irrespective of how far the destination is.
 */
internal fun viewerTapStripFrame(
    from: ViewerStripPosition, targetIndex: Int, progress: Float,
    count: Int, stridePx: Int
): ViewerStripPosition {
    require(count > 0 && stridePx > 0)
    val start = (from.index.toDouble() +
        from.scrollOffsetPx.coerceAtLeast(0).toDouble() / stridePx)
        .coerceIn(0.0, (count - 1).toDouble())
    val destination = targetIndex.coerceIn(0, count - 1).toDouble()
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f).toDouble() else 1.0
    val coordinate = start + (destination - start) * fraction
    val index = floor(coordinate).toInt().coerceIn(0, count - 1)
    val offset = ((coordinate - index) * stridePx).roundToInt().coerceIn(0, stridePx)
    return when {
        index == count - 1 -> ViewerStripPosition(index, 0)
        offset == stridePx -> ViewerStripPosition(index + 1, 0)
        else -> ViewerStripPosition(index, offset)
    }
}

/** Never animate a scrub completion, edge-page request, or a reduced-motion cut. */
internal fun viewerShouldEaseStripJump(
    origin: ViewerJumpOrigin, animationsDisabled: Boolean,
    from: ViewerStripPosition, targetIndex: Int
): Boolean = origin == ViewerJumpOrigin.THUMBNAIL && !animationsDisabled &&
    (from.index != targetIndex || from.scrollOffsetPx != 0)

/**
 * A regular one-finger swipe on an unzoomed photo belongs to HorizontalPager.
 * Compose detectTransformGestures consumes single-finger pans unconditionally
 * after touch slop; running it here would silently break photo navigation.
 * Two pointers may initiate zoom; one pointer may pan only after zoom.
 */
internal fun viewerMayHandleTransform(pointerCount: Int, zoomScale: Float): Boolean =
    pointerCount >= 2 || (pointerCount == 1 && zoomScale.isFinite() && zoomScale > 1.01f)

/**
 * Claim a pinch for the remainder of that gesture, even if the second
 * finger lifts or zoom returns to 1x. Otherwise a pinch-to-fit accidentally
 * becomes a pager swipe as soon as one finger is left touching the screen.
 * The caller resets claimed=false after all pointers are lifted.
 */
internal fun viewerGestureOwned(
    alreadyClaimed: Boolean, activePointerCount: Int, zoomScale: Float
): Boolean = alreadyClaimed || viewerMayHandleTransform(activePointerCount, zoomScale)

internal data class ViewerZoomPosition(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f
)

/**
 * Source-aware, finite zoom/pan. Source size can be the Coil-decoded oriented
 * bitmap dimensions; only the aspect ratio matters. Unknown sizes fail closed
 * to zero panning, rather than allowing unbounded blank space.
 *
 * Scale and translation are graphicsLayer values relative to viewport centre.
 * The requested point remains anchored when scale changes unless a hard image
 * boundary requires clamping. No original bitmap is decoded by this math.
 */
internal fun viewerZoomGesture(
    pose: ViewerZoomPosition,
    scaleChange: Float,
    panX: Float, panY: Float,
    focalX: Float, focalY: Float,
    viewportWidth: Int, viewportHeight: Int,
    sourceWidth: Int, sourceHeight: Int,
    maxScale: Float = 5f
): ViewerZoomPosition {
    require(maxScale.isFinite() && maxScale >= 1f)
    if (viewportWidth <= 0 || viewportHeight <= 0) return ViewerZoomPosition()
    val oldScale = pose.scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    val ratio = scaleChange.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = (oldScale * ratio).coerceIn(1f, maxScale)
    if (scale <= 1.0001f) return ViewerZoomPosition()
    val dx = focalX.takeIf { it.isFinite() }?.minus(viewportWidth / 2f) ?: 0f
    val dy = focalY.takeIf { it.isFinite() }?.minus(viewportHeight / 2f) ?: 0f
    val oldX = pose.translationX.takeIf { it.isFinite() } ?: 0f
    val oldY = pose.translationY.takeIf { it.isFinite() } ?: 0f
    val change = scale / oldScale
    val requestedX = oldX + (1f - change) * (dx - oldX) + panX.takeIf { it.isFinite() }.orZero()
    val requestedY = oldY + (1f - change) * (dy - oldY) + panY.takeIf { it.isFinite() }.orZero()
    return viewerClampedPose(scale, requestedX, requestedY,
        viewportWidth, viewportHeight, sourceWidth, sourceHeight)
}

/**
 * The pose at [scale] nearest the requested translation that shows no blank
 * space beyond the photo's edges. Unknown source sizes fail closed to zero
 * panning, exactly as [viewerZoomGesture] always has.
 */
private fun viewerClampedPose(
    scale: Float, requestedX: Float, requestedY: Float,
    viewportWidth: Int, viewportHeight: Int,
    sourceWidth: Int, sourceHeight: Int
): ViewerZoomPosition {
    if (sourceWidth <= 0 || sourceHeight <= 0) return ViewerZoomPosition(scale)
    val fitFactor = min(viewportWidth.toDouble() / sourceWidth, viewportHeight.toDouble() / sourceHeight)
    val fittedW = sourceWidth * fitFactor
    val fittedH = sourceHeight * fitFactor
    val boundX = ((fittedW * scale - viewportWidth) / 2.0).coerceAtLeast(0.0).toFloat()
    val boundY = ((fittedH * scale - viewportHeight) / 2.0).coerceAtLeast(0.0).toFloat()
    return ViewerZoomPosition(
        scale,
        requestedX.takeIf { it.isFinite() }?.coerceIn(-boundX, boundX) ?: 0f,
        requestedY.takeIf { it.isFinite() }?.coerceIn(-boundY, boundY) ?: 0f
    )
}

private fun Float?.orZero(): Float = this ?: 0f

internal fun viewerDoubleTapZoom(
    pose: ViewerZoomPosition, focalX: Float, focalY: Float,
    viewportWidth: Int, viewportHeight: Int,
    sourceWidth: Int, sourceHeight: Int
): ViewerZoomPosition = if (pose.scale > 1.01f) {
    ViewerZoomPosition()
} else {
    viewerZoomGesture(pose, 2.5f, 0f, 0f, focalX, focalY,
        viewportWidth, viewportHeight, sourceWidth, sourceHeight)
}

/**
 * FLOW-4 (#713): one frame of the animated double-tap zoom from [from] to
 * [to], where [fraction] is the already-eased progress (0 = from, 1 = to).
 *
 * Scale moves straight from start to end. Translation keeps the photo point
 * that was under the finger at the tap position, so the zoom pivots on the
 * tap. When the end pose had to be clamped at a photo edge (a tap near a
 * corner), the clamp offset is blended in over the animation rather than
 * applied in one jump. Every frame is also clamped to the bounds of its own
 * scale, so a new touch can take over from any frame without a snap: the
 * next [viewerZoomGesture] step starts from a pose that is already legal.
 */
internal fun viewerDoubleTapFrame(
    from: ViewerZoomPosition, to: ViewerZoomPosition,
    focalX: Float, focalY: Float,
    viewportWidth: Int, viewportHeight: Int,
    sourceWidth: Int, sourceHeight: Int,
    fraction: Float
): ViewerZoomPosition {
    val e = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    if (e >= 1f || viewportWidth <= 0 || viewportHeight <= 0) return to
    if (e <= 0f) return from
    val s0 = from.scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    val s1 = to.scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    val t0x = from.translationX.takeIf { it.isFinite() } ?: 0f
    val t0y = from.translationY.takeIf { it.isFinite() } ?: 0f
    val t1x = to.translationX.takeIf { it.isFinite() } ?: 0f
    val t1y = to.translationY.takeIf { it.isFinite() } ?: 0f
    val dx = focalX.takeIf { it.isFinite() }?.minus(viewportWidth / 2f) ?: 0f
    val dy = focalY.takeIf { it.isFinite() }?.minus(viewportHeight / 2f) ?: 0f
    // Photo point under the finger, relative to the viewport centre at 1x.
    val ux = (dx - t0x) / s0
    val uy = (dy - t0y) / s0
    // How far the end pose leaves that point from the finger (edge clamp).
    val residualX = s1 * ux + t1x - dx
    val residualY = s1 * uy + t1y - dy
    val scale = s0 + (s1 - s0) * e
    return viewerClampedPose(scale,
        dx - scale * ux + residualX * e,
        dy - scale * uy + residualY * e,
        viewportWidth, viewportHeight, sourceWidth, sourceHeight)
}

/** Re-clamp on preview-to-HQ upgrade or viewport rotation. */
internal fun viewerReboundZoom(
    pose: ViewerZoomPosition, viewportWidth: Int, viewportHeight: Int,
    sourceWidth: Int, sourceHeight: Int,
    maxScale: Float = 5f
): ViewerZoomPosition = viewerZoomGesture(
    pose, 1f, 0f, 0f, viewportWidth / 2f, viewportHeight / 2f,
    viewportWidth, viewportHeight, sourceWidth, sourceHeight, maxScale
)

/**
 * A zoomed photo may request an adjacent page with a *separate* intentional
 * swipe starting at a hard horizontal image boundary. Returning zero is the
 * conservative fallback (unknown size, diagonal pan, or not already at edge).
 * Positive one requests the next item, negative one the preceding item.
 *
 * No parent-pager pointer interception is required: PhotoPage issues an
 * ordinary existing selection intent only after the gesture releases.
 */
internal fun viewerEdgeSwipeDirection(
    poseAtDown: ViewerZoomPosition, currentPose: ViewerZoomPosition,
    panX: Float, panY: Float,
    viewportWidth: Int, viewportHeight: Int,
    imageWidth: Int, imageHeight: Int
): Int {
    if (poseAtDown.scale <= 1.01f || currentPose.scale <= 1.01f ||
        viewportWidth <= 0 || viewportHeight <= 0 ||
        imageWidth <= 0 || imageHeight <= 0 ||
        !panX.isFinite() || !panY.isFinite() ||
        !poseAtDown.translationX.isFinite() || !currentPose.translationX.isFinite() ||
        kotlin.math.abs(panX) <= 2f ||
        kotlin.math.abs(panX) <= kotlin.math.abs(panY) * 1.4f
    ) return 0
    val fit = min(viewportWidth.toDouble() / imageWidth,
        viewportHeight.toDouble() / imageHeight)
    val extent = ((imageWidth * fit * currentPose.scale - viewportWidth) / 2.0)
        .coerceAtLeast(0.0)
    val downExtent = ((imageWidth * fit * poseAtDown.scale - viewportWidth) / 2.0)
        .coerceAtLeast(0.0)
    val epsilon = 2.0
    return when {
        panX < 0f &&
            poseAtDown.translationX <= -downExtent + epsilon &&
            currentPose.translationX <= -extent + epsilon -> 1
        panX > 0f &&
            poseAtDown.translationX >= downExtent - epsilon &&
            currentPose.translationX >= extent - epsilon -> -1
        else -> 0
    }
}

/**
 * Trigger exactly one adjacent page at gesture end only after meaningful
 * *accumulated* overscroll. A small edge wiggle or pointer reversal resets
 * accumulated distance in the caller and never navigates.
 */
internal fun viewerEdgePageTarget(
    pageIndex: Int, itemCount: Int, overscrollX: Float, viewportWidth: Int
): Int? {
    if (pageIndex !in 0 until itemCount || viewportWidth <= 0 ||
        !overscrollX.isFinite()) return null
    val threshold = (viewportWidth * .18f).coerceIn(64f, 120f)
    val destination = when {
        overscrollX <= -threshold -> pageIndex + 1
        overscrollX >= threshold -> pageIndex - 1
        else -> return null
    }
    return destination.takeIf { it in 0 until itemCount }
}
