package nl.atlas.gallery.featureidentity.engine

import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import java.io.File

/**
 * Identity Studio engine contract (IDS-6, goal #467). See docs/identity-studio/ENGINE_CONTRACT.md.
 *
 * Why this is not an [nl.atlas.gallery.featureaiedit.provider.ImageEditProvider]: identity edits
 * are not instruction edits. They need a target face with landmarks, a person's reference set,
 * a mode (face or whole head) and a warm model kept loaded across candidates. Forcing that into
 * the instruction/poll contract would hide the parts that matter. What fits is reused unchanged:
 * [EditImage] for every image and [CostEstimate] for cost.
 *
 * Pure JVM: no Android, network, clock or thread types, so the whole contract runs in JVM tests.
 * Nothing in this package logs pixels, reference handles or face geometry.
 */

/** FACE changes the inner face. HEAD also changes hair, hairline, head shape, ears and facial hair. */
enum class IdentityEditMode { FACE, HEAD }

/** Where an engine runs. Only LOCAL may be used without an explicit, told route choice. */
enum class EngineRoute { LOCAL, SELF_HOSTED, PAID }

data class FacePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) { "Landmark must be finite" } }
}

/** Pixel box in source coordinates, right/bottom exclusive. */
data class FaceBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) { "Face box must have a positive size" } }
    val width get() = right - left
    val height get() = bottom - top
    fun contains(point: FacePoint) =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    /** Box grown around its centre by [factor], clamped to the image. Used for HEAD regions. */
    fun expanded(factor: Double, imageWidth: Int, imageHeight: Int): FaceBox {
        require(factor >= 1.0 && factor.isFinite())
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        val hw = width * factor / 2.0
        val hh = height * factor / 2.0
        val l = (cx - hw).toInt().coerceIn(0, imageWidth - 1)
        val t = (cy - hh).toInt().coerceIn(0, imageHeight - 1)
        val r = kotlin.math.ceil(cx + hw).toInt().coerceIn(l + 1, imageWidth)
        val b = kotlin.math.ceil(cy + hh).toInt().coerceIn(t + 1, imageHeight)
        return FaceBox(l, t, r, b)
    }
}

/** The face to change: box plus the usual 5 landmarks (eyes, nose tip, mouth corners). */
data class TargetFace(val box: FaceBox, val landmarks: List<FacePoint>) {
    init { require(landmarks.size == LANDMARK_COUNT) { "A target face needs exactly 5 landmarks" } }
    companion object { const val LANDMARK_COUNT = 5 }
}

/**
 * The person's references: opaque handles resolved by the engine adapter, plus the chosen
 * appearance variant (for example "with beard"). Never face templates or embeddings.
 */
data class IdentityReferenceSet(
    val profileId: String,
    val variantId: String?,
    val references: List<EditImage>
) {
    init { require(profileId.isNotBlank()) { "A profile id is required" } }
}

enum class HairChoice { KEEP_ORIGINAL, USE_PERSONS }

data class IdentityPreferences(
    val hair: HairChoice = HairChoice.KEEP_ORIGINAL,
    val keepExpression: Boolean = true,
    val moreNatural: Boolean = false
)

/**
 * What makes one candidate different from another: which references, which seed, how strongly
 * the identity is blended in. [referenceIndices] index the session's full reference list.
 */
data class IdentityVariation(
    val seed: Long,
    val referenceIndices: List<Int>,
    val blendStrength: Double
) {
    init {
        require(referenceIndices.isNotEmpty() && referenceIndices.all { it >= 0 } &&
            referenceIndices.toSet().size == referenceIndices.size) { "Reference subset is invalid" }
        require(blendStrength.isFinite() && blendStrength > 0.0 && blendStrength <= 1.0) {
            "Blend strength must be in (0, 1]"
        }
    }
}

/** One candidate's worth of work. [references] already holds only the chosen subset. */
data class IdentityEditRequest(
    val source: EditImage,
    val target: TargetFace,
    val references: IdentityReferenceSet,
    val mode: IdentityEditMode,
    val preferences: IdentityPreferences,
    val variation: IdentityVariation
)

data class IdentityEngineCapabilities(
    val supportedModes: Set<IdentityEditMode>,
    val maxReferences: Int,
    /** True when one load serves many candidates (the session relies on it for "More"). */
    val reusesWarmState: Boolean,
    val route: EngineRoute,
    val costPerCandidate: CostEstimate,
    val makesNetworkCalls: Boolean,
    val maxSourcePixels: Long
) {
    init {
        require(supportedModes.isNotEmpty()) { "An engine must support at least one mode" }
        require(maxReferences >= 1) { "An engine must accept at least one reference" }
        require(maxSourcePixels >= 1) { "Source pixel limit must be positive" }
        require(costPerCandidate.minorUnits >= 0) { "Cost cannot be negative" }
        if (route == EngineRoute.LOCAL) {
            require(!makesNetworkCalls) { "A local engine must not make network calls" }
            require(costPerCandidate.minorUnits == 0L) { "A local engine must be free per candidate" }
        }
    }
}

/** Things that can go wrong, in engine terms. The session turns each into plain words. */
enum class IdentityFailureKind {
    NO_FACE_FOUND, FACE_TOO_SMALL, ENGINE_NOT_INSTALLED, OUT_OF_MEMORY,
    PHOTO_TOO_LARGE, MODE_UNAVAILABLE, NO_REFERENCES, ROUTE_NOT_APPROVED, UNKNOWN
}

/** Cooperative cancellation. Engines check it between steps and call [throwIfCancelled]. */
fun interface IdentityCancellation {
    fun isCancelled(): Boolean
    fun throwIfCancelled() { if (isCancelled()) throw IdentityCancelledException() }
}

class IdentityCancelledException : RuntimeException("Identity edit cancelled")

/**
 * Engine output. [image] may carry pixels (synthetic engines) or only a handle to [file].
 * [file] must live inside the workspace the session handed to [WarmIdentityEngine.generate].
 * [fingerprint] lets the session reject near-duplicates without decoding the file.
 */
sealed interface IdentityEngineResult {
    data class Success(
        val image: EditImage,
        val file: File?,
        val fingerprint: PerceptualFingerprint
    ) : IdentityEngineResult
    data class Failure(val kind: IdentityFailureKind) : IdentityEngineResult
}

sealed interface EngineLoadResult {
    data class Loaded(val engine: WarmIdentityEngine) : EngineLoadResult
    data class Unavailable(val kind: IdentityFailureKind) : EngineLoadResult
}

/** A loaded model. Kept by the session across candidates and closed after an idle timeout. */
interface WarmIdentityEngine : AutoCloseable {
    /**
     * Makes one candidate. All temporary and output files go inside [workspace], which the session
     * creates and deletes. May throw [IdentityCancelledException] or [OutOfMemoryError].
     */
    fun generate(
        request: IdentityEditRequest,
        workspace: File,
        cancellation: IdentityCancellation
    ): IdentityEngineResult
}

interface IdentityEditEngine {
    val id: String
    val version: String
    fun capabilities(): IdentityEngineCapabilities
    /** Loads model state. Expensive; the session calls it once per warm period. */
    fun load(): EngineLoadResult
}

/**
 * Small luma fingerprint of the edit region: 8x8 box averages, values 0..255.
 * [distance] is the mean absolute difference scaled to 0..1.
 */
data class PerceptualFingerprint(val luma: List<Int>) {
    init { require(luma.size == SIZE * SIZE && luma.all { it in 0..255 }) { "Fingerprint must be 8x8 luma" } }

    fun distance(other: PerceptualFingerprint): Double =
        luma.indices.sumOf { kotlin.math.abs(luma[it] - other.luma[it]) } / (luma.size * 255.0)

    companion object {
        const val SIZE = 8

        /** Fingerprint of [region] in an image whose ARGB [pixels] are row-major [width] wide. */
        fun of(pixels: List<Int>, width: Int, height: Int, region: FaceBox): PerceptualFingerprint {
            require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
            val left = region.left.coerceIn(0, width - 1)
            val top = region.top.coerceIn(0, height - 1)
            val right = region.right.coerceIn(left + 1, width)
            val bottom = region.bottom.coerceIn(top + 1, height)
            val cells = ArrayList<Int>(SIZE * SIZE)
            for (cy in 0 until SIZE) for (cx in 0 until SIZE) {
                val x0 = left + (right - left) * cx / SIZE
                val x1 = maxOf(x0 + 1, left + (right - left) * (cx + 1) / SIZE)
                val y0 = top + (bottom - top) * cy / SIZE
                val y1 = maxOf(y0 + 1, top + (bottom - top) * (cy + 1) / SIZE)
                var sum = 0L
                var n = 0
                for (y in y0 until minOf(y1, height)) for (x in x0 until minOf(x1, width)) {
                    val p = pixels[y * width + x]
                    sum += (((p ushr 16) and 255) * 299 + ((p ushr 8) and 255) * 587 + (p and 255) * 114) / 1000
                    n++
                }
                cells += if (n == 0) 0 else (sum / n).toInt()
            }
            return PerceptualFingerprint(cells)
        }
    }
}
