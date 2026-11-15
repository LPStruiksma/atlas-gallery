package nl.atlas.gallery.featureaiedit.provider

import org.junit.Assert.*
import org.junit.Test

/** Adapter authors extend this with a deterministic fixture; no network or credentials in the suite. */
abstract class ImageEditProviderContract {
    enum class Fixture { SUCCESS, REFUSAL, FAILURE, SLOW_ASYNC, CANCELLABLE_ASYNC }
    protected abstract fun providerFor(fixture: Fixture): ImageEditProvider

    protected fun request(candidates: Int = 1) = ImageEditRequest(
        source = EditImage("synthetic-source", 8, 8, ImageFormat.PNG, List(64) { 0xff446688.toInt() }),
        instruction = "Tint the synthetic pixels",
        output = OutputSpec(8, 8, ImageFormat.PNG, candidates)
    )

    @Test fun successHonorsOutputAndProvenance() {
        val provider = providerFor(Fixture.SUCCESS)
        val outcome = provider.submit(request()).await() as EditOutcome.Success
        assertEquals(1, outcome.images.size)
        assertEquals(8, outcome.images.single().width)
        assertEquals(8, outcome.images.single().height)
        assertEquals(ImageFormat.PNG, outcome.images.single().format)
        assertTrue(outcome.providerModel.isNotBlank())
        assertTrue(outcome.providerVersion.isNotBlank())
    }

    @Test fun providerRefusalIsNotTransportFailure() {
        val result = providerFor(Fixture.REFUSAL).submit(request()).await()
        assertTrue(result is EditOutcome.Refused)
        assertTrue((result as EditOutcome.Refused).providerCode.isNotEmpty())
    }

    @Test fun failuresKeepTypedKindAndRetryability() {
        val result = providerFor(Fixture.FAILURE).submit(request()).await()
        assertTrue(result is EditOutcome.Failed)
        val failure = result as EditOutcome.Failed
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test fun cancellationIsIdempotentAndTerminal() {
        val provider = providerFor(Fixture.CANCELLABLE_ASYNC)
        assertTrue(provider.capabilities().supportsAsync)
        val job = provider.submit(request())
        assertTrue(job.poll() is JobSnapshot.Pending)
        job.cancel()
        job.cancel()
        assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
    }

    @Test fun awaitingPastBoundReportsTimeoutAndCancelsUnderlyingWork() {
        val job = providerFor(Fixture.SLOW_ASYNC).submit(request())
        val outcome = job.await(maxPolls = 2)
        assertEquals(FailureKind.TIMEOUT, (outcome as EditOutcome.Failed).kind)
        assertTrue(outcome.retryable)
        assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
    }

    @Test fun streamingReportsPendingAndOneTerminalEvent() {
        val events = mutableListOf<JobSnapshot>()
        val result = providerFor(Fixture.CANCELLABLE_ASYNC).submit(request()).stream(
            maxPolls = 6, onEvent = { events.add(it) }
        )
        assertTrue(result is EditOutcome.Success)
        assertTrue(events.any { it is JobSnapshot.Pending })
        assertEquals(1, events.count { it is JobSnapshot.Completed })
    }

    @Test fun oversizedOrTooManyCandidatesNeverReachProviderEngine() {
        val provider = providerFor(Fixture.SUCCESS)
        val max = provider.capabilities().maxCandidates
        val extra = provider.submit(request(candidates = max + 1)).await()
        assertEquals(FailureKind.INVALID_REQUEST, (extra as EditOutcome.Failed).kind)
        val huge = request().copy(output = OutputSpec(Int.MAX_VALUE, Int.MAX_VALUE, ImageFormat.PNG))
        val overflow = provider.submit(huge).await()
        assertEquals(FailureKind.INVALID_REQUEST, (overflow as EditOutcome.Failed).kind)
    }
}

class MockImageEditProviderContractTest : ImageEditProviderContract() {
    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val config = when (fixture) {
            Fixture.SUCCESS -> MockProviderConfig()
            Fixture.REFUSAL -> MockProviderConfig(forcedOutcome = EditOutcome.Refused("SYNTHETIC_REFUSAL", "declined"))
            Fixture.FAILURE -> MockProviderConfig(forcedOutcome = EditOutcome.Failed(FailureKind.NETWORK, true))
            Fixture.SLOW_ASYNC -> MockProviderConfig(pendingPolls = 100)
            Fixture.CANCELLABLE_ASYNC -> MockProviderConfig(pendingPolls = 2)
        }
        return MockImageEditProvider(config)
    }
}

class ImageEditRequestValidatorTest {
    private val caps = MockImageEditProvider().capabilities()
    private val source = EditImage("synthetic-large", 128, 128, ImageFormat.PNG)
    private fun req(image: EditImage = source) = ImageEditRequest(
        source = image, instruction = "Edit", output = OutputSpec(8, 8, ImageFormat.PNG)
    )

    @Test fun oversizeIsRejectedUnlessCallerExplicitlyOptsIntoDownscaling() {
        val denied = ImageEditRequestValidator.validate(req(), caps)
        assertEquals(RejectionReason.INPUT_PIXELS, (denied as Admission.Rejected).reason)
        val planned = ImageEditRequestValidator.validate(
            req().copy(oversizePolicy = OversizePolicy.DOWNSCALE), caps
        ) as Admission.Accepted
        assertTrue(planned.plan.source is ResizePlan.Downscale)
        val size = planned.plan.source as ResizePlan.Downscale
        assertTrue(size.width.toLong() * size.height <= caps.maxInputPixels)
        assertEquals(size.width, size.height)
    }

    @Test fun maskCannotBeSilentlyResizedOrAcceptedWhenUnsupported() {
        val mask = EditImage("synthetic-mask", 128, 128, ImageFormat.PNG)
        val result = ImageEditRequestValidator.validate(req().copy(mask = mask), caps)
        assertEquals(RejectionReason.MASK_UNSUPPORTED, (result as Admission.Rejected).reason)
    }

    @Test fun referenceLimitsAndMultiTurnAreHonest() {
        val reference = EditImage("synthetic-ref", 8, 8, ImageFormat.PNG)
        assertEquals(RejectionReason.REFERENCES_UNSUPPORTED,
            (ImageEditRequestValidator.validate(req(reference).copy(references = listOf(reference)), caps)
                as Admission.Rejected).reason)
        assertEquals(RejectionReason.MULTI_TURN_UNSUPPORTED,
            (ImageEditRequestValidator.validate(req(reference).copy(parentEditId = "previous"), caps)
                as Admission.Rejected).reason)
    }

    @Test fun advancedParameterRequiresTypedSchemaAndValidRange() {
        val key = CapabilityKey("strength")
        val supported = caps.copy(advancedParameters = mapOf(key to OptionSchema.DecimalRange(0.0, 1.0)))
        assertEquals(RejectionReason.UNKNOWN_OPTION,
            (ImageEditRequestValidator.validate(req(EditImage("s", 8, 8, ImageFormat.PNG))
                .copy(options = mapOf(key to OptionValue.DecimalValue(0.5))), caps)
                as Admission.Rejected).reason)
        assertEquals(RejectionReason.INVALID_OPTION,
            (ImageEditRequestValidator.validate(req(EditImage("s", 8, 8, ImageFormat.PNG))
                .copy(options = mapOf(key to OptionValue.TextValue("bad"))), supported)
                as Admission.Rejected).reason)
        assertTrue(ImageEditRequestValidator.validate(req(EditImage("s", 8, 8, ImageFormat.PNG))
            .copy(options = mapOf(key to OptionValue.DecimalValue(0.5))), supported) is Admission.Accepted)
    }

    @Test fun aspectAndMalformedDimensionsRejectWithoutOverflow() {
        val constrained = caps.copy(aspectRange = AspectRange(0.9, 1.1))
        assertEquals(RejectionReason.OUTPUT_ASPECT,
            (ImageEditRequestValidator.validate(
                req(EditImage("s", 8, 8, ImageFormat.PNG))
                    .copy(output = OutputSpec(100, 10, ImageFormat.PNG)), constrained
            ) as Admission.Rejected).reason)
        assertEquals(RejectionReason.INVALID_DIMENSIONS,
            (ImageEditRequestValidator.validate(req(EditImage("s", 0, 8, ImageFormat.PNG)), caps)
                as Admission.Rejected).reason)
    }

    @Test fun syntheticMockModesRenderDeterministicallyWithoutAndroidOrNetwork() {
        val input = EditImage("only-synthetic", 8, 8, ImageFormat.PNG, List(64) { 0xff446688.toInt() + it * 137 })
        for (mode in MockMode.values()) {
            val mock = MockImageEditProvider(MockProviderConfig(mode = mode))
            val request = req(input)
            val first = mock.submit(request).await() as EditOutcome.Success
            val second = mock.submit(request).await() as EditOutcome.Success
            assertEquals(first.images.single().pixels, second.images.single().pixels)
            assertEquals(64, first.images.single().pixels.size)
            assertNotEquals(input.pixels, first.images.single().pixels)
        }
    }
}
