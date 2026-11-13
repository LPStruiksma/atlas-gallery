package nl.atlas.gallery.featurealbums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuredesign.AtlasSpace

/**
 * Explicit opt-in session-only cover chooser; no saved URI or image decoding.
 *
 * The integrator MUST pass a current authorized inventory and its publication
 * epoch. All preview and selection actions fail closed on a stale source/epoch
 * and the final choice is a typed request, not a database/storage mutation.
 *
 * Rendering requires I's grant/revision-aware MediaPreview implementation.
 * Without that callback, there is no blind "select cover" control.
 */
@Composable
fun AtlasAlbumCoverChooser(
    projection: AlbumCoverChoiceProjection?,
    authorizedSource: List<MediaRecord>,
    accessEpoch: Long,
    route: AlbumRoute,
    onDismiss: () -> Unit,
    onChoose: (AlbumCoverChoice) -> Unit,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    pageCursor: AlbumCoverPageCursor? = null,
    onOlderPage: ((AlbumCoverPageCursor) -> Unit)? = null,
    onPreviousPage: (() -> Unit)? = null,
    isLoading: Boolean = false,
    /** Presentation only: the source-validated custom cover, never an access grant. */
    selectedCoverUri: String? = null,
    /** I must revalidate the live permission generation before clearing a preference. */
    onRestoreAutomatic: (() -> Unit)? = null,
    /**
     * Independent live permission-generation token, not the catalogue epoch.
     * null = conservative epoch-derived default (fails closed on a bad epoch).
     */
    currentPermissionScope: AlbumCoverPermissionScope? = null
) {
    val candidates = currentAlbumCoverChoicesOrNull(
        projection, authorizedSource, accessEpoch, route, pageCursor,
        permissionScope = currentPermissionScope)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = AtlasSpace.Gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(AtlasSpace.MinimumTarget)
                    .testTag("atlas-cover-picker-back")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close cover picker"
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Choose album cover", style = MaterialTheme.typography.titleLarge)
                Text(
                    albumCoverRouteContext(route),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("atlas-cover-album-context")
                )
                Text(
                    when (route.mediaFilter) {
                        AlbumMediaFilter.ALL -> "Photos and videos"
                        AlbumMediaFilter.PHOTOS -> "Photos only"
                        AlbumMediaFilter.VIDEOS -> "Videos only"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        when {
            isLoading -> {
                // Background next-page work has not yet published a complete
                // grant-bound projection. Do not misreport this as revocation.
                AtlasGalleryLoading("Finding album cover choices…")
            }
            candidates == null -> {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AtlasEmptyState(
                        title = "Album access changed",
                        explanation = "The library changed while choosing a cover. " +
                            "Return to Albums and open this picker again.",
                        icon = Icons.Filled.Collections,
                        actionLabel = "Close cover picker",
                        onAction = onDismiss
                    )
                }
            }
            coverRenderer == null -> {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AtlasEmptyState(
                        title = "Cover previews unavailable",
                        explanation = "A current, permission-aware photo preview is needed " +
                            "before you can choose a cover.",
                        icon = Icons.Filled.Collections,
                        actionLabel = "Close cover picker",
                        onAction = onDismiss
                    )
                }
            }
            candidates.isEmpty() -> {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AtlasEmptyState(
                        title = "No accessible cover choices",
                        explanation = "There are no currently available media items " +
                            "matching this album and type filter.",
                        icon = Icons.Filled.Collections,
                        actionLabel = "Close cover picker",
                        onAction = onDismiss
                    )
                }
            }
            else -> {
                Text(
                    if (pageCursor == null)
                        "Most recent ${candidates.size} choices · No originals changed"
                    else "Older album covers · No originals changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AtlasSpace.Gutter, vertical = 8.dp)
                )
                // Reversible, explicitly session-scoped cover editing: no silent
                // permanent save or URI retained when Android access changes.
                if (selectedCoverUri != null && onRestoreAutomatic != null) {
                    val largeText = LocalDensity.current.fontScale >= 1.5f
                    if (largeText) {
                        Column(Modifier.fillMaxWidth().padding(
                            horizontal = AtlasSpace.Gutter, vertical = 4.dp
                        )) {
                            Text("Custom cover for this session",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RestoreAutomaticCoverButton(
                                onRestoreAutomatic, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(
                            horizontal = AtlasSpace.Gutter, vertical = 4.dp
                        ), verticalAlignment = Alignment.CenterVertically) {
                            Text("Custom cover for this session",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RestoreAutomaticCoverButton(onRestoreAutomatic)
                        }
                    }
                }
                // URI keys can enter Compose's saveable lazy-item registry.
                // Scope numerical slots to the exact completed projection:
                // never persist a private URI or reuse remembered preview state
                // after another page/permission publication is displayed.
                key(projection) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(136.dp),
                    contentPadding = PaddingValues(
                        start = AtlasSpace.Gutter,
                        end = AtlasSpace.Gutter,
                        bottom = AtlasSpace.Section
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AtlasSpace.TileGap),
                    verticalArrangement = Arrangement.spacedBy(AtlasSpace.TileGap),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(candidates, key = { index, _ -> index }) { _, candidate ->
                        val isSelected = candidate.uri == selectedCoverUri
                        val label = if (candidate.isVideo)
                            "Use video as cover: ${candidate.name}"
                        else "Use photo as cover: ${candidate.name}"
                        Column(
                            Modifier.clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Use as album cover"
                                ) {
                                    // Re-evaluate the exact source and epoch at
                                    // click time, not merely composition time.
                                    chooseAlbumCoverOrNull(
                                        projection, authorizedSource, accessEpoch,
                                        route, candidate.uri, pageCursor,
                                        permissionScope = currentPermissionScope
                                    )?.let(onChoose)
                                }
                                .semantics {
                                    contentDescription = if (isSelected)
                                        "$label, selected custom cover" else label
                                }
                                .testTag("atlas-cover-choice")
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                coverRenderer(candidate, Modifier.fillMaxSize())
                                if (isSelected) {
                                    // Keep the video glyph visible on the opposite corner.
                                    Box(Modifier.align(Alignment.TopStart).padding(8.dp)
                                        .background(MaterialTheme.colorScheme.primary,
                                            CircleShape)
                                        .size(32.dp), contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                                if (candidate.isVideo) {
                                    Icon(
                                        Icons.Filled.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                            .padding(8.dp).size(24.dp)
                                    )
                                }
                            }
                            Text(
                                candidate.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
                }
                val previous = if (pageCursor != null) onPreviousPage else null
                val next = projection?.nextOlderCursor?.takeIf { onOlderPage != null }
                if (previous != null || next != null) {
                    // At large font scales place controls vertically rather than
                    // clipping two long, independently accessible button labels.
                    val largeText = LocalDensity.current.fontScale >= 1.5f
                    if (largeText) {
                        Column(
                            Modifier.fillMaxWidth().padding(
                                horizontal = AtlasSpace.Gutter, vertical = 6.dp)
                        ) {
                            if (previous != null) CoverPageButton(
                                "Newer choices", "atlas-cover-page-back",
                                previous, Modifier.fillMaxWidth()
                            )
                            if (next != null && onOlderPage != null) CoverPageButton(
                                "Older choices", "atlas-cover-page-next",
                                { onOlderPage(next) }, Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(
                                horizontal = AtlasSpace.Gutter, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (previous != null) CoverPageButton(
                                "Newer choices", "atlas-cover-page-back", previous
                            ) else Spacer(Modifier.width(1.dp))
                            if (next != null && onOlderPage != null) CoverPageButton(
                                "Older choices", "atlas-cover-page-next",
                                { onOlderPage(next) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Consistent 48dp minimum target across regular and large-text page layouts. */
@Composable
private fun CoverPageButton(
    label: String, testTag: String, onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = AtlasSpace.MinimumTarget).testTag(testTag)
    ) { Text(label) }
}

/** An optional, functional undo control. Never shows without I's live-grant callback. */
@Composable
private fun RestoreAutomaticCoverButton(
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = AtlasSpace.MinimumTarget)
            .testTag("atlas-cover-restore-automatic")
    ) { Text("Use automatic cover") }
}
