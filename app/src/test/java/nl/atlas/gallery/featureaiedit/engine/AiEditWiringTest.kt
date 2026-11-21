package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.AspectRange
import nl.atlas.gallery.featureaiedit.provider.CostDisclosure
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.CostScope
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities
import nl.atlas.gallery.featureaiedit.provider.RemoteHandle
import nl.atlas.gallery.featureaiedit.provider.RemoteRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** AIE-20 (#431) editor wiring: output size, restore decisions, gates and the state-card copy. */
class AiEditWiringTest {
    private fun caps(
        maxOutput: Long = 2048L * 2048L,
        aspect: AspectRange? = null,
        formats: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG)
    ) = ProviderCapabilities(
        supportsInstructionEdit = true, supportsMaskEdit = false, maxReferences = 0, maxCandidates = 4,
        supportsMultiTurn = false, supportsSeed = false, maxInputPixels = 50_000_000L,
        maxOutputPixels = maxOutput, aspectRange = aspect, inputFormats = setOf(ImageFormat.PNG),
        outputFormats = formats, supportsAsync = false, supportsStreaming = false
    )

    // --- OutputPlanner -------------------------------------------------------------------------

    @Test fun keepsAspectAndCapsAtTheProvidersLargestSize() {
        val spec = requireNotNull(OutputPlanner.plan(4000, 3000, caps()))
        assertTrue(spec.width.toLong() * spec.height <= 2048L * 2048L)
        assertEquals(4.0 / 3, spec.width.toDouble() / spec.height, 0.01)
        assertEquals(ImageFormat.PNG, spec.format)
        assertEquals(1, spec.candidates)
    }

    @Test fun aSmallPhotoIsNotBilledAtTheTopTier() {
        val spec = requireNotNull(OutputPlanner.plan(640, 480, caps()))
        val pixels = spec.width.toLong() * spec.height
        assertTrue("small photos plan about 1 MP, not 4 MP: $pixels", pixels in 900_000L..1_048_576L)
    }

    @Test fun clampsAnExtremeAspectIntoTheProvidersRange() {
        val spec = requireNotNull(OutputPlanner.plan(9000, 1000, caps(aspect = AspectRange(0.25, 4.0))))
        assertTrue(spec.width.toDouble() / spec.height <= 4.0)
    }

    @Test fun refusesNonsenseInputsAndFormatlessProviders() {
        assertNull(OutputPlanner.plan(0, 10, caps()))
        assertNull(OutputPlanner.plan(10, 10, caps(maxOutput = 0)))
        assertNull(OutputPlanner.plan(10, 10, caps(formats = emptySet())))
        assertEquals(ImageFormat.JPEG, OutputPlanner.plan(10, 10, caps(formats = setOf(ImageFormat.JPEG)))?.format)
    }

    // --- Restore decisions ---------------------------------------------------------------------

    private val salt = ByteArray(KeyFingerprint.SALT_BYTES) { it.toByte() }
    private val keyText = listOf("synthetic", "wiring", "key").joinToString("-") // guard: allow-synthetic-secret
    private fun record(
        stage: AiEditRequestRecord.Stage,
        recovery: RemoteRecovery = RemoteRecovery.QUERY_BY_PERSISTED_ID,
        fingerprint: String? = KeyFingerprint.of(salt, keyText),
        handleProvider: String = "fal:x"
    ) = AiEditRequestRecord(3, "p1", "fal:x", "fal.ai", recovery, stage,
        handle = if (stage == AiEditRequestRecord.Stage.ACCEPTED)
            RemoteHandle(handleProvider, "x", "req-1", 1, fingerprint) else null, prompt = "Brighten")

    @Test fun onlyAnAcceptedQueueRequestWithAValidBindingResumes() {
        assertEquals(RestoreDecision.Normal, RestoreDecision.of(null))
        assertEquals(RestoreDecision.Normal, RestoreDecision.of(record(AiEditRequestRecord.Stage.DONE)))
        assertTrue(RestoreDecision.of(record(AiEditRequestRecord.Stage.ACCEPTED)) is RestoreDecision.Resume)
        // Sent without an id, a provider with no lookup, a foreign handle or a missing fingerprint: unknown.
        assertTrue(RestoreDecision.of(record(AiEditRequestRecord.Stage.SENT)) is RestoreDecision.Unknown)
        assertTrue(RestoreDecision.of(record(AiEditRequestRecord.Stage.ACCEPTED, RemoteRecovery.NONE))
            is RestoreDecision.Unknown)
        assertTrue(RestoreDecision.of(record(AiEditRequestRecord.Stage.ACCEPTED, handleProvider = "gemini:y"))
            is RestoreDecision.Unknown)
        assertTrue(RestoreDecision.of(record(AiEditRequestRecord.Stage.ACCEPTED, fingerprint = null))
            is RestoreDecision.Unknown)
    }

    @Test fun checkAgainIsBoundToTheSubmittingKey() {
        val stored = record(AiEditRequestRecord.Stage.ACCEPTED).handle!!.keyFingerprint
        assertTrue(KeyFingerprint.matches(stored, salt, keyText))
        assertFalse(KeyFingerprint.matches(stored, salt, keyText + "2"))
        assertFalse(KeyFingerprint.matches(stored, salt, null))
        assertFalse("the fingerprint never contains the key", stored!!.contains(keyText))
    }

    // --- Gates ---------------------------------------------------------------------------------

    private val total = CostDisclosure.of(CostEstimate("USD", 3, scope = CostScope.TOTAL,
        source = "fal.ai pricing", observedOn = LocalDate.of(2026, 11, 15)))
    private val partial = CostDisclosure.of(CostEstimate("USD", 4, scope = CostScope.OUTPUT_ONLY,
        source = "Gemini API pricing", observedOn = LocalDate.of(2026, 11, 15)))

    @Test fun gatesComeInOrderAndOnlyWhenNeeded() {
        assertEquals(AiEditGate.FIRST_USE, AiEditGates.next(false, total, true, null, false))
        assertEquals(AiEditGate.PRICE_UNKNOWN, AiEditGates.next(true, CostDisclosure.Unknown, false, null, false))
        assertEquals("a partial price still needs consent",
            AiEditGate.PRICE_UNKNOWN, AiEditGates.next(true, partial, false, null, false))
        assertNull(AiEditGates.next(true, total, false, null, false))
        assertNull(AiEditGates.next(true, partial, true, null, false))
        for (issue in listOf(AiEditIssue.UNKNOWN_RECOVERABLE, AiEditIssue.UNKNOWN_CANNOT_CHECK,
                AiEditIssue.UNKNOWN_NONRECOVERABLE)) {
            assertEquals(AiEditGate.CHARGE_AGAIN, AiEditGates.next(true, total, false, issue, false))
            assertNull(AiEditGates.next(true, total, false, issue, true))
        }
        for (issue in listOf(AiEditIssue.RATE_LIMITED, AiEditIssue.OFFLINE, AiEditIssue.FAILED))
            assertNull("$issue charged nothing", AiEditGates.next(true, total, false, issue, false))
    }

    // --- State-card copy -----------------------------------------------------------------------

    @Test fun everyIssueButCancelHasACardWithTheServicesOwnNameAndNoJargon() {
        for (issue in AiEditIssue.entries) {
            val card = AiEditStateCopy.card(issue, "fal.ai")
            if (issue == AiEditIssue.CANCELLED) { assertNull(card); continue }
            assertNotNull(card); card!!
            val text = card.title + " " + card.detail
            assertFalse(text, Regex("(?i)provider|endpoint|http|·").containsMatchIn(text))
            assertTrue(card.actions.isNotEmpty())
        }
        assertTrue(AiEditStateCopy.card(AiEditIssue.WRONG_KEY, "Google Gemini")!!.title.startsWith("Google Gemini"))
    }

    @Test fun rateLimitIsNotOutOfCredit() {
        val limited = AiEditStateCopy.card(AiEditIssue.RATE_LIMITED, "fal.ai")!!
        val credit = AiEditStateCopy.card(AiEditIssue.OUT_OF_CREDIT, "fal.ai")!!
        assertEquals("fal.ai is limiting requests right now", limited.title)
        assertEquals("It didn't process this edit. Try again in a little while.", limited.detail)
        assertFalse("A2: no Open {Service} for a rate limit", IssueAction.OPEN_SERVICE in limited.actions)
        assertEquals(IssueAction.TRY_AGAIN, limited.actions.last())
        assertTrue(IssueAction.OPEN_SERVICE in credit.actions)
        assertEquals("Open fal.ai", AiEditStateCopy.actionLabel(IssueAction.OPEN_SERVICE, "fal.ai"))
    }

    @Test fun nothingWasSentAppearsOnlyWhereItIsProven() {
        for (issue in AiEditIssue.entries) {
            val detail = AiEditStateCopy.card(issue, "fal.ai")?.detail ?: continue
            val proven = issue == AiEditIssue.OFFLINE || issue == AiEditIssue.NOT_REACHED
            assertEquals("$issue", proven, detail.contains("Nothing was sent"))
        }
    }

    @Test fun outcomeUnknownOffersCheckAgainOnlyWhenALookupExists() {
        val recoverable = AiEditStateCopy.card(AiEditIssue.UNKNOWN_RECOVERABLE, "fal.ai")!!
        assertEquals(IssueAction.CHECK_AGAIN, recoverable.actions.last())
        for (issue in listOf(AiEditIssue.UNKNOWN_CANNOT_CHECK, AiEditIssue.UNKNOWN_NONRECOVERABLE)) {
            val card = AiEditStateCopy.card(issue, "fal.ai")!!
            assertFalse(IssueAction.CHECK_AGAIN in card.actions)
            assertTrue(IssueAction.TRY_AGAIN_CHARGED in card.actions)
            assertTrue(card.detail.contains("may have charged"))
        }
    }

    @Test fun costLinesNameTheirScopeSourceAndDate() {
        assertEquals("About US$0.03 per edit with Qwen Image Edit. fal.ai pricing, checked 15 Nov 2026.",
            AiEditStateCopy.costLine(total, "fal.ai", "Qwen Image Edit"))
        val partialLine = AiEditStateCopy.costLine(partial, "Google Gemini", "Nano Banana 2")
        assertTrue(partialLine, partialLine.startsWith("Image output about US$0.04; input extra; total unknown."))
        assertTrue(partialLine.endsWith("Gemini API pricing, checked 15 Nov 2026."))
        assertEquals("Sent to fal.ai. fal.ai sets the price.",
            AiEditStateCopy.costLine(CostDisclosure.Unknown, "fal.ai", null))
        assertEquals("Cancelled. fal.ai may still charge for work already started.",
            AiEditStateCopy.cancelledToast("fal.ai"))
        assertEquals("fal.ai may charge again.", AiEditStateCopy.chargeAgainTitle("fal.ai"))
        assertEquals("This photo will be sent at 2048×1536.", AiEditStateCopy.sentAtSize(2048, 1536))
    }

    @Test fun workingLineMovesFromSendingToEditing() {
        assertEquals("Sending photo…", AiEditStateCopy.working(true, "fal.ai"))
        assertEquals("Editing with fal.ai", AiEditStateCopy.working(false, "fal.ai"))
    }
}
