package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.darkGalleryColors
import nl.atlas.gallery.featuredesign.lightGalleryColors
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * #657: every app dialog draws its container in the ATLAS theme, never
 * Material's purple/lavender baseline. Each dialog is rendered in the app's
 * dark theme (the theme MainActivity always uses) at S24+ size, its container
 * pixel is checked against the theme's dialog colour, and the render is saved
 * as UI evidence. The light theme is rendered once, as a preview of the same
 * dialog if ATLAS ever follows the system light setting.
 */
@RunWith(AndroidJUnit4::class)
class DialogThemeEvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val ada = NativePersonAlbum(PersonKey("native-synthetic-ada"), "Ada",
        emptyList(), emptyList())

    @Test fun addNameDialogFollowsAppThemeDarkAndLight() {
        var dark by mutableStateOf(true)
        rule.setContent {
            Backdrop(dark) { PhotoPersonNameDialog(onDismiss = {}, onDone = { true }) }
        }
        check("dialog-657-add-name.png", darkGalleryColors)
        rule.runOnIdle { dark = false }
        check("dialog-657-add-name-light.png", lightGalleryColors)
    }

    @Test fun editNameDialogFollowsAppTheme() {
        rule.setContent {
            Backdrop(true) {
                PersonNameDialog(initial = "Ada", suggestions = { emptyList() },
                    suggestionPortrait = {}, onDismiss = {}, onSave = {})
            }
        }
        check("dialog-657-edit-name.png", darkGalleryColors)
    }

    @Test fun mergeConfirmFollowsAppTheme() {
        rule.setContent {
            Backdrop(true) { PersonMergeConfirm(currentName = null, into = ada,
                onDismiss = {}, onMerge = {}) }
        }
        check("dialog-657-merge-confirm.png", darkGalleryColors)
    }

    @Test fun peopleNumbersFollowsAppTheme() {
        rule.setContent {
            Backdrop(true) { PeopleNumbersDialog(numbers = null, loaded = false, onClose = {}) }
        }
        check("dialog-657-people-numbers.png", darkGalleryColors)
    }

    @Test fun peopleCsvApprovalFollowsAppTheme() {
        rule.setContent {
            Backdrop(true) {
                PeopleCsvApprovalDialog(
                    PendingPeopleCsv("content://synthetic/people.csv", PeopleCsvMode.IMPORT_ONCE),
                    existingProfiles = 2, onCancel = {}, onApprove = {})
            }
        }
        check("dialog-657-csv-approval.png", darkGalleryColors)
    }

    @Test fun declutterTrashConfirmFollowsAppTheme() {
        val deck = listOf(MediaRecord(1, "content://media/external/images/media/1",
            "synthetic-large", "DCIM/Fictional/", 10_000_000L, 12L, false))
        var phoneDark by mutableStateOf(true)
        rule.setContent {
            // Declutter is a normal themed route (#678), not a dark-only viewer.
            AtlasGalleryTheme(dark = phoneDark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                DeclutterScreen(folder = "DCIM/Fictional/", deck = deck,
                    onExit = {}, onTrashRequest = {})
            }
        }
        saveUiEvidence("theme-662-declutter-dark.png")
        rule.runOnIdle { phoneDark = false }
        saveUiEvidence("theme-662-declutter-light.png")
        rule.runOnIdle { phoneDark = true }
        rule.waitForIdle()
        saveUiEvidence("audit-657-declutter.png")
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithTag("atlas-declutter-review").performClick()
        rule.onNodeWithTag("atlas-declutter-trash").performClick()
        check("dialog-657-declutter-confirm.png", darkGalleryColors)
    }

    @Composable
    private fun Backdrop(dark: Boolean, dialog: @Composable () -> Unit) {
        AtlasGalleryTheme(dark = dark) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            dialog()
        }
    }

    /** The dialog's top-centre pixel (container padding, above the title). */
    private fun check(evidence: String, scheme: ColorScheme) {
        rule.waitForIdle()
        val dialog: SemanticsNodeInteraction = rule.onNode(isDialog())
        val pixels = dialog.captureToImage().toPixelMap()
        val sample = pixels[pixels.width / 2, 6]
        val expected = scheme.surfaceContainerHigh
        assertTrue("$evidence container $sample, theme dialog colour $expected",
            close(sample, expected))
        saveUiEvidence(evidence)
    }

    private fun close(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < 0.03f && abs(a.green - b.green) < 0.03f &&
            abs(a.blue - b.blue) < 0.03f
}
