package nl.atlas.gallery.featuresearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
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
 * Single shared search surface, with an icon-anchored expansion on overview
 * headers and an inline form on the global Search destination. The optional
 * tool slots let subsequent FIND units extend it without parallel chrome.
 * The caller owns the query, grant and exact screen/library source boundary.
 */
@Composable
fun AtlasSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    initialScope: AtlasSearchScope = AtlasSearchScope.THIS_SCREEN,
    showQueryField: Boolean = true,
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
    val close = {
        expanded = false
        onQueryChange("")
        if (scope != initialScope) {
            scope = initialScope
            onScopeChange(initialScope)
        }
    }
    val contents: @Composable () -> Unit = {
        Column(
            Modifier.widthIn(max = 360.dp).testTag("atlas-search-expanded"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showQueryField) Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onQueryChange(boundSearchQueryInput(it)) },
                    singleLine = true,
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f).testTag("atlas-scoped-search-field")
                )
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
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
    Column(modifier.testTag("atlas-search-shared")) {
        if (initialExpanded) {
            if (expanded) contents() else IconButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("atlas-search-expand")
            ) { Icon(Icons.Filled.Search, "Search") }
        } else {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("atlas-search-expand")
            ) { Icon(Icons.Filled.Search, "Search this screen") }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = close
            ) { contents() }
        }
    }
}
