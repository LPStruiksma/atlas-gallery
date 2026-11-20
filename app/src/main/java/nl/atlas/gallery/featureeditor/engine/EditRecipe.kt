package nl.atlas.gallery.featureeditor.engine

/**
 * The non-destructive description of one photo edit (EDIT-1, goal #629).
 *
 * A recipe never holds pixels. It names the source by size, lists the operations in the order
 * the user made them, and refers to any raster content (AI Edit, Face Swap, Erase results,
 * stickers) by content hash. The editor keeps exactly one recipe per photo and one
 * [EditHistory] over it, so every tool, including the AI tools mounted by EDIT-5, shares the
 * same undo/redo and the same save path ([RenderPlan] -> EDIT-2 render -> EDIT-3 save).
 *
 * Coordinates: rectangles and points are normalized to `0..1`. Geometry crop is in the
 * *display canvas* (after perspective, straighten and orientation). Raster patches, overlay
 * layers and perspective quads are in *source* space, so they stay attached to the picture when
 * the user later rotates, flips, straightens or crops. See `docs/editor/ENGINE.md`.
 */
data class EditRecipe(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val operations: List<EditOperation> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
    }

    /** Problems that make this recipe unrenderable; empty when valid. Never echoes user text. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (schemaVersion != CURRENT_SCHEMA_VERSION) problems += "schema version $schemaVersion"
        val ids = HashSet<String>()
        operations.forEachIndexed { index, op ->
            if (op.id.isBlank()) problems += "operation $index has a blank id"
            if (!ids.add(op.id)) problems += "operation $index repeats an id"
            op.validate()?.let { problems += "operation $index: $it" }
        }
        return problems
    }

    /** Every content hash this recipe needs from the patch/asset store (for keep-alive and GC). */
    fun referencedContentHashes(): Set<String> = operations.flatMapTo(sortedSetOf()) { op ->
        when (op) {
            is EditOperation.RasterPatch -> listOfNotNull(op.contentHash, op.maskHash)
            is EditOperation.Filter -> listOfNotNull(op.lutHash)
            is EditOperation.OverlayAdd -> listOfNotNull(op.layer.contentHash)
            is EditOperation.OverlayUpdate -> listOfNotNull(op.layer.contentHash)
            else -> emptyList()
        }
    }

    /** The recipe with no edits: what "compare to original" and Revert show. */
    fun original(): EditRecipe = copy(operations = emptyList())

    val isEmpty: Boolean get() = operations.isEmpty()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val SCHEMA_NAME = "atlas.edit-recipe"
    }
}

/** A normalized rectangle (`0..1` on both axes, left < right, top < bottom). */
data class NormRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun isValid(): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() && it >= -EPS && it <= 1 + EPS } &&
            right - left > MIN_SIZE && bottom - top > MIN_SIZE

    fun corners(): List<Point2> =
        listOf(Point2(left, top), Point2(right, top), Point2(right, bottom), Point2(left, bottom))

    companion object {
        val FULL = NormRect(0.0, 0.0, 1.0, 1.0)
        const val MIN_SIZE = 1e-4
        private const val EPS = 1e-9
    }
}

/**
 * Four corners in normalized source space, clockwise from top-left. The perspective step pulls
 * these corners to the corners of the picture (keystone correction).
 */
data class Quad(val topLeft: Point2, val topRight: Point2, val bottomRight: Point2, val bottomLeft: Point2) {
    fun points(): List<Point2> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun isIdentity(): Boolean = this == IDENTITY

    /** Finite, strictly convex and clockwise (on screen), with every corner near the picture. */
    fun isValid(): Boolean {
        val p = points()
        if (p.any { !it.x.isFinite() || !it.y.isFinite() || it.x < -1 || it.x > 2 || it.y < -1 || it.y > 2 }) {
            return false
        }
        for (i in 0..3) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 1e-6) return false
        }
        // The rectifying homography must keep the whole picture on one side of its vanishing
        // line, or the warped picture would wrap through infinity. w is 1 at the origin.
        val h = try {
            Matrix3.homography(p, IDENTITY.points())
        } catch (_: IllegalArgumentException) {
            return false
        }
        return IDENTITY.points().all { c -> h[2, 0] * c.x + h[2, 1] * c.y + h[2, 2] > MIN_W }
    }

    companion object {
        val IDENTITY = Quad(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))

        /**
         * Keystone sliders as Samsung's Perspective tool exposes them, each in `-1..1`.
         * Positive [vertical] narrows the top edge (fixes verticals leaning in when shooting up);
         * positive [horizontal] narrows the left edge. Full strength moves a corner 25 %.
         */
        fun fromKeystone(vertical: Double, horizontal: Double): Quad {
            val full = keystoneQuad(vertical.coerceIn(-1.0, 1.0), horizontal.coerceIn(-1.0, 1.0), 1.0)
            if (full.isValid()) return full
            // Strong corrections on both axes can push a corner past the vanishing line; scale
            // both sliders down together to the strongest correction that stays valid.
            var lo = 0.0
            var hi = 1.0
            repeat(40) {
                val mid = (lo + hi) / 2
                if (keystoneQuad(vertical.coerceIn(-1.0, 1.0), horizontal.coerceIn(-1.0, 1.0), mid).isValid()) lo = mid else hi = mid
            }
            return keystoneQuad(vertical.coerceIn(-1.0, 1.0), horizontal.coerceIn(-1.0, 1.0), lo)
        }

        private fun keystoneQuad(vertical: Double, horizontal: Double, factor: Double): Quad {
            val v = vertical * MAX_KEYSTONE * factor
            val h = horizontal * MAX_KEYSTONE * factor
            val top = maxOf(0.0, v); val bottom = maxOf(0.0, -v)
            val left = maxOf(0.0, h); val right = maxOf(0.0, -h)
            return Quad(
                Point2(top, left),
                Point2(1 - top, right),
                Point2(1 - bottom, 1 - right),
                Point2(bottom, 1 - left),
            )
        }

        private const val MAX_KEYSTONE = 0.25
        private const val MIN_W = 0.05
    }
}

/** Colour and light controls. Order is the fixed order the renderer applies them in. */
enum class AdjustParam(val key: String, val min: Double, val max: Double) {
    EXPOSURE("exposure", -1.0, 1.0),
    BRIGHTNESS("brightness", -1.0, 1.0),
    CONTRAST("contrast", -1.0, 1.0),
    HIGHLIGHTS("highlights", -1.0, 1.0),
    SHADOWS("shadows", -1.0, 1.0),
    TEMPERATURE("temperature", -1.0, 1.0),
    TINT("tint", -1.0, 1.0),
    SATURATION("saturation", -1.0, 1.0),
    HUE("hue", -1.0, 1.0),
    DEFINITION("definition", -1.0, 1.0),
    SHARPNESS("sharpness", 0.0, 1.0),
    VIGNETTE("vignette", -1.0, 1.0),
    ;

    companion object {
        fun fromKey(key: String): AdjustParam? = entries.firstOrNull { it.key == key }
    }
}

enum class FlipAxis(val key: String) { HORIZONTAL("horizontal"), VERTICAL("vertical") }

/** Which tool produced a raster patch. All of them share one history and one save path. */
enum class PatchTool(val key: String) {
    AI_EDIT("ai-edit"),
    FACE_SWAP("face-swap"),
    ERASE("erase"),
    RETOUCH("retouch"),
    OTHER("other"),
    ;

    companion object {
        fun fromKey(key: String): PatchTool? = entries.firstOrNull { it.key == key }
    }
}

enum class OverlayKind(val key: String) {
    STROKE("stroke"), TEXT("text"), SHAPE("shape"), STICKER("sticker"), EMOJI("emoji");

    companion object {
        fun fromKey(key: String): OverlayKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * An editable decoration layer (M4). Placement is in normalized source space; [props] carries
 * kind-specific data (text, font, colour, stroke points) as sorted string pairs so JSON stays
 * deterministic. Raster content (sticker art) is referenced by [contentHash].
 */
data class OverlayLayer(
    val layerId: String,
    val kind: OverlayKind,
    val centerX: Double,
    val centerY: Double,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
    val contentHash: String? = null,
    val props: Map<String, String> = emptyMap(),
) {
    fun validate(): String? = when {
        layerId.isBlank() -> "overlay layer id is blank"
        !centerX.isFinite() || !centerY.isFinite() -> "overlay centre is not finite"
        !(scale.isFinite() && scale > 0) -> "overlay scale must be positive"
        !rotationDegrees.isFinite() -> "overlay rotation is not finite"
        !(opacity in 0.0..1.0) -> "overlay opacity out of range"
        contentHash != null && !isContentHash(contentHash) -> "overlay content hash is malformed"
        else -> null
    }
}

/**
 * One user action in the recipe. Every operation has a stable [id] so history entries, UI
 * selection and AI patch bookkeeping can name it.
 */
sealed class EditOperation {
    abstract val id: String

    /** Null when valid; otherwise a short reason that never echoes user text. */
    open fun validate(): String? = null

    /** Quarter turns clockwise relative to the current display (1 = 90 degrees clockwise). */
    data class Rotate90(override val id: String, val quarterTurns: Int) : EditOperation()

    /** Mirror the current display along [axis]. */
    data class Flip(override val id: String, val axis: FlipAxis) : EditOperation()

    /** Absolute straighten angle as the user sees it, clockwise positive, `-45..45` degrees. */
    data class Straighten(override val id: String, val degrees: Double) : EditOperation() {
        override fun validate() =
            if (degrees.isFinite() && degrees in -MAX_STRAIGHTEN..MAX_STRAIGHTEN) null
            else "straighten angle out of range"
    }

    /** Absolute perspective correction; [Quad.IDENTITY] resets it. */
    data class Perspective(override val id: String, val quad: Quad) : EditOperation() {
        override fun validate() = if (quad.isValid()) null else "perspective quad is not convex"
    }

    /**
     * Absolute crop in the display canvas. The renderer uses the largest same-aspect rectangle
     * inside the warped picture if this one would show empty corners. [aspect] is the UI preset
     * label ("free", "1:1", "4:3", ...), kept for the crop tool, not used for maths.
     */
    data class Crop(override val id: String, val rect: NormRect, val aspect: String? = null) : EditOperation() {
        override fun validate() = if (rect.isValid()) null else "crop rectangle invalid"
    }

    /** Clears crop, rotation, flip, straighten and perspective. */
    data class ResetGeometry(override val id: String) : EditOperation()

    /** Absolute value for one control, in the parameter's range. */
    data class Adjust(override val id: String, val param: AdjustParam, val value: Double) : EditOperation() {
        override fun validate() =
            if (value.isFinite() && value in param.min..param.max) null else "adjust value out of range"
    }

    /** Sets every adjustment back to zero. */
    data class ResetAdjustments(override val id: String) : EditOperation()

    /** Look-up-table filter; null [lutId] removes the filter. [strength] in `0..1`. */
    data class Filter(
        override val id: String,
        val lutId: String?,
        val lutHash: String? = null,
        val strength: Double = 1.0,
    ) : EditOperation() {
        override fun validate() = when {
            !(strength.isFinite() && strength in 0.0..1.0) -> "filter strength out of range"
            lutHash != null && !isContentHash(lutHash) -> "filter hash is malformed"
            lutId != null && lutId.isBlank() -> "filter id is blank"
            else -> null
        }
    }

    data class OverlayAdd(override val id: String, val layer: OverlayLayer) : EditOperation() {
        override fun validate() = layer.validate()
    }

    data class OverlayUpdate(override val id: String, val layer: OverlayLayer) : EditOperation() {
        override fun validate() = layer.validate()
    }

    data class OverlayRemove(override val id: String, val layerId: String) : EditOperation() {
        override fun validate() = if (layerId.isBlank()) "overlay layer id is blank" else null
    }

    /**
     * Pixels produced by a tool (AI Edit, Face Swap, Erase, ...), stored outside the recipe and
     * referenced by SHA-256 [contentHash]. They replace [rect] (normalized source space) of the
     * picture, optionally through an alpha [maskHash]. A full-frame AI result uses
     * [NormRect.FULL]. [pixelWidth] x [pixelHeight] is the patch's own size, so the renderer
     * knows its native resolution and can report up- or downscaling.
     */
    data class RasterPatch(
        override val id: String,
        val tool: PatchTool,
        val contentHash: String,
        val rect: NormRect,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val maskHash: String? = null,
    ) : EditOperation() {
        override fun validate() = when {
            !isContentHash(contentHash) -> "patch content hash is malformed"
            maskHash != null && !isContentHash(maskHash) -> "patch mask hash is malformed"
            !rect.isValid() -> "patch rectangle invalid"
            pixelWidth <= 0 || pixelHeight <= 0 -> "patch size must be positive"
            else -> null
        }
    }

    companion object {
        const val MAX_STRAIGHTEN = 45.0
    }
}

/** Lower-case hex SHA-256. */
fun isContentHash(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
