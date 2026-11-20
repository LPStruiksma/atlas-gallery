package nl.atlas.gallery.featureaiedit.input

import java.util.Collections

/**
 * Explicit caller-provided asset descriptor. Grant facts are snapshots: the eventual IO boundary
 * must re-check Android access/revision immediately before reading or uploading any bytes.
 * This pure policy neither accesses a resolver nor scans MediaStore or contacts a provider.
 */
data class SelectedAsset(
    val handle: String,
    val uri: String,
    val declaredBytes: Long,
    val hasReadGrant: Boolean,
    val grantFresh: Boolean,
)

/** Limits must be supplied from a provider's supported bounds, not inferred from the gallery. */
data class AdmissionLimits(
    val maxReferences: Int,
    val maxBytesPerAsset: Long,
    val maxTotalBytes: Long,
)

data class SelectionAdmissionRequest(
    val selectedSources: List<SelectedAsset>,
    val explicitlyAddedReferences: List<SelectedAsset>,
    val explicitlyAddedMask: SelectedAsset?,
    val providerId: String,
    val keepGps: Boolean = false,
)

/**
 * A read-only snapshot of exactly one selected source plus optional explicitly added assets.
 * Java unmodifiable lists prevent a Kotlin caller from casting and mutating admitted references.
 * No prompts, image content, tokens, or credentials belong in this manifest.
 */
class SelectedSourceManifest internal constructor(
    val source: SelectedAsset,
    references: List<SelectedAsset>,
    val mask: SelectedAsset?,
    val providerId: String,
    val keepGps: Boolean,
    val totalBytes: Long,
) {
    val references: List<SelectedAsset> = Collections.unmodifiableList(ArrayList(references))

    // Prevent accidental disclosure of private URI/handle fields in diagnostics.
    override fun toString(): String = "SelectedSourceManifest(redacted)"
}

enum class AdmissionDenial {
    SOURCE_COUNT,
    INVALID_PROVIDER,
    INVALID_LIMITS,
    TOO_MANY_REFERENCES,
    INVALID_ASSET,
    GRANT_INVALID,
    DUPLICATE_ASSET,
    ASSET_TOO_LARGE,
    TOTAL_TOO_LARGE,
}

sealed interface AdmissionResult {
    class Allowed(val manifest: SelectedSourceManifest) : AdmissionResult {
        override fun toString(): String = "Allowed(redacted)"
    }

    data class Denied(val code: AdmissionDenial) : AdmissionResult
}

object SelectedSourceAdmission {
    /**
     * Pure, deterministic fail-closed admission: never enumerate a gallery or infer additions.
     * The caller must explicitly supply every reference/mask and a fresh permission snapshot.
     */
    fun admit(request: SelectionAdmissionRequest, limits: AdmissionLimits): AdmissionResult {
        if (request.selectedSources.size != 1) return AdmissionResult.Denied(AdmissionDenial.SOURCE_COUNT)
        if (request.providerId.isBlank() || request.providerId.length > 128 ||
            request.providerId.any { it.isISOControl() }
        ) return AdmissionResult.Denied(AdmissionDenial.INVALID_PROVIDER)
        if (limits.maxReferences < 0 || limits.maxBytesPerAsset <= 0 || limits.maxTotalBytes <= 0) {
            return AdmissionResult.Denied(AdmissionDenial.INVALID_LIMITS)
        }
        if (request.explicitlyAddedReferences.size > limits.maxReferences) {
            return AdmissionResult.Denied(AdmissionDenial.TOO_MANY_REFERENCES)
        }

        val allAssets = ArrayList<SelectedAsset>(2 + request.explicitlyAddedReferences.size)
        allAssets.add(request.selectedSources.single())
        allAssets.addAll(request.explicitlyAddedReferences)
        request.explicitlyAddedMask?.let(allAssets::add)

        val seenHandles = HashSet<String>()
        val seenUris = HashSet<String>()
        var totalBytes = 0L
        for (asset in allAssets) {
            if (asset.handle.isBlank() || asset.handle.length > 512 ||
                asset.uri.isBlank() || asset.uri.length > 8192 ||
                !asset.uri.startsWith("content://") ||
                asset.uri.any { it.isWhitespace() || it.isISOControl() } ||
                asset.declaredBytes < 0
            ) return AdmissionResult.Denied(AdmissionDenial.INVALID_ASSET)
            if (!asset.hasReadGrant || !asset.grantFresh) {
                return AdmissionResult.Denied(AdmissionDenial.GRANT_INVALID)
            }
            if (!seenHandles.add(asset.handle) || !seenUris.add(asset.uri)) {
                return AdmissionResult.Denied(AdmissionDenial.DUPLICATE_ASSET)
            }
            if (asset.declaredBytes > limits.maxBytesPerAsset) {
                return AdmissionResult.Denied(AdmissionDenial.ASSET_TOO_LARGE)
            }
            if (asset.declaredBytes > limits.maxTotalBytes - totalBytes) {
                return AdmissionResult.Denied(AdmissionDenial.TOTAL_TOO_LARGE)
            }
            totalBytes += asset.declaredBytes
        }

        return AdmissionResult.Allowed(
            SelectedSourceManifest(
                source = allAssets[0],
                references = request.explicitlyAddedReferences,
                mask = request.explicitlyAddedMask,
                providerId = request.providerId,
                keepGps = request.keepGps,
                totalBytes = totalBytes,
            )
        )
    }
}
