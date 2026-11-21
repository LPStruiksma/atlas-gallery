package nl.atlas.gallery.featureaiedit.reference

import nl.atlas.gallery.featureaiedit.input.SelectedAsset
import nl.atlas.gallery.featureaiedit.provider.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceSelectionPlanTest {
    private fun asset(id: String, granted: Boolean = true, fresh: Boolean = true, bytes: Long = 12) =
        SelectedAsset(id, "content://media/$id", bytes, granted, fresh)
    private fun image(id: String, width: Int = 10, height: Int = 10, format: ImageFormat = ImageFormat.PNG) =
        EditImage(id, width, height, format)
    private fun caps(
        max: Int = 2,
        formats: Set<ImageFormat> = setOf(ImageFormat.PNG),
        pixels: Long = 100,
        options: Map<CapabilityKey, OptionSchema> = mapOf(
            CapabilityKey("strength") to OptionSchema.DecimalRange(0.0, 1.0),
            CapabilityKey("steps") to OptionSchema.IntegerRange(1, 20)
        )
    ) = ProviderCapabilities(
        supportsInstructionEdit = true, supportsMaskEdit = false,
        maxReferences = max, maxCandidates = 1, supportsMultiTurn = false, supportsSeed = false,
        maxInputPixels = pixels, maxOutputPixels = 100, aspectRange = null,
        inputFormats = formats, outputFormats = setOf(ImageFormat.PNG),
        supportsAsync = false, supportsStreaming = false, advancedParameters = options
    )
    private fun request(
        refs: List<ReferenceCandidate> = emptyList(),
        provider: String = "provider-a",
        options: Map<CapabilityKey, OptionValue> = emptyMap(),
        source: ReferenceCandidate = ReferenceCandidate(asset("source"), image("source"))
    ) = ReferenceSelectionRequest(source, refs, provider, options)
    private fun ref(id: String, width: Int = 10, height: Int = 10, format: ImageFormat = ImageFormat.PNG,
                    granted: Boolean = true, fresh: Boolean = true) =
        ReferenceCandidate(asset(id, granted, fresh), image(id, width, height, format))
    private fun accepted(request: ReferenceSelectionRequest, capabilities: ProviderCapabilities = caps(),
                         provider: String = "provider-a"): ReferenceSelectionPlan =
        (ReferenceSelectionPlanner.plan(request, provider, capabilities) as ReferenceSelectionResult.Allowed).plan
    private fun denied(request: ReferenceSelectionRequest, reason: ReferenceSelectionDenial,
                       capabilities: ProviderCapabilities = caps(), provider: String = "provider-a") {
        assertEquals(reason, (ReferenceSelectionPlanner.plan(request, provider, capabilities)
            as ReferenceSelectionResult.Denied).reason)
    }

    @Test fun zeroOneAndMaximumReferencesRetainCallerOrder() {
        assertTrue(accepted(request()).references.isEmpty())
        assertEquals(listOf("a"), accepted(request(listOf(ref("a")))).references.map { it.asset.handle })
        val mutable = mutableListOf(ref("z"), ref("a"))
        val plan = accepted(request(mutable))
        mutable.clear()
        assertEquals(listOf("z", "a"), plan.references.map { it.asset.handle })
        try {
            @Suppress("UNCHECKED_CAST")
            (plan.references as MutableList<ReferenceCandidate>).clear()
            fail("Selection must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun maxPlusOneDisabledAndInvalidCapabilityLimitsDenied() {
        denied(request(listOf(ref("a"), ref("b"), ref("c"))), ReferenceSelectionDenial.TOO_MANY_REFERENCES)
        denied(request(listOf(ref("a"))), ReferenceSelectionDenial.REFERENCES_UNSUPPORTED, caps(max = 0))
        denied(request(), ReferenceSelectionDenial.INVALID_CAPABILITIES, caps(max = -1))
        denied(request(listOf(ref("a"))), ReferenceSelectionDenial.INVALID_CAPABILITIES, caps(pixels = 0))
    }

    @Test fun unsupportedFormatsPixelLimitsAndOverflowDeniedWithoutResizing() {
        denied(request(listOf(ref("a", format = ImageFormat.JPEG))), ReferenceSelectionDenial.REFERENCE_FORMAT)
        denied(request(listOf(ref("a", width = 11))), ReferenceSelectionDenial.REFERENCE_PIXELS)
        denied(request(listOf(ref("a", width = Int.MAX_VALUE, height = Int.MAX_VALUE))), ReferenceSelectionDenial.REFERENCE_PIXELS)
        denied(request(listOf(ref("a", width = -1))), ReferenceSelectionDenial.INVALID_REFERENCE)
        denied(request(listOf(ref("a").copy(image = image("a", 1, 1).copy(pixels = listOf(1, 2))))),
            ReferenceSelectionDenial.INVALID_REFERENCE)
        assertEquals(11, accepted(request(listOf(ref("a", width = 11))), caps(pixels = 110)).references.single().image.width)
    }

    @Test fun missingGrantsDuplicatesSelfAndChangedProviderDenied() {
        denied(request(listOf(ref("a", granted = false))), ReferenceSelectionDenial.REFERENCE_GRANT)
        denied(request(listOf(ref("a", fresh = false))), ReferenceSelectionDenial.REFERENCE_GRANT)
        denied(request(listOf(ref("source"))), ReferenceSelectionDenial.SELF_REFERENCE)
        denied(request(listOf(ref("a"), ref("a"))), ReferenceSelectionDenial.DUPLICATE_REFERENCE)
        denied(request(listOf(ref("a"), ref("b").copy(asset = asset("a").copy(handle = "b")))),
            ReferenceSelectionDenial.DUPLICATE_REFERENCE)
        denied(request(listOf(ref("a")), provider = "provider-a"), ReferenceSelectionDenial.PROVIDER_CHANGED,
            provider = "provider-b")
        denied(request(provider = " "), ReferenceSelectionDenial.INVALID_PROVIDER)
    }

    @Test fun unsupportedTypedOptionsAreNeverDropped() {
        val unknown = CapabilityKey("unknown")
        val strength = CapabilityKey("strength")
        val steps = CapabilityKey("steps")
        denied(request(options = mapOf(unknown to OptionValue.BooleanValue(true))),
            ReferenceSelectionDenial.UNKNOWN_OPTION)
        denied(request(options = mapOf(strength to OptionValue.DecimalValue(1.1))),
            ReferenceSelectionDenial.INVALID_OPTION)
        denied(request(options = mapOf(strength to OptionValue.DecimalValue(Double.NaN))),
            ReferenceSelectionDenial.INVALID_OPTION)
        denied(request(options = mapOf(steps to OptionValue.TextValue("3"))),
            ReferenceSelectionDenial.INVALID_OPTION)
        denied(request(options = mapOf(CapabilityKey("seed") to OptionValue.IntegerValue(12))),
            ReferenceSelectionDenial.SEED_UNSUPPORTED)
        assertEquals(2, accepted(request(options = mapOf(
            strength to OptionValue.DecimalValue(1.0),
            steps to OptionValue.IntegerValue(20)
        ))).options.size)
    }

    @Test fun sourceValidationAndNoPrivateDataInDenials() {
        denied(request(source = ref("source", granted = false)), ReferenceSelectionDenial.SOURCE_GRANT)
        denied(request(source = ref("source", format = ImageFormat.JPEG)), ReferenceSelectionDenial.SOURCE_FORMAT)
        denied(request(source = ref("source", width = 11)), ReferenceSelectionDenial.SOURCE_PIXELS)
        denied(request(listOf(ref("a").copy(image = image("other")))), ReferenceSelectionDenial.INVALID_REFERENCE)
        val privateId = "private-photo-uri-123"
        val rejected = ReferenceSelectionPlanner.plan(request(listOf(ref(privateId, granted = false))),
            "provider-a", caps())
        assertFalse(rejected.toString().contains(privateId))
    }
}
