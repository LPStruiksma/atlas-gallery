package nl.atlas.gallery.featureeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Orientation as one element of the 8-element symmetry group of a rectangle, in canonical form
 * "mirror horizontally in source space (if [mirrored]), then turn [quarterTurns] x 90 degrees
 * clockwise". Any sequence of user rotations and flips folds into this form.
 */
data class Orientation(val quarterTurns: Int = 0, val mirrored: Boolean = false) {
    init {
        require(quarterTurns in 0..3) { "quarterTurns must be 0..3" }
    }

    val swapsAxes: Boolean get() = quarterTurns % 2 == 1

    /** The user turns the current display clockwise by [turns] quarter turns. */
    fun rotatedClockwise(turns: Int): Orientation = copy(quarterTurns = Math.floorMod(quarterTurns + turns, 4))

    /** The user mirrors the current display left-right (H) or top-bottom (V). */
    fun flipped(axis: FlipAxis): Orientation = when (axis) {
        // H * R(q) * M^m = R(-q) * M^(m+1)
        FlipAxis.HORIZONTAL -> Orientation(Math.floorMod(-quarterTurns, 4), !mirrored)
        // V = R(2) * H
        FlipAxis.VERTICAL -> Orientation(Math.floorMod(2 - quarterTurns, 4), !mirrored)
    }

    /** Pixel transform from a `w x h` canvas into the oriented canvas. */
    fun matrix(w: Double, h: Double): Matrix3 {
        val mirror = if (mirrored) Matrix3(doubleArrayOf(-1.0, 0.0, w, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) else Matrix3.IDENTITY
        val turn = when (quarterTurns) {
            0 -> Matrix3.IDENTITY
            1 -> Matrix3(doubleArrayOf(0.0, -1.0, h, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)) // (x,y)->(h-y, x)
            2 -> Matrix3(doubleArrayOf(-1.0, 0.0, w, 0.0, -1.0, h, 0.0, 0.0, 1.0))
            else -> Matrix3(doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, w, 0.0, 0.0, 1.0)) // (x,y)->(y, w-x)
        }
        return turn * mirror
    }

    companion object {
        val NONE = Orientation()
    }
}

/** How a display-space crop rectangle moves when the user turns or mirrors the display. */
internal fun NormRect.rotatedClockwise(turns: Int): NormRect {
    var r = this
    repeat(Math.floorMod(turns, 4)) { r = NormRect(1 - r.bottom, r.left, 1 - r.top, r.right) }
    return r
}

internal fun NormRect.flipped(axis: FlipAxis): NormRect = when (axis) {
    FlipAxis.HORIZONTAL -> NormRect(1 - right, top, 1 - left, bottom)
    FlipAxis.VERTICAL -> NormRect(left, 1 - bottom, right, 1 - top)
}

/**
 * The folded geometry of a recipe. Pixel pipeline, from source to output:
 *
 * 1. perspective: the homography pulling [perspective] (source space) to the picture corners;
 * 2. straighten: rotation by the source-space angle about the picture centre;
 * 3. orientation: quarter turns and mirror ([orientation]) into the display canvas;
 * 4. crop: [effectiveCrop] of the display canvas, scaled to the output size.
 *
 * [requestedCrop] is what the user last chose. [effectiveCrop] is that crop shrunk (keeping
 * aspect) until it lies inside the warped picture, so the output never shows empty corners and
 * moving straighten back to zero restores the user's crop.
 */
class GeometryState(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val orientation: Orientation = Orientation.NONE,
    val straightenDegrees: Double = 0.0,
    val perspective: Quad = Quad.IDENTITY,
    val requestedCrop: NormRect = NormRect.FULL,
    val cropAspect: String? = null,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
    }

    /** Display canvas size in pixels (source size, axes swapped by odd quarter turns). */
    val canvasWidth: Int get() = if (orientation.swapsAxes) sourceHeight else sourceWidth
    val canvasHeight: Int get() = if (orientation.swapsAxes) sourceWidth else sourceHeight

    /** Straighten angle in source space (mirroring reverses the visual sense of rotation). */
    val sourceStraightenDegrees: Double get() = if (orientation.mirrored) -straightenDegrees else straightenDegrees

    /** Source pixels to display-canvas pixels (perspective, straighten, orientation). */
    val sourceToCanvas: Matrix3 by lazy {
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()
        val persp = if (perspective.isIdentity()) {
            Matrix3.IDENTITY
        } else {
            val from = perspective.points().map { Point2(it.x * w, it.y * h) }
            val to = listOf(Point2(0.0, 0.0), Point2(w, 0.0), Point2(w, h), Point2(0.0, h))
            Matrix3.homography(from, to)
        }
        val straighten = if (sourceStraightenDegrees == 0.0) {
            Matrix3.IDENTITY
        } else {
            Matrix3.translate(w / 2, h / 2) *
                Matrix3.rotate(Math.toRadians(sourceStraightenDegrees)) *
                Matrix3.translate(-w / 2, -h / 2)
        }
        orientation.matrix(w, h) * straighten * persp
    }

    /** The warped picture outline in normalized display-canvas coordinates (convex, 4 corners). */
    val validRegion: List<Point2> by lazy {
        val w = sourceWidth.toDouble(); val h = sourceHeight.toDouble()
        val cw = canvasWidth.toDouble(); val ch = canvasHeight.toDouble()
        listOf(Point2(0.0, 0.0), Point2(w, 0.0), Point2(w, h), Point2(0.0, h))
            .map { sourceToCanvas.map(it) }
            .map { Point2(it.x / cw, it.y / ch) }
    }

    /** Where a crop may go: the warped picture clipped to the canvas (convex). */
    val cropRegion: List<Point2> by lazy { clipToUnitSquare(validRegion) }

    val effectiveCrop: NormRect by lazy { fitInside(requestedCrop) }

    /** Output size in pixels at full resolution for [effectiveCrop]. */
    val outputWidth: Int get() = max(1, (effectiveCrop.width * canvasWidth).roundToInt())
    val outputHeight: Int get() = max(1, (effectiveCrop.height * canvasHeight).roundToInt())

    val isIdentity: Boolean
        get() = orientation == Orientation.NONE && straightenDegrees == 0.0 &&
            perspective.isIdentity() && effectiveCrop == NormRect.FULL

    /**
     * Source pixels to output pixels for an output of [outW] x [outH] (defaults to full size).
     * The crop rectangle maps exactly onto the output edges.
     */
    fun sourceToOutput(outW: Int = outputWidth, outH: Int = outputHeight): Matrix3 {
        val c = effectiveCrop
        val cw = canvasWidth.toDouble(); val ch = canvasHeight.toDouble()
        val crop = Matrix3.scale(outW / (c.width * cw), outH / (c.height * ch)) *
            Matrix3.translate(-c.left * cw, -c.top * ch)
        return crop * sourceToCanvas
    }

    /**
     * Resolution-independent form: normalized source (`0..1`) to normalized output (`0..1`).
     * Ultra HDR gainmaps (any resolution) and previews reuse this matrix.
     */
    fun normalizedSourceToOutput(): Matrix3 {
        val w = sourceWidth.toDouble(); val h = sourceHeight.toDouble()
        val ow = outputWidth.toDouble(); val oh = outputHeight.toDouble()
        return Matrix3.scale(1 / ow, 1 / oh) * sourceToOutput() * Matrix3.scale(w, h)
    }

    /** True when every corner of [rect] lies inside the warped picture. */
    fun contains(rect: NormRect, tolerance: Double = 1e-9): Boolean =
        rect.corners().all { insideConvex(cropRegion, it, tolerance) }

    /**
     * Shrinks [rect] (keeping its aspect) towards the picture centre until it fits inside the
     * warped picture. Returns [rect] unchanged when it already fits.
     */
    fun fitInside(rect: NormRect): NormRect {
        val clamped = NormRect(
            rect.left.coerceIn(0.0, 1.0), rect.top.coerceIn(0.0, 1.0),
            rect.right.coerceIn(0.0, 1.0), rect.bottom.coerceIn(0.0, 1.0),
        ).takeIf { it.isValid() } ?: NormRect.FULL
        if (contains(clamped)) return clamped
        val centre = centroid(cropRegion)
        val tinyW = clamped.width * 1e-6
        val tinyH = clamped.height * 1e-6
        val fallback = NormRect(centre.x - tinyW, centre.y - tinyH, centre.x + tinyW, centre.y + tinyH)
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (contains(lerp(fallback, clamped, mid), 0.0)) lo = mid else hi = mid
        }
        return lerp(fallback, clamped, lo)
    }

    /**
     * The largest crop of display aspect [aspect] (width / height in pixels, null keeps the
     * canvas aspect) centred on the picture centre that fits inside the warped picture. This is
     * the "auto crop" Samsung applies while straightening.
     */
    fun largestCrop(aspect: Double? = null): NormRect {
        val cw = canvasWidth.toDouble(); val ch = canvasHeight.toDouble()
        val a = aspect ?: (cw / ch)
        require(a.isFinite() && a > 0) { "aspect must be positive" }
        // Full-canvas-bounded rectangle with that pixel aspect, as normalized size.
        var nw = 1.0
        var nh = (cw / a) / ch
        if (nh > 1.0) { nw /= nh; nh = 1.0 }
        val centre = Point2(0.5, 0.5).takeIf { insideConvex(cropRegion, it, 0.0) } ?: centroid(cropRegion)
        val full = NormRect(centre.x - nw / 2, centre.y - nh / 2, centre.x + nw / 2, centre.y + nh / 2)
        return fitInside(shiftIntoUnit(full))
    }

    /** Maps a tap in normalized output coordinates back to normalized source coordinates. */
    fun outputToSource(p: Point2): Point2 = normalizedSourceToOutput().inverse().map(p)

    /** Maps normalized source coordinates to normalized output coordinates. */
    fun sourceToOutputNormalized(p: Point2): Point2 = normalizedSourceToOutput().map(p)

    fun copy(
        orientation: Orientation = this.orientation,
        straightenDegrees: Double = this.straightenDegrees,
        perspective: Quad = this.perspective,
        requestedCrop: NormRect = this.requestedCrop,
        cropAspect: String? = this.cropAspect,
    ) = GeometryState(sourceWidth, sourceHeight, orientation, straightenDegrees, perspective, requestedCrop, cropAspect)

    override fun equals(other: Any?): Boolean = other is GeometryState &&
        sourceWidth == other.sourceWidth && sourceHeight == other.sourceHeight &&
        orientation == other.orientation && straightenDegrees == other.straightenDegrees &&
        perspective == other.perspective && requestedCrop == other.requestedCrop && cropAspect == other.cropAspect

    override fun hashCode(): Int =
        listOf(sourceWidth, sourceHeight, orientation, straightenDegrees, perspective, requestedCrop, cropAspect).hashCode()

    companion object {
        /** Folds the geometry operations of [recipe], in order. */
        fun of(recipe: EditRecipe): GeometryState =
            recipe.operations.fold(GeometryState(recipe.sourceWidth, recipe.sourceHeight)) { g, op -> g.apply(op) }
    }

    internal fun apply(op: EditOperation): GeometryState = when (op) {
        is EditOperation.Rotate90 -> copy(
            orientation = orientation.rotatedClockwise(op.quarterTurns),
            requestedCrop = requestedCrop.rotatedClockwise(op.quarterTurns),
        )
        is EditOperation.Flip -> copy(
            orientation = orientation.flipped(op.axis),
            requestedCrop = requestedCrop.flipped(op.axis),
            straightenDegrees = -straightenDegrees,
        )
        is EditOperation.Straighten -> copy(straightenDegrees = op.degrees)
        is EditOperation.Perspective -> copy(perspective = op.quad)
        is EditOperation.Crop -> copy(requestedCrop = op.rect, cropAspect = op.aspect)
        is EditOperation.ResetGeometry -> GeometryState(sourceWidth, sourceHeight)
        else -> this
    }
}

private fun lerp(a: NormRect, b: NormRect, t: Double) = NormRect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)

private fun shiftIntoUnit(r: NormRect): NormRect {
    val dx = when { r.left < 0 -> -r.left; r.right > 1 -> 1 - r.right; else -> 0.0 }
    val dy = when { r.top < 0 -> -r.top; r.bottom > 1 -> 1 - r.bottom; else -> 0.0 }
    return NormRect(
        max(0.0, r.left + dx), max(0.0, r.top + dy), min(1.0, r.right + dx), min(1.0, r.bottom + dy),
    )
}

/** Sutherland-Hodgman clip of a convex polygon to the unit square. */
internal fun clipToUnitSquare(poly: List<Point2>): List<Point2> {
    var out = poly
    val edges: List<Pair<(Point2) -> Double, Boolean>> = listOf(
        { p: Point2 -> p.x } to true, { p: Point2 -> p.x } to false,
        { p: Point2 -> p.y } to true, { p: Point2 -> p.y } to false,
    )
    for ((coord, isMin) in edges) {
        // Signed distance inside the half-plane coord >= 0 (min) or coord <= 1 (max).
        val dist = { p: Point2 -> if (isMin) coord(p) else 1 - coord(p) }
        val input = out
        if (input.isEmpty()) break
        val result = ArrayList<Point2>()
        for (i in input.indices) {
            val a = input[i]; val b = input[(i + 1) % input.size]
            val da = dist(a); val db = dist(b)
            if (da >= 0) result += a
            if ((da >= 0) != (db >= 0)) {
                val t = da / (da - db)
                result += Point2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        out = result.filterIndexed { i, p ->
            val prev = result[(i + result.size - 1) % result.size]
            result.size == 1 || abs(p.x - prev.x) > 1e-12 || abs(p.y - prev.y) > 1e-12
        }
    }
    return out
}

internal fun centroid(poly: List<Point2>): Point2 =
    Point2(poly.sumOf { it.x } / poly.size, poly.sumOf { it.y } / poly.size)

/** Point-in-convex-polygon test that accepts either winding; [tolerance] in normalized units. */
internal fun insideConvex(poly: List<Point2>, p: Point2, tolerance: Double): Boolean {
    var sign = 0
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val ex = b.x - a.x; val ey = b.y - a.y
        val len = kotlin.math.sqrt(ex * ex + ey * ey)
        if (len < 1e-9) continue // near-duplicate vertex: its direction is noise
        // Signed distance of p from edge line.
        val d = (ex * (p.y - a.y) - ey * (p.x - a.x)) / len
        if (abs(d) <= tolerance) continue
        val s = if (d > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}
