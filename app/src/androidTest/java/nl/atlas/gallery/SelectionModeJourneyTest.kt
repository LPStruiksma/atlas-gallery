package nl.atlas.gallery

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #556 Samsung selection mode in the real Activity, on twelve invented PNGs
 * (two days of six, newest in the grid) inserted by this test and deleted afterwards: long-press
 * one tile, long-press-drag across six, a date header's check circle, then
 * Share. The chooser launch is intercepted, so no share target ever opens.
 * Screenshots go to the emulator-only Pictures/ATLAS-UI-Evidence folder.
 */
@RunWith(AndroidJUnit4::class)
class SelectionModeJourneyTest {
    private val fixtures = SelectionFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun longPressDragAcrossSixHeaderCheckThenShare() {
        val ids = fixtures.ids
        // Header tags carry the day bucket key. The fixtures are dated in the
        // next two days so they stay on top of Newest first, above any other
        // emulator image (UI-evidence screenshots land on today).
        val dayOne = "atlas-timeline-header-header:DAY:${fixtures.firstDay}"
        val dayTwo = "atlas-timeline-header-header:DAY:${fixtures.firstDay.minusDays(1)}"
        waitFor(hasTestTag("atlas-nav-photos"))
        waitFor(hasTestTag("atlas-timeline-media-${ids[11]}"))
        compose.waitForIdle()

        // 1. Long-press one tile: selection with exactly that tile, tab bar swapped for actions.
        compose.onNodeWithTag("atlas-timeline-media-${ids[2]}").performTouchInput { longClick() }
        waitFor(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[2]}").assertIsSelected()
        compose.onNodeWithTag("atlas-selection-bottom-bar").assertExists()
        compose.onNodeWithTag("atlas-nav-photos").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("selection-556-single.png")

        // Tapping it again leaves Samsung's empty selection mode open.
        compose.onNodeWithTag("atlas-timeline-media-${ids[2]}").performClick()
        waitFor(hasTestTag("atlas-selection-count") and hasText("Select items"))

        // 2. Long-press the first tile and, without lifting, drag to the sixth (next row).
        val grid = compose.onNodeWithTag("atlas-timeline-grid").fetchSemanticsNode().boundsInRoot
        fun centre(id: Long): Offset =
            compose.onNodeWithTag("atlas-timeline-media-$id").fetchSemanticsNode()
                .boundsInRoot.center - grid.topLeft
        val from = centre(ids[0])
        val to = centre(ids[5])
        compose.onNodeWithTag("atlas-timeline-grid").performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 150L)
            val steps = 12
            for (i in 1..steps) {
                moveTo(from + (to - from) * (i / steps.toFloat()))
                advanceEventTime(16L)
            }
            up()
        }
        waitFor(hasTestTag("atlas-selection-count") and hasText("6 selected"))
        // A LazyVerticalGrid only emits semantics for the visible rows. The
        // count proves six selections, but inspect each real tile after
        // bringing it into view to prove the RIGHT six were selected.
        fun assertTileSelected(id: Long, selected: Boolean) {
            val tag = "atlas-timeline-media-$id"
            compose.onNodeWithTag("atlas-timeline-grid")
                .performScrollToNode(hasTestTag(tag))
            if (selected) compose.onNodeWithTag(tag).assertIsSelected()
            else compose.onNodeWithTag(tag).assertIsNotSelected()
        }
        (0..5).forEach { assertTileSelected(ids[it], selected = true) }
        assertTileSelected(ids[6], selected = false)
        // T6: a whole day's selected state belongs to the header itself.
        // Scroll before each check: headers can also leave the lazy tree.
        waitForHeader(dayOne)
        compose.onNodeWithTag(dayOne).assertIsSelected()
        waitForHeader(dayTwo)
        compose.onNodeWithTag(dayTwo).assertIsNotSelected()
        compose.waitForIdle()
        saveUiEvidence("selection-556-drag-range.png")

        // 3. The second day's header circle selects its whole group.
        compose.onNodeWithTag(dayTwo).performClick()
        waitFor(hasTestTag("atlas-selection-count") and hasText("12 selected"))
        compose.onNodeWithTag(dayTwo).assertIsSelected()
        compose.waitForIdle()
        saveUiEvidence("selection-556-header-group.png")
        // ...and a second tap clears it again.
        compose.onNodeWithTag(dayTwo).performClick()
        waitFor(hasTestTag("atlas-selection-count") and hasText("6 selected"))

        // 4. Share hands Android's chooser exactly the six selected items.
        var shared: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                shared = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("atlas-selection-share").performClick()
            compose.waitUntil(30_000L) { shared != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        val send = requireNotNull(shared).let {
            @Suppress("DEPRECATION") it.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send?.action)
        @Suppress("DEPRECATION")
        val streams = send!!.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        // The app shares the scanned URIs (external_primary volume) while the
        // fixtures were inserted via "external": compare MediaStore ids.
        assertEquals((0..5).map { ids[it] }.toSet(),
            streams.map { ContentUris.parseId(it) }.toSet())

        // Back leaves selection; the tab bar returns.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasTestTag("atlas-nav-photos"))
        compose.onNodeWithTag("atlas-selection-bottom-bar").assertDoesNotExist()
    }

    /** Fails with the header tags actually on screen, so a date mismatch is visible in CI. */
    private fun waitForHeader(tag: String) {
        try {
            compose.onNodeWithTag("atlas-timeline-grid")
                .performScrollToNode(hasTestTag(tag))
            waitFor(hasTestTag(tag))
        } catch (e: Throwable) {
            val shown = compose.onAllNodes(SemanticsMatcher("timeline header") { node ->
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
                    ?.startsWith("atlas-timeline-header-") == true
            }).fetchSemanticsNodes().map {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
            }
            throw AssertionError("No $tag; headers shown: $shown", e)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/** Twelve invented, locally drawn squares: six on [firstDay], six the day before. Newest first. */
private class SelectionFixtures : TestRule {
    val uris = mutableListOf<Uri>()
    val ids: List<Long> get() = uris.map { ContentUris.parseId(it) }
    val firstDay: java.time.LocalDate = java.time.LocalDate.now().plusDays(2)

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

    private fun insertAll(context: android.content.Context) {
        val nameStamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val noon = firstDay.atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(12) { i ->
            val day = i / 6
            val taken = noon - day * 86_400_000L - (i % 6) * 60_000L
            val values = ContentValues().apply {
                // Camera-style name: if MediaStore's scan drops the app-set
                // DATE_TAKEN of a PNG, the gallery's filename hint gives the same time.
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + nameStamp.format(
                    java.time.Instant.ofEpochMilli(taken).atZone(java.time.ZoneId.systemDefault())) +
                    "_select$i.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Selection-Fixture/")
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Could not create synthetic selection fixture $i"
            }
            uris += uri
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(40 + 15 * i, 120 + 6 * i, 210 - 12 * i))
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
        check(Build.VERSION.SDK_INT >= 29)
    }
}
