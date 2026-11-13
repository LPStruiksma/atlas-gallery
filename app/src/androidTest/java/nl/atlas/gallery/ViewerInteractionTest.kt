package nl.atlas.gallery

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri

/** Works with isolated synthetic metadata; never requires the user's photos. */
@RunWith(AndroidJUnit4::class)
class ViewerInteractionTest {
    private val rule = createComposeRule()

    // #645: evidence at the owner's S24+ size; the harness gives the viewer
    // the app's theme and dark system bars (full screen, like MainActivity).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)
    /** Create 64 locally generated colored image files; no personal photo permissions. */
    private fun fixtures(): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-test-gallery").apply { mkdirs() }
        return (0 until 64).map { i ->
            val image = File(dir, "fixture-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.rgb((i * 39) % 256, (i * 71) % 256, (i * 113) % 256)
                canvas.drawColor(paint.color)
                paint.color = Color.WHITE
                paint.textSize = 86f
                canvas.drawText("$i", 65f, 145f, paint)
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Fixture $i",
                "DCIM/ATLAS-TEST/", image.length(), i.toLong(), false)
        }
    }

    @Test fun successiveFilmstripTapReversalsHonorTheLatestIntentAndCenter() {
        val invented = fixtures()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(invented, invented[10].uri) {} }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")

        // Tap quickly back and forth, including the same image twice.
        // Every new tap supersedes any unfinished earlier pager/strip jump.
        for (index in listOf(11, 9, 11, 11, 12)) {
            rule.onNodeWithTag("atlas-thumbnail-$index").performClick()
        }
        rule.waitUntil(10_000) {
            rule.onNodeWithTag("atlas-photo-counter")
                .fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString("") { it.text } == "13 of 64"
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-thumbnail-12").assertIsSelected()
        val filmstrip = rule.onNodeWithTag("atlas-filmstrip")
            .fetchSemanticsNode().boundsInRoot
        val selected = rule.onNodeWithTag("atlas-thumbnail-12")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Fast final tap must center the latest tile",
            kotlin.math.abs(filmstrip.center.x - selected.center.x) <
                selected.width * .25f)
    }

    /**
     * Owner-reported S24+ issue #400: while browsing a seeded Random album,
     * releasing the filmstrip or coarse seek must not rewind to an arbitrary
     * intermediate pager preview. The input permutation itself is immutable:
     * scrubbing navigates indices, never invokes Reshuffle.
     *
     * Uses only invented PNGs in this test app's cache, not phone media.
     */
    @Test fun randomAlbumScrubReleaseDoesNotRewindOrReshuffle() {
        val randomAlbum = sortMedia(fixtures(), SortOrder.RANDOM, 20261103L)
        val originalOrder = randomAlbum.map { it.uri }
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(randomAlbum, randomAlbum[22].uri) {} }
        }
        fun selectedIndex(): Int = rule.onNodeWithTag("atlas-photo-counter")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }.substringBefore(" of ").toInt() - 1
        fun assertSelectionAndCenter(label: String) {
            val selected = selectedIndex()
            assertTrue("$label: selected index outside immutable Random album",
                selected in randomAlbum.indices)
            // The counter and the selected tile must refer to the SAME
            // Random-order record, not two adjacent but differently shuffled IDs.
            rule.onNodeWithTag("atlas-thumbnail-$selected")
                .assert(hasContentDescription(randomAlbum[selected].name, substring = true))
                .assertIsSelected()
            val strip = rule.onNodeWithTag("atlas-filmstrip")
                .fetchSemanticsNode().boundsInRoot
            val tile = rule.onNodeWithTag("atlas-thumbnail-$selected")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$label: pager/filmstrip diverged after gesture release",
                kotlin.math.abs(strip.center.x - tile.center.x) < tile.width * .25f)
            assertEquals("$label: underlying Random permutation changed",
                originalOrder, randomAlbum.map { it.uri })
        }

        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("23 of 64")
        assertSelectionAndCenter("initial")

        // Let an actual thumbnail fling end; a stale pager-currentPage effect
        // must not recenter the strip before its final highlighted tile commits.
        rule.onNodeWithTag("atlas-filmstrip").performTouchInput {
            swipeLeft(startX = width * .82f, endX = width * .22f,
                durationMillis = 180)
        }
        rule.waitForIdle()
        assertTrue("Random album's forward strip drag did not advance", selectedIndex() > 22)
        assertSelectionAndCenter("filmstrip release")

        // #509: a second, reversed strip fling also lands exactly where it stops.
        rule.onNodeWithTag("atlas-filmstrip").performTouchInput {
            swipeRight(startX = width * .30f, endX = width * .70f, durationMillis = 220)
        }
        rule.waitForIdle()
        assertSelectionAndCenter("reverse filmstrip release")
    }

    @Test fun tappingFilmstripChangesFullScreenPhotoAndBackReturns() {
        val fakeMedia = fixtures()
        rule.setContent {
            var opened by remember { mutableStateOf(true) }
            AtlasEvidenceHarness(edgeToEdge = true) {
                if (opened) GalleryViewer(fakeMedia, fakeMedia[10].uri) { opened = false }
                else Text("Returned to gallery")
            }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        rule.onNodeWithTag("atlas-thumbnail-11").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("12 of 64")
        awaitViewerDrawn()
        saveUiEvidence("viewer-after-thumbnail-scrub.png")
        rule.onNodeWithTag("atlas-back").performClick()
        rule.onNodeWithText("Returned to gallery").assertIsDisplayed()
    }

    @Test fun draggingFilmstripUpdatesDisplayedPhotoWithoutTap() {
        val fakeMedia = fixtures()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(fakeMedia, fakeMedia[10].uri) {} }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-filmstrip").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val currentText = rule.onNodeWithTag("atlas-photo-counter")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
        val selected = currentText.substringBefore(" of ").toInt()
        assertTrue("Filmstrip swipe did not advance the displayed image: $currentText", selected > 11)
        awaitViewerDrawn()
        saveUiEvidence("viewer-after-swipe.png")
    }

    @Test fun reversingFilmstripDirectionSettlesOnLatestCenteredSelection() {
        val media = fixtures()
        rule.setContent { AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(media, media[32].uri) {} } }

        fun selectedIndex(): Int = rule.onNodeWithTag("atlas-photo-counter")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }.substringBefore(" of ").toInt() - 1
        fun assertCentered() {
            val position = selectedIndex()
            val center = rule.onNodeWithTag("atlas-filmstrip")
                .fetchSemanticsNode().boundsInRoot.center.x
            val tile = rule.onNodeWithTag("atlas-thumbnail-$position")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Latest selected tile $position drifted away from center",
                kotlin.math.abs(center - tile.center.x) < tile.width * .25f)
        }

        // Reverse several times; canceled obsolete jumps must not arrive late
        // and overwrite the thumbnail where the user finally stopped.
        val strip = rule.onNodeWithTag("atlas-filmstrip")
        strip.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val afterLeft = selectedIndex()
        assertTrue("Forward strip swipe did not advance", afterLeft > 32)
        assertCentered()

        strip.performTouchInput { swipeRight() }
        rule.waitForIdle()
        val afterRight = selectedIndex()
        assertTrue("Reverse swipe did not decrease selection", afterRight < afterLeft)
        assertCentered()

        strip.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue("Final swipe did not select latest direction", selectedIndex() > afterRight)
        assertCentered()
    }

    @Test fun syntheticFilmstripRecordsAnonymousPreviewTimingWithoutMediaIdentifiers() {
        val fakeMedia = fixtures()
        val probe = ViewerTimingProbe()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) {
                GalleryViewer(fakeMedia, fakeMedia[10].uri, onBack = {}, timingProbe = probe)
            }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-thumbnail-11").performClick()
        rule.waitUntil(10_000) {
            probe.snapshot().any { it.selectedIndex == 11 }
        }
        val sample = probe.snapshot().single { it.selectedIndex == 11 }
        assertTrue(sample.pagerSelectedMs >= 0.0)
        assertTrue(sample.firstPreviewMs >= 0.0)
        // The opt-in probe returns only local indices and durations.
        val aggregate = probe.aggregate().toString()
        assertTrue(!aggregate.contains("fixture"))
        assertTrue(!aggregate.contains("content://"))
        assertTrue(!aggregate.contains("DCIM"))
    }

    @Test fun selectedThumbnailStaysUnderStationaryCenterMarker() {
        val fakeMedia = fixtures()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(fakeMedia, fakeMedia[10].uri) {} }
        }
        fun selectedIndex(): Int = rule.onNodeWithTag("atlas-photo-counter")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }.substringBefore(" of ").toInt() - 1
        fun assertCentered(label: String) {
            val index = selectedIndex()
            val strip = rule.onNodeWithTag("atlas-filmstrip").fetchSemanticsNode().boundsInRoot
            val selected = rule.onNodeWithTag("atlas-thumbnail-$index")
                .fetchSemanticsNode().boundsInRoot
            val distance = kotlin.math.abs(strip.center.x - selected.center.x)
            assertTrue(
                "$label: selected item $index not under center mark; distance=$distance px",
                distance < selected.width * .25f
            )
        }

        rule.waitForIdle()
        assertCentered("initial")
        rule.onNodeWithTag("atlas-thumbnail-11").performClick()
        rule.waitForIdle()
        assertCentered("thumbnail tap")
        rule.onNodeWithTag("atlas-filmstrip").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertCentered("post-fling scrub")
    }

    @Test fun singlePhotoTapTogglesImmersiveChromeWithoutLosingPosition() {
        val fakeMedia = fixtures()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(fakeMedia, fakeMedia[10].uri) {} }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        // The pager composes adjacent offscreen photo pages too, so tap the
        // unique pager's center (the actually visible focused photo).
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { click(center) }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-filmstrip").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("atlas-main-pager").assertExists()
        rule.onNodeWithTag("atlas-back").assertDoesNotExist()
        // Second tap returns precisely to the same media; no accidental
        // shuffle or gallery-context reset and no new photo permissions.
        // The pager composes adjacent offscreen photo pages too, so tap the
        // unique pager's center (the actually visible focused photo).
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { click(center) }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-filmstrip").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-back").assertExists()
        val strip = rule.onNodeWithTag("atlas-filmstrip").fetchSemanticsNode().boundsInRoot
        val thumb = rule.onNodeWithTag("atlas-thumbnail-10").fetchSemanticsNode().boundsInRoot
        assertTrue("Restored strip lost its current center",
            kotlin.math.abs(strip.center.x - thumb.center.x) < thumb.width * .25f)
    }

    @Test fun leavingAndRevisitingZoomedPhotoRestoresNormalSwipe() {
        val fakeMedia = fixtures()
        rule.setContent { AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(fakeMedia, fakeMedia[10].uri) {} } }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        // Double-tap zoom disables the main pager's horizontal swipe.
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")

        // Filmstrip navigation remains available while zoomed. Coming back
        // must start from an unzoomed photo, not a cached offscreen transform.
        rule.onNodeWithTag("atlas-thumbnail-11").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-thumbnail-10").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(10_000) {
            rule.onNodeWithTag("atlas-photo-counter")
                .fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString("") { it.text } != "11 of 64"
        }
    }

    /**
     * #509 (owner S24+ report): during a fast scrub the big photo lagged
     * behind the filmstrip and one of them jumped on release. While the
     * finger is still down, what is shown must be the tile under the centre,
     * and there must be no "SCRUBBING" label or second rail.
     */
    @Test fun scrubPreviewFollowsTheCentreTileWhileTheFingerIsDown() {
        val media = fixtures()
        rule.setContent { AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(media, media[10].uri) {} } }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        val strip = rule.onNodeWithTag("atlas-filmstrip")
        strip.performTouchInput {
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(-width * .05f, 0f)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-scrub-preview").assertExists()
        rule.onNodeWithText("SCRUBBING").assertDoesNotExist()
        rule.onNodeWithText("DRAG THUMBNAILS TO SCRUB").assertDoesNotExist()
        rule.onNodeWithTag("atlas-coarse-seek").assertDoesNotExist()
        fun shown(): Int = rule.onNodeWithTag("atlas-photo-counter")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }.substringBefore(" of ").toInt() - 1
        val midScrub = shown()
        assertTrue("Scrub did not move the shown photo", midScrub > 10)
        val centre = strip.fetchSemanticsNode().boundsInRoot.center.x
        val tile = rule.onNodeWithTag("atlas-thumbnail-$midScrub").fetchSemanticsNode().boundsInRoot
        assertTrue("Shown photo $midScrub is not the tile under the centre",
            kotlin.math.abs(centre - tile.center.x) < tile.width * .6f)
        strip.performTouchInput { up() }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-scrub-preview").fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
        val landed = shown()
        val landedTile = rule.onNodeWithTag("atlas-thumbnail-$landed").fetchSemanticsNode().boundsInRoot
        assertTrue("Release jumped away from the centred tile",
            kotlin.math.abs(strip.fetchSemanticsNode().boundsInRoot.center.x - landedTile.center.x) <
                landedTile.width * .25f)
        rule.onNodeWithTag("atlas-thumbnail-$landed").assertIsSelected()
        awaitViewerDrawn()
        saveUiEvidence("viewer-509-after-scrub.png")
    }

    /** #509 (Lukas 2026-11-10): Samsung's swipe up on a photo shows its details. */
    @Test fun swipeUpOnPhotoOpensDetails() {
        val media = fixtures()
        rule.setContent { AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(media, media[10].uri) {} } }
        rule.onNodeWithTag("atlas-main-pager").performTouchInput {
            swipeUp(startY = height * .75f, endY = height * .35f, durationMillis = 250)
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-viewer-details").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-viewer-details").assertIsDisplayed()
        saveUiEvidence("viewer-509-swipe-up-details.png")
    }

    /** #509: swipe down closes the viewer back to the gallery (parity research VIEW:V22). */
    @Test fun swipeDownOnPhotoReturnsToGallery() {
        val media = fixtures()
        rule.setContent {
            var opened by remember { mutableStateOf(true) }
            AtlasEvidenceHarness(edgeToEdge = true) {
                if (opened) GalleryViewer(media, media[10].uri) { opened = false }
                else Text("Returned to gallery")
            }
        }
        rule.onNodeWithTag("atlas-main-pager").performTouchInput {
            swipeDown(startY = height * .30f, endY = height * .75f, durationMillis = 250)
        }
        rule.onNodeWithText("Returned to gallery").assertIsDisplayed()
    }

    @Test fun singleItemViewerHasNoCoarsePositionRail() {
        val only = fixtures().take(1)
        rule.setContent { AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(only, only.single().uri) {} } }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 1")
        rule.onNodeWithTag("atlas-coarse-seek").assertDoesNotExist()
    }

    @Test fun initialSelectionIsStableNearEndOfLargeFilmstrip() {
        val fakeMedia = fixtures()
        rule.setContent {
            AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(fakeMedia, fakeMedia[57].uri) {} }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("58 of 64")
        awaitViewerDrawn()
        saveUiEvidence("viewer-near-end.png")
    }

    /**
     * #645: evidence shows the viewer as a person sees it: the photo's full
     * decode and every on-screen filmstrip thumbnail drawn, not the black
     * page or grey placeholder tiles of a capture taken while Coil decodes.
     */
    private fun awaitViewerDrawn() {
        rule.waitUntil(30_000L) {
            rule.onAllNodes(SemanticsMatcher.expectValue(ViewerPhotoDecodedKey, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
        while (true) {
            rule.waitForIdle()
            val pending = placeholderThumbnails()
            if (pending.isEmpty()) return
            check(android.os.SystemClock.uptimeMillis() < deadline) {
                "Filmstrip thumbnails never drew: $pending"
            }
            Thread.sleep(100L)
        }
    }

    /**
     * Tags of the filmstrip tiles around the centre (the middle half of the
     * strip: the selected photo and its neighbours) still showing the
     * placeholder. Tiles at the strip's edges may legitimately load later
     * after a drag, so the wait does not hold the evidence for them.
     */
    private fun placeholderThumbnails(): List<String> {
        val strip = rule.onNodeWithTag("atlas-filmstrip").fetchSemanticsNode().boundsInRoot
        val middle = (strip.left + strip.width / 4)..(strip.right - strip.width / 4)
        val tags = rule.onAllNodes(thumbnailTile).fetchSemanticsNodes()
            .filter { it.size.width > 0 && it.size.height > 0 }
            .filter { it.boundsInRoot.center.x in middle }
            .map { it.config[SemanticsProperties.TestTag] }
        if (tags.size < 3) return listOf("only ${tags.size} filmstrip tiles near the centre")
        return tags.filter { tag ->
            // A tile recycled or still at zero size since the lookup counts
            // as not drawn yet; the caller looks again.
            val pixels = runCatching { rule.onNodeWithTag(tag).captureToImage().toPixelMap() }
                .getOrNull() ?: return@filter true
            val c = pixels[pixels.width / 2, pixels.height / 2]
            // The tile background is #2A2A2A until its thumbnail draws.
            listOf(c.red, c.green, c.blue).all { kotlin.math.abs(it * 255f - 0x2A) <= 6f }
        }
    }

    private val thumbnailTile = SemanticsMatcher("filmstrip tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-thumbnail-") == true
    }
}
