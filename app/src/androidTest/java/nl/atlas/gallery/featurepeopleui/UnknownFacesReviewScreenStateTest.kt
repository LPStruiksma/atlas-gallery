package nl.atlas.gallery.featurepeopleui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic-only UI regression: X can retain the exact same private face_uid
 * across a revoke/regrant when unchanged source detections remain valid.
 * A preview remembered for the old grant must NEVER survive as the new crop.
 *
 * This test deliberately has no MediaStore grants, user photos, detector or DB.
 */
class UnknownFacesReviewScreenStateTest {
    @get:Rule val compose = createComposeRule()

    private fun page(epoch: String, generation: String): NativeFaceReviewQueue {
        val candidate = DetectedFaceReviewCandidate(
            key = DetectedFaceReviewKey(
                nativeFaceUid = "a".repeat(32), // same UID across both grants
                canonicalMediaKey = "atlas-face-media-v1:" + "b".repeat(64),
                sourceRevision = "atlas-face-rev-v1:" + "c".repeat(64),
                detectorVersion = "test-model",
                permissionEpoch = epoch,
                ordinal = 0
            ),
            title = "Unknown face", explanation = "Not identified"
        )
        return NativeFaceReviewQueue(
            epoch, generation, listOf(candidate), 1, hasMore = false
        )
    }

    @Test fun regrantWithSameNativeUidDropsOldRememberedPreview() {
        var livePage by mutableStateOf(page("grant-A", "index-A"))
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = livePage,
                    currentPermissionEpoch = livePage.permissionEpoch,
                    currentSourceGeneration = livePage.sourceGeneration,
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = false,
                    onDecide = {},
                    onClose = {},
                    // Simulate a decoder/crop with remembered source-bound state:
                    // a reused LazyColumn slot would incorrectly show grant A.
                    sourcePreview = { candidate ->
                        val firstSeenGrant = remember { candidate.key.permissionEpoch }
                        Text("Preview authorized by $firstSeenGrant")
                    }
                )
            }
        }
        compose.onNodeWithText("Preview authorized by grant-A").assertExists()
        compose.runOnIdle {
            livePage = page("grant-B", "index-B")
        }
        compose.onNodeWithText("Preview authorized by grant-B").assertExists()
        compose.onNodeWithText("Preview authorized by grant-A").assertDoesNotExist()
    }

    @Test fun nextPageWithReusedUidDropsOldRememberedPreviewInSameGrant() {
        var livePage by mutableStateOf(page("grant-C", "index-C"))
        var revision by mutableStateOf("review-page-one")
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = livePage,
                    currentPermissionEpoch = "grant-C",
                    currentSourceGeneration = "index-C",
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = false,
                    onDecide = {},
                    onClose = {},
                    sourcePreview = {
                        // A distinct read-only page, even in one grant, must
                        // get its own remembered crop/preview scope.
                        val initialRevision = remember { revision }
                        Text("Preview $initialRevision")
                    }
                )
            }
        }
        compose.onNodeWithText("Preview review-page-one").assertExists()
        compose.runOnIdle {
            revision = "review-page-two"
            livePage = page("grant-C", "index-C")
        }
        compose.onNodeWithText("Preview review-page-two").assertExists()
        compose.onNodeWithText("Preview review-page-one").assertDoesNotExist()
    }

    @Test fun emptyNativePageDistinguishesZeroPendingFromLastPaginationPage() {
        var current by mutableStateOf(
            NativeFaceReviewQueue("grant-D", "index-D", emptyList(), 0, false)
        )
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = current,
                    currentPermissionEpoch = "grant-D",
                    currentSourceGeneration = "index-D",
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = false,
                    onDecide = {},
                    onClose = {}
                )
            }
        }
        compose.onNodeWithText(
            "0 face observations awaiting review. These are not confirmed identities."
        ).assertExists()
        compose.onNodeWithText(
            "No unknown-face observations are awaiting review."
        ).assertExists()
        compose.runOnIdle {
            // An exhausted terminal page can be empty even though the current
            // authorized index still contains pending observations elsewhere.
            current = NativeFaceReviewQueue(
                "grant-D", "index-D", emptyList(), 5, false
            )
        }
        compose.onNodeWithText(
            "5 face observations awaiting review. These are not confirmed identities."
        ).assertExists()
        compose.onNodeWithText("No more unknown faces on this page.").assertExists()
        compose.onNodeWithText(
            "No unknown-face observations are awaiting review."
        ).assertDoesNotExist()
    }
}
