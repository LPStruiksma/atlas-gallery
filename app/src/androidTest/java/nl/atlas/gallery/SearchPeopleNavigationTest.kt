package nl.atlas.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No user's media or personal names: navigates only fictional identities. */
@RunWith(AndroidJUnit4::class)
class SearchPeopleNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingNamedPersonShortcutReturnsExactIdentityWithoutMergingNames() {
        val people = listOf(
            PersonEntry(41, "Alex Example", setOf("/storage/emulated/0/DCIM/synthetic-a.jpg")),
            PersonEntry(77, "Alex Example", setOf("/storage/emulated/0/DCIM/synthetic-b.jpg")),
            PersonEntry(12, "Bea Sample", emptySet())
        )
        // A filename match in the wrong folder is NOT a reviewed People link.
        // Neither URI points at private photo content or uploads any media.
        val accessibleMedia = listOf(
            MediaRecord(81, "content://media/external/images/media/81", "synthetic-b.jpg",
                "DCIM/", 100L, 81L, false),
            MediaRecord(82, "content://media/external/images/media/82", "synthetic-a.jpg",
                "DCIM/Unrelated/", 100L, 82L, false)
        )
        var receivedId: Long? = null
        rule.setContent {
            var selected by remember { mutableStateOf<Long?>(null) }
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    PeopleSearchShortcuts(people, "aLeX", accessibleMedia) { id ->
                        receivedId = id
                        selected = id
                    }
                    Text("Selected ${selected ?: "none"}", modifier = Modifier.testTag("selected-person"))
                }
            }
        }
        rule.onNodeWithTag("atlas-search-people").assertExists()
        rule.onNodeWithTag("atlas-search-person-41").assertExists()
        // Photo-backed chips use accessible MediaStore paths, never matching
        // another photo merely by basename; missing paths show a fallback.
        rule.onNodeWithTag("atlas-search-person-cover-77", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("atlas-search-person-cover-41", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("atlas-search-person-count-77", useUnmergedTree = true)
            .assertTextEquals("1 accessible photo")
        rule.onNodeWithTag("atlas-search-person-count-41", useUnmergedTree = true)
            .assertTextEquals("0 accessible photos")
        rule.onNodeWithTag("atlas-search-person-77").performClick()
        rule.onNodeWithTag("selected-person").assertTextEquals("Selected 77")
        assertEquals(77L, receivedId)
        rule.onNodeWithTag("atlas-search-person-12").assertDoesNotExist()
    }
}
