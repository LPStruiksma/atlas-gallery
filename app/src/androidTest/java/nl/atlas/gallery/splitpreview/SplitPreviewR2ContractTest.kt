package nl.atlas.gallery.splitpreview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewFace
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewGroup
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewScreen
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewUiModel
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Synthetic-only red-first acceptance checks for the R2 pre-build design (#229). */
class SplitPreviewR2ContractTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun preview(groupCount: Int): SplitPreviewUiModel = SplitPreviewUiModel(
        groups = (1..groupCount).map { group ->
            SplitPreviewGroup(
                id = "group-$group",
                label = "Group $group",
                faces = (1..4).map { SplitPreviewFace("face-$group-$it") }
            )
        },
        unsure = listOf(SplitPreviewFace("unsure-1"))
    )

    @Test fun emptyProposalShowsHonestSafeExitWithoutAccept() {
        // R2 requires an empty state rather than a constructor exception or fake group.
        val empty = SplitPreviewUiModel(groups = emptyList(), unsure = emptyList())
        var cancelCount = 0
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model = empty,
                    onAccept = { error("Empty state cannot be accepted") },
                    onAdjust = {},
                    onCancel = { cancelCount++ },
                    onManualSplit = {},
                    onSelectFace = {},
                    onMoveFace = { _, _ -> }
                )
            }
        }
        compose.onNodeWithText("No faces to split.").assertIsDisplayed()
        compose.onNodeWithTag("split-preview-accept").assertDoesNotExist()
        compose.onNodeWithTag("split-preview-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelCount) }
    }

    @Test fun threeGroupReviewKeepsDecisionRailVisibleWithoutScrolling() {
        // On the S24-class viewport, tall group content must not bury Cancel/Adjust/Accept.
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model = preview(groupCount = 3),
                    onAccept = {},
                    onAdjust = {},
                    onCancel = {},
                    onManualSplit = {},
                    onSelectFace = {},
                    onMoveFace = { _, _ -> }
                )
            }
        }
        compose.onNodeWithTag("split-preview-cancel").assertIsDisplayed()
        compose.onNodeWithTag("split-preview-adjust").assertIsDisplayed()
        compose.onNodeWithTag("split-preview-accept").assertIsDisplayed()
    }
    @Test fun loadingAndErrorExposeSafeCancelAndHostRetryOnly() {
        var retried = 0
        var cancelled = 0
        val status = androidx.compose.runtime.mutableStateOf(SplitPreviewStatus.LOADING)
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model = preview(2).copy(status = status.value),
                    onAccept = { error("No Accept while pending") },
                    onAdjust = {}, onCancel = { cancelled++ },
                    onManualSplit = {}, onSelectFace = {}, onMoveFace = { _, _ -> },
                    onRetry = { retried++ }
                )
            }
        }
        compose.onNodeWithText("Finding groups").assertExists()
        compose.onNodeWithTag("split-preview-accept").assertDoesNotExist()
        compose.runOnIdle { status.value = SplitPreviewStatus.ERROR }
        compose.onNodeWithText("Couldn't load suggested groups.").assertExists()
        compose.onNodeWithTag("split-preview-retry").performClick()
        compose.onNodeWithTag("split-preview-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retried)
            assertEquals(1, cancelled)
        }
    }

    @Test fun undocumentedProposalOrderUsesCountThenStableKeyAndBoundsRows() {
        val groups = listOf(
            SplitPreviewGroup("z", "Smaller", listOf(SplitPreviewFace("small"))),
            SplitPreviewGroup("a", "Larger", (1..68).map { SplitPreviewFace("many-$it") })
        )
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model = SplitPreviewUiModel(groups, emptyList()),
                    onAccept = {}, onAdjust = {}, onCancel = {},
                    onManualSplit = {}, onSelectFace = {}, onMoveFace = { _, _ -> }
                )
            }
        }
        // First display group has three exemplar rows, not all 68.
        compose.onNodeWithTag("split-preview-face-1-3").assertExists()
        compose.onNodeWithText("See all 68 faces").assertExists()
    }

}
