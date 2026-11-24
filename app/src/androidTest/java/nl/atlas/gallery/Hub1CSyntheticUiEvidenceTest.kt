package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum
import nl.atlas.gallery.featurepeopleui.NeedsSplittingRow
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.SamePersonQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * HUB-1C (#212): real Compose controls rendered on a synthetic S24+ display.
 * These direct-mount UI tests prove the visible affordance, touch path and
 * state transitions; reducer/Skip persistence are tested separately.
 * Portraits are neutral vectors, never real identities or device photos.
 */
@RunWith(AndroidJUnit4::class)
class Hub1CSyntheticUiEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun face(i: Int) = NativeFaceObservationId("%032x".format(i))
    private fun question() = SamePersonQueueItem(
        subject = PersonPageTarget.Named(PersonKey("native-synthetic-subject")),
        subjectName = "Fictional person",
        subjectFaces = 4,
        subjectSamples = (1..4).map(::face),
        other = FaceClusterId(face(5)),
        otherFaces = 4,
        otherSamples = (5..8).map(::face)
    )

    @Test fun lightMixedUndoAndRememberedSkipScreens() = mixedAndSkip(dark = false)
    @Test fun darkMixedUndoAndRememberedSkipScreens() = mixedAndSkip(dark = true)

    private fun mixedAndSkip(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val asked = question()
        var current by mutableStateOf<SamePersonQueueItem?>(asked)
        var markSubject by mutableStateOf(false)
        var markOther by mutableStateOf(false)
        var canUndo by mutableStateOf(false)
        var skipped by mutableStateOf(false)
        var open by mutableStateOf(1)
        var committed: Pair<Boolean, Boolean>? = null

        compose.setContent {
            AtlasEvidenceHarness(edgeToEdge = true, dark = dark) {
                SamePersonQueueScreen(
                    item = current,
                    open = open,
                    loaded = true,
                    skippedSome = skipped,
                    onShowSkipped = { skipped = false; current = asked },
                    markSubject = markSubject,
                    markOther = markOther,
                    actionBusy = false,
                    canUndoMixed = canUndo,
                    onToggleSubject = { markSubject = !markSubject },
                    onToggleOther = { markOther = !markOther },
                    onMarkMixed = {
                        committed = markSubject to markOther
                        canUndo = true
                        current = null
                        open = 0
                    },
                    onUndoMixed = {
                        canUndo = false
                        markSubject = false
                        markOther = false
                        current = asked
                        open = 1
                    },
                    face = { syntheticPortrait() },
                    onAnswer = {},
                    onSkip = { skipped = true; current = null },
                    onClose = {}
                )
            }
        }
        compose.onNodeWithTag("atlas-same-queue-subject").assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-other").assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-no").assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-yes").assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-skip").assertIsDisplayed()
        saveUiEvidence("hub1c-same-person-$mode.png")

        compose.onNodeWithTag("atlas-same-queue-subject-mixed").performClick()
        compose.onNodeWithText("Mark this group").assertIsDisplayed()
        saveUiEvidence("hub1c-one-side-$mode.png")
        compose.onNodeWithTag("atlas-same-queue-other-mixed").performClick()
        compose.onNodeWithText("Mark both groups").assertIsDisplayed()
        saveUiEvidence("hub1c-both-sides-$mode.png")
        compose.onNodeWithTag("atlas-same-queue-confirm-mixed").performClick()
        compose.waitForIdle()
        assertEquals(true to true, committed)
        compose.onNodeWithTag("atlas-same-queue-undo").assertIsDisplayed()
        saveUiEvidence("hub1c-marked-undo-$mode.png")

        compose.onNodeWithTag("atlas-same-queue-undo").performClick()
        compose.onNodeWithTag("atlas-same-queue-subject").assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-undo").assertDoesNotExist()
        saveUiEvidence("hub1c-after-undo-$mode.png")
        compose.onNodeWithTag("atlas-same-queue-skip").performClick()
        compose.onNodeWithTag("atlas-same-queue-show-skipped").assertIsDisplayed()
        saveUiEvidence("hub1c-skipped-$mode.png")
        compose.onNodeWithTag("atlas-same-queue-show-skipped").performClick()
        compose.onNodeWithTag("atlas-same-queue-subject").assertIsDisplayed()
        assertTrue(!skipped)
        saveUiEvidence("hub1c-show-skipped-$mode.png")
    }

    @Test fun visibleAndHiddenSplittingRemainSeparateOnS24() {
        val visible = NeedsSplittingRow(
            PersonPageTarget.Unnamed(FaceClusterId(face(11))),
            null, 4, face(11)
        )
        val hidden = NeedsSplittingRow(
            PersonPageTarget.Named(PersonKey("native-synthetic-hidden")),
            "Fictional hidden person", 5, face(12)
        )
        var hiddenOnly by mutableStateOf(false)
        var authorized by mutableStateOf(true)
        var load by mutableStateOf(NeedsSplittingLoad.Ready)
        var retried = 0
        var opened: PersonPageTarget? = null
        var wentBack = false
        compose.setContent {
            AtlasEvidenceHarness {
                NeedsSplittingScreen(
                    rows = if (hiddenOnly) listOf(hidden) else listOf(visible),
                    hiddenOnly = hiddenOnly,
                    authorized = authorized,
                    face = { syntheticPortrait() },
                    onBack = { wentBack = true },
                    onOpen = { opened = it.target },
                    load = load,
                    onRetry = { retried++ }
                )
            }
        }
        compose.onNodeWithTag("atlas-needs-splitting").assertIsDisplayed()
        compose.onNodeWithText("Unnamed group").assertIsDisplayed()
        compose.onNodeWithText(hidden.name!!).assertDoesNotExist()
        saveUiEvidence("hub1c-needs-splitting-visible.png")
        compose.onNodeWithTag("atlas-needs-splitting-row").performClick()
        assertEquals(visible.target, opened)

        hiddenOnly = true
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-hidden-needs-splitting").assertIsDisplayed()
        compose.onNodeWithText(hidden.name!!).assertIsDisplayed()
        compose.onNodeWithText("Unnamed group").assertDoesNotExist()
        saveUiEvidence("hub1c-needs-splitting-hidden.png")
        compose.onNodeWithTag("atlas-needs-splitting-row").performClick()
        assertEquals(hidden.target, opened)
        compose.onNodeWithTag("atlas-header-back").performClick()
        assertTrue(wentBack)

        // #212 v3: settling shows a quiet loading line, never "(0)" or stale rows.
        load = NeedsSplittingLoad.Loading
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-needs-splitting-loading").assertIsDisplayed()
        compose.onNodeWithTag("atlas-needs-splitting-row").assertDoesNotExist()
        saveUiEvidence("hub1c-needs-splitting-loading.png")

        // A revoked grant or read error fails closed with a working Retry.
        load = NeedsSplittingLoad.Unavailable
        authorized = false
        compose.waitForIdle()
        compose.onNodeWithText("Couldn\u2019t load groups.").assertIsDisplayed()
        compose.onNodeWithTag("atlas-needs-splitting-row").assertDoesNotExist()
        compose.onNodeWithTag("atlas-needs-splitting-retry").assertIsDisplayed()
            .assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retried)
        saveUiEvidence("hub1c-needs-splitting-revoked.png")
    }

    @Test fun hiddenHasItsOwnCountedFilterBelowSearch() {
        val hiddenPerson = NativePersonAlbum(
            PersonKey("native-synthetic-hidden"),
            "Fictional hidden person",
            listOf(face(20)), listOf("synthetic-media")
        )
        var opened = false
        compose.setContent {
            AtlasEvidenceHarness {
                HiddenPeopleScreen(
                    people = listOf(hiddenPerson),
                    portrait = { syntheticPortrait() },
                    onBack = {},
                    onOpen = {},
                    onOpenNeedsSplitting = { opened = true },
                    needsSplittingCount = 1
                )
            }
        }
        compose.onNodeWithTag("atlas-people-hidden").assertIsDisplayed()
        compose.onNodeWithText("Fictional hidden person").assertIsDisplayed()
        compose.onNodeWithText("Needs splitting (1)").assertIsDisplayed()
        saveUiEvidence("hub1c-hidden-scoped-filter.png")
        compose.onNodeWithTag("atlas-hidden-needs-splitting-open").performClick()
        assertTrue(opened)
    }

    @Test fun largeTextKeepsMixedChoicesAccessible() {
        val asked = question()
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, 1.3f)) {
                AtlasEvidenceHarness(edgeToEdge = true) {
                    SamePersonQueueScreen(
                        item = asked, open = 1, loaded = true, skippedSome = false,
                        onShowSkipped = {}, markSubject = false, markOther = false,
                        actionBusy = false, canUndoMixed = false,
                        onToggleSubject = {}, onToggleOther = {}, onMarkMixed = {},
                        onUndoMixed = {}, face = { syntheticPortrait() },
                        onAnswer = {}, onSkip = {}, onClose = {}
                    )
                }
            }
        }
        for (tag in listOf("atlas-same-queue-subject-mixed",
            "atlas-same-queue-other-mixed")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
                .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        for (tag in listOf("atlas-same-queue-no", "atlas-same-queue-skip",
            "atlas-same-queue-yes")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        }
        saveUiEvidence("hub1c-large-text-130.png")
    }

    @androidx.compose.runtime.Composable
    private fun syntheticPortrait() {
        Box(
            Modifier.size(72.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, contentDescription = "Synthetic portrait",
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
