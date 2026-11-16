package nl.atlas.gallery.splitpreview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewFace
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewGroup
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewScreen
import nl.atlas.gallery.featurepeople.splitpreview.SplitPreviewUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Every face token is generated for this test and has no media backing. */
class SplitPreviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun model(groups: Int, unsure: Int = 0) = SplitPreviewUiModel(
        groups = (1..groups).map { group ->
            SplitPreviewGroup(
                id = "synthetic-group-$group",
                label = "Suggested person $group",
                faces = (1..2).map { face ->
                    SplitPreviewFace("synthetic-$group-$face")
                }
            )
        },
        unsure = (1..unsure).map { SplitPreviewFace("synthetic-unsure-$it") }
    )

    @Test fun oneGroupShowsNoClearSplitAndManualActionWithoutAccept() {
        var manual = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model(1),
                    onAccept = { error("Accept must not be offered for k=1") },
                    onAdjust = {},
                    onCancel = { cancelled++ },
                    onManualSplit = { manual++ },
                    onSelectFace = {},
                    onMoveFace = { _, _ -> }
                )
            }
        }
        compose.onNodeWithText("No clear split").assertExists()
        compose.onNodeWithTag("split-preview-accept").assertDoesNotExist()
        compose.onNodeWithTag("split-preview-manual").performClick()
        compose.onNodeWithTag("split-preview-cancel").performClick()
        compose.runOnIdle { assertEquals(1, manual); assertEquals(1, cancelled) }
    }

    @Test fun twoGroupsAndUnsureDispatchOpaqueSelectionAndMoveWithoutMutatingModel() {
        val ui = model(2, unsure = 1)
        var selected by mutableStateOf<String?>(null)
        val selections = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String?>>()
        var accepted = 0
        var adjusted = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    ui,
                    onAccept = { accepted++ },
                    onAdjust = { adjusted++ },
                    onCancel = { cancelled++ },
                    onManualSplit = {},
                    onSelectFace = { selections += it; selected = it },
                    onMoveFace = { face, destination -> moves += face to destination },
                    selectedFaceHandle = selected
                )
            }
        }
        compose.onNodeWithTag("split-preview-face-1-1").performScrollTo().performClick()
        compose.onNodeWithTag("split-preview-move-to-2").performScrollTo().performClick()
        compose.onNodeWithTag("split-preview-screen")
            .performScrollToNode(hasTestTag("split-preview-unsure-face-1"))
        compose.onNodeWithTag("split-preview-unsure-face-1").performClick()
        compose.onNodeWithTag("split-preview-move-to-unsure").performScrollTo().performClick()
        compose.onNodeWithTag("split-preview-adjust").performScrollTo().performClick()
        compose.onNodeWithTag("split-preview-accept").performScrollTo().performClick()
        compose.onNodeWithTag("split-preview-cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("synthetic-1-1", "synthetic-unsure-1"), selections)
            assertEquals(
                listOf("synthetic-1-1" to "synthetic-group-2", "synthetic-unsure-1" to null),
                moves
            )
            assertEquals(1, adjusted)
            assertEquals(1, accepted)
            assertEquals(1, cancelled)
            assertEquals(2, ui.groups[0].faces.size)
            assertEquals(1, ui.unsure.size)
        }
    }

    @Test fun threeGroupsRemainDistinctAndHaveAccessibleFaceLabels() {
        compose.setContent {
            MaterialTheme {
                SplitPreviewScreen(
                    model(3, unsure = 2),
                    onAccept = {}, onAdjust = {}, onCancel = {},
                    onManualSplit = {}, onSelectFace = {}, onMoveFace = { _, _ -> }
                )
            }
        }
        val screen = compose.onNodeWithTag("split-preview-screen")
        screen.performScrollToNode(hasTestTag("split-preview-group-3"))
        compose.onNodeWithTag("split-preview-group-3").assertExists()
        screen.performScrollToNode(hasTestTag("split-preview-unsure"))
        compose.onNodeWithTag("split-preview-unsure").assertExists()
    }

    @Test fun syntheticLightDarkEvidenceCapture() {
        // Capture only these neutral glyphs. Never inject any real face crop.
        for (dark in listOf(false, true)) {
            compose.setContent {
                MaterialTheme(colorScheme = if (dark)
                    androidx.compose.material3.darkColorScheme()
                    else androidx.compose.material3.lightColorScheme()) {
                    SplitPreviewScreen(
                        model(2, 1), onAccept = {}, onAdjust = {}, onCancel = {},
                        onManualSplit = {}, onSelectFace = {}, onMoveFace = { _, _ -> }
                    )
                }
            }
            compose.waitForIdle()
            saveUiEvidence(if (dark) "split-preview-synthetic-dark.png" else "split-preview-synthetic-light.png")
        }
    }
}
