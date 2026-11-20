package nl.atlas.gallery.featureidentity.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S1 person sheet: "Swap person", a 4-column grid of round 64 dp faces (recents, then named
 * People by photo count), "Choose photos" last, search above 12 people, long press opens S7.
 */
@Composable
internal fun PersonPickerSheet(state: SwapPersonUiState, machine: SwapPersonMachine) {
    var expanded by remember { mutableStateOf(false) }
    SwapBottomSheet(expanded, { expanded = it }, onDismiss = machine::close, tag = "swap-person-sheet") {
        Text("Swap person", fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp))
        if (state.showSearch) {
            SearchField(state.search, machine::search, onFocus = { if (it) expanded = true })
            Spacer(Modifier.height(12.dp))
        }
        (state.notice as? SwapNotice.TooFewPhotos)?.let { notice ->
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("swap-too-few"),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a few more photos of ${notice.name}", Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(machine::openPhotosUsed, Modifier.height(48.dp).testTag("swap-too-few-add")) { Text("Add photos") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val people = state.listedPeople
        if (!state.peopleLoading && people.isEmpty() && state.search.isBlank()) {
            Text("No named people yet. Choose a few photos of the person instead.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).testTag("swap-no-people"))
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f).testTag("swap-person-grid"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.peopleLoading) items(8) { SkeletonTile() }
            else items(people, key = { it.profileId }) { person ->
                PersonTile(person, onTap = { machine.pickPerson(person) }, onLongPress = { machine.openPhotosUsed(person) })
            }
            if (!state.peopleLoading) item(key = "choose") { ChoosePhotosTile { machine.choosePhotos() } }
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp), shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("Search people", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                BasicTextField(text, onChange, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag("swap-person-search").onFocusChanged { onFocus(it.isFocused) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonTile(person: SwapPersonChoice, onTap: () -> Unit, onLongPress: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClick = onTap, onLongClick = onLongPress)
        .padding(vertical = 4.dp).testTag("swap-person-tile")
        .semantics { contentDescription = person.displayName },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp)) {
            PersonAvatar(person, Modifier.size(64.dp))
            if (person.looks.size > 1) LooksBadge(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(6.dp))
        Text(person.displayName, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
internal fun PersonAvatar(person: SwapPersonChoice, modifier: Modifier) {
    val face = person.face
    if (face != null) FaceCropImage(face.image, face.box, modifier.clip(CircleShape))
    else Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        Text(person.displayName.take(1).uppercase(), fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** 16 dp stacked-circle badge: this person has more than one look. */
@Composable
private fun LooksBadge(modifier: Modifier) {
    Box(modifier.size(20.dp).testTag("swap-looks-badge")) {
        val ring = MaterialTheme.colorScheme.surface
        val fill = MaterialTheme.colorScheme.primary
        Box(Modifier.size(12.dp).offset(0.dp, 0.dp).clip(CircleShape).background(fill.copy(alpha = .55f)).border(1.5.dp, ring, CircleShape))
        Box(Modifier.size(12.dp).offset(6.dp, 6.dp).clip(CircleShape).background(fill).border(1.5.dp, ring, CircleShape))
    }
}

@Composable
private fun ChoosePhotosTile(onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .combinedClickableCompat(onTap).padding(vertical = 4.dp).testTag("swap-choose-photos"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text("Choose photos", fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SkeletonTile() {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("swap-person-skeleton"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(44.dp, 10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onTap: () -> Unit): Modifier = this.combinedClickable(onClick = onTap)
