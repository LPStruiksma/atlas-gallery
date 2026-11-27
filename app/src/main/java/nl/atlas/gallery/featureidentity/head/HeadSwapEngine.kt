package nl.atlas.gallery.featureidentity.head

import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureidentity.engine.EngineLoadResult
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.FakeIdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityCancellation
import nl.atlas.gallery.featureidentity.engine.IdentityCancelledException
import nl.atlas.gallery.featureidentity.engine.IdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityEditRequest
import nl.atlas.gallery.featureidentity.engine.IdentityEngineCapabilities
import nl.atlas.gallery.featureidentity.engine.IdentityEngineResult
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.PerceptualFingerprint
import nl.atlas.gallery.featureidentity.engine.WarmIdentityEngine
import java.io.File

/** Decodes the selected source to ARGB and encodes the result. Android supplies a bitmap codec. */
interface HeadImageCodec {
    /** Row-major ARGB of [image] at its full size, or null when it cannot be read. */
    fun decode(image: EditImage): IntArray?
    /** PNG without metadata. */
    fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray
}

/** Codec for synthetic JVM fixtures: reads [EditImage.pixels] only. */
object SyntheticHeadImageCodec : HeadImageCodec {
    override fun decode(image: EditImage): IntArray? =
        image.pixels.takeIf { it.size.toLong() == image.width.toLong() * image.height }?.toIntArray()
    override fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray =
        FakeIdentityEditEngine.encodePng(pixels.asList(), width, height)
}

/**
 * Identity Studio whole-head engine (IDS-12C #479): [IdentityEditMode.HEAD] over an explicitly
 * approved SELF_HOSTED FLUX.2 klein 4B head adapter. See docs/identity-studio/FULL_HEAD.md.
 *
 * Gate: [load] admits only when the route is SELF_HOSTED and user-approved, the server is the
 * dedicated head backend, it advertises `full_head`, a mask, a seed, enough references and the
 * exact adapter SHA-256 the user accepted. Until the #1092 verdict lets the server admit
 * `full_head`, every real configuration gets MODE_UNAVAILABLE. Disconnect, timeout or refusal is
 * a failure of this engine only: it never falls back to FACE, PAID or LOCAL.
 *
 * Per candidate: crop the whole head (hair, hairline, ears, facial hair, neck boundary), send
 * the crop, a prior head mask, the chosen references and the variation seed, then paste the
 * returned crop into a copy of the full-resolution source with a change-following mask
 * ([HeadMaskComposer]). Pixels outside the head crop are bit-identical to the source. The output
 * PNG is written only inside the session's private workspace; the original is never touched.
 */
class HeadSwapEngine(
    private val approval: HeadRouteApproval?,
    private val transport: SelfHostedHeadTransport?,
    private val codec: HeadImageCodec = SyntheticHeadImageCodec
) : IdentityEditEngine {

    override val id = ENGINE_ID
    /** Pins the adapter in saved lineage: the same engine with another adapter is another version. */
    override val version: String =
        approval?.let { "$ENGINE_VERSION+adapter-${it.adapter.adapterSha256.take(12)}" } ?: ENGINE_VERSION

    override fun capabilities() = IdentityEngineCapabilities(
        supportedModes = setOf(IdentityEditMode.HEAD),
        maxReferences = approval?.adapter?.maxReferences ?: 1,
        reusesWarmState = true,
        route = EngineRoute.SELF_HOSTED,
        costPerCandidate = CostEstimate("EUR", 0, "Runs on your own computer; no charge per candidate"),
        makesNetworkCalls = true,
        maxSourcePixels = MAX_SOURCE_PIXELS
    )

    override fun load(): EngineLoadResult {
        val chosen = approval
        if (chosen == null || chosen.route != EngineRoute.SELF_HOSTED || !chosen.userApproved)
            return EngineLoadResult.Unavailable(IdentityFailureKind.ROUTE_NOT_APPROVED)
        val wire = transport ?: return EngineLoadResult.Unavailable(IdentityFailureKind.MODE_UNAVAILABLE)
        val handshake = try { wire.handshake() } catch (e: Exception) {
            HeadHandshake.Unreachable(HeadTransportFailure.PROTOCOL)
        }
        val caps = (handshake as? HeadHandshake.Reachable)?.capabilities
            ?: return EngineLoadResult.Unavailable(IdentityFailureKind.MODE_UNAVAILABLE)
        if (!admits(caps, chosen.adapter)) return EngineLoadResult.Unavailable(IdentityFailureKind.MODE_UNAVAILABLE)
        return EngineLoadResult.Loaded(Warm(wire, caps, chosen.adapter))
    }

    private inner class Warm(
        private val wire: SelfHostedHeadTransport,
        private val caps: HeadServerCapabilities,
        private val adapter: HeadAdapterIdentity
    ) : WarmIdentityEngine {
        @Volatile private var closed = false

        override fun close() { closed = true }

        override fun generate(
            request: IdentityEditRequest,
            workspace: File,
            cancellation: IdentityCancellation
        ): IdentityEngineResult {
            check(!closed) { "Engine was released" }
            if (request.mode != IdentityEditMode.HEAD) return fail(IdentityFailureKind.MODE_UNAVAILABLE)
            val refs = request.references.references
            if (refs.isEmpty()) return fail(IdentityFailureKind.NO_REFERENCES)
            if (refs.size > adapter.maxReferences) return fail(IdentityFailureKind.UNKNOWN)
            val source = request.source
            val pixelsCount = source.width.toLong() * source.height
            if (pixelsCount > minOf(caps.maxInputPixels, MAX_SOURCE_PIXELS))
                return fail(IdentityFailureKind.PHOTO_TOO_LARGE)
            val face = request.target.box
            if (face.width < MIN_FACE_EDGE || face.height < MIN_FACE_EDGE) return fail(IdentityFailureKind.FACE_TOO_SMALL)
            val crop = HeadMaskComposer.headCrop(request.target, source.width, source.height)
            if (crop.width < caps.minCropEdge || crop.height < caps.minCropEdge)
                return fail(IdentityFailureKind.FACE_TOO_SMALL)
            if (crop.width > caps.maxCropEdge || crop.height > caps.maxCropEdge)
                return fail(IdentityFailureKind.PHOTO_TOO_LARGE)
            cancellation.throwIfCancelled()

            val original = codec.decode(source)?.takeIf { it.size.toLong() == pixelsCount }
                ?: return fail(IdentityFailureKind.UNKNOWN)
            val prior = HeadMaskComposer.priorMask(request.target, crop, source.width, source.height)
            val job = HeadTransportRequest(
                target = source,
                mask = prior,
                crop = crop,
                references = refs,
                seed = request.variation.seed and HeadTransportRequest.MAX_SEED,
                instruction = INSTRUCTION,
                workspace = workspace
            )
            cancellation.throwIfCancelled()
            val reply = try { wire.submit(job, cancellation) } catch (e: IdentityCancelledException) {
                throw e
            } catch (e: Exception) {
                return fail(IdentityFailureKind.UNKNOWN)
            }
            cancellation.throwIfCancelled()
            val generated = when (reply) {
                HeadTransportResult.Cancelled -> throw IdentityCancelledException()
                is HeadTransportResult.Refused, is HeadTransportResult.Failed -> return fail(IdentityFailureKind.UNKNOWN)
                is HeadTransportResult.Success -> reply
            }
            if (generated.providerId != adapter.providerId || generated.modelId != adapter.modelId ||
                generated.adapterSha256 != adapter.adapterSha256) {
                // The server swapped what runs behind the accepted identity: stop using it.
                closed = true
                return fail(IdentityFailureKind.MODE_UNAVAILABLE)
            }
            if (generated.width != crop.width || generated.height != crop.height ||
                generated.pixels.size != crop.width * crop.height) return fail(IdentityFailureKind.UNKNOWN)

            val alpha = HeadMaskComposer.changeMask(
                HeadMaskComposer.cut(original, source.width, crop), generated.pixels, crop, prior
            )
            val out = HeadMaskComposer.composite(original, source.width, source.height, crop, generated.pixels, alpha)
            cancellation.throwIfCancelled()
            val file = File(workspace, OUTPUT_NAME)
            file.writeBytes(codec.encodePng(out, source.width, source.height))
            val list = out.asList()
            return IdentityEngineResult.Success(
                image = EditImage(
                    handle = file.path,
                    width = source.width,
                    height = source.height,
                    format = ImageFormat.PNG,
                    // Pixels ride along only for synthetic fixtures, like the source did.
                    pixels = if (source.pixels.isNotEmpty()) list else emptyList()
                ),
                file = file,
                fingerprint = PerceptualFingerprint.of(list, source.width, source.height, crop.asFaceBox())
            )
        }
    }

    companion object {
        const val ENGINE_ID = "atlas-head-self-hosted"
        const val ENGINE_VERSION = "1"
        const val MAX_SOURCE_PIXELS = 4_194_304L
        const val MIN_FACE_EDGE = 24
        const val OUTPUT_NAME = "head-candidate.png"
        const val INSTRUCTION = "Replace the whole head (hair, hairline, head shape, ears and facial hair) " +
            "with the reference person's. Keep the target's pose, head size, lighting and neck."

        /** Admission. Every condition must hold; a stock backend never qualifies. */
        fun admits(caps: HeadServerCapabilities, adapter: HeadAdapterIdentity): Boolean =
            caps.providerId == HeadAdapterIdentity.HEAD_PROVIDER_ID &&
                caps.providerId == adapter.providerId &&
                adapter.modelId in caps.modelIds &&
                caps.fullHead && caps.maskEdit && caps.seed &&
                caps.adapterSha256 == adapter.adapterSha256 &&
                caps.maxReferences >= adapter.maxReferences

        private fun fail(kind: IdentityFailureKind) = IdentityEngineResult.Failure(kind)
    }
}
