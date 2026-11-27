package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureaiedit.engine.AiEditStateCopy
import nl.atlas.gallery.featureaiedit.preview.CompareViewportPlanner
import nl.atlas.gallery.featureaiedit.ui.AiEditActions
import nl.atlas.gallery.featureaiedit.ui.AiEditExtras
import nl.atlas.gallery.featureaiedit.ui.AiEditPhase
import nl.atlas.gallery.featureaiedit.ui.AiEditScreenContent
import nl.atlas.gallery.featureaiedit.ui.AiEditServiceChoice
import nl.atlas.gallery.featureaiedit.ui.AiEditUiState
import nl.atlas.gallery.featureaiedit.ui.AiEditViewerState
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

/**
 * AIE-21 rev 5 (#433): compare, refine and save, A–F, in both phone themes on the synthetic
 * S24+ 1080×2340 display, plus keyboard, font 1.3, landscape, TalkBack semantics and real
 * gestures on the compare pane. Synthetic pixels only (large enough that zoom and the bounded
 * decoder matter); no provider, network or personal photo.
 */
@RunWith(AndroidJUnit4::class)
class AiEdit21UiEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)

    private val services = listOf(AiEditServiceChoice("FAL", "fal.ai", "Qwen Image Edit"))
    private fun candidate(id: String, parent: String?, prompt: String) = AiEditWorkingCandidate(
        id = id, fileName = "$id.png", parentId = parent, prompt = prompt, providerId = "fal",
        model = "fal-ai/qwen-image-edit-2511", modelVersion = "1")

    /** A synthetic scene: sky band, ground, grid lines and discs, so crops and zoom are visible. */
    private fun scene(name: String, width: Int, height: Int, sky: Int, ground: Int): Uri {
        val file = File(compose.activity.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bitmap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(ground)
            p.color = sky
            c.drawRect(0f, 0f, width.toFloat(), height * .45f, p)
            p.color = Color.argb(150, 255, 255, 255)
            p.strokeWidth = width / 400f
            for (i in 1 until 16) {
                c.drawLine(width * i / 16f, 0f, width * i / 16f, height.toFloat(), p)
                c.drawLine(0f, height * i / 16f, width.toFloat(), height * i / 16f, p)
            }
            p.color = Color.rgb(240, 200, 60)
            c.drawCircle(width * .72f, height * .22f, height * .08f, p)
            p.color = Color.rgb(40, 70, 50)
            for (k in 0 until 5) c.drawCircle(width * (.12f + k * .18f), height * .7f, height * .07f, p)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
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
        assertTrue("$tag top ${bounds.top} overlaps bars", bounds.top >= (bars?.top ?: 0) - 2f)
        assertTrue("$tag bottom ${bounds.bottom} exceeds $safeBottom", bounds.bottom <= safeBottom + 2f)
        assertTrue("$tag right ${bounds.right} exceeds window", bounds.right <= decor.width - (bars?.right ?: 0) + 2f)
    }

    private fun heightDp(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.height /
            compose.activity.resources.displayMetrics.density

    private fun assertTarget48(tag: String) {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val d = compose.activity.resources.displayMetrics.density
        assertTrue("$tag is ${b.height / d} dp high", b.height / d >= 47.5f)
    }

    @Test fun compareRefineAndSaveStatesInLightAndDark() {
        val original = scene("aie21-original.png", 3000, 2000, Color.rgb(120, 140, 170), Color.rgb(110, 100, 80))
        val results = listOf(
            scene("aie21-r1.png", 1536, 1024, Color.rgb(70, 130, 230), Color.rgb(110, 100, 80)),
            scene("aie21-r2.png", 1536, 1024, Color.rgb(250, 150, 90), Color.rgb(100, 110, 80)),
            scene("aie21-r3.png", 1536, 1024, Color.rgb(90, 200, 220), Color.rgb(90, 100, 90)),
            scene("aie21-r4.png", 1024, 1024, Color.rgb(160, 110, 220), Color.rgb(120, 100, 90))
        )
        val one = listOf(candidate("r1", null, "Brighten the sky"))
        val four = listOf(
            candidate("r1", null, "Brighten the sky"), candidate("r2", null, "Brighten the sky"),
            candidate("r3", "r2", "Make the sky a warm sunset"), candidate("r4", "r3", "Add a little haze"))
        val deep = four + listOf(candidate("r5", "r4", "Sharpen the trees"))
        fun uriOf(c: AiEditWorkingCandidate): Uri = results[(c.id.removePrefix("r").toInt() - 1) % results.size]

        val composing = AiEditUiState(phase = AiEditPhase.Composing, prompt = "",
            serviceName = "fal.ai", modelName = "Qwen Image Edit", providerId = "fal")
        val result1 = composing.copy(phase = AiEditPhase.Result, prompt = "Brighten the sky",
            candidates = one, selectedId = "r1", branchParentId = "r1")
        val result4 = composing.copy(phase = AiEditPhase.Result, prompt = "Add a little haze",
            candidates = four, selectedId = "r2", branchParentId = "r2")
        val resultDeep = result4.copy(candidates = deep, selectedId = "r5", branchParentId = "r5")
        val costLine = "About US\$0.03 per edit with fal.ai, Qwen Image Edit"

        var state by mutableStateOf(composing)
        var extras by mutableStateOf(AiEditExtras(services = services, costLine = costLine))
        var refineUri by mutableStateOf<Uri?>(null)
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        val viewer = AiEditViewerState()
        val calls = mutableListOf<String>()
        val actions = AiEditActions(
            onPromptChanged = { state = state.copy(prompt = it); calls += "prompt" },
            onGenerate = { calls += "generate" },
            onAccept = { calls += "accept" },
            onEditThisResult = { calls += "edit-this" },
            onStartOver = { calls += "start-over" },
            onSelectCandidate = { id -> state = state.copy(selectedId = id, branchParentId = id); calls += "select-$id" },
            onUndo = { calls += "undo" },
            onView = { calls += "view" },
            onHintSeen = { calls += "hint" }
        )
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().testTag("atlas-ai-edit-host")) {
                            AiEditScreenContent(
                                state = state, extras = extras, originalUri = original,
                                selectedCandidateUri = state.selectedCandidate?.let(::uriOf),
                                candidateUri = { uriOf(it) }, actions = actions,
                                refineSourceUri = refineUri, viewer = viewer)
                        }
                    }
                }
            }
        }
        fun settle() {
            compose.waitForIdle()
            compose.waitUntil(15_000L) { viewer.settled }
            compose.waitForIdle()
        }
        fun shot(name: String) {
            settle()
            saveUiEvidence("aie-21-${if (dark) "dark" else "light"}-$name.png") { settle() }
        }
        fun set(s: AiEditUiState, e: AiEditExtras = AiEditExtras(services = services, costLine = costLine),
                refine: Uri? = null) {
            compose.runOnIdle {
                state = s; extras = e; refineUri = refine
                viewer.endCompare(); viewer.resetZoom(); viewer.holding = false; viewer.pinnedOriginal = false
            }
            compose.waitForIdle()
        }

        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; fontScale = 1f }
            // A: composing, five static chips (two shown + More), Recent, Generate disabled.
            set(composing)
            compose.onNodeWithTag("atlas-ai-edit-suggestion-0").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-suggestion-1").assertExists()
            assertTarget48("atlas-ai-edit-more-suggestions"); assertTarget48("atlas-ai-edit-recent")
            assertWithinBars("atlas-ai-edit-generate")
            shot("a-compose-empty")
            set(AiEditUiState(phase = AiEditPhase.Composing), AiEditExtras())
            compose.onNodeWithTag("atlas-ai-edit-connect").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-suggestions").assertExists()
            shot("a-no-service")
            set(composing)
            compose.onNodeWithTag("atlas-ai-edit-more-suggestions").performClick()
            shot("a-more-suggestions")
            compose.onNodeWithTag("atlas-ai-edit-suggestion-sheet-3").performClick()
            compose.runOnIdle { assertEquals("Change sky", state.prompt) }
            set(composing.copy(prompt = "Change sky"))
            compose.onNodeWithTag("atlas-ai-edit-recent").performClick()
            shot("a-recent-empty")
            compose.onNodeWithTag("atlas-ai-edit-history-sheet").assertExists()
            compose.runOnIdle { extras = extras.copy(recentPrompts = listOf("Brighten the sky", "Remove object")) }
            compose.waitForIdle()
            shot("a-recent-list")
            // Close the dialog so it does not cover every later state.
            compose.onNode(hasText("Close") and hasClickAction()).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText("Recent prompts").assertCountEquals(0)
            set(composing)

            // C: one and four results; filmstrip ring + sphere; trail; Compare toggle.
            set(result1, AiEditExtras(services = services, showHoldHint = true))
            compose.onNodeWithTag("atlas-ai-edit-candidates").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-compare-toggle").assertExists()
            assertTarget48("atlas-ai-edit-compare-toggle")
            assertWithinBars("atlas-ai-edit-accept")
            shot("c-result-one")
            set(result4, AiEditExtras(services = services, showHoldHint = false))
            compose.onNode(hasContentDescription("Result 2 of 4, selected")).assertExists()
            compose.onNodeWithTag("atlas-ai-edit-trail-r2").assertExists()
            shot("c-result-four-selected-2")
            compose.onNodeWithTag("atlas-ai-edit-candidate-r3").performClick()
            compose.waitForIdle()
            // Result 3's own lineage (Original › Result 2 › Result 3) and its own prompt.
            compose.onNodeWithTag("atlas-ai-edit-trail-r2").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-trail-r3").assertExists()
            compose.onNode(hasContentDescription("Result 3 of 4, selected")).assertExists()
            shot("c-result-four-selected-3")
            set(resultDeep, AiEditExtras(services = services, showHoldHint = false))
            compose.onNodeWithTag("atlas-ai-edit-history").assertExists()
            shot("c-trail-deep")
            compose.onNodeWithTag("atlas-ai-edit-history").performClick()
            compose.onNodeWithTag("atlas-ai-edit-lineage-sheet").assertExists()
            shot("c-history-sheet")
            compose.onNodeWithTag("atlas-ai-edit-history-original").performClick()

            // D: split compare at 50 %, then 25 % zoomed, then hold-to-original.
            set(result4, AiEditExtras(services = services, showHoldHint = false))
            compose.onNodeWithTag("atlas-ai-edit-compare-toggle").performClick()
            compose.onNodeWithTag("atlas-ai-edit-divider").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-label-original").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-label-edited").assertExists()
            shot("d-compare-50")
            compose.runOnIdle {
                viewer.moveSplit(.25f)
                viewer.setView(nl.atlas.gallery.featureaiedit.ui.AiEditCompareMath.View(.62, .3, 2.5))
            }
            shot("d-compare-25-zoomed")
            assertTrue("decoded ${viewer.decodedBytes} bytes", viewer.decodedBytes in 1..CompareViewportPlanner.DEFAULT_COMBINED_PREVIEW_BYTES)
            Log.i("ATLAS_EVIDENCE", "aie-21 compare zoom 2.5 decoded bytes=${viewer.decodedBytes}")
            compose.runOnIdle { viewer.endCompare(); viewer.resetZoom(); viewer.holding = true }
            shot("d-hold-original")
            compose.runOnIdle { viewer.holding = false }

            // E: refine (Edit this result), saved with Undo, undoing, failed and removed outcomes.
            set(composing.copy(prompt = "Make the sky a warm sunset", candidates = four, selectedId = "r2",
                branchParentId = "r2"), refine = results[1])
            compose.onNodeWithTag("atlas-ai-edit-refining").assertExists()
            compose.onNodeWithTag("atlas-ai-edit-use-original").assertExists()
            shot("e-refine-result-2")
            set(composing.copy(phase = AiEditPhase.Generating, prompt = "Make the sky a warm sunset",
                candidates = four, selectedId = "r2", branchParentId = "r2", submitSent = true), refine = results[1])
            shot("e-refine-generating")
            val saved = result4.copy(phase = AiEditPhase.Saved)
            set(saved, AiEditExtras(services = services, canUndo = true))
            assertTarget48("atlas-ai-edit-undo"); assertTarget48("atlas-ai-edit-view")
            assertWithinBars("atlas-ai-edit-done")
            shot("e-saved")
            set(saved, AiEditExtras(services = services, canUndo = true, undoing = true))
            shot("e-undoing")
            set(saved, AiEditExtras(services = services, canUndo = true, toast = AiEditStateCopy.UNDO_FAILED))
            compose.onNodeWithTag("atlas-ai-edit-view").assertExists()
            shot("e-undo-failed")
            set(result4, AiEditExtras(services = services, showHoldHint = false, toast = AiEditStateCopy.UNDO_REMOVED))
            shot("e-undo-removed")
        }

        // Callbacks: chips never Generate; Edit this result, Start over, Undo and View are explicit.
        compose.runOnIdle { calls.clear() }
        set(result4, AiEditExtras(services = services, showHoldHint = false))
        compose.onNodeWithTag("atlas-ai-edit-change").performClick()
        compose.onNodeWithTag("atlas-ai-edit-start-over").performClick()
        set(result4.copy(phase = AiEditPhase.Saved), AiEditExtras(services = services, canUndo = true))
        compose.onNodeWithTag("atlas-ai-edit-undo").performClick()
        compose.onNodeWithTag("atlas-ai-edit-view").performClick()
        set(composing)
        compose.onNodeWithTag("atlas-ai-edit-suggestion-0").performClick()
        compose.runOnIdle {
            assertTrue(calls.containsAll(listOf("edit-this", "start-over", "undo", "view", "prompt")))
            assertTrue("a chip must never generate", "generate" !in calls)
        }

        // Gestures on the real pane: divider drag, centre haptic once, double tap, pinch, hold.
        compose.runOnIdle { dark = false }
        set(result4, AiEditExtras(services = services, showHoldHint = false))
        compose.onNodeWithTag("atlas-ai-edit-compare-toggle").performClick()
        settle()
        val ticksBefore = viewer.centreTicks
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput {
            down(center)
            moveTo(Offset(width * .2f, centerY))
            moveTo(Offset(width * .8f, centerY))
            moveTo(Offset(width * .5f + 2f, centerY))
            moveTo(Offset(width * .5f - 2f, centerY))
            moveTo(Offset(width * .25f, centerY))
            up()
        }
        compose.runOnIdle {
            assertEquals(.25f, viewer.split, .02f)
            assertTrue("centre crossings tick, wiggles don't: ${viewer.centreTicks - ticksBefore}",
                viewer.centreTicks - ticksBefore in 1..2)
            assertEquals(1.0, viewer.zoom, 0.0)
        }
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput {
            doubleClick(Offset(width * .7f, height * .4f))
        }
        compose.runOnIdle { assertEquals(2.5, viewer.zoom, 1e-6) }
        compose.onNodeWithTag("atlas-ai-edit-zoom-reset").performClick()
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput {
            pinch(Offset(centerX - 60f, centerY + 200f), Offset(centerX - 300f, centerY + 200f),
                Offset(centerX + 60f, centerY + 200f), Offset(centerX + 300f, centerY + 200f))
        }
        compose.runOnIdle { assertTrue("pinch zoom ${viewer.zoom}", viewer.zoom > 1.5) }
        settle()
        assertTrue(viewer.decodedBytes <= CompareViewportPlanner.DEFAULT_COMBINED_PREVIEW_BYTES)
        compose.runOnIdle { viewer.resetZoom(); viewer.endCompare() }
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput {
            down(Offset(width * .7f, height * .4f)); moveBy(Offset(1f, 0f), delayMillis = 400)
        }
        compose.runOnIdle { assertTrue("stationary press shows the original", viewer.holding) }
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput { up() }
        compose.runOnIdle { assertTrue(!viewer.holding) }
        // A press that moves past slop never becomes a hold.
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput {
            down(Offset(width * .5f, height * .5f)); moveBy(Offset(80f, 0f)); moveBy(Offset(1f, 0f), delayMillis = 400)
        }
        compose.runOnIdle { assertTrue("moved press must not hold", !viewer.holding) }
        compose.onNodeWithTag("atlas-ai-edit-compare").performTouchInput { up() }

        // TalkBack: adjustable divider, zoom actions and a screen-reader path to candidates.
        compose.onNodeWithTag("atlas-ai-edit-compare-toggle").performClick()
        compose.waitForIdle()
        val divider = compose.onNodeWithTag("atlas-ai-edit-divider")
        val range = divider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertTrue("divider exposes an adjustable range", range != null)
        divider.performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        compose.runOnIdle { assertEquals(.75f, viewer.split, .001f) }
        // The actions live on the viewer node inside the photo box.
        val viewerLabels = compose.onNode(hasContentDescription("Comparison of original and Result 2"))
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }.orEmpty()
        assertTrue("zoom and compare actions: $viewerLabels",
            viewerLabels.containsAll(listOf("Exit compare", "Zoom in")))
        compose.runOnIdle { viewer.endCompare() }

        // F + font 1.3: wrapped actions stay on screen, the photo keeps 160 dp.
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; fontScale = 1.3f }
            for ((name, s) in listOf("result-four" to result4, "saved" to result4.copy(phase = AiEditPhase.Saved))) {
                set(s, AiEditExtras(services = services, showHoldHint = false, canUndo = true))
                assertWithinBars(if (name == "saved") "atlas-ai-edit-done" else "atlas-ai-edit-accept")
                assertTrue("photo ${heightDp("atlas-ai-edit-compare")} dp at 1.3", heightDp("atlas-ai-edit-compare") >= 160f)
                shot("f-font13-$name")
            }
        }

        // B: keyboard open with a long prompt at 1.3; chips hide, photo keeps 160 dp.
        compose.runOnIdle { dark = false; fontScale = 1.3f }
        set(composing.copy(prompt = "Make the sky a warm sunset and keep the trees, the people and the " +
            "shadows exactly as they are, with a soft haze"))
        compose.tap(hasTestTag("atlas-ai-edit-prompt"))
        compose.typeText(hasTestTag("atlas-ai-edit-prompt"), " please", KeyboardAfter.Shown)
        compose.waitForIdle()
        assertWithinBars("atlas-ai-edit-generate")
        compose.onNodeWithTag("atlas-ai-edit-suggestions").assertDoesNotExist()
        assertTrue("photo ${heightDp("atlas-ai-edit-compare")} dp with keyboard", heightDp("atlas-ai-edit-compare") >= 160f)
        shot("b-keyboard-long-prompt")
        compose.hideKeyboard()

        // F: landscape puts the controls beside the photo.
        compose.runOnIdle { fontScale = 1f }
        try {
            rotateViewerEvidenceLandscape()
            compose.waitUntil(10_000L) {
                compose.onNodeWithTag("atlas-ai-edit-host").fetchSemanticsNode().boundsInRoot.let { it.width > it.height }
            }
            for (theme in listOf(false, true)) {
                compose.runOnIdle { dark = theme }
                set(result4, AiEditExtras(services = services, showHoldHint = false))
                compose.onNodeWithTag("atlas-ai-edit-controls").assertExists()
                assertWithinBars("atlas-ai-edit-accept")
                assertTrue("landscape photo ${heightDp("atlas-ai-edit-compare")} dp", heightDp("atlas-ai-edit-compare") >= 160f)
                shot("f-landscape-result")
                compose.onNodeWithTag("atlas-ai-edit-compare-toggle").performClick()
                shot("f-landscape-compare")
            }
        } finally {
            restoreViewerEvidencePortrait()
        }
        compose.waitUntil(10_000L) {
            compose.onNodeWithTag("atlas-ai-edit-host").fetchSemanticsNode().boundsInRoot.let { it.height > it.width }
        }
        for (name in listOf("aie21-original.png", "aie21-r1.png", "aie21-r2.png", "aie21-r3.png", "aie21-r4.png"))
            File(compose.activity.cacheDir, name).delete()
    }
}
