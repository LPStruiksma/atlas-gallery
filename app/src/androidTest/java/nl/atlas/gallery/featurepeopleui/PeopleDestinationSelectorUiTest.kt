package nl.atlas.gallery.featurepeopleui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** API35 Compose tests use synthetic vectors and a neutral host preview only. */
class PeopleDestinationSelectorUiTest {
    @get:Rule val compose = createComposeRule()

    private val first = PersonKey("ui-synthetic-first")
    private val second = PersonKey("ui-synthetic-second")
    private val source = PersonKey("ui-synthetic-source")

    private fun face(i: Int) = NativeFaceObservationId(i.toString(16).padStart(32, '0'))

    private fun review(): PeopleClusterReviewState {
        val empty = FaceClusterState.empty("ui-synthetic-v1", 2,
            ClusterPolicy.SFACE_CONSERVATIVE)
        var result = PeopleClusterReviewState(
            "ui-grant", "ui-scan",
            FaceClusterer.update(empty, add = listOf(
                FaceEmbedding(face(1), "ui-synthetic-v1", floatArrayOf(1f, 0f)),
                FaceEmbedding(face(2), "ui-synthetic-v1", floatArrayOf(0f, 1f)),
                FaceEmbedding(face(3), "ui-synthetic-v1", floatArrayOf(-1f, 0f)),
                FaceEmbedding(face(4), "ui-synthetic-v1", floatArrayOf(0f, -1f))
            ))
        )
        for ((n, person, name) in listOf(
            Triple(1, first, "Same Name"), Triple(2, second, "Same Name"),
            Triple(3, source, "Source")
        )) {
            val group = result.clusters.unknownClusters.single { face(n) in it.members }
            result = reviewPeopleCluster(
                result, PeopleReviewAction.NameCluster(group.id, person, name)
            )!!
        }
        return result
    }

    @Test fun homonymsAppearAsSeparateExplicitOptionsAndAssignTheCorrectPerson() {
        val initial = review()
        var live: PeopleClusterReviewState? = initial
        val commands = mutableListOf<PeopleClusterReviewCommand>()
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = null, currentPermissionEpoch = "ui-grant",
                    currentSourceGeneration = "ui-scan",
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = true,
                    onDecide = {}, onClose = {},
                    clusterReview = initial,
                    liveClusterReviewAtAction = { live },
                    onClusterReviewCommand = { commands.add(it) },
                    clusterFacePreview = { Text("Synthetic authorized preview") }
                )
            }
        }
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasTestTag("atlas-group-1-destination-toggle-search"))
        compose.onNodeWithTag("atlas-group-1-destination-toggle-search").performClick()
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasTestTag("atlas-group-1-destination-search"))
        compose.onNodeWithTag("atlas-group-1-destination-search")
            .performTextInput("Same Name")
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasTestTag("atlas-group-1-destination-option-2"))
        compose.onNodeWithTag("atlas-group-1-destination-option-2").clickOption()
        compose.onNodeWithTag("atlas-group-1-destination-selected").assertExists()
        compose.onNodeWithTag("atlas-group-1-destination-assign")
            .performScrollTo().performClick()
        compose.onNodeWithTag("atlas-group-1-destination-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, commands.size)
            val action = commands.single().action as PeopleReviewAction.AssignCluster
            assertEquals(second, action.person)
        }
        // Same rendered callback after revoke must not dispatch a second action.
        compose.runOnIdle { live = null }
        compose.onNodeWithTag("atlas-group-1-destination-option-1")
            .performScrollTo().performClick()
        compose.onNodeWithTag("atlas-group-1-destination-assign")
            .performScrollTo().performClick()
        compose.onNodeWithTag("atlas-group-1-destination-confirm").performClick()
        compose.runOnIdle { assertEquals(1, commands.size) }
    }

    @Test fun reviewedSourceMergeAndReassignmentUseSelectedPersonNotDisplayName() {
        val initial = review()
        val commands = mutableListOf<PeopleClusterReviewCommand>()
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = null, currentPermissionEpoch = "ui-grant",
                    currentSourceGeneration = "ui-scan",
                    liveReviewStateAtAction = { null },
                    reviewBackendReady = true, onDecide = {}, onClose = {},
                    clusterReview = initial, liveClusterReviewAtAction = { initial },
                    onClusterReviewCommand = { commands.add(it) },
                    clusterFacePreview = { Text("Synthetic authorized preview") }
                )
            }
        }
        val screen = compose.onNodeWithTag("atlas-unknown-face-review-screen")
        // Search returns matching identities by stable catalogue position.
        screen.performScrollToNode(hasTestTag("atlas-album-3-destination-search"))
        compose.onNodeWithTag("atlas-album-3-destination-search")
            .performTextInput("Same Name")
        screen.performScrollToNode(hasTestTag("atlas-album-3-destination-option-1"))
        compose.onNodeWithTag("atlas-album-3-destination-option-1").clickOption()
        compose.onNodeWithTag("atlas-album-3-destination-merge")
            .performScrollTo().performClick()
        compose.runOnIdle {
            val merge = commands.single().action as PeopleReviewAction.MergePeople
            assertEquals(source, merge.source)
            assertEquals(first, merge.target)
        }
        screen.performScrollToNode(hasTestTag("atlas-album-3-destination-option-2"))
        compose.onNodeWithTag("atlas-album-3-destination-option-2").clickOption()
        compose.onNodeWithTag("atlas-album-3-destination-face-1")
            .performScrollTo().performClick()
        compose.onNodeWithTag("atlas-album-3-destination-reassign")
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, commands.size)
            val reassign = commands.last().action as PeopleReviewAction.ReassignFace
            assertEquals(face(3), reassign.face)
            assertEquals(second, reassign.target)
        }
    }

    @Test fun equalValuedReplacementGrantRegrantAndEmptyChoicesClearOrBlockSelection() {
        val initial = review()
        var visible by mutableStateOf(initial, referentialEqualityPolicy())
        var epoch by mutableStateOf("ui-grant")
        var generation by mutableStateOf("ui-scan")
        val commands = mutableListOf<PeopleClusterReviewCommand>()
        compose.setContent {
            MaterialTheme {
                UnknownFacesReviewScreen(
                    queue = null, currentPermissionEpoch = epoch,
                    currentSourceGeneration = generation,
                    liveReviewStateAtAction = { null }, reviewBackendReady = true,
                    onDecide = {}, onClose = {},
                    clusterReview = visible,
                    liveClusterReviewAtAction = { visible },
                    onClusterReviewCommand = { commands.add(it) }
                )
            }
        }
        val screen = compose.onNodeWithTag("atlas-unknown-face-review-screen")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-toggle-search"))
        compose.onNodeWithTag("atlas-group-1-destination-toggle-search").performClick()
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-search"))
        compose.onNodeWithTag("atlas-group-1-destination-search")
            .performTextInput("Same Name")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        compose.onNodeWithTag("atlas-group-1-destination-option-1").clickOption()
        compose.onNodeWithTag("atlas-group-1-destination-selected").assertExists()
        val oldScope = compose.onNodeWithTag("atlas-unknown-identity-group")
            .fetchSemanticsNode().config[ReviewRowDiagnosticKey]
        var copied: PeopleClusterReviewState? = null
        compose.runOnIdle {
            copied = initial.copy()
            assertTrue("Copy must be a different review snapshot", copied !== initial)
            visible = copied!!
        }
        // A new committed reference resets the on-demand search and the selected target.
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-toggle-search"))
        compose.onNodeWithTag("atlas-group-1-destination-toggle-search").performClick()
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-search"))
        compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Same Name")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        val newScope = compose.onNodeWithTag("atlas-unknown-identity-group")
            .fetchSemanticsNode().config[ReviewRowDiagnosticKey]
        assertTrue("Equal-valued copy did not reach row scope: $newScope",
            oldScope.substringBefore(";") != newScope.substringBefore(";"))
        assertTrue("Row's selectedPosition is still retained after copy: $newScope",
            newScope.endsWith("selected choice none"))
        assertEquals("Equal-valued review copy retained selection", 0,
            compose.onAllNodesWithTag("atlas-group-1-destination-selected").fetchSemanticsNodes().size)
        compose.onNodeWithTag("atlas-group-1-destination-assign").assertIsNotEnabled()
        compose.runOnIdle { epoch = "revoked" }
        assertEquals("Revoked permission retained selection", 0,
            compose.onAllNodesWithTag("atlas-group-1-destination-selected").fetchSemanticsNodes().size)
        compose.runOnIdle {
            epoch = "new-grant"
            generation = "new-scan"
            visible = initial.copy(permissionEpoch = "new-grant",
                sourceGeneration = "new-scan")
        }
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-toggle-search"))
        compose.onNodeWithTag("atlas-group-1-destination-toggle-search").performClick()
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-search"))
        compose.onNodeWithTag("atlas-group-1-destination-search").performTextInput("Same Name")
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-1"))
        assertEquals("Regrant retained pre-revocation selection", 0,
            compose.onAllNodesWithTag("atlas-group-1-destination-selected").fetchSemanticsNodes().size)
        compose.onNodeWithTag("atlas-group-1-destination-assign").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }
        // A fresh explicit choice after regrant must still work, and dispatch
        // against the new committed object rather than the old selection.
        // Scroll first: below the sticky faces header option 2 can sit just off screen,
        // where a click lands outside the viewport and selects nothing.
        screen.performScrollToNode(hasTestTag("atlas-group-1-destination-option-2"))
        compose.onNodeWithTag("atlas-group-1-destination-option-2").clickOption()
        compose.onNodeWithTag("atlas-group-1-destination-selected").assertExists()
        compose.onNodeWithTag("atlas-group-1-destination-assign")
            .performScrollTo().performClick()
        compose.onNodeWithTag("atlas-group-1-destination-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, commands.size)
            assertTrue("Command must bind the new regranted review",
                commands.single().expectedState === visible)
            assertEquals(second,
                (commands.single().action as PeopleReviewAction.AssignCluster).person)
        }
    }
    @Test fun seventyHomonymsDoNotAppearUntilQueryAndCanExpandWithoutPages() {
        val scope = ReviewReferenceKey(Any())
        val catalog = (1..70).map { n ->
            NativePersonAlbum(PersonKey("synthetic-many-$n"), "Morgan",
                listOf(face(n)), emptyList())
        }
        compose.setContent {
            MaterialTheme {
                PeopleDestinationSelector(
                    selectionScope = scope, candidates = catalog,
                    selectedPosition = null, onSelect = {}, enabled = true,
                    tagPrefix = "atlas-search70",
                    preview = { Text("Synthetic face") }
                )
            }
        }
        compose.onNodeWithTag("atlas-search70-option-1").assertDoesNotExist()
        compose.onNodeWithTag("atlas-search70-search").performTextInput("Morgan")
        compose.onNodeWithTag("atlas-search70-option-1").assertExists()
        compose.onNodeWithTag("atlas-search70-option-5").assertExists()
        compose.onNodeWithTag("atlas-search70-option-6").assertDoesNotExist()
        compose.onNodeWithText("Show more matches").performClick()
        compose.onNodeWithTag("atlas-search70-option-6").assertExists()
        compose.onNodeWithTag("atlas-search70-option-10").assertExists()
        compose.onNodeWithTag("atlas-search70-option-11").assertDoesNotExist()
        compose.onNodeWithText("Choice page", substring = true).assertDoesNotExist()
    }

}

/**
 * #356 CI (atlas-gallery#360, shard 0): a pointer click on a radio option that
 * performScrollToNode left at the viewport edge, under the sticky faces header,
 * can land outside the node and select nothing (line 183 failed that way). These
 * tests check selection state, not hit-testing, so invoke the option's own
 * OnClick semantics action, which does not depend on where the node sits.
 */
private fun SemanticsNodeInteraction.clickOption(): SemanticsNodeInteraction =
    performSemanticsAction(SemanticsActions.OnClick)
