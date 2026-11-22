package nl.atlas.gallery.featureeditor.overlay

import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.Point2
import nl.atlas.gallery.featureeditor.engine.isContentHash

/**
 * EDIT-9. Placement is in normalized *original source* coordinates. Local geometry is in
 * units of the shorter source edge, centred at the layer pivot. Neither geometry nor assets
 * are flattened into the photo; every field round-trips through OverlayLayer.props.
 */
data class DecorationPlacement(
    val layerId: String,
    val centerX: Double,
    val centerY: Double,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
) {
    init {
        require(layerId.isNotBlank() && layerId.length <= 128) { "Invalid decoration id" }
        require(centerX.isFinite() && centerX in -2.0..3.0 &&
            centerY.isFinite() && centerY in -2.0..3.0) { "Invalid decoration centre" }
        require(scale.isFinite() && scale in 0.01..100.0) { "Invalid decoration scale" }
        require(rotationDegrees.isFinite() && rotationDegrees in -3600.0..3600.0) { "Invalid decoration rotation" }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Invalid decoration opacity" }
    }

    fun moved(x: Double, y: Double) = copy(centerX = x, centerY = y)
    fun transformed(newScale: Double = scale, degrees: Double = rotationDegrees) =
        copy(scale = newScale, rotationDegrees = degrees)
}

data class InkPoint(val x: Double, val y: Double, val pressure: Double? = null) {
    init {
        require(x.isFinite() && x in -4.0..4.0 && y.isFinite() && y in -4.0..4.0) {
            "Invalid decoration point"
        }
        require(pressure == null || (pressure.isFinite() && pressure in 0.1..4.0)) {
            "Invalid decoration pressure"
        }
    }
    fun asPoint() = Point2(x, y)
}

enum class StrokeMode { PEN, HIGHLIGHTER, ERASER }
enum class ShapeType { LINE, ARROW, RECTANGLE, ELLIPSE }
enum class TextFace { SANS, SERIF, MONO, SYSTEM }
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class TextBackground { NONE, SOLID, ROUNDED }

sealed interface DecorationLayer {
    val placement: DecorationPlacement
}

data class StrokeLayer(
    override val placement: DecorationPlacement,
    val mode: StrokeMode,
    val colorArgb: Int,
    val width: Double,
    val points: List<InkPoint>,
) : DecorationLayer {
    init {
        require(width.isFinite() && width in 0.00001..0.25) { "Invalid stroke width" }
        require(points.isNotEmpty() && points.size <= 10_000) { "Invalid stroke length" }
    }
}

data class ShapeLayer(
    override val placement: DecorationPlacement,
    val shape: ShapeType,
    val start: Point2,
    val end: Point2,
    val colorArgb: Int,
    val width: Double,
    val filled: Boolean = false,
    val snapped: Boolean = false,
) : DecorationLayer {
    init {
        require(width.isFinite() && width in 0.00001..0.25) { "Invalid shape width" }
        require(listOf(start.x, start.y, end.x, end.y).all { it.isFinite() && it in -4.0..4.0 }) {
            "Invalid shape bounds"
        }
    }
}

data class TextLayer(
    override val placement: DecorationPlacement,
    val text: String,
    val face: TextFace,
    val size: Double,
    val colorArgb: Int,
    val align: TextAlign,
    val boxWidth: Double,
    val background: TextBackground = TextBackground.NONE,
    val backgroundArgb: Int = 0,
) : DecorationLayer {
    init {
        require(text.isNotEmpty() && text.length <= 4096 && text.count { it == '\n' } <= 100) {
            "Invalid decoration text"
        }
        require(size.isFinite() && size in 0.005..1.0) { "Invalid text size" }
        require(boxWidth.isFinite() && boxWidth in 0.01..4.0) { "Invalid text box" }
    }
    val boxHeight: Double get() = size * 1.25 * text.split('\n').size
}

data class EmojiLayer(
    override val placement: DecorationPlacement,
    val glyph: String,
    val size: Double,
) : DecorationLayer {
    init {
        require(glyph.isNotBlank() && glyph.length <= 64) { "Invalid emoji" }
        require(size.isFinite() && size in 0.01..2.0) { "Invalid emoji size" }
    }
}

data class StickerLayer(
    override val placement: DecorationPlacement,
    val contentHash: String,
    val width: Double,
    val height: Double,
) : DecorationLayer {
    init {
        require(isContentHash(contentHash)) { "Invalid sticker hash" }
        require(width.isFinite() && width in 0.001..4.0 &&
            height.isFinite() && height in 0.001..4.0) { "Invalid sticker size" }
    }
}

/**
 * Typed, lossless adapter for EDIT-1's unchanged OverlayAdd/Update/Remove and RecipeJson.
 * Keys are versioned. Malformed or unsupported properties are refused, never silently drawn
 * as another tool. User captions and glyphs are never included in exception messages.
 */
object DecorationCodec {
    fun encode(layer: DecorationLayer): OverlayLayer {
        val p = layer.placement
        val props = buildMap<String, String> {
            put("d.version", "1")
            when (layer) {
                is StrokeLayer -> {
                    put("mode", layer.mode.name)
                    put("color", color(layer.colorArgb))
                    put("width", number(layer.width))
                    put("points", layer.points.joinToString(";") {
                        number(it.x) + "," + number(it.y) + "," + (it.pressure?.let(::number) ?: "")
                    })
                }
                is ShapeLayer -> {
                    put("shape", layer.shape.name)
                    put("start", point(layer.start)); put("end", point(layer.end))
                    put("color", color(layer.colorArgb)); put("width", number(layer.width))
                    put("filled", layer.filled.toString()); put("snapped", layer.snapped.toString())
                }
                is TextLayer -> {
                    put("text", layer.text); put("face", layer.face.name); put("size", number(layer.size))
                    put("color", color(layer.colorArgb)); put("align", layer.align.name)
                    put("boxWidth", number(layer.boxWidth))
                    put("background", layer.background.name); put("backgroundColor", color(layer.backgroundArgb))
                }
                is EmojiLayer -> {
                    put("glyph", layer.glyph); put("size", number(layer.size))
                }
                is StickerLayer -> {
                    put("width", number(layer.width)); put("height", number(layer.height))
                }
            }
        }
        val kind = when (layer) {
            is StrokeLayer -> OverlayKind.STROKE
            is ShapeLayer -> OverlayKind.SHAPE
            is TextLayer -> OverlayKind.TEXT
            is EmojiLayer -> OverlayKind.EMOJI
            is StickerLayer -> OverlayKind.STICKER
        }
        return OverlayLayer(p.layerId, kind, p.centerX, p.centerY, p.scale,
            p.rotationDegrees, p.opacity, (layer as? StickerLayer)?.contentHash, props)
    }

    fun decode(raw: OverlayLayer): DecorationLayer {
        require(raw.validate() == null && raw.props["d.version"] == "1") {
            "Invalid decoration layer"
        }
        val p = DecorationPlacement(raw.layerId, raw.centerX, raw.centerY,
            raw.scale, raw.rotationDegrees, raw.opacity)
        val map = raw.props
        return when (raw.kind) {
            OverlayKind.STROKE -> {
                require(raw.contentHash == null) { "Unexpected decoration asset" }
                val tokens = map.need("points")
                require(tokens.length <= 500_000) { "Invalid stroke data" }
                val points = tokens.split(';').map { token ->
                    val parts = token.split(',')
                    require(parts.size == 3) { "Invalid stroke point" }
                    InkPoint(real(parts[0]), real(parts[1]),
                        if (parts[2].isEmpty()) null else real(parts[2]))
                }
                StrokeLayer(p, choice(map.need("mode"), StrokeMode.entries),
                    argb(map.need("color")), real(map.need("width")), points)
            }
            OverlayKind.SHAPE -> {
                require(raw.contentHash == null) { "Unexpected decoration asset" }
                ShapeLayer(p, choice(map.need("shape"), ShapeType.entries),
                    parsePoint(map.need("start")), parsePoint(map.need("end")),
                    argb(map.need("color")), real(map.need("width")),
                    boolean(map.need("filled")), boolean(map.need("snapped")))
            }
            OverlayKind.TEXT -> {
                require(raw.contentHash == null) { "Unexpected decoration asset" }
                TextLayer(p, map.need("text"), choice(map.need("face"), TextFace.entries),
                    real(map.need("size")), argb(map.need("color")),
                    choice(map.need("align"), TextAlign.entries), real(map.need("boxWidth")),
                    choice(map.need("background"), TextBackground.entries),
                    argb(map.need("backgroundColor")))
            }
            OverlayKind.EMOJI -> {
                require(raw.contentHash == null) { "Unexpected decoration asset" }
                EmojiLayer(p, map.need("glyph"), real(map.need("size")))
            }
            OverlayKind.STICKER -> StickerLayer(p,
                raw.contentHash ?: throw IllegalArgumentException("Missing sticker hash"),
                real(map.need("width")), real(map.need("height")))
        }
    }

    private fun Map<String, String>.need(key: String): String =
        this[key] ?: throw IllegalArgumentException("Missing decoration property")
    private fun real(text: String): Double = text.toDoubleOrNull()
        ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid decoration number")
    private fun boolean(text: String): Boolean = when (text) {
        "true" -> true; "false" -> false
        else -> throw IllegalArgumentException("Invalid decoration boolean")
    }
    private fun <T : Enum<T>> choice(text: String, values: List<T>): T =
        values.firstOrNull { it.name == text } ?: throw IllegalArgumentException("Unknown decoration option")
    private fun argb(text: String): Int {
        require(text.length == 8 && text.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid decoration colour"
        }
        return text.toUIntOrNull(16)?.toInt() ?: throw IllegalArgumentException("Invalid decoration colour")
    }
    private fun number(value: Double) = value.toString()
    private fun point(p: Point2) = number(p.x) + "," + number(p.y)
    private fun parsePoint(text: String): Point2 {
        val parts = text.split(',')
        require(parts.size == 2) { "Invalid decoration point" }
        return Point2(real(parts[0]), real(parts[1]))
    }
    private fun color(value: Int) = value.toUInt().toString(16).padStart(8, '0')
}
