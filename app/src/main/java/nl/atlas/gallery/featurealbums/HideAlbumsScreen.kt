package nl.atlas.gallery.featurealbums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuredesign.AtlasSpace
import nl.atlas.gallery.featureorganization.SelectionCheckCircle

/**
 * Samsung's "Hide albums" screen (parity research `GRID:A7`, #568), opened from the
 * Albums ⋮ menu: every album with a check circle; a checked album is hidden
 * from Albums and its items from Photos. Each tap applies at once; Back
 * leaves. Nothing on the device is moved or changed.
 */
@Composable
fun HideAlbumsScreen(
    albums: List<AlbumSummary>?,
    hidden: Set<AlbumBucketKey>,
    onToggle: (AlbumBucketKey) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)? = null
) {
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag("atlas-hide-albums")) {
        AtlasGalleryHeader(title = "Hide albums", onBack = onClose)
        if (albums == null) {
            AtlasGalleryLoading("Gathering albums on this device…")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = AtlasSpace.Gutter, end = AtlasSpace.Gutter,
                top = 8.dp, bottom = AtlasSpace.Section),
            horizontalArrangement = Arrangement.spacedBy(AtlasSpace.TileGap),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().testTag("atlas-hide-albums-grid")
        ) {
            itemsIndexed(albums) { index, album ->
                val key = AlbumBucketKey(album.volumeName, album.relativePath)
                val isHidden = key in hidden
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                        .toggleable(value = isHidden, role = Role.Checkbox) { onToggle(key) }
                        .semantics {
                            contentDescription = listOfNotNull(album.title, album.locationLabel).joinToString(", ")
                            stateDescription = if (isHidden) "Hidden" else "Shown"
                        }
                        .testTag("atlas-hide-album-$index")
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val cover = album.coverMedia
                        if (cover != null && coverRenderer != null) {
                            // Hidden albums are dimmed, as in Samsung's list.
                            coverRenderer(cover, Modifier.fillMaxSize().alpha(if (isHidden) .45f else 1f))
                        }
                        SelectionCheckCircle(isHidden, Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(album.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
                    if (album.locationLabel != null) {
                        Text(album.locationLabel, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
                    }
                }
            }
        }
    }
}
