package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fast, imaginary video selection. The fake MediaStore URIs are deliberately
 * absent; this test MUST NOT create Media3 or access personal galleries.
 * It exercises the production filmstrip and poster path only.
 */
@RunWith(AndroidJUnit4::class)
class ViewerVideoSettleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun quickThumbnailChangesKeepOnlyPosterWithoutAllocatingPlayers() {
        val media = (0L..2L).map { i ->
            MediaRecord(
                id = 8000L + i,
                uri = "content://media/external/video/media/${8000L + i}",
                name = "fictional-video-$i",
                relativePath = "DCIM/ATLAS-TEST/",
                sizeBytes = 1L,
                takenAtMillis = i,
                isVideo = true
            )
        }
        var exits = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                GalleryViewer(items = media, startUri = media[0].uri,
                    onBack = { exits++ })
            }
        }
        rule.mainClock.advanceTimeByFrame()
        org.junit.Assert.assertTrue(
            rule.onAllNodesWithTag("atlas-viewer-video-poster")
                .fetchSemanticsNodes().isNotEmpty()
        )
        rule.onAllNodesWithTag("atlas-viewer-video-player").assertCountEquals(0)

        // Each tap changes the selected video before its 180ms settle delay.
        // We never advance the virtual clock long enough to create Media3.
        for (index in listOf(1, 2, 0, 1, 0)) {
            rule.onNodeWithTag("atlas-thumbnail-$index").performClick()
            rule.mainClock.advanceTimeBy(24)
            org.junit.Assert.assertTrue(
                rule.onAllNodesWithTag("atlas-viewer-video-poster")
                    .fetchSemanticsNodes().isNotEmpty()
            )
            rule.onAllNodesWithTag("atlas-viewer-video-player").assertCountEquals(0)
        }
        rule.onNodeWithTag("atlas-back").performClick()
        rule.runOnIdle { assertEquals(1, exits) }
        rule.mainClock.autoAdvance = true
    }
}
