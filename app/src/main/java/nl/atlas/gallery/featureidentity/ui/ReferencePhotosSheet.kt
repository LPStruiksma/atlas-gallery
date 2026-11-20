package nl.atlas.gallery.featureidentity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * S7 "Photos used for <name>": the reference faces, each removable; "+ Add photos"; and
 * "Forget photos for <name>" behind "Forget? Your saved photos stay."
 */
@Composable
internal fun PhotosUsedSheet(state: SwapPersonUiState, machine: SwapPersonMachine) {
    val name = state.person?.displayName ?: "this person"
    SwapBottomSheet(expanded = true, onExpandedChange = {}, onDismiss = machine::closeSheet, tag = "swap-photos-used-sheet") {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Photos used for $name", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(machine::closeSheet, Modifier.size(48.dp).testTag("swap-photos-used-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        Spacer(Modifier.height(8.dp))
        val photos = state.photosUsed
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().weight(1f).testTag("swap-photos-used-grid"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photos == null) items(6) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            } else items(photos, key = { it.id }) { photo ->
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).testTag("swap-reference")) {
                    FaceCropImage(photo.face.image, photo.face.box, Modifier.fillMaxSize())
                    IconButton({ machine.removeReference(photo.id) }, Modifier.align(Alignment.TopEnd).size(48.dp)
                        .testTag("swap-reference-remove")) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(SwapColors.StrongScrim), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove this photo", tint = Color.White,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            item(key = "add") {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clickable { machine.choosePhotos(state.person?.profileId) }.testTag("swap-add-photos"),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Add photos", fontSize = 12.sp)
                    }
                }
            }
        }
        TextButton(machine::askForget, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp)
            .testTag("swap-forget")) {
            Text("Forget photos for $name", color = MaterialTheme.colorScheme.error)
        }
    }
    if (state.forgetDialog) AlertDialog(
        onDismissRequest = machine::cancelForget,
        text = { Text("Forget? Your saved photos stay.") },
        confirmButton = { TextButton(machine::forget, Modifier.testTag("swap-forget-confirm")) { Text("Forget") } },
        dismissButton = { TextButton(machine::cancelForget) { Text("Cancel") } },
        modifier = Modifier.testTag("swap-forget-dialog")
    )
}

/**
 * S8 "Choose photos": pick 3–12 photos of one person, then (for a new person) an optional name
 * pinned above the keyboard with the chosen faces visible. A multi-face photo asks
 * "Which one is <name>?" unless the local match is clear.
 */
@Composable
internal fun ChoosePhotosSheet(state: SwapPersonUiState, pick: SwapPickState, machine: SwapPersonMachine) {
    val who = pick.forProfile?.let { state.person?.displayName } ?: "this person"
    if (state.sheet == SwapSheet.WHICH_FACE && pick.asking != null) return WhichFaceSheet(pick.asking, who, machine)
    SwapBottomSheet(expanded = true, onExpandedChange = {}, onDismiss = machine::closeSheet, tag = "swap-choose-sheet") {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(machine::closeSheet, Modifier.size(48.dp).testTag("swap-choose-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            Text(if (pick.naming) "Name this person" else "Choose photos", fontSize = 20.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (!pick.naming) Button(machine::pickNext, enabled = pick.canContinue,
                modifier = Modifier.heightIn(min = 48.dp).testTag("swap-choose-next")) { Text("Next") }
        }
        if (pick.naming) NamingStep(pick, machine)
        else {
            Text("Choose 3 to 12 photos of one person. ${pick.chosen.size} chosen.", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("swap-choose-count"))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().weight(1f).testTag("swap-choose-grid"),
                contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(pick.photos, key = { it.id }) { photo ->
                    val chosen = pick.chosen.any { it.first.id == photo.id }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable { machine.togglePick(photo) }
                        .testTag("swap-choose-photo").semantics { this.selected = chosen }) {
                        FaceCropImage(photo.image, null, Modifier.fillMaxSize().then(if (chosen) Modifier.padding(8.dp)
                            .clip(RoundedCornerShape(8.dp)) else Modifier))
                        Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape)
                            .background(if (chosen) MaterialTheme.colorScheme.primary else SwapColors.Scrim)
                            .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NamingStep(pick: SwapPickState, machine: SwapPersonMachine) {
    Spacer(Modifier.weight(1f))
    LazyRow(Modifier.fillMaxWidth().testTag("swap-name-crops"), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pick.chosen, key = { it.first.id }) { (photo, box) ->
            FaceCropImage(photo.image, box, Modifier.size(56.dp).clip(CircleShape))
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(pick.name, machine::nameChanged, Modifier.weight(1f).testTag("swap-name-field"),
            placeholder = { Text("Name this person") }, singleLine = true)
        Spacer(Modifier.width(8.dp))
        Button(machine::pickDone, Modifier.heightIn(min = 48.dp).testTag("swap-name-done")) { Text("Done") }
    }
}

/** "Which one is <name>?": the photo with every face outlined (≥ 48 dp targets) and Skip. */
@Composable
private fun WhichFaceSheet(photo: SwapPickPhoto, who: String, machine: SwapPersonMachine) {
    SwapBottomSheet(expanded = true, onExpandedChange = {}, onDismiss = { machine.answerWhichFace(null) }, tag = "swap-which-face") {
        Text("Which one is $who?", fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val rect = fit(photo.image.width, photo.image.height, w, h)
            val density = LocalDensity.current
            val minTarget = with(density) { 48.dp.toPx() }
            val stroke = with(density) { 2.dp.toPx() }
            FittedImage(photo.image, Modifier.fillMaxSize())
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                photo.faces.forEach { box ->
                    val tl = rect.toView(box.left.toFloat(), box.top.toFloat())
                    val br = rect.toView(box.right.toFloat(), box.bottom.toFloat())
                    drawRoundRect(Color.White, Offset(tl.x - 8f, tl.y - 8f), Size(br.x - tl.x + 16f, br.y - tl.y + 16f),
                        CornerRadius(12.dp.toPx()), style = Stroke(stroke))
                }
            }
            photo.faces.forEachIndexed { i, box ->
                val tl = rect.toView(box.left.toFloat(), box.top.toFloat())
                val br = rect.toView(box.right.toFloat(), box.bottom.toFloat())
                val side = max(max(br.x - tl.x, br.y - tl.y) + 16f, minTarget)
                val cx = (tl.x + br.x) / 2; val cy = (tl.y + br.y) / 2
                with(density) {
                    Box(Modifier.padding(start = (cx - side / 2).coerceAtLeast(0f).toDp(), top = (cy - side / 2).coerceAtLeast(0f).toDp())
                        .size(side.toDp()).clickable { machine.answerWhichFace(box) }.testTag("swap-which-face-$i")
                        .semantics { role = Role.Button; contentDescription = "Face ${i + 1} of ${photo.faces.size}" })
                }
            }
        }
        TextButton({ machine.answerWhichFace(null) }, Modifier.align(Alignment.End).padding(8.dp).heightIn(min = 48.dp)
            .testTag("swap-which-skip")) { Text("Skip this photo") }
    }
}
