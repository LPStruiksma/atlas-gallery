package nl.atlas.gallery.featurealbums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuredesign.AtlasSpace
import nl.atlas.gallery.featureorganization.SelectionCheckCircle

/**
 * HIDE-1 (#585): the Hidden collection. Entered in one tap from the Albums menu,
 * no authentication and no warning screen; a hidden item is only a stored key,
 * other apps still see the file. A long press starts selection; Unhide is direct.
 * Tapping a photo outside selection opens the viewer, where More offers Unhide.
 * [items] is the inverse of the shared Hidden filter, never an ordinary list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HiddenMediaScreen(
    items: List<MediaRecord>?,
    onOpen: (MediaRecord) -> Unit,
    onUnhide: (List<MediaRecord>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    tileRenderer: (@Composable (MediaRecord, Modifier) -> Unit)? = null
) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    // Back leaves selection first, then the screen.
    BackHandler { if (selecting) selected = emptySet() else onClose() }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag("atlas-hidden")) {
        AtlasGalleryHeader(
            title = if (selecting) "${selected.size} selected" else "Hidden",
            onBack = { if (selecting) selected = emptySet() else onClose() })
        when {
            items == null -> AtlasGalleryLoading("Loading hidden items…", bottomInset = 0.dp)
            items.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(AtlasSpace.Gutter),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No hidden photos or videos.", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("atlas-hidden-empty"))
                TextButton(onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-hidden-back")) {
                    Text("Back to albums")
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f).testTag("atlas-hidden-grid")
                ) {
                    items(items, key = { it.uri }) { record ->
                        val isSelected = record.uri in selected
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) selected =
                                            if (isSelected) selected - record.uri else selected + record.uri
                                        else onOpen(record)
                                    },
                                    onLongClick = { selected = selected + record.uri },
                                    onClickLabel = if (selecting) "Toggle selection" else "Open",
                                    onLongClickLabel = "Select",
                                    role = Role.Button
                                )
                                .semantics {
                                    contentDescription = if (record.isVideo) "Hidden video" else "Hidden photo"
                                    if (selecting) stateDescription = if (isSelected) "Selected" else "Not selected"
                                }
                                .testTag("atlas-hidden-tile")
                        ) {
                            tileRenderer?.invoke(record, Modifier.fillMaxSize())
                            if (selecting) SelectionCheckCircle(isSelected,
                                Modifier.align(Alignment.TopStart).padding(8.dp))
                        }
                    }
                }
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = {
                                val chosen = items.filter { it.uri in selected }
                                selected = emptySet()
                                onUnhide(chosen)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                                .semantics { contentDescription = "Unhide, ${selected.size} selected items" }
                                .testTag("atlas-hidden-unhide")
                        ) { Text("Unhide") }
                    }
                }
            }
        }
    }
}
