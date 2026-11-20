package nl.atlas.gallery.featureeditor.overlay

import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.Matrix3
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.Point2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Source-pixel transform supplied by EDIT-1 (RenderStage.Overlays.sourceToOutput).
 * The local units are fractions of min(sourceWidth, sourceHeight), NOT fractions of output.
 * Therefore rotating/flipping/cropping later keeps all decoration points on their source pixels.
 */
data class DecorationSpace(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceToOutput: Matrix3,
) {
    init { require(sourceWidth > 0 && sourceHeight > 0) { "Invalid source dimensions" } }
    val shortSide: Double get() = min(sourceWidth, sourceHeight).toDouble()

    fun localToOutputMatrix(p: DecorationPlacement): Matrix3 =
        sourceToOutput *
            Matrix3.translate(p.centerX * sourceWidth, p.centerY * sourceHeight) *
            Matrix3.rotate(Math.toRadians(p.rotationDegrees)) *
            Matrix3.scale(shortSide * p.scale, shortSide * p.scale)

    fun localToOutput(p: DecorationPlacement, local: Point2): Point2 =
        localToOutputMatrix(p).map(local)

    fun outputToLocal(p: DecorationPlacement, output: Point2): Point2 =
        localToOutputMatrix(p).inverse().map(output)

    /** Tolerance in local units, near the pivot (projective geometry can vary across a layer). */
    fun localTolerance(p: DecorationPlacement, px: Double): Double {
        val o = localToOutput(p, Point2(0.0, 0.0))
        val x = localToOutput(p, Point2(0.01, 0.0))
        val y = localToOutput(p, Point2(0.0, 0.01))
        val pixelsPerUnit = min(hypot(x.x - o.x, x.y - o.y),
            hypot(y.x - o.x, y.y - o.y)) / 0.01
        return px / pixelsPerUnit.coerceAtLeast(1e-6)
    }
}

object DecorationGeometry {
    /**
     * Uniform Catmull-Rom curve sampling, including both endpoints. An 8-part segment is a
     * close CPU approximation to the interactive cubic curve (with endpoint duplication).
     * Pressure is interpolated when supplied; missing pressure defaults to 1.0.
     */
    fun smooth(points: List<InkPoint>, subdivisions: Int = 8): List<InkPoint> {
        require(subdivisions in 1..64) { "Invalid curve resolution" }
        if (points.size < 2) return points.toList()
        val result = ArrayList<InkPoint>((points.size - 1) * subdivisions + 1)
        fun cubic(a: Double, b: Double, c: Double, d: Double, t: Double): Double {
            val t2 = t * t
            return 0.5 * ((2.0 * b) + (-a + c) * t +
                (2 * a - 5 * b + 4 * c - d) * t2 +
                (-a + 3 * b - 3 * c + d) * t2 * t)
        }
        for (i in 0 until points.lastIndex) {
            val a = points[maxOf(0, i - 1)]
            val b = points[i]
            val c = points[i + 1]
            val d = points[minOf(points.lastIndex, i + 2)]
            for (step in 0 until subdivisions) {
                val t = step.toDouble() / subdivisions
                val x = cubic(a.x, b.x, c.x, d.x, t).coerceIn(-4.0, 4.0)
                val y = cubic(a.y, b.y, c.y, d.y, t).coerceIn(-4.0, 4.0)
                val pressure = if (listOf(a, b, c, d).all { it.pressure == null }) null
                    else cubic(a.pressure ?: 1.0, b.pressure ?: 1.0,
                        c.pressure ?: 1.0, d.pressure ?: 1.0, t).coerceIn(0.1, 4.0)
                result += InkPoint(x, y, pressure)
            }
        }
        result += points.last()
        return result
    }

    /** Constrain lines/arrows to 0, 45, 90 ... degrees; square and circle use equal sides. */
    fun snappedEnd(start: Point2, end: Point2, type: ShapeType): Point2 {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return when (type) {
            ShapeType.LINE, ShapeType.ARROW -> {
                val radius = hypot(dx, dy)
                val angle = round(atan2(dy, dx) / (PI / 4)) * PI / 4
                Point2(start.x + radius * cos(angle), start.y + radius * sin(angle))
            }
            ShapeType.RECTANGLE, ShapeType.ELLIPSE -> {
                val edge = min(abs(dx), abs(dy))
                Point2(start.x + if (dx < 0) -edge else edge,
                    start.y + if (dy < 0) -edge else edge)
            }
        }
    }

    fun arrowHead(start: Point2, end: Point2, width: Double): List<Pair<Point2, Point2>> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length < 1e-9) return emptyList()
        val back = min(length * 0.4, maxOf(width * 8.0, 0.04))
        val angle = atan2(dy, dx)
        return listOf(-PI / 6, PI / 6).map { offset ->
            end to Point2(end.x - back * cos(angle + offset), end.y - back * sin(angle + offset))
        }
    }

    /** Last drawn layer wins. A stroke eraser is a selectable editable layer in its own right. */
    fun hitTopmost(
        layers: List<OverlayLayer>, output: Point2, space: DecorationSpace, tolerancePx: Double = 12.0,
    ): String? = layers.asReversed().firstOrNull {
        hitTest(DecorationCodec.decode(it), output, space, tolerancePx)
    }?.layerId

    fun hitTest(
        layer: DecorationLayer, output: Point2, space: DecorationSpace, tolerancePx: Double = 12.0,
    ): Boolean {
        require(tolerancePx.isFinite() && tolerancePx >= 0) { "Invalid hit tolerance" }
        val p = layer.placement
        val local = space.outputToLocal(p, output)
        val tol = space.localTolerance(p, tolerancePx)
        return when (layer) {
            is StrokeLayer -> {
                val points = smooth(layer.points).map { it.asPoint() }
                nearLine(local, points, layer.width / 2 + tol)
            }
            is ShapeLayer -> {
                val a = layer.start; val b = layer.end
                val rect = listOf(Point2(a.x, a.y), Point2(b.x, a.y),
                    Point2(b.x, b.y), Point2(a.x, b.y))
                when (layer.shape) {
                    ShapeType.LINE -> nearLine(local, listOf(a, b), layer.width / 2 + tol)
                    ShapeType.ARROW -> nearLine(local, listOf(a, b), layer.width / 2 + tol) ||
                        arrowHead(a, b, layer.width).any {
                            nearLine(local, listOf(it.first, it.second), layer.width / 2 + tol)
                        }
                    ShapeType.RECTANGLE -> {
                        (layer.filled && insideRect(local, a, b, tol)) ||
                            nearLine(local, rect + rect.first(), layer.width / 2 + tol)
                    }
                    ShapeType.ELLIPSE -> {
                        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
                        val rx = abs(a.x - b.x) / 2; val ry = abs(a.y - b.y) / 2
                        if (rx < 1e-9 || ry < 1e-9) nearLine(local, listOf(a, b), layer.width / 2 + tol)
                        else {
                            val ring = (0..64).map { i ->
                                val angle = i * 2 * PI / 64
                                Point2(cx + rx * cos(angle), cy + ry * sin(angle))
                            }
                            (layer.filled && ((local.x - cx) / (rx + tol)) * ((local.x - cx) / (rx + tol)) +
                                ((local.y - cy) / (ry + tol)) * ((local.y - cy) / (ry + tol)) <= 1.0) ||
                                nearLine(local, ring, layer.width / 2 + tol)
                        }
                    }
                }
            }
            is TextLayer -> abs(local.x) <= layer.boxWidth / 2 + tol &&
                abs(local.y) <= layer.boxHeight / 2 + tol
            is EmojiLayer -> abs(local.x) <= layer.size / 2 + tol &&
                abs(local.y) <= layer.size / 2 + tol
            is StickerLayer -> abs(local.x) <= layer.width / 2 + tol &&
                abs(local.y) <= layer.height / 2 + tol
        }
    }

    /**
     * EDIT-1 folds in insertion order; an update keeps a layer's position. Moving to front
     * therefore needs a remove and a new add, with distinct operation ids for history.
     */
    fun bringToFront(layer: OverlayLayer, removeOperationId: String, addOperationId: String):
        List<EditOperation> {
        require(removeOperationId.isNotBlank() && addOperationId.isNotBlank() &&
            removeOperationId != addOperationId) { "Invalid reorder operations" }
        return listOf(
            EditOperation.OverlayRemove(removeOperationId, layer.layerId),
            EditOperation.OverlayAdd(addOperationId, layer),
        )
    }

    private fun insideRect(p: Point2, a: Point2, b: Point2, expand: Double): Boolean =
        p.x in (minOf(a.x, b.x) - expand)..(maxOf(a.x, b.x) + expand) &&
            p.y in (minOf(a.y, b.y) - expand)..(maxOf(a.y, b.y) + expand)

    private fun nearLine(p: Point2, points: List<Point2>, radius: Double): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return hypot(p.x - points[0].x, p.y - points[0].y) <= radius
        return points.zipWithNext().any { (a, b) ->
            val dx = b.x - a.x; val dy = b.y - a.y
            val d2 = dx * dx + dy * dy
            val t = if (d2 <= 1e-18) 0.0 else
                (((p.x - a.x) * dx + (p.y - a.y) * dy) / d2).coerceIn(0.0, 1.0)
            hypot(p.x - a.x - dx * t, p.y - a.y - dy * t) <= radius
        }
    }
}
