package nl.atlas.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureaiedit.engine.AiEditGate
import nl.atlas.gallery.featureaiedit.engine.AiEditIssue
import nl.atlas.gallery.featureaiedit.engine.AiEditStateCopy
import nl.atlas.gallery.featureaiedit.engine.IssueAction
import nl.atlas.gallery.featureaiedit.provider.CostDisclosure
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.CostScope
import nl.atlas.gallery.featureaiedit.ui.AiEditActions
import nl.atlas.gallery.featureaiedit.ui.AiEditExtras
import nl.atlas.gallery.featureaiedit.ui.AiEditPhase
import nl.atlas.gallery.featureaiedit.ui.AiEditScreenContent
import nl.atlas.gallery.featureaiedit.ui.AiEditServiceChoice
import nl.atlas.gallery.featureaiedit.ui.AiEditUiState
import nl.atlas.gallery.featureaiedit.ui.AiEditWorkingCandidate
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.hideKeyboard
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Every AIE-20 editor state (#431 rev 4 A to F plus addendum A2) rendered with fully synthetic
 * images in both phone themes, plus the keyboard, landscape and 1.3 font-scale checks. The body
 * is stateless here: no provider, no network, no personal photo.
 */
@RunWith(AndroidJUnit4::class)
class AiEditUiEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)
    private val candidate = AiEditWorkingCandidate(
        id = "synthetic", fileName = "synthetic.png", parentId = null,
        prompt = "Brighten the sky", providerId = "fal", model = "fal-ai/qwen-image-edit-2511",
        modelVersion = "1"
    )
    private val services = listOf(
        AiEditServiceChoice("GEMINI", "Google Gemini", "Nano Banana 2"),
        AiEditServiceChoice("FAL", "fal.ai", "Qwen Image Edit")
    )
    private val checked = LocalDate.of(2026, 11, 15)
    private val totalLine = AiEditStateCopy.costLine(
        CostDisclosure.of(CostEstimate("USD", 3, scope = CostScope.TOTAL,
            source = "fal.ai pricing", observedOn = checked)), "fal.ai", "Qwen Image Edit")
    private val partialLine = AiEditStateCopy.costLine(
        CostDisclosure.of(CostEstimate("USD", 4, scope = CostScope.OUTPUT_ONLY,
            source = "Gemini API pricing", observedOn = checked)), "Google Gemini", "Nano Banana 2")
    private val unknownLine = AiEditStateCopy.costLine(CostDisclosure.Unknown, "fal.ai", null)

    private fun image(name: String, tint: Int): Uri {
        val file = File(compose.activity.cacheDir, name)
        val pixels = IntArray(96 * 72) { i ->
            val x = i % 96; val y = i / 96
            0xff000000.toInt() or
                (((x * 2 + tint) and 255) shl 16) or
                (((y * 3 + 36) and 255) shl 8) or 0x50
        }
        val bitmap = Bitmap.createBitmap(pixels, 96, 72, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        return Uri.fromFile(file)
    }

    private fun assertWithinBars(tag: String) {
        val decor = compose.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeBottom = if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val safeBottom = decor.height - maxOf(bars?.bottom ?: 0, imeBottom)
        assertTrue("$tag top ${bounds.top} overlaps system bars ${bars?.top}",
            bounds.top >= (bars?.top ?: 0) - 2f)
        assertTrue("$tag bottom ${bounds.bottom} exceeds inset boundary $safeBottom",
            bounds.bottom <= safeBottom + 2f)
        assertTrue("$tag right ${bounds.right} exceeds window ${decor.width - (bars?.right ?: 0)}",
            bounds.right <= decor.width - (bars?.right ?: 0) + 2f)
    }

    private fun photoHeightDp(): Float {
        val bounds = compose.onNodeWithTag("atlas-ai-edit-compare").fetchSemanticsNode().boundsInWindow
        return bounds.height / compose.activity.resources.displayMetrics.density
    }

    private val composing = AiEditUiState(phase = AiEditPhase.Composing, prompt = "Brighten the sky",
        serviceName = "fal.ai", modelName = "Qwen Image Edit", providerId = "fal")

    @Test fun everyStateHasVisualEvidenceInLightAndDark() {
        val original = image("aie20-evidence-before.png", 10)
        val edited = image("aie20-evidence-after.png", 90)
        var state by mutableStateOf(composing)
        var extras by mutableStateOf(AiEditExtras(services = services, costLine = totalLine))
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        val clicks = mutableMapOf<String, Int>()
        fun count(key: String) { clicks[key] = (clicks[key] ?: 0) + 1 }
        val actions = AiEditActions(
            onPromptChanged = { state = state.copy(prompt = it) },
            onGenerate = { count("generate") },
            onCancel = { count("cancel") },
            onAccept = { count("accept") },
            onIssueAction = { count("issue-${it.name}") },
            onConnect = { count("connect") },
            onGateConfirm = { count("gate-confirm") },
            onGateDismiss = { count("gate-dismiss") }
        )
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag("atlas-ai-edit-host")) {
                            AiEditScreenContent(
                                state = state, extras = extras, originalUri = original,
                                selectedCandidateUri = if (state.candidates.isEmpty()) null else edited,
                                candidateUri = { edited }, actions = actions
                            )
                        }
                    }
                }
            }
        }

        data class Case(val name: String, val state: AiEditUiState, val extras: AiEditExtras, val tag: String)
        val withResult = composing.copy(phase = AiEditPhase.Result, candidates = listOf(candidate),
            selectedId = candidate.id)
        val base = AiEditExtras(services = services, costLine = totalLine)
        val cases = mutableListOf(
            Case("a-compose-total", composing, base, "atlas-ai-edit-generate"),
            Case("a-compose-partial", composing.copy(serviceName = "Google Gemini", modelName = "Nano Banana 2"),
                base.copy(costLine = partialLine), "atlas-ai-edit-generate"),
            Case("a-compose-unknown", composing.copy(modelName = null),
                base.copy(costLine = unknownLine), "atlas-ai-edit-generate"),
            Case("a-compose-size", composing, base.copy(sizeNotice = AiEditStateCopy.sentAtSize(2048, 1536)),
                "atlas-ai-edit-generate"),
            Case("a-no-service", AiEditUiState(phase = AiEditPhase.Composing), AiEditExtras(),
                "atlas-ai-edit-connect"),
            Case("b-sending", composing.copy(phase = AiEditPhase.Generating, sending = true), base,
                "atlas-ai-edit-cancel"),
            Case("b-editing-indeterminate", composing.copy(phase = AiEditPhase.Generating, submitSent = true),
                base, "atlas-ai-edit-cancel"),
            Case("b-editing-progress", composing.copy(phase = AiEditPhase.Generating, submitSent = true,
                progress = .45f, determinate = true), base, "atlas-ai-edit-cancel"),
            Case("c-result-hint", withResult, base, "atlas-ai-edit-accept"),
            Case("c-result", withResult, base.copy(showHoldHint = false), "atlas-ai-edit-accept"),
            Case("c-saving", withResult.copy(phase = AiEditPhase.Accepting), base, "atlas-ai-edit-accepting"),
            Case("c-saved", withResult.copy(phase = AiEditPhase.Saved), base, "atlas-ai-edit-done"),
            Case("e-cancelled", composing.copy(phase = AiEditPhase.Cancelled),
                base.copy(toast = AiEditStateCopy.cancelledToast("fal.ai")), "atlas-ai-edit-toast")
        )
        for (issue in AiEditIssue.entries.filter { it != AiEditIssue.CANCELLED }) {
            val card = requireNotNull(AiEditStateCopy.card(issue, "fal.ai"))
            cases += Case("d-${issue.name.lowercase().replace('_', '-')}",
                composing.copy(phase = if (issue.isDecline) AiEditPhase.Refused else AiEditPhase.Failed,
                    issue = issue, submitSent = issue.mayHaveCharged),
                base, "atlas-ai-edit-action-${card.actions.last().name}")
        }
        for (gate in AiEditGate.entries) cases += Case("f-gate-${gate.name.lowercase().replace('_', '-')}",
            composing, base.copy(costLine = if (gate == AiEditGate.PRICE_UNKNOWN) unknownLine else totalLine,
                gate = gate), "atlas-ai-edit-gate-confirm")

        for (theme in listOf(false, true)) for (case in cases) {
            compose.runOnIdle { dark = theme; state = case.state; extras = case.extras }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-ai-edit-screen").assertExists()
            compose.onNodeWithTag(case.tag).assertExists()
            if (case.extras.gate == null) {
                assertWithinBars("atlas-ai-edit-back")
                assertWithinBars(case.tag)
                assertWithinBars("atlas-ai-edit-slab")
            }
            if (case.state.issue != null) {
                compose.onNodeWithTag("atlas-ai-edit-issue-${case.state.issue!!.name}").assertExists()
                // A2: a rate limit never offers "Open {Service}" (that is for out of credit).
                if (case.state.issue == AiEditIssue.RATE_LIMITED)
                    compose.onNodeWithTag("atlas-ai-edit-action-OPEN_SERVICE").assertDoesNotExist()
            }
            if (case.name == "c-result") compose.onNodeWithTag("atlas-ai-edit-compare-hint").assertDoesNotExist()
            saveUiEvidence("aie-20-${if (theme) "dark" else "light"}-${case.name}.png")
        }

        // Taps reach the right callbacks; nothing here talks to a service.
        compose.runOnIdle { dark = false; state = composing; extras = base }
        compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
        compose.runOnIdle { state = composing.copy(phase = AiEditPhase.Generating, sending = true) }
        compose.onNodeWithTag("atlas-ai-edit-cancel").performClick()
        compose.runOnIdle { state = withResult }
        compose.onNodeWithTag("atlas-ai-edit-accept").performClick()
        compose.runOnIdle { state = composing.copy(phase = AiEditPhase.Failed, issue = AiEditIssue.UNKNOWN_RECOVERABLE) }
        compose.onNodeWithTag("atlas-ai-edit-action-CHECK_AGAIN").performClick()
        compose.runOnIdle { state = composing; extras = base.copy(gate = AiEditGate.CHARGE_AGAIN) }
        compose.onNodeWithTag("atlas-ai-edit-gate-confirm").performClick()
        compose.runOnIdle { extras = AiEditExtras(); state = AiEditUiState(phase = AiEditPhase.Composing) }
        compose.onNodeWithTag("atlas-ai-edit-connect").performClick()
        compose.runOnIdle {
            assertEquals(1, clicks["generate"]); assertEquals(1, clicks["cancel"])
            assertEquals(1, clicks["accept"]); assertEquals(1, clicks["issue-${IssueAction.CHECK_AGAIN.name}"])
            assertEquals(1, clicks["gate-confirm"]); assertEquals(1, clicks["connect"])
        }

        // Font scale 1.3: photo stays at least 220 dp and the primary action stays on screen.
        for (theme in listOf(false, true)) {
            for ((name, s) in listOf("compose" to composing, "result" to withResult,
                    "rate-limited" to composing.copy(phase = AiEditPhase.Failed, issue = AiEditIssue.RATE_LIMITED))) {
                compose.runOnIdle { dark = theme; fontScale = 1.3f; state = s; extras = base }
                compose.waitForIdle()
                val tag = when (name) { "compose" -> "atlas-ai-edit-generate"; "result" -> "atlas-ai-edit-accept"
                    else -> "atlas-ai-edit-action-TRY_AGAIN" }
                assertWithinBars(tag)
                assertTrue("photo ${photoHeightDp()} dp at font 1.3", photoHeightDp() >= 220f)
                saveUiEvidence("aie-20-${if (theme) "dark" else "light"}-font13-$name.png")
            }
        }

        // Keyboard open (font 1.3): Generate sits above the keyboard and the photo keeps 220 dp.
        compose.runOnIdle { dark = false; fontScale = 1.3f; state = composing.copy(prompt = "Brighten"); extras = base }
        compose.waitForIdle()
        compose.tap(hasTestTag("atlas-ai-edit-prompt"))
        compose.typeText(hasTestTag("atlas-ai-edit-prompt"), " the sky", KeyboardAfter.Shown)
        compose.waitForIdle()
        assertWithinBars("atlas-ai-edit-generate")
        assertTrue("photo ${photoHeightDp()} dp with keyboard", photoHeightDp() >= 220f)
        saveUiEvidence("aie-20-light-keyboard.png")
        compose.hideKeyboard()

        // Landscape: the slab and its action stay inside the bars and cutout, the photo stays visible.
        compose.runOnIdle { fontScale = 1f; state = composing }
        try {
            rotateViewerEvidenceLandscape()
            compose.waitUntil(10_000L) {
                compose.onNodeWithTag("atlas-ai-edit-host").fetchSemanticsNode().boundsInRoot
                    .let { it.width > it.height }
            }
            compose.waitForIdle()
            for (theme in listOf(false, true)) {
                for ((name, s) in listOf("compose" to composing, "result" to withResult)) {
                    compose.runOnIdle { dark = theme; state = s }
                    compose.waitForIdle()
                    assertWithinBars(if (name == "compose") "atlas-ai-edit-generate" else "atlas-ai-edit-accept")
                    assertTrue("photo ${photoHeightDp()} dp in landscape", photoHeightDp() >= 120f)
                    saveUiEvidence("aie-20-${if (theme) "dark" else "light"}-landscape-$name.png")
                }
            }
        } finally {
            restoreViewerEvidencePortrait()
        }
        compose.waitUntil(10_000L) {
            compose.onNodeWithTag("atlas-ai-edit-host").fetchSemanticsNode().boundsInRoot
                .let { it.height > it.width }
        }
        File(compose.activity.cacheDir, "aie20-evidence-before.png").delete()
        File(compose.activity.cacheDir, "aie20-evidence-after.png").delete()
    }
}
