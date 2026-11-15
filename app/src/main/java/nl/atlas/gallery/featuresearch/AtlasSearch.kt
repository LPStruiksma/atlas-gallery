package nl.atlas.gallery.featuresearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * One shared Samsung-style AtlasSearch shell. Overviews show a subtle icon
 * at rest; Search starts expanded. All tools are optional composition slots
 * so FIND-1/2/3 can extend this panel without duplicating the chrome.
 *
 * The host owns the query and both source sets. Switching scope does not
 * silently change permissions; an explicit library option is the only escape.
 */
@Composable
fun AtlasSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    initialScope: AtlasSearchScope = AtlasSearchScope.THIS_SCREEN,
    onScopeChange: (AtlasSearchScope) -> Unit = {},
    textTool: (@Composable () -> Unit)? = null,
    faceTool: (@Composable () -> Unit)? = null,
    imageTool: (@Composable () -> Unit)? = null,
    filtersTool: (@Composable () -> Unit)? = null,
    historyTool: (@Composable () -> Unit)? = null,
    suggestionsTool: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    var scope by remember { mutableStateOf(initialScope) }
    // The host is responsible for restoring a route's scope when it changes.
    Column(modifier.testTag("atlas-search-shared")) {
        if (!expanded) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("atlas-search-expand")
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search this screen")
            }
        } else {
            Column(
                Modifier.fillMaxWidth().testTag("atlas-search-expanded"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { onQueryChange(boundSearchQueryInput(it)) },
                        singleLine = true,
                        placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        modifier = Modifier.weight(1f).testTag("atlas-scoped-search-field")
                    )
                    IconButton(onClick = {
                        expanded = false
                        onQueryChange("")
                        if (scope != initialScope) {
                            scope = initialScope
                            onScopeChange(initialScope)
                        }
                    }) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == AtlasSearchScope.THIS_SCREEN,
                        onClick = {
                            scope = AtlasSearchScope.THIS_SCREEN
                            onScopeChange(scope)
                        },
                        label = { Text("This screen") },
                        modifier = Modifier.testTag("atlas-search-scope-screen")
                    )
                    FilterChip(
                        selected = scope == AtlasSearchScope.LIBRARY,
                        onClick = {
                            scope = AtlasSearchScope.LIBRARY
                            onScopeChange(scope)
                        },
                        label = { Text("Whole library") },
                        modifier = Modifier.testTag("atlas-search-scope-library")
                    )
                }
                textTool?.invoke()
                faceTool?.invoke()
                imageTool?.invoke()
                filtersTool?.invoke()
                historyTool?.invoke()
                suggestionsTool?.invoke()
            }
        }
    }
}
