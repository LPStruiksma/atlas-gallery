package nl.atlas.gallery.featureorganization

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Samsung's selection blue for checked circles. */
val SelectionCheckBlue = Color(0xFF3E91FF)

/**
 * Samsung check circle: an outlined ring while unchecked, a filled blue
 * circle with a tick when checked. A photo tile retains its white overlay ring;
 * "All" and date headers instead use the theme's contrasting page ink.
 */
@Composable
fun SelectionCheckCircle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 22,
    onPage: Boolean = false
) {
    if (checked) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SelectionCheckBlue,
            modifier = modifier.size(size.dp))
    } else {
        Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
            tint = if (onPage) MaterialTheme.colorScheme.onSurfaceVariant
                else Color.White.copy(alpha = .92f),
            modifier = modifier.size(size.dp).then(
                if (onPage) Modifier.testTag("atlas-selection-page-ring") else Modifier
            ))
    }
}

/**
 * Samsung selection top bar (#556): "All" check circle on the left with its
 * small label underneath, then "N selected" (or "Select items"). Nothing else:
 * no close button, no help text. Back exits selection.
 */
@Composable
fun SamsungSelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 10.dp, end = 16.dp)
            .testTag("atlas-selection-top-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Checkbox,
                    onClickLabel = if (allSelected) "Deselect all" else "Select all",
                    onClick = onToggleAll)
                .semantics { stateDescription = if (allSelected) "All selected" else "Not all selected" }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("atlas-selection-all"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SelectionCheckCircle(allSelected, size = 24, onPage = true)
            Text("All", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(10.dp))
        Text(selectionTitle(count), style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.semantics { heading() }.testTag("atlas-selection-count"))
    }
}

/** One entry of the selection bar's More menu, in Samsung's wording. */
data class SelectionMoreAction(val label: String, val testTag: String, val onClick: () -> Unit)

/**
 * Samsung selection bottom bar (#556), in place of the tab bar while
 * selecting: icon over label, Samsung order. Only actions with a real handler
 * are shown; all are disabled while nothing is selected or work is running.
 * Trash is always Android's own consent dialog, never a direct delete.
 * More (#568) appears only when it has entries (Copy to album, Move to album).
 */
@Composable
fun SamsungSelectionBottomBar(
    count: Int,
    enabled: Boolean,
    allFavourite: Boolean,
    onShare: (() -> Unit)?,
    onTrash: (() -> Unit)?,
    onFavourite: (() -> Unit)?,
    modifier: Modifier = Modifier,
    moreActions: List<SelectionMoreAction> = emptyList()
) {
    val active = enabled && count > 0
    var moreOpen by remember { mutableStateOf(false) }
    NavigationBar(modifier.testTag("atlas-selection-bottom-bar"),
        containerColor = MaterialTheme.colorScheme.surface) {
        if (onShare != null) NavigationBarItem(
            selected = false, enabled = active, onClick = onShare,
            icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
            label = { Text("Share") }, alwaysShowLabel = true,
            modifier = Modifier.testTag("atlas-selection-share"))
        if (onTrash != null) NavigationBarItem(
            selected = false, enabled = active, onClick = onTrash,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            label = { Text("Delete") }, alwaysShowLabel = true,
            modifier = Modifier.testTag("atlas-selection-trash"))
        if (onFavourite != null) NavigationBarItem(
            selected = false, enabled = active, onClick = onFavourite,
            icon = {
                Icon(if (allFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null)
            },
            label = { Text(if (allFavourite) "Unfavourite" else "Favourite") },
            alwaysShowLabel = true,
            modifier = Modifier.testTag("atlas-selection-favourite"))
        if (moreActions.isNotEmpty()) NavigationBarItem(
            selected = false, enabled = active, onClick = { moreOpen = true },
            icon = {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
                // Anchored to the icon, so the menu opens above the bar's More.
                DropdownMenu(expanded = moreOpen && active, onDismissRequest = { moreOpen = false },
                    modifier = Modifier.testTag("atlas-selection-more-menu")) {
                    moreActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = { moreOpen = false; action.onClick() },
                            modifier = Modifier.testTag(action.testTag))
                    }
                }
            },
            label = { Text("More") }, alwaysShowLabel = true,
            modifier = Modifier.testTag("atlas-selection-more"))
    }
}
