package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production consent dialog exercised with fictional document pointers only.
 * No real provider is opened and the repository/import operation is mocked
 * by a callback counter. Tests do not access People DB or phone media.
 */
@RunWith(AndroidJUnit4::class)
class PeopleCsvApprovalDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cancelLeavesImportUncalledAndConsumesDialog() {
        var canceled = 0
        var imports = 0
        rule.setContent {
            MaterialTheme {
                PeopleCsvApprovalDialog(
                    PendingPeopleCsv("content://fictional/once.csv", PeopleCsvMode.IMPORT_ONCE),
                    existingProfiles = 7,
                    onCancel = { canceled++ },
                    onApprove = { imports++ }
                )
            }
        }
        rule.onNodeWithText("Replace ATLAS People assignments?").assertExists()
        rule.onNodeWithText("currently 7 named People", substring = true).assertExists()
        rule.onNodeWithText("One-time import:", substring = true).assertExists()
        rule.onNodeWithTag("atlas-people-csv-cancel").performClick()
        rule.runOnIdle {
            assertEquals(1, canceled)
            assertEquals(0, imports)
        }
        rule.onNodeWithTag("atlas-people-csv-confirm").assertIsNotEnabled()
    }

    @Test fun liveLinkRequiresExplicitApprovalAndCannotDoubleSubmit() {
        var canceled = 0
        var imports = 0
        rule.setContent {
            MaterialTheme {
                PeopleCsvApprovalDialog(
                    PendingPeopleCsv("content://fictional/live.csv", PeopleCsvMode.LINK_LIVE),
                    existingProfiles = 41,
                    onCancel = { canceled++ },
                    onApprove = { imports++ }
                )
            }
        }
        rule.onNodeWithText("Live link:", substring = true).assertExists()
        rule.onNodeWithTag("atlas-people-csv-confirm").assertTextEquals("Replace and link live")
        rule.runOnIdle {
            assertEquals(0, imports)
            assertEquals(0, canceled)
        }
        rule.onNodeWithTag("atlas-people-csv-confirm").performClick()
        rule.onNodeWithTag("atlas-people-csv-confirm").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, imports)
            assertEquals(0, canceled)
        }
    }
}
