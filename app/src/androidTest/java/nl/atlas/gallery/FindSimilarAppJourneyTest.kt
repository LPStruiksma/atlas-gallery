package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * VS-S2b (#468) in the real Activity, with three locally drawn PNGs: the
 * "Visual search index" panel opens from People ⋮, and "Find similar" from
 * the viewer on a photo whose library is not indexed ends in its typed state
 * (never a spinner); Back returns to the same photo. Nothing is opted in, so
 * no model runs. Fixtures are inserted by this app, emulator only, and
 * deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FindSimilarAppJourneyTest {
    private val fixtures = SyntheticSimilarFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // Grant + insert BEFORE the Activity launches, delete after it finishes.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    // Photos opens on the timeline (atlas-timeline-media-<id>); the plain grid
    // tags atlas-grid-media-<id>-photo. Tags on a clickable tile are merged
    // away, so both are looked up in the unmerged tree.
    private val gridPhoto = SemanticsMatcher("a photo tile") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let {
            it.startsWith("atlas-timeline-media-") ||
                (it.startsWith("atlas-grid-media-") && it.endsWith("-photo"))
        } == true
    }

    @Test fun peopleShowsTheIndexPanelAndFindSimilarReachesATypedState() {
        // The shell (bottom bar included) waits for the first MediaStore scan.
        waitFor(hasTestTag("atlas-nav-people"))
        compose.onNodeWithTag("atlas-nav-people").performClick()
        // #643: the index opt-in lives in ⋮ → Library status & settings.
        waitFor(hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(hasTestTag("atlas-library-status"))
        compose.onNodeWithTag("atlas-library-status").performClick()
        waitFor(hasTestTag("atlas-people-visual-index"))
        compose.onNodeWithTag("atlas-people-visual-index").performClick()
        waitFor(hasTestTag("atlas-visual-index"))
        compose.onNodeWithTag("atlas-visual-index-switch").assertExists()
        dismissWithBack(compose, hasTestTag("atlas-visual-index"))

        compose.onNodeWithTag("atlas-nav-photos").performClick()
        waitFor(gridPhoto)
        compose.onAllNodes(gridPhoto, useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("atlas-viewer-find-similar"))
        compose.onNodeWithTag("atlas-viewer-find-similar").performClick()

        // Face discovery and the visual index are off: a typed message with a
        // way to the settings switch, not "Comparing photos…" forever.
        waitFor(hasTestTag("atlas-find-similar-index-off"))
        compose.onNodeWithTag("atlas-visual-search-loading").assertDoesNotExist()
        compose.onNodeWithTag("atlas-find-similar-open-people").assertExists()

        // Back returns to the photo Find similar started from.
        compose.onNodeWithTag("atlas-find-similar-back").performClick()
        waitFor(hasTestTag("atlas-viewer-find-similar"))

        // From Search: the chip opens the reference chooser.
        compose.onNodeWithTag("atlas-back").performClick()
        waitFor(hasTestTag("atlas-nav-search"))
        compose.onNodeWithTag("atlas-nav-search").performClick()
        waitFor(hasTestTag("atlas-search-find-similar"))
        compose.onNodeWithTag("atlas-search-find-similar").performClick()
        waitFor(hasTestTag("atlas-face-reference-chooser"))
        // The chooser filters its photos on Dispatchers.Default, which Compose's
        // idle sync does not wait for: wait for the first tile itself.
        waitFor(hasTestTag("atlas-face-reference-0"))
        compose.onNodeWithTag("atlas-face-reference-0").performClick()
        waitFor(hasTestTag("atlas-find-similar-index-off"))
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(60_000L) {
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for ${matcher.description}. Screen:\n$tree", e)
        }
    }
}

/** Invented, locally drawn images only; never real photos. */
private class SyntheticSimilarFixtures : TestRule {
    private val inserted = mutableListOf<Uri>()

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
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }

    private fun insertAll(context: android.content.Context) {
        (1..3).forEach { step ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_SIMILAR_FIXTURE_$step.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Similar-Fixture/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Could not create synthetic fixture $step"
            }
            inserted += uri
            val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(60 * step, 140, 220 - 50 * step))
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
    }
}
