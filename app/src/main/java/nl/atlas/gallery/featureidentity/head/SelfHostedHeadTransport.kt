package nl.atlas.gallery.featureidentity.head

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.selfhost.SelfHostedWire
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.IdentityCancellation
import java.io.File

/**
 * Self-hosted whole-head transport contract (IDS-12C #479, goal #467). See
 * docs/identity-studio/FULL_HEAD.md "Android HEAD engine".
 *
 * The server side is the `flux2-head` backend of tools/ai_edit_server (#1017, #1063): one target
 * image, one full-size head mask, an explicit head crop, reference(s) and a seed in; one
 * crop-sized generated head out. Today that backend is an admission scaffold that reports
 * `full_head = false`, so [HeadSwapEngine] refuses every real configuration with
 * MODE_UNAVAILABLE. Nothing here may treat the stock `fake`, `qwen`, `flux` or `comfy`
 * backends as a head engine: only [HeadAdapterIdentity.HEAD_PROVIDER_ID] is accepted.
 *
 * Pure JVM. No pixels, reference handles, URLs or tokens are logged or put in exception text.
 */

/** The adapter and model the user accepted for whole-head edits. Admission must match it exactly. */
data class HeadAdapterIdentity(
    val providerId: String = HEAD_PROVIDER_ID,
    val modelId: String = HEAD_MODEL_ID,
    /** SHA-256 of the admitted head-swap adapter, lowercase hex, as the server advertises it. */
    val adapterSha256: String,
    /** References one candidate sends. The admitted graph (#1063) connects exactly one today. */
    val maxReferences: Int = 1
) {
    init {
        require(providerId == HEAD_PROVIDER_ID) { "Only the dedicated head backend can serve whole-head edits" }
        require(modelId.isNotBlank() && modelId.length <= 128) { "A model id is required" }
        require(SHA256.matches(adapterSha256)) { "Adapter identity must be a lowercase SHA-256" }
        require(maxReferences in 1..MAX_REFERENCES) { "A head edit sends 1 to $MAX_REFERENCES references" }
    }

    companion object {
        const val HEAD_PROVIDER_ID = "self-hosted-flux2-head"
        const val HEAD_MODEL_ID = "flux.2-klein-4b+verified-head-adapter"
        const val MAX_REFERENCES = 4
        internal val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * The user's explicit route choice. Whole-head edits run only on an approved SELF_HOSTED route;
 * there is no implicit route and no fallback to FACE, PAID or LOCAL.
 */
data class HeadRouteApproval(
    val route: EngineRoute,
    val userApproved: Boolean,
    val adapter: HeadAdapterIdentity
)

/** What a head server says it can do, decoded from `GET /v1/capabilities` (protocol 1.0). */
data class HeadServerCapabilities(
    val providerId: String,
    val modelIds: List<String>,
    val fullHead: Boolean,
    val maskEdit: Boolean,
    val seed: Boolean,
    val maxReferences: Int,
    /** Applies to the target, the mask and every reference, as the server enforces. */
    val maxInputPixels: Long,
    /** Ceiling for the returned crop. */
    val maxOutputPixels: Long,
    val adapterSha256: String?,
    val minCropEdge: Int,
    val maxCropEdge: Int
) {
    companion object {
        /**
         * Strict decode. Anything malformed returns null, which the engine treats as unavailable.
         * `features.full_head` missing means false. `features.head_adapter.sha256` is the adapter
         * identity the activation unit (#1090/#1092) must advertise; without it nothing admits.
         */
        fun fromProtocolJson(json: Map<String, Any?>?): HeadServerCapabilities? {
            if (json == null || SelfHostedWire.int(json["schema_version"], 1, 1) != 1 ||
                json["protocol_version"] != "1.0") return null
            val provider = SelfHostedWire.safeId(json["provider_id"]) ?: return null
            val models = SelfHostedWire.list(json["models"])?.map { raw ->
                (SelfHostedWire.map(raw)?.get("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: return null
            } ?: return null
            val f = SelfHostedWire.map(json["features"]) ?: return null
            val fullHead = when (val v = f["full_head"]) { null -> false; is Boolean -> v; else -> return null }
            val mask = f["mask_edit"] as? Boolean ?: return null
            val seed = f["seed"] as? Boolean ?: return null
            val refs = SelfHostedWire.int(SelfHostedWire.map(f["reference_images"])?.get("max_count"), 0, 10)
                ?: return null
            val pixels = SelfHostedWire.long(f["max_input_pixels"], 1, 100_000_000) ?: return null
            val outPixels = SelfHostedWire.long(f["max_output_pixels"], 1, 100_000_000) ?: return null
            val adapter = when (val raw = f["head_adapter"]) {
                null -> null
                else -> (SelfHostedWire.map(raw)?.get("sha256") as? String)
                    ?.takeIf { HeadAdapterIdentity.SHA256.matches(it) } ?: return null
            }
            var minEdge = DEFAULT_MIN_CROP_EDGE
            var maxEdge = DEFAULT_MAX_CROP_EDGE
            for (raw in SelfHostedWire.list(json["advanced_parameters"]) ?: emptyList()) {
                val p = SelfHostedWire.map(raw) ?: return null
                if (p["name"] == "head_width" || p["name"] == "head_height") {
                    // An advertised limit that cannot be read fails admission; never default it away.
                    if (p["minimum"] != null)
                        minEdge = maxOf(minEdge, SelfHostedWire.int(p["minimum"], 1, 65_535) ?: return null)
                    if (p["maximum"] != null)
                        maxEdge = minOf(maxEdge, SelfHostedWire.int(p["maximum"], 1, 65_535) ?: return null)
                }
            }
            if (minEdge > maxEdge) return null
            return HeadServerCapabilities(provider, models, fullHead, mask, seed, refs, pixels, outPixels, adapter, minEdge, maxEdge)
        }

        const val DEFAULT_MIN_CROP_EDGE = 32
        const val DEFAULT_MAX_CROP_EDGE = 2048
    }
}

enum class HeadTransportFailure { OFFLINE, TIMEOUT, AUTH, SERVER, PROTOCOL }

sealed interface HeadHandshake {
    data class Reachable(val capabilities: HeadServerCapabilities) : HeadHandshake
    data class Unreachable(val reason: HeadTransportFailure) : HeadHandshake
}

/**
 * One head job. [mask] is full-size and selects pixels only inside [crop]; [references] already
 * holds the chosen subset; [seed] is 0..4294967295 as the server requires. Any temporary upload
 * file goes in [workspace], which the candidate session deletes.
 */
class HeadTransportRequest(
    val target: EditImage,
    val mask: HeadMask,
    val crop: HeadCrop,
    val references: List<EditImage>,
    val seed: Long,
    val instruction: String,
    val workspace: File
) {
    init {
        require(seed in 0..MAX_SEED) { "Seed outside the protocol range" }
        require(references.isNotEmpty()) { "A head job needs a reference" }
        require(mask.width == target.width && mask.height == target.height) { "Mask must match the target" }
        require(crop.right <= target.width && crop.bottom <= target.height) { "Crop exceeds the target" }
    }

    companion object { const val MAX_SEED = 4_294_967_295L }
}

sealed interface HeadTransportResult {
    /** The generated head crop, ARGB row-major, plus the identity of what produced it. */
    class Success(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val providerId: String,
        val modelId: String,
        val adapterSha256: String
    ) : HeadTransportResult
    data class Refused(val code: String) : HeadTransportResult
    data class Failed(val reason: HeadTransportFailure) : HeadTransportResult
    data object Cancelled : HeadTransportResult
}

/**
 * Wire access to the selected self-hosted head server. Implementations keep bearer auth, TLS for
 * remote hosts, no redirects, bounded uploads and server-side cancel (as AtlasProtocolProvider
 * does), strip metadata from every upload and never retry a submit whose reply was lost.
 */
interface SelfHostedHeadTransport {
    fun handshake(): HeadHandshake
    fun submit(request: HeadTransportRequest, cancellation: IdentityCancellation): HeadTransportResult
}
