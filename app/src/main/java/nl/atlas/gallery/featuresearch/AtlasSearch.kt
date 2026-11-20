package nl.atlas.gallery.featuresearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * FIND-5 (#341, frame L): below this much window height above the keyboard
 * (landscape with the IME up), the field, both scope chips and Close share
 * one row so none of them sits under the keyboard.
 */
internal const val ATLAS_SEARCH_SINGLE_ROW_BELOW_DP = 280

/**
 * True when the panel must use its single-row layout (frame L).
 * [windowHeightDp] is the host window's current height, so a window that
 * resizes for the keyboard (and reports no IME inset) counts as well as an
 * edge-to-edge window that reports the keyboard as [imeBottomDp].
 */
internal fun atlasSearchSingleRow(windowHeightDp: Int, imeBottomDp: Int): Boolean =
    windowHeightDp - imeBottomDp < ATLAS_SEARCH_SINGLE_ROW_BELOW_DP

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
    persistentInline: Boolean = false,
    initialScope: AtlasSearchScope = AtlasSearchScope.THIS_SCREEN,
    selectedScope: AtlasSearchScope? = null,
    showQueryField: Boolean = true,
    showScopeChips: Boolean = true,
    onScopeChange: (AtlasSearchScope) -> Unit = {},
    textTool: (@Composable () -> Unit)? = null,
    faceTool: (@Composable () -> Unit)? = null,
    imageTool: (@Composable () -> Unit)? = null,
    filtersTool: (@Composable () -> Unit)? = null,
    historyTool: (@Composable () -> Unit)? = null,
    suggestionsTool: (@Composable () -> Unit)? = null,
    /**
     * FIND-3a (#182): this bar's own recent searches (never per album or
     * person id). Null, or no [LocalSearchHistory], shows no history.
     */
    historyScope: SearchHistoryScope? = null
) {
    val history = LocalSearchHistory.current.takeIf { historyScope != null }
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(initialExpanded) }
    var localScope by remember { mutableStateOf(initialScope) }
    val scope = selectedScope ?: localScope
    val selectScope: (AtlasSearchScope) -> Unit = {
        localScope = it
        onScopeChange(it)
    }
    val close = {
        expanded = persistentInline
        onQueryChange("")
        if (scope != initialScope) {
            selectScope(initialScope)
        }
    }
    // Read the keyboard inset here, in the host window: the DropdownMenu
    // popup below does not receive the IME inset itself.
    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value.toInt() }
    val windowHeightDp = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
    }
    val singleRow = showQueryField && showScopeChips &&
        atlasSearchSingleRow(windowHeightDp, imeBottomDp)
    val queryField: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(boundSearchQueryInput(it)) },
            singleLine = true,
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (history != null && historyScope != null && query.isNotBlank()) {
                    history.remember(historyScope, SearchHistoryEntry(query.trim()))
                }
                keyboard?.hide()
            }),
            modifier = fieldModifier.testTag("atlas-scoped-search-field")
        )
    }
    val closeButton: @Composable () -> Unit = {
        IconButton(onClick = close, modifier = Modifier.testTag("atlas-search-close")) {
            Icon(Icons.Filled.Close, contentDescription = "Close search")
        }
    }
    val scopeChips: @Composable () -> Unit = {
        FilterChip(
            selected = scope == AtlasSearchScope.THIS_SCREEN,
            onClick = {
                selectScope(AtlasSearchScope.THIS_SCREEN)
            },
            label = { Text("This screen") },
            modifier = Modifier.testTag("atlas-search-scope-screen")
        )
        FilterChip(
            selected = scope == AtlasSearchScope.LIBRARY,
            onClick = {
                selectScope(AtlasSearchScope.LIBRARY)
            },
            label = { Text("Whole library") },
            modifier = Modifier.testTag("atlas-search-scope-library")
        )
    }
    val contents: @Composable () -> Unit = {
        Column(
            (if (singleRow) Modifier else Modifier.widthIn(max = 360.dp))
                .testTag("atlas-search-expanded"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (singleRow) {
                // Frame L: field, This screen, Whole library, Close in one
                // row (also the TalkBack order), all above the keyboard.
                Row(
                    Modifier.padding(horizontal = 8.dp).testTag("atlas-search-single-row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    queryField(Modifier.width(240.dp))
                    scopeChips()
                    closeButton()
                }
            } else {
                if (showQueryField) Row(Modifier.fillMaxWidth()) {
                    queryField(Modifier.weight(1f))
                    closeButton()
                }
                if (showScopeChips) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scopeChips()
                }
            }
            textTool?.invoke()
            faceTool?.invoke()
            imageTool?.invoke()
            filtersTool?.invoke()
            historyTool?.invoke()
            if (history != null && historyScope != null && showQueryField && query.isBlank()) {
                RecentSearchPills(
                    entries = history.visible(historyScope),
                    labelOf = { it.text },
                    onPick = { entry ->
                        onQueryChange(boundSearchQueryInput(entry.text))
                        history.remember(historyScope, entry)
                    },
                    onRemove = { history.remove(historyScope, it) },
                    onClear = { history.clear(historyScope) }
                )
            }
            suggestionsTool?.invoke()
        }
    }
    Column(modifier.testTag("atlas-search-shared")) {
        if (persistentInline) {
            contents()
        } else if (initialExpanded) {
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
