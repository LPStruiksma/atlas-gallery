package nl.atlas.gallery.featurealbums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuredesign.AtlasSpace

/**
 * Samsung's "Copy to album" / "Move to album" picker (#568): the album grid
 * with "Create album" as its first tile. A tap on an album picks it; Create
 * album asks for a name first. Back or the arrow closes without changes.
 */
@Composable
fun AlbumPickerScreen(
    title: String,
    albums: List<AlbumSummary>?,
    onPick: (AlbumSummary) -> Unit,
    onCreate: (String) -> Unit,
    nameProblem: (String) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    coverRenderer: (@Composable (MediaRecord, Modifier) -> Unit)? = null
) {
    BackHandler(onBack = onClose)
    var creating by remember { mutableStateOf(false) }
    // The picker draws above the Scaffold, so it keeps clear of the system bars itself.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .systemBarsPadding().testTag("atlas-album-picker")) {
        AtlasGalleryHeader(title = title, onBack = onClose)
        if (albums == null) {
            AtlasGalleryLoading("Gathering albums on this device…")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = AtlasSpace.Gutter, end = AtlasSpace.Gutter,
                    top = 8.dp, bottom = AtlasSpace.Section),
                horizontalArrangement = Arrangement.spacedBy(AtlasSpace.TileGap),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().testTag("atlas-album-picker-grid")
            ) {
                item(key = "create") {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                            .clickable(role = Role.Button, onClickLabel = "Create album") { creating = true }
                            .testTag("atlas-album-picker-create")
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Create album", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 3.dp))
                    }
                }
                // Positional keys: folder paths never reach lazy saved state.
                itemsIndexed(albums) { index, album ->
                    AtlasAlbumCard(
                        album = album,
                        onOpen = { onPick(album) },
                        mediaFilter = AlbumMediaFilter.ALL,
                        coverRenderer = coverRenderer,
                        modifier = Modifier.testTag("atlas-album-picker-album-$index")
                    )
                }
            }
        }
    }
    if (creating) {
        CreateAlbumDialog(
            nameProblem = nameProblem,
            onDismiss = { creating = false },
            onCreate = { name -> creating = false; onCreate(name) }
        )
    }
}

/** Samsung's "Create album" name dialog: one field, Cancel and Create. */
@Composable
fun CreateAlbumDialog(
    nameProblem: (String) -> String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val problem = nameProblem(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create album") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; touched = true },
                singleLine = true,
                placeholder = { Text("Album name") },
                isError = touched && problem != null,
                supportingText = if (touched && problem != null) ({ Text(problem) }) else null,
                modifier = Modifier.fillMaxWidth().testTag("atlas-create-album-name")
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = problem == null,
                modifier = Modifier.testTag("atlas-create-album-confirm")) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
