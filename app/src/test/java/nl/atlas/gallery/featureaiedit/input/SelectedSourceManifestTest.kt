package nl.atlas.gallery.featureaiedit.input

import org.junit.Assert.*
import org.junit.Test

class SelectedSourceManifestTest {
    private fun source(id: String = "source", bytes: Long = 10, granted: Boolean = true, fresh: Boolean = true) =
        SelectedAsset(id, "content://media/$id", bytes, granted, fresh)
    private fun request(
        sources: List<SelectedAsset> = listOf(source()),
        references: List<SelectedAsset> = emptyList(),
        mask: SelectedAsset? = null,
        provider: String = "local-provider",
        keepGps: Boolean = false,
    ) = SelectionAdmissionRequest(sources, references, mask, provider, keepGps = keepGps)

    private fun admitted(request: SelectionAdmissionRequest, limits: AdmissionLimits = AdmissionLimits(2, 100, 200)): SelectedSourceManifest =
        (SelectedSourceAdmission.admit(request, limits) as AdmissionResult.Allowed).manifest
    private fun denied(request: SelectionAdmissionRequest, code: AdmissionDenial, limits: AdmissionLimits = AdmissionLimits(2, 100, 200)) {
        assertEquals(AdmissionResult.Denied(code), SelectedSourceAdmission.admit(request, limits))
    }

    @Test fun exactlyOneExplicitlySelectedSourceRequired() {
        denied(request(sources = emptyList()), AdmissionDenial.SOURCE_COUNT)
        denied(request(sources = listOf(source(), source("other"))), AdmissionDenial.SOURCE_COUNT)
        denied(request(sources = listOf(source(" "))), AdmissionDenial.INVALID_ASSET)
    }

    @Test fun missingOrStaleGrantsDeniedForEveryRole() {
        denied(request(sources = listOf(source(granted = false))), AdmissionDenial.GRANT_INVALID)
        denied(request(sources = listOf(source(fresh = false))), AdmissionDenial.GRANT_INVALID)
        denied(request(references = listOf(source("ref", fresh = false))), AdmissionDenial.GRANT_INVALID)
        denied(request(mask = source("mask", granted = false)), AdmissionDenial.GRANT_INVALID)
    }

    @Test fun duplicateAndSelfReferencesDeniedAcrossRoles() {
        denied(request(references = listOf(source())), AdmissionDenial.DUPLICATE_ASSET)
        denied(request(references = listOf(source("r"), source("r"))), AdmissionDenial.DUPLICATE_ASSET)
        denied(request(references = listOf(source("r")), mask = source("r")), AdmissionDenial.DUPLICATE_ASSET)
        denied(request(mask = source()), AdmissionDenial.DUPLICATE_ASSET)
        denied(request(references = listOf(source("r", bytes = 1).copy(handle = "different", uri = "content://media/source"))), AdmissionDenial.DUPLICATE_ASSET)
    }

    @Test fun explicitLimitsIncludeBoundaryValuesAndCumulativeBytes() {
        val limits = AdmissionLimits(2, 100, 200)
        val manifest = admitted(request(references = listOf(source("r1", 50), source("r2", 50))), limits)
        assertEquals(110L, manifest.totalBytes)
        denied(request(references = listOf(source("r1"), source("r2"), source("r3"))), AdmissionDenial.TOO_MANY_REFERENCES, limits)
        denied(request(sources = listOf(source(bytes = 101))), AdmissionDenial.ASSET_TOO_LARGE, limits)
        denied(request(references = listOf(source("r1", 100), source("r2", 100))), AdmissionDenial.TOTAL_TOO_LARGE, limits)
        assertEquals(200, admitted(request(sources = listOf(source(bytes = 100)), references = listOf(source("r", 100))), limits).totalBytes)
        denied(request(sources = listOf(source(bytes = -1))), AdmissionDenial.INVALID_ASSET)
    }

    @Test fun onlyExplicitInputsAppearAndTheirOrderIsPreserved() {
        val refs = mutableListOf(source("third"), source("first"), source("second"))
        val selected = mutableListOf(source())
        val manifest = admitted(request(sources = selected, references = refs), AdmissionLimits(3, 100, 400))
        assertEquals(listOf("third", "first", "second"), manifest.references.map { it.handle })
        assertEquals("source", manifest.source.handle)
        refs.clear()
        selected.clear()
        assertEquals(3, manifest.references.size)
        assertEquals("source", manifest.source.handle)
    }

    @Test fun gpsDefaultsOffAndMustBeExplicitlyEnabled() {
        assertFalse(admitted(request()).keepGps)
        assertTrue(admitted(request(keepGps = true)).keepGps)
    }

    @Test fun malformedProviderAndUriAreRejectedWithoutEchoingData() {
        denied(request(provider = " "), AdmissionDenial.INVALID_PROVIDER)
        denied(request(sources = listOf(source().copy(uri = "file:///secret/path"))), AdmissionDenial.INVALID_ASSET)
        val privateValue = "content://media/private-photo-123"
        val result = SelectedSourceAdmission.admit(request(references = listOf(source("r").copy(uri = privateValue, grantFresh = false))), AdmissionLimits(2, 100, 200))
        assertEquals(AdmissionResult.Denied(AdmissionDenial.GRANT_INVALID), result)
        assertFalse(result.toString().contains(privateValue))
    }

    @Test fun totalsAreOverflowSafeAndNoGalleryEnumerationParameterExists() {
        denied(request(sources = listOf(source(bytes = Long.MAX_VALUE)), references = listOf(source("r", Long.MAX_VALUE))), AdmissionDenial.ASSET_TOO_LARGE, AdmissionLimits(2, Long.MAX_VALUE - 1, Long.MAX_VALUE))
        denied(request(sources = listOf(source(bytes = Long.MAX_VALUE)), references = listOf(source("r", 1))), AdmissionDenial.TOTAL_TOO_LARGE, AdmissionLimits(2, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
