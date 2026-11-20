package nl.atlas.gallery.featureaiedit.reference

import java.util.Collections
import nl.atlas.gallery.featureaiedit.input.SelectedAsset
import nl.atlas.gallery.featureaiedit.provider.CapabilityKey
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.OptionValue
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities

/**
 * Explicitly selected handle and its separately inspected image metadata. Neither field is
 * read here; callers must revalidate grants and source bytes at the eventual IO boundary.
 */
data class ReferenceCandidate(val asset: SelectedAsset, val image: EditImage)

data class ReferenceSelectionRequest(
    val source: ReferenceCandidate,
    val references: List<ReferenceCandidate>,
    /** The provider chosen when the user selected these references. Never silently switch. */
    val providerId: String,
    val options: Map<CapabilityKey, OptionValue> = emptyMap(),
)

/** Do not include sensitive handles or content URIs in UI-readable diagnostics. */
enum class ReferenceSelectionDenial {
    INVALID_PROVIDER, PROVIDER_CHANGED, INVALID_CAPABILITIES,
    INVALID_SOURCE, SOURCE_GRANT, SOURCE_FORMAT, SOURCE_PIXELS,
    REFERENCES_UNSUPPORTED, TOO_MANY_REFERENCES,
    INVALID_REFERENCE, REFERENCE_GRANT, SELF_REFERENCE, DUPLICATE_REFERENCE,
    REFERENCE_FORMAT, REFERENCE_PIXELS, UNKNOWN_OPTION, INVALID_OPTION,
    SEED_UNSUPPORTED,
}

/**
 * Read-only snapshot. Copies protect against callers changing selection order or option values
 * after admission. Nothing is automatically omitted, downscaled or submitted.
 */
class ReferenceSelectionPlan internal constructor(
    val providerId: String,
    val source: ReferenceCandidate,
    references: List<ReferenceCandidate>,
    options: Map<CapabilityKey, OptionValue>,
) {
    val references: List<ReferenceCandidate> = Collections.unmodifiableList(ArrayList(references))
    val options: Map<CapabilityKey, OptionValue> =
        Collections.unmodifiableMap(LinkedHashMap(options))

    override fun toString(): String = "ReferenceSelectionPlan(redacted)"
}

sealed interface ReferenceSelectionResult {
    class Allowed(val plan: ReferenceSelectionPlan) : ReferenceSelectionResult {
        override fun toString(): String = "Allowed(redacted)"
    }

    data class Denied(val reason: ReferenceSelectionDenial) : ReferenceSelectionResult
}

object ReferenceSelectionPlanner {
    /**
     * Pure capability gate over exactly the chosen provider and caller-selected assets.
     * Does not enumerate the gallery, open Android permissions, or perform network transfers.
     */
    fun plan(
        request: ReferenceSelectionRequest,
        activeProviderId: String,
        capabilities: ProviderCapabilities,
    ): ReferenceSelectionResult {
        fun deny(reason: ReferenceSelectionDenial) = ReferenceSelectionResult.Denied(reason)
        fun validProvider(id: String) =
            id.isNotBlank() && id.length <= 128 && id.none { it.isISOControl() }
        if (!validProvider(request.providerId) || !validProvider(activeProviderId)) {
            return deny(ReferenceSelectionDenial.INVALID_PROVIDER)
        }
        if (request.providerId != activeProviderId) {
            return deny(ReferenceSelectionDenial.PROVIDER_CHANGED)
        }
        if (capabilities.maxReferences < 0 || capabilities.maxInputPixels <= 0L ||
            capabilities.inputFormats.isEmpty()
        ) return deny(ReferenceSelectionDenial.INVALID_CAPABILITIES)

        fun valid(candidate: ReferenceCandidate): Boolean {
            val asset = candidate.asset
            val image = candidate.image
            return asset.handle.isNotBlank() && asset.handle.length <= 512 &&
                asset.uri.startsWith("content://") && asset.uri.length <= 8192 &&
                asset.uri.none { it.isWhitespace() || it.isISOControl() } &&
                asset.declaredBytes >= 0 && image.handle == asset.handle &&
                image.width > 0 && image.height > 0 &&
                (image.pixels.isEmpty() ||
                    image.pixels.size.toLong() == image.width.toLong() * image.height.toLong())
        }
        fun hasGrant(candidate: ReferenceCandidate) =
            candidate.asset.hasReadGrant && candidate.asset.grantFresh

        if (!valid(request.source)) return deny(ReferenceSelectionDenial.INVALID_SOURCE)
        if (!hasGrant(request.source)) return deny(ReferenceSelectionDenial.SOURCE_GRANT)
        if (request.source.image.format !in capabilities.inputFormats) {
            return deny(ReferenceSelectionDenial.SOURCE_FORMAT)
        }
        if (request.source.image.width.toLong() * request.source.image.height >
            capabilities.maxInputPixels
        ) return deny(ReferenceSelectionDenial.SOURCE_PIXELS)

        if (request.references.isNotEmpty() && capabilities.maxReferences == 0) {
            return deny(ReferenceSelectionDenial.REFERENCES_UNSUPPORTED)
        }
        if (request.references.size > capabilities.maxReferences) {
            return deny(ReferenceSelectionDenial.TOO_MANY_REFERENCES)
        }
        val seenHandles = hashSetOf(request.source.asset.handle)
        val seenUris = hashSetOf(request.source.asset.uri)
        for (candidate in request.references) {
            if (!valid(candidate)) return deny(ReferenceSelectionDenial.INVALID_REFERENCE)
            if (!hasGrant(candidate)) return deny(ReferenceSelectionDenial.REFERENCE_GRANT)
            if (candidate.asset.handle == request.source.asset.handle ||
                candidate.asset.uri == request.source.asset.uri
            ) return deny(ReferenceSelectionDenial.SELF_REFERENCE)
            if (!seenHandles.add(candidate.asset.handle) || !seenUris.add(candidate.asset.uri)) {
                return deny(ReferenceSelectionDenial.DUPLICATE_REFERENCE)
            }
            if (candidate.image.format !in capabilities.inputFormats) {
                return deny(ReferenceSelectionDenial.REFERENCE_FORMAT)
            }
            if (candidate.image.width.toLong() * candidate.image.height >
                capabilities.maxInputPixels
            ) return deny(ReferenceSelectionDenial.REFERENCE_PIXELS)
        }
        for ((key, value) in request.options) {
            if (key.name == "seed") {
                if (!capabilities.supportsSeed) return deny(ReferenceSelectionDenial.SEED_UNSUPPORTED)
                if (value !is OptionValue.IntegerValue) {
                    return deny(ReferenceSelectionDenial.INVALID_OPTION)
                }
            } else {
                val schema = capabilities.advancedParameters[key]
                    ?: return deny(ReferenceSelectionDenial.UNKNOWN_OPTION)
                if (!schema.accepts(value)) return deny(ReferenceSelectionDenial.INVALID_OPTION)
            }
        }
        return ReferenceSelectionResult.Allowed(
            ReferenceSelectionPlan(
                request.providerId,
                request.source,
                request.references,
                request.options,
            )
        )
    }
}
