package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two entirely imaginary image records, no MediaStore writes/Trash intent.
 * The Compose virtual clock models a fast swipe immediately after an
 * already-settled photo. A prior card's settled state must NOT leak into the
 * next photo's first frame and cause a needless 1600px decode.
 */
@RunWith(AndroidJUnit4::class)
class DeclutterProgressivePreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun equalUriFreshDeckRestartsSettlingAndResetsReviewSafely() {
        val media = MediaRecord(
            id = 977L, uri = "content://atlas.synthetic/images/977",
            name = "fictional-same-photo.png", relativePath = "DCIM/ATLAS-SYNTHETIC/",
            sizeBytes = 4096L, takenAtMillis = 977L, isVideo = false
        )
        var source by mutableStateOf<List<MediaRecord>>(
            listOf(media), referentialEqualityPolicy()
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                DeclutterScreen(folder = "DCIM/ATLAS-SYNTHETIC/",
                    deck = source, onExit = {}, onTrashRequest = {})
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertExists()

        // Different MediaStore/deck generation with exactly the same URI:
        // readiness resets, and the LaunchedEffect MUST also restart.
        // Before the fix, it was canceled only by changes to media.uri,
        // reviewing, dragging or verifying, so it never ran again.
        rule.runOnIdle { source = listOf(media.copy()) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("atlas-declutter-photo-progressive").assertExists()
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertExists()

        // Last-stage status is reset for a new source, not inherited.
        rule.onNodeWithTag("atlas-declutter-review").assertTextContains("Review staged (0)")
        rule.mainClock.autoAdvance = true
    }

    @Test fun fullResolutionRequiresEachCardsOwnSettleInterval() {
        val deck = (1L..2L).map { id ->
            MediaRecord(
                id = id,
                uri = "content://media/external/images/media/$id",
                name = "fictional-$id.png",
                relativePath = "DCIM/ATLAS-SYNTHETIC/",
                sizeBytes = 1024L * id,
                takenAtMillis = id,
                isVideo = false
            )
        }
        var trashCalls = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                DeclutterScreen(
                    folder = "DCIM/ATLAS-SYNTHETIC/",
                    deck = deck,
                    onExit = {},
                    onTrashRequest = { trashCalls++ }
                )
            }
        }

        // Initial preview is cheap; its settled-only layer is not yet shown.
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("atlas-declutter-photo-progressive").assertExists()
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertDoesNotExist()

        // First card is intentionally allowed to settle.
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertExists()

        // Immediately advance to card two. No high-resolution carryover from
        // the first card is allowed, even before the restarted effect executes.
        rule.onNodeWithTag("atlas-declutter-keep").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("atlas-declutter-photo-progressive").assertExists()
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, trashCalls) }

        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("atlas-declutter-photo-hires").assertExists()
        rule.runOnIdle { assertEquals(0, trashCalls) }
        rule.mainClock.autoAdvance = true
    }
}
