package nl.atlas.gallery.featureaiedit.preview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Dimension-only input; no image handle, bitmap or platform dependency. */
data class CompareImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
    }
}

/** The visible compare pane in physical display pixels, not source image pixels. */
data class CompareViewportSize(val width: Int, val height: Int) {
    init {
        require(width in 1..CompareViewportPlanner.MAX_VIEWPORT_SIDE &&
            height in 1..CompareViewportPlanner.MAX_VIEWPORT_SIDE) {
            "Viewport dimensions outside supported bounds"
        }
    }
}

/** Both frames use this same point in their own normalized [0, 1] image coordinates. */
data class CompareNormalizedPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
            "Normalized focus must be finite and in [0, 1]"
        }
    }
}

data class CompareViewportPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Viewport point must be finite" }
    }
}

/** Half-open integer source region. The exclusive right/bottom never exceed source size. */
data class CompareSourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Source crop must be non-empty and non-negative"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Image-covered part of a pane, excluding any letterbox margins. */
data class CompareViewportRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0.0 && top >= 0.0 && right > left && bottom > top) {
            "Visible viewport region must be finite, positive and non-empty"
        }
    }

    fun contains(point: CompareViewportPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

/**
 * Exactly one requested region/subsample per frame, not a collection of overlapping
 * image decodes. Dimensions are an upper bound on the *requested* decoded buffer when
 * a decoder honors the source crop and power-of-two sampling.
 *
 * This is not an allocation or a decoder implementation: an IO consumer MUST enforce
 * these bounds against real decoded output before allocating or publishing pixels.
 */
data class ComparePreviewDecode(
    val source: CompareSourceRect,
    val sampleLevel: Int,
    val downsampleBy: Long,
    val width: Int,
    val height: Int,
    val pixels: Long,
    val reservedBytes: Long,
)

/**
 * Mapping between normalized frame coordinates and a specific compare pane.
 * The transform uses the full image's scaled origin, not the rounded decoder crop,
 * so both independently sized frames retain the same logical focus and stable
 * round-trip coordinates at crop and letterbox boundaries.
 */
data class CompareFrameViewportPlan(
    val image: CompareImageSize,
    val visibleSource: CompareSourceRect,
    val visibleViewport: CompareViewportRect,
    val previewDecode: ComparePreviewDecode,
    val scale: Double,
    val imageLeft: Double,
    val imageTop: Double,
) {
    /** May lie outside the pane when the normalized source point has been panned offscreen. */
    fun sourceToViewport(point: CompareNormalizedPoint): CompareViewportPoint =
        CompareViewportPoint(
            x = imageLeft + point.x * image.width.toDouble() * scale,
            y = imageTop + point.y * image.height.toDouble() * scale,
        )

    /** Returns null for letterbox or clipped-away pixels; never extrapolates into them. */
    fun viewportToSourceOrNull(point: CompareViewportPoint): CompareNormalizedPoint? {
        if (!visibleViewport.contains(point)) return null
        val x = (point.x - imageLeft) / (image.width.toDouble() * scale)
        val y = (point.y - imageTop) / (image.height.toDouble() * scale)
        // Floating-point transforms can overshoot a source edge by an ulp.
        if (!x.isFinite() || !y.isFinite() ||
            x < -EDGE_TOLERANCE || x > 1.0 + EDGE_TOLERANCE ||
            y < -EDGE_TOLERANCE || y > 1.0 + EDGE_TOLERANCE) {
            return null
        }
        return CompareNormalizedPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }

    private companion object {
        const val EDGE_TOLERANCE: Double = 1e-9
    }
}

/** Both frame plans are fixed by one user focus, zoom, viewport and combined budget. */
data class CompareViewportPlan(
    val viewport: CompareViewportSize,
    val focus: CompareNormalizedPoint,
    val zoom: Double,
    val original: CompareFrameViewportPlan,
    val candidate: CompareFrameViewportPlan,
    val totalDecodeBytes: Long,
)

/**
 * Pure compare-mode geometry. Fit-centre a frame at zoom=1, then apply the same
 * relative zoom and normalized focus independently to both source frames.
 *
 * When the scaled image fits in an axis it remains centred (letterbox); otherwise
 * the focus attempts to land in the pane centre, clamped to avoid blank overscroll.
 * Different aspect ratios can necessarily yield different visible crops, but the
 * normalized point maps consistently across those frames.
 *
 * Budgets are for the two frame plans *combined*, including a four-byte-per-pixel
 * decode estimate. Each frame receives at most half the combined pixel budget.
 * The chosen integer power-of-two sample is the smallest one meeting both the
 * per-frame pixel ceiling and per-side ceiling. The preview may be coarser than
 * its display size under a tight budget: it must never silently request full-size
 * decoding instead. This planner never allocates pixel buffers or reads files.
 */
object CompareViewportPlanner {
    const val BYTES_PER_PIXEL: Long = 4L
    const val MAX_ZOOM: Double = 64.0
    const val MAX_VIEWPORT_SIDE: Int = 16384
    const val MAX_DECODED_SIDE: Int = 8192
    const val DEFAULT_COMBINED_PREVIEW_BYTES: Long = 8L * 1024L * 1024L

    // Avoid returning a mathematically nonempty but subpixel-sized visual region
    // for pathological synthetic aspect ratios; it cannot be used as a stable
    // interactive viewport with Double pixel coordinates.
    private const val MIN_PROJECTED_SIDE: Double = 1.0 / 1024.0

    fun plan(
        original: CompareImageSize,
        candidate: CompareImageSize,
        viewport: CompareViewportSize,
        focus: CompareNormalizedPoint = CompareNormalizedPoint(0.5, 0.5),
        zoom: Double = 1.0,
        maxCombinedPreviewBytes: Long = DEFAULT_COMBINED_PREVIEW_BYTES,
        maxDecodedSide: Int = 2048,
    ): CompareViewportPlan {
        require(zoom.isFinite() && zoom in 1.0..MAX_ZOOM) {
            "Zoom must be finite and inside the supported range"
        }
        require(maxCombinedPreviewBytes >= 2L * BYTES_PER_PIXEL) {
            "Combined decode budget must fit at least one pixel per frame"
        }
        require(maxDecodedSide in 1..MAX_DECODED_SIDE) {
            "Maximum sampled side outside supported bounds"
        }

        val perFramePixels = maxCombinedPreviewBytes / (2L * BYTES_PER_PIXEL)
        val originalPlan = framePlan(original, viewport, focus, zoom, perFramePixels, maxDecodedSide)
        val candidatePlan = framePlan(candidate, viewport, focus, zoom, perFramePixels, maxDecodedSide)
        val total = originalPlan.previewDecode.reservedBytes + candidatePlan.previewDecode.reservedBytes
        check(total <= maxCombinedPreviewBytes) { "Combined decode budget exceeded" }

        return CompareViewportPlan(
            viewport = viewport,
            focus = focus,
            zoom = zoom,
            original = originalPlan,
            candidate = candidatePlan,
            totalDecodeBytes = total,
        )
    }

    private fun framePlan(
        image: CompareImageSize,
        viewport: CompareViewportSize,
        focus: CompareNormalizedPoint,
        zoom: Double,
        maxPixels: Long,
        maxSide: Int,
    ): CompareFrameViewportPlan {
        val scale = min(
            viewport.width.toDouble() / image.width,
            viewport.height.toDouble() / image.height,
        ) * zoom
        val scaledWidth = image.width.toDouble() * scale
        val scaledHeight = image.height.toDouble() * scale
        require(scaledWidth >= MIN_PROJECTED_SIDE && scaledHeight >= MIN_PROJECTED_SIDE) {
            "Source aspect ratio projects below subpixel resolution"
        }

        val left = origin(viewport.width.toDouble(), scaledWidth, focus.x)
        val top = origin(viewport.height.toDouble(), scaledHeight, focus.y)
        val visible = CompareViewportRect(
            left = max(0.0, left),
            top = max(0.0, top),
            right = min(viewport.width.toDouble(), left + scaledWidth),
            bottom = min(viewport.height.toDouble(), top + scaledHeight),
        )
        val crop = CompareSourceRect(
            left = floorToSource((visible.left - left) / scale, image.width),
            top = floorToSource((visible.top - top) / scale, image.height),
            right = ceilToSource((visible.right - left) / scale, image.width),
            bottom = ceilToSource((visible.bottom - top) / scale, image.height),
        )
        val decode = sample(crop, maxPixels, maxSide)
        return CompareFrameViewportPlan(
            image = image,
            visibleSource = crop,
            visibleViewport = visible,
            previewDecode = decode,
            scale = scale,
            imageLeft = left,
            imageTop = top,
        )
    }

    private fun origin(viewportSide: Double, scaledSide: Double, focus: Double): Double =
        if (scaledSide <= viewportSide) {
            (viewportSide - scaledSide) / 2.0
        } else {
            (viewportSide / 2.0 - focus * scaledSide)
                .coerceIn(viewportSide - scaledSide, 0.0)
        }

    private fun floorToSource(pixel: Double, limit: Int): Int =
        floor(pixel).toLong().coerceIn(0L, limit.toLong()).toInt()

    private fun ceilToSource(pixel: Double, limit: Int): Int =
        ceil(pixel).toLong().coerceIn(0L, limit.toLong()).toInt()

    private fun sample(crop: CompareSourceRect, maxPixels: Long, maxSide: Int): ComparePreviewDecode {
        var level = 0
        var downsample = 1L
        var width: Long
        var height: Long
        while (true) {
            width = (crop.width.toLong() + downsample - 1L) / downsample
            height = (crop.height.toLong() + downsample - 1L) / downsample
            // Two positive Int dimensions multiply safely in Long.
            if (width <= maxSide && height <= maxSide && width * height <= maxPixels) break
            require(level < 31) { "No bounded sample level for source crop" }
            downsample *= 2L
            level++
        }

        val pixels = width * height
        return ComparePreviewDecode(
            source = crop,
            sampleLevel = level,
            downsampleBy = downsample,
            width = width.toInt(),
            height = height.toInt(),
            pixels = pixels,
            reservedBytes = pixels * BYTES_PER_PIXEL,
        )
    }
}
