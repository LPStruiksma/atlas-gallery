package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurepersistence.HiddenMediaStore
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * HIDE-1 (#585) in the real Activity at the S24+ size, on four invented PNGs
 * this test draws, inserts and deletes: selection More → Hide removes two items
 * from Photos, the files never move, Albums ⋮ → Hidden lists exactly them, a
 * long-press selection Unhides them, and viewer More → Hide drops the open item.
 * Screenshots go to the emulator-only UI-evidence folder, one set per theme.
 */
abstract class HiddenItemsJourney(private val dark: Boolean) {
    private val fixtures = HideFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val theme = if (dark) "dark" else "light"

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark)).around(fixtures).around(compose)

    @Test fun hideFromSelectionAndViewerThenFindAndUnhideInHidden() {
        val ids = fixtures.ids
        awaitNode(hasTestTag("atlas-nav-photos"))
        awaitNode(hasTestTag("atlas-timeline-media-${ids[3]}"))
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-timeline-grid")
            .performScrollToNode(hasTestTag("atlas-timeline-media-${ids[0]}"))

        step("start")
        // 1. Select two items, More → Hide.
        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}").performTouchInput { longClick() }
        awaitNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[1]}").performClick()
        awaitNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.tap(hasTestTag("atlas-selection-more"))
        awaitNode(hasTestTag("atlas-selection-hide"))
        compose.waitForIdle()
        step("save"); saveUiEvidence("hidden-585-selection-more-$theme")
        compose.tap(hasTestTag("atlas-selection-hide"))

        // Both leave Photos at once; the other two stay; no file moved or was deleted.
        compose.awaitThat("hidden items leave Photos") {
            compose.onAllNodes(hasTestTag("atlas-timeline-media-${ids[0]}")).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasTestTag("atlas-timeline-media-${ids[1]}")).fetchSemanticsNodes().isEmpty()
        }
        awaitNode(hasTestTag("atlas-hidden-result-undo"))
        awaitNode(hasTestTag("atlas-timeline-media-${ids[2]}"))
        compose.waitForIdle()
        step("save"); saveUiEvidence("hidden-585-after-hide-$theme")
        assertEquals(4, fixtures.namesIn(FIXTURE_PATH).size)
        ids.forEach { assertEquals(FIXTURE_PATH, fixtures.relativePathOf(it)) }
        // Durable: the set survives in the private store, as two media keys.
        val stored = HiddenMediaStore(fixtures.context).readOrNull()
        assertEquals(2, stored?.size)

        step("part2")
        // 2. Albums ⋮ → Hidden lists exactly the two hidden items.
        compose.tap(hasTestTag("atlas-nav-albums"))
        awaitNode(hasTestTag("atlas-header-more"))
        compose.tap(hasTestTag("atlas-header-more"))
        awaitNode(hasTestTag("atlas-albums-menu-hidden"))
        compose.tap(hasTestTag("atlas-albums-menu-hidden"))
        awaitNode(hasTestTag("atlas-hidden-grid"))
        compose.awaitThat("two hidden tiles") {
            compose.onAllNodes(hasTestTag("atlas-hidden-tile")).fetchSemanticsNodes().size == 2
        }
        compose.waitForIdle()
        step("save"); saveUiEvidence("hidden-585-filled-$theme")

        step("part3")
        // 3. Long-press a tile, Unhide: the collection empties, Photos shows them again.
        compose.onAllNodesWithTag("atlas-hidden-tile")[0].performTouchInput { longClick() }
        awaitNode(hasTestTag("atlas-hidden-unhide"))
        compose.onAllNodesWithTag("atlas-hidden-tile")[1].performClick()
        compose.tap(hasTestTag("atlas-hidden-unhide"))
        awaitNode(hasTestTag("atlas-hidden-empty"))
        compose.waitForIdle()
        step("save"); saveUiEvidence("hidden-585-empty-$theme")
        compose.tap(hasTestTag("atlas-hidden-back"))
        compose.tap(hasTestTag("atlas-nav-photos"))
        awaitNode(hasTestTag("atlas-timeline-media-${ids[0]}"))
        awaitNode(hasTestTag("atlas-timeline-media-${ids[1]}"))
        assertEquals(0, HiddenMediaStore(fixtures.context).readOrNull()?.size)

        step("part4")
        // 4. Viewer More → Hide hides the open item and moves on to a neighbour.
        compose.onNodeWithTag("atlas-timeline-grid")
            .performScrollToNode(hasTestTag("atlas-timeline-media-${ids[2]}"))
        compose.onNodeWithTag("atlas-timeline-media-${ids[2]}").performClick()
        awaitNode(hasTestTag("atlas-viewer-more"))
        compose.tap(hasTestTag("atlas-viewer-more"))
        awaitNode(hasTestTag("atlas-viewer-hide"))
        compose.waitForIdle()
        step("save"); saveUiEvidence("hidden-585-viewer-more-$theme")
        compose.tap(hasTestTag("atlas-viewer-hide"))
        compose.awaitThat("viewer item is hidden") {
            HiddenMediaStore(fixtures.context).readOrNull()?.size == 1
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitNode(hasTestTag("atlas-timeline-grid"))
        compose.awaitThat("viewer item left Photos") {
            compose.onAllNodes(hasTestTag("atlas-timeline-media-${ids[2]}")).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithTag("atlas-timeline-media-${ids[3]}").assertCountEquals(1)
        assertEquals(4, fixtures.namesIn(FIXTURE_PATH).size)
        step("end")
        val listing = shellOut("ls /data/local/tmp/atlas-ui-evidence")
        assertTrue("no hidden-585-$theme evidence on device: $listing",
            listing.lines().count { it.contains("hidden-585-") && it.contains(theme) } == 5)
    }

    private fun step(what: String) =
        android.util.Log.i("ATLAS_TESTKIT", "hide-journey $theme $what")

    private fun shellOut(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    private fun awaitNode(matcher: SemanticsMatcher) =
        compose.awaitThat("a node matching $matcher") { compose.exists(matcher) }

    private companion object {
        const val FIXTURE_PATH = "Pictures/ATLAS-Hide-Fixture/"
    }

    /** Four invented squares dated three days ahead (top of Newest first); removed afterwards. */
    private class HideFixtures : TestRule {
        private val uris = mutableListOf<Uri>()
        val ids: List<Long> get() = uris.map { ContentUris.parseId(it) }
        lateinit var context: Context

        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    context = instrumentation.targetContext
                    listOf(Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO).forEach {
                        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
                    }
                    clearGalleryInventoryCacheForTest(context)
                    reset()
                    try {
                        insertAll()
                        base.evaluate()
                    } finally {
                        reset()
                    }
                }
            }

        private fun reset() {
            runCatching {
                context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(FIXTURE_PATH))
            }
            context.deleteDatabase("atlas_hidden_media.db")
        }

        fun namesIn(path: String): List<String> = buildList {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf(path), null)?.use { while (it.moveToNext()) add(it.getString(0)) }
        }

        fun relativePathOf(id: Long): String? =
            context.contentResolver.query(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }

        private fun insertAll() {
            val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            val noon = java.time.LocalDate.now().plusDays(3).atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            repeat(4) { i ->
                val taken = noon - i * 60_000L
                val name = "IMG_" + stamp.format(java.time.Instant.ofEpochMilli(taken)
                    .atZone(java.time.ZoneId.systemDefault())) + "_hide$i.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, FIXTURE_PATH)
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create fixture $i" }
                uris += uri
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(200 - 30 * i, 80 + 25 * i, 60 + 35 * i))
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
            assertTrue(namesIn(FIXTURE_PATH).size == 4)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class HiddenItemsLightJourneyTest : HiddenItemsJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class HiddenItemsDarkJourneyTest : HiddenItemsJourney(dark = true)
