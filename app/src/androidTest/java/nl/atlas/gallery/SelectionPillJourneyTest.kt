package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featureorganization.SamsungSelectionBottomBar
import nl.atlas.gallery.featureorganization.SelectionMoreAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** #17: real Photos selection and floating action pill, in both system themes. */
@RunWith(AndroidJUnit4::class)
class SelectionPillLightJourneyTest : SelectionPillActivityJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class SelectionPillDarkJourneyTest : SelectionPillActivityJourney(dark = true)

abstract class SelectionPillActivityJourney(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(SelectionPillNightModeRule(dark))
        .around(SyntheticHeaderFixtures(
            sizes = List(4) { 120 to 90 },
            folderOf = { "ATLAS-Selection-Pill-17" }))
        .around(compose)

    @Test fun oneAndMultipleSelectedItemsHaveInsetPillAndUsableMore() {
        val theme = if (dark) "dark" else "light"
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        val tile = SemanticsMatcher("visible timeline media") {
            it.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("atlas-timeline-media-") == true
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodes(tile).fetchSemanticsNodes().size >= 2
        }
        val tags = compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
        assertTrue("Need at least two synthetic media tiles", tags.size >= 2)

        compose.onNodeWithTag(tags[0]).performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-nav-photos").assertDoesNotExist()
        val outer = compose.onNodeWithTag("atlas-selection-bottom-bar")
            .getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag("atlas-selection-pill")
            .getUnclippedBoundsInRoot()
        assertTrue("Action pill must be narrower than the full-width bar",
            (pill.right - pill.left) < (outer.right - outer.left))
        assertTrue("Action pill must clear the bottom inset/margin", pill.bottom < outer.bottom)
        compose.onNodeWithTag("atlas-selection-share").assertIsEnabled()
        compose.onNodeWithTag("atlas-selection-trash").assertIsEnabled()
        compose.onNodeWithTag("atlas-selection-favourite").assertIsOff()
        compose.waitForIdle()
        saveUiEvidence("selection-pill-17-$theme-single.png")

        compose.onNodeWithTag(tags[1]).performClick()
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("2 selected"))
        compose.waitForIdle()
        saveUiEvidence("selection-pill-17-$theme-multi.png")

        compose.onNodeWithTag("atlas-selection-more").performClick()
        compose.waitForNode(hasTestTag("atlas-selection-copy-to-album"))
        compose.waitForNode(hasTestTag("atlas-selection-move-to-album"))
        compose.waitForIdle()
        saveUiEvidence("selection-pill-17-$theme-more.png")
    }
}

/** Separate semantics/callback checks without mutating MediaStore or accepting Trash. */
@RunWith(AndroidJUnit4::class)
class SelectionPillActionsTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun callbacksFavouriteStateAndDisabledControls() {
        var count by mutableIntStateOf(1)
        var enabled by mutableStateOf(true)
        var favourite by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            AtlasGalleryTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.BottomCenter) {
                    SamsungSelectionBottomBar(
                        count = count, enabled = enabled, allFavourite = favourite,
                        onShare = { calls += "share" },
                        onTrash = { calls += "trash-consent-callback" },
                        onFavourite = { favourite = !favourite; calls += "favourite" },
                        moreActions = listOf(SelectionMoreAction(
                            "Copy to album", "atlas-selection-copy-to-album") {
                            calls += "copy"
                        })
                    )
                }
            }
        }
        compose.onNodeWithTag("atlas-selection-share").assertIsEnabled().performClick()
        compose.onNodeWithTag("atlas-selection-trash").assertIsEnabled().performClick()
        compose.onNodeWithTag("atlas-selection-favourite").assertIsOff().performClick()
        compose.onNodeWithTag("atlas-selection-favourite").assertIsOn()
        compose.onNodeWithTag("atlas-selection-more").performClick()
        compose.onNodeWithTag("atlas-selection-copy-to-album").performClick()
        assertEquals(listOf("share", "trash-consent-callback", "favourite", "copy"), calls)

        compose.runOnIdle { count = 0 }
        for (tag in listOf("atlas-selection-share", "atlas-selection-trash",
                "atlas-selection-favourite", "atlas-selection-more")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.runOnIdle { count = 2; enabled = false }
        compose.onNodeWithTag("atlas-selection-trash").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-selection-more-menu").assertDoesNotExist()
    }
}

/** Shell night mode is restored even when the journey fails. */
private class SelectionPillNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
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
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().decodeToString() }
    }
}
