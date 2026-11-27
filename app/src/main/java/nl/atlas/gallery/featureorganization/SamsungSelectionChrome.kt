package nl.atlas.gallery.featureorganization

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import nl.atlas.gallery.featuredesign.AtlasPillColor
import nl.atlas.gallery.featuredesign.AtlasTabPillHeight
import nl.atlas.gallery.featuredesign.LocalAtlasDark
import androidx.compose.ui.unit.dp

/** Samsung's selection blue for checked circles. */
val SelectionCheckBlue = nl.atlas.gallery.featuredesign.AtlasTokens.Colors.Selection.Accent

/**
 * Selection mark: an outlined ring while unchecked; when checked, the Lapis
 * held sphere (BRAND-L4), with [order] as its number when the selection is ordered. A photo tile retains its white overlay ring;
 * "All" and date headers instead use the theme's contrasting page ink.
 */
@Composable
fun SelectionCheckCircle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 22,
    onPage: Boolean = false,
    order: Int? = null
) {
    val ring = modifier.size(size.dp).then(
        if (onPage && !checked) Modifier.testTag("atlas-selection-page-ring") else Modifier
    )
    if (checked) {
        // BRAND-L4 (#657): the held sphere marks what is chosen: a lapis
        // sphere with a 2 dp white halo, the pick number when ordered.
        nl.atlas.gallery.featuredesign.AtlasSelectionSphere(modifier, size.dp, order)
    } else {
        // A subdued image scrim keeps the white ring readable against bright thumbnails.
        Box(ring.clip(CircleShape).then(if (onPage) Modifier else
            Modifier.background(Color.Black.copy(alpha = .28f))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                tint = if (onPage) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                modifier = Modifier.fillMaxSize())
        }
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
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 18.dp)
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
            Text("All", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Text(selectionTitle(count), style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.semantics { heading() }.testTag("atlas-selection-count"))
    }
}

/** One entry of the selection bar's More menu, in Samsung's wording. */
data class SelectionMoreAction(val label: String, val testTag: String, val onClick: () -> Unit)

/**
 * #17 floating selection action pill, replacing Material's full-width NavigationBar.
 * Match the main gallery pill: centered rounded surface, 12 dp edge minimum,
 * 8 dp vertical clearance and explicit navigation-bar inset handling.
 *
 * Every visible action retains its real callback and stable test tag. In
 * particular, onTrash is supplied by MainActivity's Android consent request;
 * this presentation layer never mutates media itself.
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
    LaunchedEffect(active) { if (!active) moreOpen = false }

    BoxWithConstraints(
        modifier.fillMaxWidth()
            // Tag the full-width outer region, not its inset/padded content.
            .testTag("atlas-selection-bottom-bar")
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Tablet layouts stay compact; narrow phones divide available width
        // equally among the actual actions rather than overflowing the edge.
        val pillWidth = minOf(maxWidth, 360.dp)
        val directTransfer = moreActions.firstOrNull { it.testTag == "atlas-selection-copy-to-album" }
            ?: moreActions.firstOrNull { it.testTag == "atlas-selection-move-to-album" }
        // Keep both Copy and Move available from More for existing navigation
        // and accessibility, while the direct action offers the common Copy path.
        val overflowActions = moreActions
        Surface(
            modifier = Modifier.width(pillWidth).height(AtlasTabPillHeight)
                .testTag("atlas-selection-pill"),
            shape = CircleShape,
            color = AtlasPillColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (LocalAtlasDark.current) 0.dp else 4.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onShare != null) SelectionPillAction(
                    "Share", Icons.Outlined.Share, active, "atlas-selection-share",
                    onShare, Modifier.weight(1f))
                if (onTrash != null) SelectionPillAction(
                    "Delete", Icons.Outlined.Delete, active, "atlas-selection-trash",
                    onTrash, Modifier.weight(1f))
                if (directTransfer != null) SelectionPillAction(
                    if (directTransfer.testTag == "atlas-selection-move-to-album") "Move" else "Copy",
                    Icons.Outlined.ContentCopy,
                    active, "atlas-selection-transfer",
                    directTransfer.onClick, Modifier.weight(1f))
                if (onFavourite != null && directTransfer == null) SelectionPillFavourite(
                    allFavourite, active, onFavourite, Modifier.weight(1f))
                if (overflowActions.isNotEmpty() || onFavourite != null && directTransfer != null) {
                    Box(Modifier.weight(1f)) {
                        SelectionPillAction(
                            "More", Icons.Filled.MoreVert, active, "atlas-selection-more",
                            { moreOpen = true }, Modifier.fillMaxWidth())
                        DropdownMenu(
                            expanded = moreOpen && active,
                            onDismissRequest = { moreOpen = false },
                            modifier = Modifier.testTag("atlas-selection-more-menu")
                        ) {
                            if (onFavourite != null && directTransfer != null) {
                                DropdownMenuItem(
                                    text = { Text(if (allFavourite) "Unfavourite" else "Favourite") },
                                    onClick = { moreOpen = false; onFavourite() },
                                    modifier = Modifier.testTag("atlas-selection-more-favourite")
                                )
                            }
                            overflowActions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = { moreOpen = false; action.onClick() },
                                    modifier = Modifier.testTag(action.testTag)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Large touch targets but no Material NavigationBar selection indicator. */
@Composable
private fun SelectionPillAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val tint = if (enabled) ink else ink.copy(alpha = .38f)
    Column(
        modifier.fillMaxHeight().clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A real toggleable favourite state for TalkBack (not a fake selected tab). */
@Composable
private fun SelectionPillFavourite(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val tint = when {
        !enabled -> ink.copy(alpha = .38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> ink
    }
    Column(
        modifier.fillMaxHeight().clip(CircleShape)
            .toggleable(
                value = selected, enabled = enabled, role = Role.Checkbox,
                onValueChange = { onClick() })
            .testTag("atlas-selection-favourite"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(if (selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(if (selected) "Unfavourite" else "Favourite",
            color = tint, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
