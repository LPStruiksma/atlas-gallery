package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Locale

/** #34 visual change: reproduce the old dialog, then render the new one in both themes.
 * Screenshot evidence comes from emulator output, not from photo inventory.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDetailsDialogEvidenceTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)
    private val fields = mapOf(
        "heightPixels" to 106, "orientationDegrees" to 219_098,
        "sizeBytes" to 76, "widthPixels" to 106
    )

    @Test fun comparisonRendersBeforeAndAfterInDarkAndLight() {
        var dark by mutableStateOf(true)
        var updated by mutableStateOf(false)
        rule.setContent {
            AtlasGalleryTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                if (updated) {
                    PhotoDetailsDialog(
                        content = photoDetailsContent(248_812, 219_098, 0, fields, Locale.US),
                        onClose = {})
                } else {
                    // Faithful before-state reproduction of the old MainActivity dialog.
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("Close") } },
                        title = { Text("Photo details") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(
                                androidx.compose.ui.unit.Dp(8f))) {
                                Text("Checked 248812 · partial 219098 · omitted 0")
                                fields.toSortedMap().forEach { (field, count) ->
                                    Text("$field: $count (" +
                                        "%.2f".format(Locale.US, count * 100.0 / 248_812) +
                                        "%)")
                                }
                            }
                        }
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("heightPixels:", substring = true).assertExists()
        saveUiEvidence("photo-details-34-before-dark.png")
        rule.runOnIdle { dark = false }
        rule.waitForIdle()
        saveUiEvidence("photo-details-34-before-light.png")
        rule.runOnIdle { updated = true; dark = true }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-people-details-dialog").assertExists()
        rule.onNodeWithText("Rotation not recorded").assertExists()
        rule.onNodeWithText("Width/height unknown").assertExists()
        rule.onNodeWithText("heightPixels:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Photos checked: 248,812", substring = true).assertExists()
        saveUiEvidence("photo-details-34-after-dark.png")
        rule.runOnIdle { dark = false }
        rule.waitForIdle()
        rule.onNodeWithText("Rotation not recorded").assertExists()
        saveUiEvidence("photo-details-34-after-light.png")
    }
}
