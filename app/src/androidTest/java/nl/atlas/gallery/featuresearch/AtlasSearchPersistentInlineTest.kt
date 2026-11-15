package nl.atlas.gallery.featuresearch

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FIND-0: the existing library Search field must never collapse into an icon. */
@RunWith(AndroidJUnit4::class)
class AtlasSearchPersistentInlineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun globalSearchKeepsItsExistingFieldWithoutScopeChips() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            AtlasSearch(
                query = query,
                onQueryChange = { query = it },
                initialExpanded = true,
                persistentInline = true,
                initialScope = AtlasSearchScope.LIBRARY,
                selectedScope = AtlasSearchScope.LIBRARY,
                showQueryField = false,
                showScopeChips = false,
                textTool = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.testTag("existing-global-search-field")
                    )
                }
            )
        }

        compose.onNodeWithTag("existing-global-search-field").performTextInput("June")
        compose.onNodeWithTag("existing-global-search-field").assertTextContains("June")
        compose.onNodeWithTag("atlas-search-expand").assertDoesNotExist()
        compose.onNodeWithTag("atlas-search-scope-library").assertDoesNotExist()
        compose.onNodeWithTag("atlas-search-scope-screen").assertDoesNotExist()
    }

    @Test fun closeCannotCollapsePersistentInlineSearch() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            AtlasSearch(
                query = query,
                onQueryChange = { query = it },
                persistentInline = true,
                showQueryField = true
            )
        }

        compose.onNodeWithTag("atlas-scoped-search-field").performTextInput("Ada")
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithTag("atlas-scoped-search-field").assertExists()
        compose.onNodeWithTag("atlas-search-expand").assertDoesNotExist()
    }
}
