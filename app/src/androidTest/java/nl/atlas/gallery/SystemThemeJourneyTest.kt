package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import nl.atlas.gallery.featuredesign.darkGalleryColors
import nl.atlas.gallery.featuredesign.lightGalleryColors
import kotlin.math.abs
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream

/**
 * #662: ATLAS follows the phone's light/dark setting. Each class launches the
 * real MainActivity with the system night mode set, checks the page colour
 * behind the tabs, and saves the four main tabs plus Photo DNA as evidence.
 * Synthetic fixtures only; the night mode is restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SystemLightThemeJourneyTest : SystemThemeJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class SystemDarkThemeJourneyTest : SystemThemeJourney(dark = true)

abstract class SystemThemeJourney(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(NightModeRule(dark)).around(ThemeFixtures()).around(compose)

    private val mode = if (dark) "dark" else "light"

    /**
     * DESIGN-12: check the actual Activity window, not a Compose-only theme
     * override. The same journey saves full-device light/dark captures on the
     * Photos and Search tabs, which include Android's system bars.
     */
    @Test fun mainScreenSystemBarIconsMatchSystemTheme() {
        waitFor(hasTestTag("atlas-nav-photos"))
        listOf("photos", "search").forEach { tab ->
            compose.onNodeWithTag("atlas-nav-$tab").performClick()
            settle()
            compose.activityRule.scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(
                    activity.window, activity.window.decorView
                )
                assertEquals("$mode $tab status bar icons", !dark,
                    controller.isAppearanceLightStatusBars)
                assertEquals("$mode $tab navigation bar icons", !dark,
                    controller.isAppearanceLightNavigationBars)
            }
            saveUiEvidence("design-12-$tab-$mode.png")
        }
    }

    @Test fun mainTabsAndPhotoDnaFollowTheSystemSetting() {
        waitFor(hasTestTag("atlas-nav-photos"))
        compose.activityRule.scenario.onActivity {
            assertEquals("activity sees the system $mode setting",
                dark, it.resources.configuration.isNightMode())
        }
        listOf("photos", "albums", "people", "search").forEach { tab ->
            compose.onNodeWithTag("atlas-nav-$tab").performClick()
            settle()
            saveUiEvidence("theme-662-$tab-$mode.png")
            // #685: capture the real selected and inactive pill icons on
            // both primary tabs, in both system themes, at S24+ resolution.
            if (tab == "photos" || tab == "albums") {
                compose.onNodeWithTag("atlas-nav-$tab").assertIsSelected()
                val other = if (tab == "photos") "albums" else "photos"
                compose.onNodeWithTag("atlas-nav-$other").assertIsNotSelected()
                saveUiEvidence("nav-685-$tab-$mode.png")
            }
        }
        compose.tap(hasTestTag("atlas-nav-albums"))
        compose.awaitThat("the Albums tab selected") {
            compose.exists(hasTestTag("atlas-nav-albums") and isSelected())
        }
        openMenuItem("atlas-header-more", "atlas-albums-menu-photo-dna")
        waitFor(hasTestTag("photo-dna-exact-group"))
        settle()
        compose.onNodeWithTag("atlas-photo-dna-title").assertTextEquals("Photo DNA")
        check(compose.onAllNodes(hasText("None selected")).fetchSemanticsNodes().isEmpty())
        saveUiEvidence("theme-662-photo-dna-$mode.png")
        saveUiEvidence("theme-681-photo-dna-$mode.png")

        // The viewer stays dark with light bar icons under either setting.
        compose.onAllNodes(hasTestTag("atlas-photo-dna-thumb")).onFirst().performClick()
        waitFor(hasTestTag("atlas-photo-counter"))
        settle()
        compose.activityRule.scenario.onActivity {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            assertFalse("viewer status bar icons are dark under $mode",
                bars.isAppearanceLightStatusBars)
            assertFalse("viewer navigation bar icons are dark under $mode",
                bars.isAppearanceLightNavigationBars)
        }
        assertDarkBottomStrip("viewer under $mode")
        saveUiEvidence("theme-662-viewer-$mode.png")
        waitFor(hasTestTag("atlas-filmstrip"))
        saveUiEvidence("parity-24-viewer-filmstrip-$mode.png")
    }


    /**
     * #24: capture real album detail and Hidden People under each system
     * theme, through MainActivity with only invented fixture images.
     */
    @Test fun parityAlbumDetailAndHiddenPeopleRoutes() {
        waitFor(hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        val fixtureAlbum = hasTestTag("atlas-album-card") and
            hasText("ATLAS-Theme-Fixture", substring = true)
        waitFor(fixtureAlbum)
        compose.onAllNodes(fixtureAlbum).onFirst().performClick()
        waitFor(hasTestTag("atlas-header-title") and
            hasText("ATLAS-Theme-Fixture", substring = true))
        settle()
        saveUiEvidence("parity-24-albums-detail-$mode.png")
        // On entry, the detail title, Back and sort remain visible in both themes.
        compose.onNodeWithTag("atlas-photos-sort").assertIsDisplayed()
        compose.onNodeWithTag("atlas-header-back").assertIsDisplayed()
        compose.onNodeWithTag("atlas-header-title").assertIsDisplayed()
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(hasTestTag("atlas-nav-people"))
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor(hasTestTag("atlas-people-home"))
        openMenuItem("atlas-header-more", "atlas-people-hidden-open")
        waitFor(hasTestTag("atlas-people-hidden"))
        settle()
        saveUiEvidence("parity-24-people-hidden-$mode.png")
    }

    /**
     * #670 review: the navigation bar area must be dark behind the viewer's
     * light glyphs. Samples the bottom 48 px of a real screenshot.
     */
    private fun assertDarkBottomStrip(what: String) {
        // Compose idle does not mean SurfaceFlinger composited the viewer: one
        // CI shot still showed the light route below (#82, mean 0.789). Poll up
        // to 5 s; the threshold is unchanged.
        var mean = bottomStripMean()
        runCatching {
            compose.awaitThat("a dark navigation bar strip under $what", timeoutMs = 5_000L) {
                mean = bottomStripMean()
                mean < 0.35
            }
        }
        assertTrue("$what: navigation bar strip is light (mean luminance $mean)",
            mean < 0.35)
    }

    private fun bottomStripMean(): Double {
        val shot = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.takeScreenshot()) { "Screenshot acquisition failed" }
        try {
            var sum = 0.0
            var n = 0
            for (y in shot.height - 48 until shot.height step 4) {
                for (x in 0 until shot.width step 8) {
                    val c = shot.getPixel(x, y)
                    sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) +
                        0.0722 * Color.blue(c)) / 255.0
                    n++
                }
            }
            return sum / n
        } finally {
            shot.recycle()
        }
    }


    /**
     * #679: enter the actual Photos selection route under both phone themes.
     * A single selected synthetic tile leaves "All" and its date group
     * unchecked. Their rendered icon pixels must use the page's contrasting
     * colour; the photo-overlay circles are covered by existing selection tests.
     */
    @Test fun uncheckedSelectionPageRingsContrastWithBackground() {
        // A previous sort journey may have persisted non-date ordering. Force
        // the dated view before looking for a date header's selection circle.
        waitFor(hasTestTag("atlas-nav-photos"))
        openMenuItem("atlas-photos-sort", "atlas-media-sort-date_created")
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        // MediaStore inventory loads off the Compose frame clock. Wait for the
        // fixture's actual tile rather than sampling immediately after nav appears.
        val visibleTile = SemanticsMatcher("visible timeline tile") { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
                ?.startsWith("atlas-timeline-media-") == true
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodes(visibleTile, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val tileTag = requireNotNull(
            compose.onAllNodes(visibleTile, useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrElseNullable(SemanticsProperties.TestTag) { null }
        ) { "Synthetic Photos fixture must expose a timeline tile" }
        compose.onNodeWithTag(tileTag).performTouchInput { longClick() }
        waitFor(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.waitForIdle()

        // The icon test tag is on the unchecked PAGE icon, not the row, so the
        // assertions inspect actual pixels of the All and date-header rings.
        val icons = compose.onAllNodesWithTag("atlas-selection-page-ring", useUnmergedTree = true)
        val count = icons.fetchSemanticsNodes().size
        assertTrue("Expected unchecked All and date-header circles in $mode; got $count",
            count >= 2)
        val ink = if (dark) darkGalleryColors.onSurfaceVariant else lightGalleryColors.onSurfaceVariant
        for (index in 0 until count) {
            val map = icons[index].captureToImage().toPixelMap()
            var inkPixels = 0
            for (x in 0 until map.width) for (y in 0 until map.height) {
                val pixel = map[x, y]
                if (abs(pixel.red - ink.red) < 0.08f &&
                    abs(pixel.green - ink.green) < 0.08f &&
                    abs(pixel.blue - ink.blue) < 0.08f) inkPixels++
            }
            assertTrue("Page ring $index in $mode has no painted onSurfaceVariant pixels",
                inkPixels >= 4)
        }
        saveUiEvidence("selection-679-page-$mode.png")
    }

    /** #664: Settings is reached from all three Samsung-style ⋮ menus. */
    @Test fun settingsFromPhotosAlbumsPeopleFollowSystemTheme() {
        waitFor(hasTestTag("atlas-nav-photos"))
        listOf("photos", "albums", "people").forEach { tab ->
            compose.onNodeWithTag("atlas-nav-$tab").performClick()
            waitFor(hasTestTag("atlas-header-more"))
            if (tab == "photos") {
                // The Photos sort icon remains available on the Photos route.
                compose.onNodeWithTag("atlas-photos-sort").assertExists()
            }
            compose.tap(hasTestTag("atlas-header-more"))
            compose.awaitThat("the $tab overflow menu open") {
                compose.exists(hasTestTag("atlas-library-status"))
            }
            settle()
            saveUiEvidence("settings-664-menu-$tab-$mode.png")
            // Assert the Settings menu entry is still visible before tapping,
            // then observe the destination header before inspecting its content.
            compose.onNodeWithTag("atlas-library-status").assertIsDisplayed()
            compose.tap(hasTestTag("atlas-library-status"))
            waitFor(hasTestTag("atlas-header-title") and hasText("Settings"))
            waitFor(hasTestTag("atlas-settings-screen"))
            if (tab == "photos") {
                // #664 review: Photos actions must not leak into the Settings header.
                compose.onNodeWithTag("atlas-photos-sort").assertDoesNotExist()
            }
            listOf("atlas-settings-face-scanning", "atlas-people-numbers-open",
                "atlas-settings-theme", "atlas-settings-advanced",
                "atlas-settings-version").forEach {
                compose.onNodeWithTag(it).assertExists()
            }
            // Advanced is collapsed until deliberately opened.
            compose.onNodeWithTag("atlas-people-import-v4").assertDoesNotExist()
            settle()
            saveUiEvidence("settings-664-$tab-$mode.png")
            compose.onNodeWithTag("atlas-settings-advanced").performClick()
            listOf("atlas-people-import-v4", "atlas-people-link-v4",
                "atlas-settings-diagnostics").forEach {
                compose.onNodeWithTag(it).assertExists()
            }
            if (tab == "photos") {
                settle()
                saveUiEvidence("settings-664-advanced-$mode.png")
                compose.onNodeWithTag("atlas-settings-diagnostics").performScrollTo().performClick()
                waitFor(hasTestTag("atlas-header-title") and hasText("Library status"))
                waitFor(hasTestTag("atlas-header-back"))
                settle()
                saveUiEvidence("settings-664-diagnostics-$mode.png")
                // Diagnostics Back must reveal Settings, not the Photos route.
                compose.onNodeWithTag("atlas-header-back").performClick()
                waitFor(hasTestTag("atlas-header-title") and hasText("Settings"))
                waitFor(hasTestTag("atlas-settings-screen"))
            }
            compose.onNodeWithTag("atlas-header-back").performClick()
            waitFor(hasTestTag("atlas-nav-$tab"))
        }
    }

    @Test fun photoAndAlbumIconsHaveDistinctActiveAndInactiveVectors() {
        val photos = AtlasDestination.PHOTOS
        val albums = AtlasDestination.ALBUMS
        assertNotEquals("Photos and Albums need distinguishable inactive glyphs",
            photos.icon, albums.icon)
        assertNotEquals("Photos and Albums need distinguishable active glyphs",
            photos.selectedIcon, albums.selectedIcon)
        assertNotEquals("Photos selected state must be filled", photos.icon, photos.selectedIcon)
        assertNotEquals("Albums selected state must be filled", albums.icon, albums.selectedIcon)
    }

    /**
     * Before a screenshot or a window check: Compose idle (its animations run
     * on the test clock) and the window settled (rule 6), instead of the old
     * fixed 1 s pause.
     */
    private fun settle() {
        compose.waitForIdle()
        compose.awaitWindowSettled()
    }

    /** Taps [opener], waits for [item] in the window it opens (rule 5), then taps [item]. */
    private fun openMenuItem(opener: String, item: String) {
        compose.tap(hasTestTag(opener))
        compose.awaitThat("$item in the window opened by $opener") {
            compose.exists(hasTestTag(item))
        }
        compose.tap(hasTestTag(item))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/** Sets the system night mode for the test and restores the previous one. */
private class NightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                // "Night mode: no" / "yes" / "auto" / "custom".
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
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .use { it.readBytes().decodeToString() }
    }
}

/** Invented, locally drawn images: two identical copies and one different. */
private class ThemeFixtures : TestRule {
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
                    val same = png(Color.rgb(40, 120, 160))
                    insert(target, "ATLAS_THEME_COPY_A.png", same)
                    insert(target, "ATLAS_THEME_COPY_B.png", same)
                    insert(target, "ATLAS_THEME_OTHER.png", png(Color.rgb(210, 150, 40)))
                    base.evaluate()
                } finally {
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun insert(context: android.content.Context, name: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Theme-Fixture/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create synthetic theme fixture $name"
        }
        inserted += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }
}
