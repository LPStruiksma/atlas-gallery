package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * E2a (#439) end to end in the real Activity: four invented PNGs dated on
 * today's month/day in earlier (leap-safe) years make On this day qualify.
 * Albums → Stories entry → grid → story → slideshow (moment 2) → recreate
 * → same moment (#451) → viewer →
 * Back → same slideshow moment (#444) → Back → grid → Back → Albums.
 * Fixtures are inserted by this app, emulator only, and deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class StoriesJourneyTest {
    private val fixtures = SyntheticStoryFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // Grant + insert BEFORE the Activity launches, delete after it finishes.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun albumsStoriesEntryOpensSlideshowViewerAndBackReturnsToAlbums() {
        // With no cached catalogue (cleared by the fixture rule) the shell
        // appears once the first MediaStore scan publishes; wait for it.
        waitFor(hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(hasTestTag("atlas-albums-menu-stories"))
        compose.onNodeWithTag("atlas-albums-menu-stories").performClick()

        waitFor(hasTestTag("atlas-stories-grid"))
        compose.onNodeWithTag("atlas-story-on_this_day").performClick()
        waitFor(hasTestTag("atlas-story-player"))
        // Pause, then step to the second moment so "same item" is observable.
        compose.onNodeWithTag("atlas-story-pause").performClick()
        compose.onNodeWithTag("atlas-story-next").performClick()
        waitFor(hasContentDescription("Moment 2 of", substring = true))

        // #451: a real Activity recreate (rotation) keeps the open story at
        // moment 2, paused, once the re-planned deck for the new tokens lands.
        compose.activityRule.scenario.recreate()
        waitFor(hasTestTag("atlas-story-player"))
        compose.onAllNodes(hasContentDescription("Moment 2 of", substring = true))
            .assertCountEquals(1)
        compose.onNodeWithContentDescription("Resume slideshow").assertExists()
        compose.onNodeWithTag("atlas-stories-grid").assertDoesNotExist()

        compose.onNodeWithTag("atlas-story-open-gallery").performClick()

        // The existing viewer route, re-authorized against the live publication.
        waitFor(hasTestTag("atlas-photo-counter"))
        compose.onNodeWithTag("atlas-story-player").assertDoesNotExist()
        compose.onNodeWithTag("atlas-back").performClick()

        // #444: Back from the viewer reopens the slideshow at that same moment.
        waitFor(hasTestTag("atlas-story-player"))
        compose.onNodeWithTag("atlas-photo-counter").assertDoesNotExist()
        compose.onAllNodes(hasContentDescription("Moment 2 of", substring = true))
            .assertCountEquals(1)
        compose.onNodeWithContentDescription("Resume slideshow").assertExists()

        // Second Back closes the slideshow onto the story grid.
        pressBack()
        waitFor(hasTestTag("atlas-stories-grid"))
        compose.onNodeWithTag("atlas-story-player").assertDoesNotExist()

        // System Back closes Stories and lands on the Albums tab itself.
        pressBack()
        waitFor(hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-stories-grid").assertDoesNotExist()
        compose.onNodeWithTag("atlas-header-more").assertExists()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/** Invented, locally drawn images only; never real photos. */
private class SyntheticStoryFixtures : TestRule {
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
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd")
        // Multiples of four years keep a Feb 29 run on a real leap day.
        (1L..4L).forEach { step ->
            val day = today.minusYears(4L * step)
            val taken = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,
                    "IMG_${day.format(stamp)}_10000${step}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Stories-Fixture/")
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Could not create synthetic story fixture $step"
            }
            inserted += uri
            val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(40 * step.toInt(), 120, 200 - 30 * step.toInt()))
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
