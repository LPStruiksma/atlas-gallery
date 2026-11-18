package nl.atlas.gallery

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featureorganization.AlbumTransferMode
import nl.atlas.gallery.featureorganization.AlbumTransferResult
import nl.atlas.gallery.featureorganization.SamsungSelectionBottomBar
import nl.atlas.gallery.featureorganization.SelectionResultDuration
import nl.atlas.gallery.featureorganization.SelectionResultMessages
import nl.atlas.gallery.featureorganization.SelectionResultSnackbarHost
import nl.atlas.gallery.featureorganization.albumTransferMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import kotlin.math.abs

/**
 * #365 DESIGN-10c in the real Activity at the S24+ size, both system themes,
 * on invented bright and dark PNGs this test draws, inserts and deletes.
 * Android's own Trash consent (MediaStore.createTrashRequest, owned by
 * MediaProvider) is captured over the selection, then canceled (the result
 * sits above the selection pill, which stays) and finally accepted (selection
 * ends; the result sits above the nav pill). ATLAS draws no confirm of its own.
 */
@RunWith(AndroidJUnit4::class)
class SelectionResultLightJourneyTest : SelectionResultActivityJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class SelectionResultDarkJourneyTest : SelectionResultActivityJourney(dark = true)

abstract class SelectionResultActivityJourney(private val dark: Boolean) {
    private val fixtures = BrightDarkFixtures(if (dark) "Dark" else "Light")
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(SelectionResultNightModeRule(dark))
        .around(fixtures)
        .around(compose)

    @Test fun trashPromptCanceledThenAcceptedShowsOneResultAboveTheVisiblePill() {
        val theme = if (dark) "dark" else "light"
        val ids = fixtures.ids
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[0]}"))
        compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[1]}"))
        compose.waitForIdle()

        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[1]}").performClick()
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(0)

        // 1. Before: Android's own Trash consent over the selection. No ATLAS result yet.
        compose.onNodeWithTag("atlas-selection-trash").performClick()
        val consent = waitForTrashConsent()
        Thread.sleep(600L) // let the system dialog finish its enter animation
        saveUiEvidence("selection-result-365-$theme-trash-prompt.png")

        // 2. Cancel: the photos stay, the selection stays, one honest result above its pill.
        consent.cancel()
        waitForResumed()
        compose.waitForNode(resultWithText("Trash canceled · your photos are unchanged"))
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        assertEquals(2, fixtures.untrashedCount())
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(1)
        assertAboveAnchor(compose, "atlas-selection-pill")
        compose.waitForIdle()
        saveUiEvidence("selection-result-365-$theme-trash-canceled-selection-pill.png")

        // 3. Accept: Android trashes the two items, selection ends, the new
        //    result replaces the old one and anchors to the nav pill.
        compose.onNodeWithTag("atlas-selection-trash").performClick()
        waitForTrashConsent().allow()
        waitForResumed()
        compose.waitForNode(resultWithText("Moved selected items to Android Trash"))
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        compose.onNodeWithTag("atlas-selection-bottom-bar").assertDoesNotExist()
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(1)
        compose.waitUntil(30_000L) { fixtures.untrashedCount() == 0 }
        assertAboveAnchor(compose, "atlas-nav-pill")
        compose.waitForIdle()
        saveUiEvidence("selection-result-365-$theme-trash-done-nav-pill.png")
    }

    /**
     * #376: a real rotation (portrait → landscape → portrait, each a new
     * MainActivity) keeps the one visible result with its exact text and only
     * its original remaining lifetime; the timer never restarts.
     *
     * Rotations on a CI emulator take seconds, longer than the result's own
     * 4 s, so Android's accessibility "time to take action" setting stretches
     * every timeout to [A11Y_TIMEOUT_MS] for this test. The snackbar's delay
     * runs on the rule's virtual clock while [SelectionResultMessages] counts
     * real monotonic time, so after ≥ [LANDSCAPE_HOLD_MS] real ms the restored
     * result must vanish within [A11Y_TIMEOUT_MS] − [LANDSCAPE_HOLD_MS]
     * virtual ms, where a restarted timer would still need the full timeout.
     */
    @Test fun rotationKeepsTheResultTextAndItsOriginalDeadline() {
        val theme = if (dark) "dark" else "light"
        val ids = fixtures.ids
        val text = "Trash canceled · your photos are unchanged"
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[0]}"))
        compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[1]}"))
        compose.waitForIdle()

        val timeoutBefore = secureSetting(A11Y_TIMEOUT_KEY)
        shellCommand("settings put secure $A11Y_TIMEOUT_KEY $A11Y_TIMEOUT_MS")
        try {
            compose.waitUntil(10_000L) { recommendedTimeoutMillis() == A11Y_TIMEOUT_MS }

            compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
            compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
            compose.onNodeWithTag("atlas-timeline-media-${ids[1]}").performClick()
            compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
            compose.onNodeWithTag("atlas-selection-trash").performClick()
            waitForTrashConsent().cancel()
            waitForResumed()
            compose.waitForNode(resultWithText(text))
            val shownAt = android.os.SystemClock.uptimeMillis()
            assertOneResult(text)
            assertAboveAnchor(compose, "atlas-selection-pill")
            saveUiEvidence("selection-result-376-$theme-rotation-portrait-before.png")

            // Landscape: a new Activity, the same single result.
            rotateTo(landscape = true)
            assertOneResult(text)
            assertNotOverVisiblePill()
            saveUiEvidence("selection-result-376-$theme-rotation-landscape.png")
            val hold = LANDSCAPE_HOLD_MS - (android.os.SystemClock.uptimeMillis() - shownAt)
            if (hold > 0) Thread.sleep(hold)
            compose.waitForIdle()
            assertOneResult(text)

            // Back to portrait: another new Activity, still the same result.
            rotateTo(landscape = false)
            val elapsed = android.os.SystemClock.uptimeMillis() - shownAt
            assertOneResult(text)
            assertAboveAnchor(compose, visiblePillTag())
            saveUiEvidence("selection-result-376-$theme-rotation-portrait-after.png")

            // Not dismissed early: at least A11Y_TIMEOUT_MS − elapsed is left.
            check(elapsed < A11Y_TIMEOUT_MS - LANDSCAPE_HOLD_MS) {
                "Rotations took $elapsed ms; too slow to tell a kept deadline from a restarted one"
            }
            compose.mainClock.advanceTimeBy(STILL_SHOWN_ADVANCE_MS)
            compose.waitForIdle()
            assertOneResult(text)

            // Gone by the original deadline (≤ A11Y_TIMEOUT_MS − LANDSCAPE_HOLD_MS
            // after the last restore); a restarted timer would need A11Y_TIMEOUT_MS.
            compose.mainClock.advanceTimeBy(GONE_ADVANCE_MS)
            compose.waitForIdle()
            compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(0)
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            if (timeoutBefore.isEmpty() || timeoutBefore == "null") {
                shellCommand("settings delete secure $A11Y_TIMEOUT_KEY")
            } else {
                shellCommand("settings put secure $A11Y_TIMEOUT_KEY $timeoutBefore")
            }
        }
    }

    /** #404: the selected keys and contextual pill survive Activity recreation. */
    @Test fun rotationRetainsTheSelectedPhotosAndContextualPill() {
        val theme = if (dark) "dark" else "light"
        val ids = fixtures.ids
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        ids.take(2).forEach { id ->
            compose.waitForNode(hasTestTag("atlas-timeline-media-${id}"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[1]}").performClick()
        assertSelectedPair(ids)
        saveUiEvidence("selection-404-${theme}-portrait-before.png")
        try {
            rotateTo(landscape = true)
            assertSelectedPair(ids)
            saveUiEvidence("selection-404-${theme}-landscape.png")
            rotateTo(landscape = false)
            assertSelectedPair(ids)
            saveUiEvidence("selection-404-${theme}-portrait-after.png")
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun assertSelectedPair(ids: List<Long>) {
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.onNodeWithTag("atlas-selection-pill").assertExists()
        compose.onNodeWithTag("atlas-nav-pill").assertDoesNotExist()
        ids.take(2).forEach { id ->
            val tag = "atlas-timeline-media-${id}"
            compose.onNodeWithTag("atlas-timeline-grid")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsSelected()
        }
    }

    private fun assertOneResult(text: String) {
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(1)
        compose.onAllNodes(resultWithText(text)).assertCountEquals(1)
    }

    /** The selection ends with the old Activity's state; the result sits above whichever pill shows. */
    private fun visiblePillTag(): String =
        if (compose.onAllNodes(hasTestTag("atlas-selection-pill")).fetchSemanticsNodes().isNotEmpty()) {
            "atlas-selection-pill"
        } else "atlas-nav-pill"

    private fun assertNotOverVisiblePill() {
        compose.waitForIdle()
        val result = compose.onNodeWithTag("atlas-selection-result").getUnclippedBoundsInRoot()
        val pills = listOf("atlas-selection-pill", "atlas-nav-pill").filter {
            compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty()
        }
        pills.forEach { tag ->
            val pill = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            check(result.bottom <= pill.top) { "Result $result overlaps $tag $pill" }
        }
        check(result.right - result.left <= 352.5.dp) { "Result wider than 352 dp: $result" }
    }

    private fun activityIdentity(): Int {
        var identity = 0
        compose.activityRule.scenario.onActivity { identity = System.identityHashCode(it) }
        return identity
    }

    /** Real device rotation: MainActivity has no configChanges, so Android recreates it. */
    private fun rotateTo(landscape: Boolean) {
        val before = activityIdentity()
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(
            if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90
            else android.app.UiAutomation.ROTATION_FREEZE_0)
        compose.waitUntil(30_000L) {
            compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED &&
                activityIdentity() != before
        }
        compose.waitUntil(30_000L) {
            val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
            root != null && (root.width > root.height) == landscape
        }
        compose.waitUntil(30_000L) {
            listOf("atlas-selection-pill", "atlas-nav-pill").any {
                compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitForIdle()
    }

    private fun recommendedTimeoutMillis(): Long {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return manager.getRecommendedTimeoutMillis(
            SelectionResultDuration.SHORT.millis.toInt(),
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
    }

    private fun secureSetting(key: String): String = shellCommand("settings get secure $key").trim()

    private fun shellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().decodeToString() }
    }

    private fun waitForResumed() {
        compose.waitUntil(30_000L) {
            compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }

    /** MediaProvider's consent window, found only inside its own package. */
    private inner class TrashConsent(private val root: AccessibilityNodeInfo) {
        fun cancel() {
            val deny = button(root) { it.equals("Deny", true) || it.equals("Cancel", true) }
            if (deny == null || !deny.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // Back on Android's dialog is the same RESULT_CANCELED.
                interactiveUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }

        fun allow() {
            val allow = requireNotNull(button(root) {
                it.equals("Allow", true) || it.startsWith("Allow ", true)
            }) { "MediaProvider Trash consent has no Allow button; texts=${texts(root)}" }
            check(allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Allow click refused" }
        }
    }

    private fun waitForTrashConsent(): TrashConsent {
        val automation = interactiveUiAutomation()
        val deadline = System.currentTimeMillis() + 60_000L
        while (true) {
            clearSystemAnrDialogs()
            val roots = automation.windows.mapNotNull { it.root } +
                listOfNotNull(automation.rootInActiveWindow)
            val media = roots.firstOrNull {
                it.packageName?.toString()?.startsWith("com.android.providers.media") == true &&
                    button(it) { label -> label.equals("Allow", true) ||
                        label.startsWith("Allow ", true) } != null
            }
            if (media != null) return TrashConsent(media)
            check(System.currentTimeMillis() < deadline) {
                "No MediaProvider Trash consent; windows=" +
                    automation.windows.joinToString { "${it.type}:${it.title}" } +
                    " texts=" + roots.joinToString { texts(it).toString() }
            }
            // trashBatch() preflights on atlasScope, whose effect dispatcher is
            // the v2 rule's test dispatcher: it only advances while Compose idles.
            compose.waitForIdle()
            Thread.sleep(300L)
        }
    }

    private fun button(root: AccessibilityNodeInfo, accept: (String) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            val label = node.text?.toString()?.trim()
            if (label != null && accept(label)) {
                generateSequence(node) { it.parent }.take(4).firstOrNull { it.isClickable }
                    ?.let { return it }
            }
            return (0 until node.childCount).firstNotNullOfOrNull { walk(node.getChild(it), depth + 1) }
        }
        return walk(root, 0)
    }

    private fun texts(node: AccessibilityNodeInfo?, out: MutableSet<String> = LinkedHashSet(),
        depth: Int = 0): Set<String> {
        if (node == null || depth > 30 || out.size > 60) return out
        listOfNotNull(node.text, node.contentDescription).map { it.toString().trim().take(80) }
            .filter { it.isNotEmpty() }.forEach { out += it }
        for (i in 0 until node.childCount) texts(node.getChild(i), out, depth + 1)
        return out
    }
}

/**
 * The states a real Activity cannot reach on demand (partial Copy/Move
 * failure, stale selection, two results in a row, the anchor changing while
 * a result is up) on the real snackbar host, real selection pill and real
 * nav pill, in a Scaffold like the app's over bright and dark tiles.
 */
@RunWith(AndroidJUnit4::class)
class SelectionResultLightStatesTest : SelectionResultStates(dark = false)

@RunWith(AndroidJUnit4::class)
class SelectionResultDarkStatesTest : SelectionResultStates(dark = true)

abstract class SelectionResultStates(private val dark: Boolean) {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun partialStaleReplacedAndReanchoredResults() {
        val theme = if (dark) "dark" else "light"
        val messages = SelectionResultMessages()
        var selecting by mutableStateOf(true)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SelectionResultSnackbarHost(messages) },
                    bottomBar = {
                        if (selecting) SamsungSelectionBottomBar(
                            count = 40, enabled = true, allFavourite = false,
                            onShare = {}, onTrash = {}, onFavourite = {})
                        else AtlasGalleryNavigation(selected = AtlasDestination.PHOTOS, onSelect = {})
                    }
                ) { insets ->
                    // Photos draws under the pill: alternate near-white and near-black tiles.
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = insets.calculateTopPadding())) {
                        items(64) { i ->
                            val bright = (i / 4 + i) % 2 == 0
                            Box(Modifier.padding(1.dp).aspectRatio(1f).background(
                                if (bright) androidx.compose.ui.graphics.Color(0xFFF7F4EC)
                                else androidx.compose.ui.graphics.Color(0xFF0E1214)))
                        }
                    }
                }
            }
        }

        // Copy/Move with some failures: the full, long count text wraps above the pill.
        val partial = albumTransferMessage(AlbumTransferMode.MOVE, "Summer holiday Friesland 2026",
            AlbumTransferResult(done = 37, failed = 3, skipped = 5))
        compose.runOnIdle { messages.show(partial) }
        compose.waitForNode(resultWithText(partial))
        assertAboveAnchor(compose, "atlas-selection-pill")
        saveUiEvidence("selection-result-365-$theme-partial-transfer-selection-pill.png")

        // Stale selection (today's honest copy); replaces the previous result at once.
        val stale = "Selected files changed or became inaccessible. Select again; nothing was trashed."
        compose.runOnIdle { messages.show(stale) }
        compose.waitForNode(resultWithText(stale))
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(1)
        compose.onAllNodes(hasText(partial)).assertCountEquals(0)
        assertAboveAnchor(compose, "atlas-selection-pill")
        saveUiEvidence("selection-result-365-$theme-stale-selection-pill.png")

        // Two results in a row: the second replaces the first, never stacks.
        compose.runOnIdle {
            messages.show("Trash canceled · your photos are unchanged", SelectionResultDuration.SHORT)
            messages.show("Move canceled · your photos are unchanged", SelectionResultDuration.SHORT)
        }
        compose.waitForNode(resultWithText("Move canceled · your photos are unchanged"))
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(1)
        compose.onAllNodes(hasText("Trash canceled · your photos are unchanged")).assertCountEquals(0)
        assertAboveAnchor(compose, "atlas-selection-pill")
        saveUiEvidence("selection-result-365-$theme-replaced-selection-pill.png")

        // Selection ends while the result is up: same message, re-anchored to the nav pill.
        compose.runOnIdle {
            messages.show("Moved selected items to Android Trash")
            selecting = false
        }
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(resultWithText("Moved selected items to Android Trash"))
        compose.onNodeWithTag("atlas-selection-pill").assertDoesNotExist()
        assertAboveAnchor(compose, "atlas-nav-pill")
        saveUiEvidence("selection-result-365-$theme-reanchored-nav-pill.png")

        // It auto-dismisses after the LONG duration (TalkBack is off on CI).
        // The v2 rule's effect clock is virtual: advance it past the timeout.
        compose.mainClock.advanceTimeBy(SelectionResultDuration.LONG.millis + 1_000L)
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("atlas-selection-result")).assertCountEquals(0)
    }
}

/** #376: Android's accessibility "time to take action", stretched for the rotation test. */
private const val A11Y_TIMEOUT_KEY = "accessibility_non_interactive_ui_timeout_ms"
private const val A11Y_TIMEOUT_MS = 90_000L
private const val LANDSCAPE_HOLD_MS = 30_000L
private const val STILL_SHOWN_ADVANCE_MS = 10_000L
private const val GONE_ADVANCE_MS = 55_000L

private fun resultWithText(text: String): SemanticsMatcher =
    hasTestTag("atlas-selection-result-text") and hasText(text)

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    val found = fetchSemanticsNodes().size
    assertEquals("expected $n node(s)", n, found)
}

/**
 * The brief's geometry: the result is centred, at most 352 dp wide, its
 * bottom 12–16 dp above the top of the visible pill [anchorTag], and it never
 * overlaps the pill or reaches the system navigation bar.
 */
private fun assertAboveAnchor(compose: ComposeTestRule, anchorTag: String) {
    // Re-measure until the layout settles: right after S24DisplayRule's
    // wm size/density change the window insets can still be moving, and one
    // run measured 11.0 dp while the same head's other run passed. A layout
    // that stays outside the brief still fails, with the last measurement.
    val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
    while (true) {
        compose.waitForIdle()
        val problem = anchorProblem(compose, anchorTag) ?: return
        if (android.os.SystemClock.uptimeMillis() >= deadline) throw AssertionError(problem)
        Thread.sleep(250L)
    }
}

private fun anchorProblem(compose: ComposeTestRule, anchorTag: String): String? {
    val result: DpRect = compose.onNodeWithTag("atlas-selection-result").getUnclippedBoundsInRoot()
        .let { DpRect(it.left, it.top, it.right, it.bottom) }
    val pill = compose.onNodeWithTag(anchorTag).getUnclippedBoundsInRoot()
    val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        .maxBy { it.boundsInRoot.width * it.boundsInRoot.height }.boundsInRoot
    val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    val rootWidth: Dp = (root.width / density).dp
    val gap = pill.top - result.bottom
    val centre = (result.left + result.right) / 2
    val describe = "result=$result $anchorTag=$pill gap=$gap rootWidth=$rootWidth"
    return when {
        gap < 11.5.dp || gap > 16.5.dp -> "Gap above the pill must be 12–16 dp: $describe"
        result.right - result.left > 352.5.dp -> "At most 352 dp wide: $describe"
        abs((centre - rootWidth / 2).value) > 1.5f -> "Centred: $describe"
        result.bottom >= pill.top -> "Never over the pill: $describe"
        result.bottom - result.top < 40.dp -> "Readable height: $describe"
        else -> null
    }
}

/** Shell night mode is restored even when the journey fails. */
private class SelectionResultNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("cmd uimode night").substringAfter(":").trim()
                shell("cmd uimode night " + if (dark) "yes" else "no")
                try {
                    base.evaluate()
                } finally {
                    if (before in setOf("yes", "no", "auto")) shell("cmd uimode night $before")
                }
            }
        }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().decodeToString() }
    }
}

/**
 * Twelve invented squares, alternating near-white and near-black, dated two
 * days ahead so they lead Newest first. Deleted afterwards, trashed or not.
 */
private class BrightDarkFixtures(private val tag: String) : TestRule {
    private val uris = mutableListOf<Uri>()
    val ids: List<Long> get() = uris.map { ContentUris.parseId(it) }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val target = instrumentation.targetContext
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it)
                }
                clearGalleryInventoryCacheForTest(target)
                try {
                    insertAll(target)
                    base.evaluate()
                } finally {
                    uris.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }

    /** Fixtures among [ids[0]] and [ids[1]] that are not in Android Trash. */
    fun untrashedCount(): Int {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns._ID} IN (?, ?)")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(ids[0].toString(), ids[1].toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }
        return resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count } ?: -1
    }

    private fun insertAll(context: android.content.Context) {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val noon = java.time.LocalDate.now().plusDays(2).atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(12) { i ->
            val taken = noon - i * 60_000L
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + stamp.format(
                    java.time.Instant.ofEpochMilli(taken).atZone(java.time.ZoneId.systemDefault())) +
                    "_result$tag$i.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Result-Fixture-$tag/")
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Could not create synthetic result fixture $i"
            }
            uris += uri
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            // Checkerboard of a bright and a dark tile per row, like real photos behind the pill.
            val bright = (i / 4 + i) % 2 == 0
            bitmap.eraseColor(if (bright) Color.rgb(247, 244, 236) else Color.rgb(14, 18, 20))
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
            }, null, null)
        }
    }
}
