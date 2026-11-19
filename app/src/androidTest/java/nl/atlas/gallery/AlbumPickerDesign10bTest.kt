package nl.atlas.gallery

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.AlbumPickerScreen
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, source-free picker states; never touches MediaStore or transfer consent. */
@RunWith(AndroidJUnit4::class)
class AlbumPickerDesign10bTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emptyCopyPickerKeepsCreateFirstAndCancelPreservesSelection() {
        val events = mutableListOf<String>()
        rule.setContent {
            AtlasGalleryTheme(dark = false) {
                AlbumPickerScreen(
                    title = "Copy to album",
                    albums = emptyList(),
                    onPick = { events += "pick" },
                    onCreate = { events += "create:$it" },
                    nameProblem = { if (it.isBlank()) "Name required" else null },
                    onClose = { events += "close" }
                )
            }
        }
        rule.onNodeWithText("Copy to album").assertIsDisplayed()
        rule.onNodeWithTag("atlas-album-picker-create").assertIsDisplayed()
        rule.onNodeWithTag("atlas-album-picker-empty").assertIsDisplayed()
        rule.onNodeWithTag("atlas-album-picker-create").performClick()
        rule.onNodeWithTag("atlas-create-album-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-create-album-cancel").performClick()
        rule.onNodeWithTag("atlas-header-back").performClick()
        rule.runOnIdle { assertEquals(listOf("close"), events) }
    }

    @Test fun moveDialogEnablesCreateOnlyForValidName() {
        val created = mutableListOf<String>()
        rule.setContent {
            AtlasGalleryTheme(dark = true) {
                AlbumPickerScreen(
                    title = "Move to album",
                    albums = emptyList(),
                    onPick = {},
                    onCreate = { created += it },
                    nameProblem = {
                        when {
                            it.isBlank() -> "Name required"
                            it.trim().equals("Taken", ignoreCase = true) -> "Already exists"
                            else -> null
                        }
                    },
                    onClose = {}
                )
            }
        }
        rule.onNodeWithText("Move to album").assertIsDisplayed()
        rule.onNodeWithTag("atlas-album-picker-create").performClick()
        rule.onNodeWithTag("atlas-create-album-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-create-album-name").performTextInput("Taken")
        rule.onNodeWithTag("atlas-create-album-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-create-album-name").performTextInput(" new")
        rule.onNodeWithTag("atlas-create-album-confirm").assertIsEnabled()
        rule.onNodeWithTag("atlas-create-album-confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("Taken new"), created) }
    }
}
