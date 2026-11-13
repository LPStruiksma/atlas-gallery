package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No actual Trash request: asserts safe staging and a second ATLAS confirm. */
@RunWith(AndroidJUnit4::class)
class DeclutterScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stageKeepUndoReviewUnstageAndExplicitConfirmBeforeCallback() {
        val deck = listOf(
            MediaRecord(1, "content://media/external/images/media/1", "large-a",
                "DCIM/Fictional/", 10_000_000L, 12L, false),
            MediaRecord(2, "content://media/external/images/media/2", "small-b",
                "DCIM/Fictional/", 2_000_000L, 11L, false)
        )
        var requestCount = 0
        var requested = emptyList<MediaRecord>()
        rule.setContent {
            MaterialTheme {
                DeclutterScreen(
                    folder = "DCIM/Fictional/", deck = deck,
                    onExit = {},
                    onTrashRequest = {
                        requestCount++
                        requested = it
                    }
                )
            }
        }
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithTag("atlas-declutter-undo").performClick()
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithTag("atlas-declutter-keep").performClick()
        rule.onNodeWithText("Folder review complete").assertExists()
        rule.onNodeWithTag("atlas-declutter-review").performClick()
        rule.onNodeWithTag("atlas-declutter-trash").performClick()
        rule.runOnIdle { assertEquals(0, requestCount) }
        rule.onNodeWithTag("atlas-declutter-confirm").performClick()
        rule.runOnIdle {
            assertEquals(1, requestCount)
            assertEquals(listOf(deck[0]), requested)
        }
        rule.onNodeWithText("Keep").performClick()
        rule.onNodeWithTag("atlas-declutter-trash").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, requestCount) }
    }

    @Test fun whileVerifyingUnchangedMetadataSwipesAndButtonsCannotChangeQueue() {
        val fake = MediaRecord(
            5, "content://media/external/images/media/5", "Invented photo",
            "DCIM/Imaginary/", 100L, 10L, false, modifiedAtSeconds = 123L
        )
        var promptRequests = 0
        rule.setContent {
            MaterialTheme {
                DeclutterScreen(
                    "DCIM/Imaginary/", listOf(fake),
                    onExit = {},
                    onTrashRequest = { promptRequests++ },
                    verifying = true
                )
            }
        }
        rule.onNodeWithTag("atlas-declutter-verifying").assertExists()
        rule.onNodeWithTag("atlas-declutter-discard").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-declutter-keep").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-declutter-undo").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, promptRequests) }
    }

    @Test fun staleOrUnresolvableMediaNeverPassesFinalTrashPreflight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fake = MediaRecord(
            982341L, "content://media/external/images/media/982341",
            "Nonexistent synthetic image", "DCIM/ATLAS-TEST/",
            888L, 123L, false, modifiedAtSeconds = 123L
        )
        assertFalse(DeclutterTrashPreflight.unchanged(
            context.contentResolver, listOf(fake)))
        assertFalse(DeclutterTrashPreflight.unchanged(
            context.contentResolver, listOf(fake, fake)))
        assertFalse(DeclutterTrashPreflight.unchanged(
            context.contentResolver, listOf(fake.copy(
                uri = "file:///storage/emulated/0/DCIM/private.jpg"))))
    }

    @Test fun rapidVideoKeepAcrossCardsNeverStagesOrRequestsTrash() {
        val videos = (1L..4L).map {
            MediaRecord(
                it, "content://media/external/video/media/${it + 9000L}",
                "synthetic-video-${it}.mp4", "DCIM/Fictional/",
                it * 1_000_000L, it, true
            )
        }
        var osTrashRequests = 0
        rule.setContent {
            MaterialTheme {
                DeclutterScreen("DCIM/Fictional/", videos, onExit = {},
                    onTrashRequest = { osTrashRequests++ })
            }
        }
        repeat(videos.size) {
            rule.onNodeWithTag("atlas-declutter-keep").performClick()
        }
        rule.onNodeWithText("Folder review complete").assertExists()
        rule.onNodeWithTag("atlas-declutter-review").performClick()
        rule.onNodeWithTag("atlas-declutter-trash").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, osTrashRequests) }
    }

    @Test fun discardNeverMeansImmediateDeleteOrOSPrompt() {
        val deck = listOf(
            MediaRecord(99, "content://media/external/video/media/99", "largest-video",
                "DCIM/Example/", 300_000_000L, 20L, true)
        )
        var requests = 0
        rule.setContent {
            MaterialTheme {
                DeclutterScreen("DCIM/Example/", deck, onExit = {},
                    onTrashRequest = { requests++ })
            }
        }
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithText("Folder review complete").assertExists()
        rule.runOnIdle { assertEquals(0, requests) }
        rule.onNodeWithTag("atlas-declutter-review").performClick()
        rule.onNodeWithTag("atlas-declutter-trash").performClick()
        rule.runOnIdle { assertEquals(0, requests) }
        // Dismiss first ATLAS review confirmation.
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(0, requests) }
    }
}
