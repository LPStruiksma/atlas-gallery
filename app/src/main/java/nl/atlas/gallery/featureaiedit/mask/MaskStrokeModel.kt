package nl.atlas.gallery.featureaiedit.mask

import kotlin.math.max
import kotlin.math.min

/** Continuous edge coordinates: (0,0) is the upper-left image edge, (width,height) the opposite edge. */
data class PixelPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

data class NormalizedPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
    fun clipped(): NormalizedPoint = NormalizedPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
}

data class NormalizedBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

enum class BrushMode { ADD, REMOVE }
enum class ScaleMode { FIT, CROP }

/**
 * EXIF orientation codes 1..8 applied to source pixel-edge coordinates.
 * Coordinates remain Double throughout (including when image dimensions are Int.MAX_VALUE).
 * No image bytes are held or modified by this geometry-only type.
 */
data class ImageOrientation(val width: Int, val height: Int, val exif: Int) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(exif in 1..8) { "EXIF orientation must be 1..8" }
    }

    val orientedWidth: Int get() = if (exif in 5..8) height else width
    val orientedHeight: Int get() = if (exif in 5..8) width else height

    fun originalToOriented(point: PixelPoint): PixelPoint {
        val w = width.toDouble()
        val h = height.toDouble()
        val x = point.x
        val y = point.y
        return when (exif) {
            1 -> PixelPoint(x, y)
            2 -> PixelPoint(w - x, y)
            3 -> PixelPoint(w - x, h - y)
            4 -> PixelPoint(x, h - y)
            5 -> PixelPoint(y, x)
            6 -> PixelPoint(h - y, x)
            7 -> PixelPoint(h - y, w - x)
            else -> PixelPoint(y, w - x)
        }
    }

    fun orientedToOriginal(point: PixelPoint): PixelPoint {
        val w = width.toDouble()
        val h = height.toDouble()
        val x = point.x
        val y = point.y
        return when (exif) {
            1 -> PixelPoint(x, y)
            2 -> PixelPoint(w - x, y)
            3 -> PixelPoint(w - x, h - y)
            4 -> PixelPoint(x, h - y)
            5 -> PixelPoint(y, x)
            6 -> PixelPoint(y, h - x)
            7 -> PixelPoint(w - y, h - x)
            else -> PixelPoint(w - y, x)
        }
    }

    fun originalToNormalized(point: PixelPoint): NormalizedPoint {
        val p = originalToOriented(point)
        return NormalizedPoint(p.x / orientedWidth.toDouble(), p.y / orientedHeight.toDouble())
    }

    fun normalizedToOriginal(point: NormalizedPoint): PixelPoint =
        orientedToOriginal(PixelPoint(point.x * orientedWidth.toDouble(), point.y * orientedHeight.toDouble()))

    fun viewport(viewWidth: Double, viewHeight: Double, mode: ScaleMode): ViewportTransform =
        ViewportTransform(orientedWidth.toDouble(), orientedHeight.toDouble(), viewWidth, viewHeight, mode)
}

/** Centered fit or fill/crop. The inverse deliberately does not clip out-of-frame input. */
data class ViewportTransform(
    val imageWidth: Double,
    val imageHeight: Double,
    val viewWidth: Double,
    val viewHeight: Double,
    val mode: ScaleMode
) {
    init {
        require(listOf(imageWidth, imageHeight, viewWidth, viewHeight).all { it.isFinite() && it > 0.0 })
    }

    val scale: Double = when (mode) {
        ScaleMode.FIT -> min(viewWidth / imageWidth, viewHeight / imageHeight)
        ScaleMode.CROP -> max(viewWidth / imageWidth, viewHeight / imageHeight)
    }
    val offsetX: Double = (viewWidth - imageWidth * scale) / 2.0
    val offsetY: Double = (viewHeight - imageHeight * scale) / 2.0

    fun orientedToViewport(p: PixelPoint): PixelPoint =
        PixelPoint(p.x * scale + offsetX, p.y * scale + offsetY)

    fun viewportToOriented(p: PixelPoint): PixelPoint =
        PixelPoint((p.x - offsetX) / scale, (p.y - offsetY) / scale)
}

/**
 * Immutable stroke, independent of viewport dimensions. Radius is a normalized oriented-image
 * fraction of the shorter edge, clamped to [0,1]. Positions are clipped at construction.
 * Serialization uses hexadecimal IEEE-754 strings, making exact double round trips reproducible.
 */
data class MaskStroke(val mode: BrushMode, val radius: Double, val points: List<NormalizedPoint>) {
    init {
        require(radius.isFinite() && radius in 0.0..1.0)
        require(points.isNotEmpty())
        require(points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }) {
            "Stroke points must be normalized; use MaskStroke.clipped for outside points"
        }
    }

    val bounds: NormalizedBounds get() {
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        return NormalizedBounds((left - radius).coerceAtLeast(0.0), (top - radius).coerceAtLeast(0.0),
            (right + radius).coerceAtMost(1.0), (bottom + radius).coerceAtMost(1.0))
    }

    fun serialize(): String = buildString {
        append(mode.name).append(';').append(java.lang.Double.toHexString(radius))
        for (p in points) {
            append(';').append(java.lang.Double.toHexString(p.x))
                .append(',').append(java.lang.Double.toHexString(p.y))
        }
    }

    companion object {
        fun clipped(mode: BrushMode, radius: Double, points: List<NormalizedPoint>): MaskStroke =
            MaskStroke(mode, radius.coerceIn(0.0, 1.0), points.map { it.clipped() })

        fun parse(value: String): MaskStroke {
            val parts = value.split(';')
            require(parts.size >= 3)
            val mode = BrushMode.valueOf(parts[0])
            val radius = parts[1].toDouble()
            val points = parts.drop(2).map { segment ->
                val pair = segment.split(',')
                require(pair.size == 2)
                NormalizedPoint(pair[0].toDouble(), pair[1].toDouble())
            }
            return MaskStroke(mode, radius, points)
        }
    }
}

/** Immutable undo/redo cursor; adding after undo clears the redo tail. */
data class MaskHistory(
    private val timeline: List<MaskStroke> = emptyList(),
    private val cursor: Int = timeline.size
) {
    init { require(cursor in 0..timeline.size) }
    val strokes: List<MaskStroke> get() = timeline.take(cursor)

    fun add(stroke: MaskStroke): MaskHistory =
        MaskHistory(timeline.take(cursor) + stroke, cursor + 1)

    fun undo(): MaskHistory = if (cursor == 0) this else copy(cursor = cursor - 1)
    fun redo(): MaskHistory = if (cursor == timeline.size) this else copy(cursor = cursor + 1)

    /** Only active operations are serialized; an undone tail is deliberately discarded. */
    fun serialize(): String = (listOf("MASK1", cursor.toString()) + strokes.map { it.serialize() })
        .joinToString("|")

    companion object {
        fun parse(value: String): MaskHistory {
            val fields = value.split('|')
            require(fields.size >= 2 && fields[0] == "MASK1")
            val count = fields[1].toInt()
            val strokes = fields.drop(2).map { MaskStroke.parse(it) }
            require(count == strokes.size)
            return MaskHistory(strokes)
        }
    }
}
