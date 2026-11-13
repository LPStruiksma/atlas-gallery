package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuregrid.AtlasRatioGallery
import nl.atlas.gallery.featuregrid.AtlasTimelineGallery
import nl.atlas.gallery.featuregrid.GalleryZoomStop
import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum

/**
 * #551: everything the person page draws, already resolved by its route.
 * [items] is null while the photos are still being resolved.
 */
internal data class PersonPageUi(
    val name: String?,
    val items: List<MediaRecord>?,
    val hidden: Boolean,
    /** Offered only for a named person (an unknown group has no stable key to hide). */
    val mayHide: Boolean
)

/** The modal step the page is in; one at a time, like Samsung. */
internal sealed interface PersonPageDialog {
    data object EditName : PersonPageDialog
    data object MergePicker : PersonPageDialog
    data class ConfirmMerge(val into: NativePersonAlbum) : PersonPageDialog
}

/**
 * Samsung Gallery's person page (#551): a large round face and the name (or
 * "Add name", which opens naming), the count, then the person's photos in the
 * same timeline grid as Photos (newest first, same pinch levels). ⋮ holds
 * Edit name, Merge with…, Remove from this person and Hide this person.
 * "Remove from this person" turns the grid into a selection with one Remove
 * action at the bottom. Stateless apart from transient UI state: the route
 * owns every read and write.
 */
@Composable
internal fun PersonPageScreen(
    ui: PersonPageUi,
    gridState: LazyGridState,
    catalogueEpoch: Long,
    /** #584: a new opening of the page starts at the grid; a retarget keeps the stop. */
    opening: Long = 0L,
    portrait: @Composable () -> Unit,
    onBack: () -> Unit,
    onOpen: (MediaRecord) -> Unit,
    dialog: PersonPageDialog? = null,
    onDialog: (PersonPageDialog?) -> Unit,
    nameDialog: @Composable () -> Unit,
    mergePicker: @Composable () -> Unit,
    onHide: (Boolean) -> Unit,
    selectedUris: Set<String>?,
    onSelectionChange: (Set<String>?) -> Unit,
    /** Null when the current selection cannot be removed (none or every photo). */
    onRemoveSelected: (() -> Unit)?,
    confirmMerge: @Composable (NativePersonAlbum) -> Unit,
    /** #580: the person's open "Same person?" question, drawn under the header. */
    samePerson: (@Composable () -> Unit)? = null
) {
    val items = ui.items
    val selecting = selectedUris != null
    val title = ui.name ?: "Add name"
    val menu = buildList {
        add(AtlasHeaderMenuItem(if (ui.name == null) "Add name" else "Edit name",
            "atlas-person-edit-name", onClick = { onDialog(PersonPageDialog.EditName) }))
        add(AtlasHeaderMenuItem("Merge with…", "atlas-person-merge",
            onClick = { onDialog(PersonPageDialog.MergePicker) }))
        add(AtlasHeaderMenuItem("Remove from this person", "atlas-person-remove",
            enabled = (items?.size ?: 0) > 1,
            onClick = { onSelectionChange(emptySet()) }))
        if (ui.mayHide) add(AtlasHeaderMenuItem(
            if (ui.hidden) "Show this person" else "Hide this person",
            "atlas-person-hide", onClick = { onHide(!ui.hidden) }))
    }
    // Real-ratio rows (#527) are a zoom stop of this page, not a separate route.
    var originalProportions by rememberSaveable(opening) { mutableStateOf(false) }
    var returnDensity by remember(opening) { mutableStateOf<GridDensity?>(null) }
    // #584: the app root colour, like Photos and People (one dark theme).
    Box(Modifier.fillMaxSize().background(DeepBg).testTag("atlas-person-page")) {
        val header: @Composable () -> Unit = {
            // Keep Back and ⋮ clear of the status bar, like the album page.
            Column(Modifier.fillMaxWidth().background(DeepBg)
                .statusBarsPadding()) {
                if (selecting) {
                    SelectionBar(selectedUris.orEmpty().size) { onSelectionChange(null) }
                } else {
                    AtlasGalleryHeader(title = "", onBack = onBack, menuItems = menu)
                }
                PersonHeader(ui, title, items, portrait,
                    onName = if (selecting) null else ({ onDialog(PersonPageDialog.EditName) }))
                if (!selecting) samePerson?.invoke()
            }
        }
        if (items == null) {
            Column(Modifier.fillMaxSize()) {
                header()
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp).testTag("atlas-person-loading"))
                }
            }
        } else if (originalProportions && !selecting) {
            // #527 on the person page: pinching out past the largest square stop
            // enters the same bar-free real-ratio rows as Photos and albums; the
            // person header stays above them and pinching in returns to the grid.
            Column(Modifier.fillMaxSize()) {
                header()
                key(CatalogSourceKey(items)) {
                    AtlasRatioGallery(
                        items = items,
                        aspectRatioOf = { it.displayAspectRatioOrNull() },
                        onOpen = onOpen,
                        catalogueEpoch = catalogueEpoch,
                        onCompactGridRequested = { request ->
                            if (request.catalogueEpoch == catalogueEpoch) {
                                returnDensity = request.destination?.squareDensityOrNull()
                                    ?: GridDensity.LARGE
                                originalProportions = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            AtlasTimelineGallery(
                items = items,
                sort = TimelineSort.NEWEST,
                gridState = gridState,
                onOpen = onOpen,
                viewKey = "people",
                catalogueEpoch = catalogueEpoch,
                // #642: a person's photos are spread over many days; merge the
                // sparse ones so they fill rows instead of one photo per header.
                mergeSparseDays = true,
                selectedMediaUris = selectedUris,
                // #556: long-press starts selection, drag extends it, header circles toggle a day.
                onSelectionChange = { next -> onSelectionChange(next) },
                onMediaToggle = { media ->
                    val current = selectedUris.orEmpty()
                    onSelectionChange(if (media.uri in current) current - media.uri
                        else current + media.uri)
                },
                header = header,
                onOriginalProportionsRequested = { request ->
                    if (!selecting && request.catalogueEpoch == catalogueEpoch &&
                        request.destination == GalleryZoomStop.ORIGINAL) {
                        originalProportions = true
                    }
                },
                entryDensity = returnDensity,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (selecting) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Box(Modifier.navigationBarsPadding().heightIn(min = 64.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = { onRemoveSelected?.invoke() },
                        enabled = onRemoveSelected != null,
                        modifier = Modifier.testTag("atlas-person-remove-confirm")
                    ) { Text("Remove from this person") }
                }
            }
        }
    }
    when (val step = dialog) {
        PersonPageDialog.EditName -> nameDialog()
        PersonPageDialog.MergePicker -> mergePicker()
        is PersonPageDialog.ConfirmMerge -> confirmMerge(step.into)
        null -> Unit
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("atlas-person-select-close")) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel")
        }
        Text(if (count == 0) "Select items" else "$count selected",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("atlas-person-select-count"))
    }
}

@Composable
private fun PersonHeader(
    ui: PersonPageUi,
    title: String,
    items: List<MediaRecord>?,
    portrait: @Composable () -> Unit,
    onName: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(132.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .testTag("atlas-person-portrait"),
            contentAlignment = Alignment.Center
        ) { portrait() }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
            fontWeight = if (ui.name == null) FontWeight.Normal else FontWeight.SemiBold,
            color = if (ui.name == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
                .then(if (onName != null) Modifier.clickable(onClickLabel =
                    if (ui.name == null) "Add name" else "Edit name") { onName() } else Modifier)
                .semantics { heading() }
                .testTag("atlas-person-name")
        )
        if (items != null) {
            val videos = items.count { it.isVideo }
            Text(nl.atlas.gallery.featurepeopleui.personPageCount(items.size - videos, videos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag("atlas-person-album-count"))
        }
    }
}

/**
 * #580 "Same person?": an unnamed group that may be this person, with its
 * face and size. Only a question: Yes adds the group to this person, No keeps
 * it apart and the pair is not asked again. Nothing is joined without an answer.
 */
@Composable
internal fun SamePersonCard(
    otherFaces: Int,
    otherPortrait: @Composable () -> Unit,
    onAnswer: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .testTag("atlas-person-same")
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .testTag("atlas-person-same-portrait"),
                contentAlignment = Alignment.Center
            ) { otherPortrait() }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Same person?", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (otherFaces == 1) "1 more face" else "%,d more faces"
                        .format(java.util.Locale.US, otherFaces),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("atlas-person-same-count"))
            }
            TextButton(onClick = { onAnswer(false) },
                modifier = Modifier.testTag("atlas-person-same-no")) { Text("No") }
            TextButton(onClick = { onAnswer(true) },
                modifier = Modifier.testTag("atlas-person-same-yes")) { Text("Yes") }
        }
    }
}

/** The default portrait while no face crop is available. */
@Composable
internal fun PersonPortraitFallback() {
    Icon(Icons.Filled.Person, contentDescription = null,
        modifier = Modifier.fillMaxSize(0.6f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Samsung's naming sheet: one field, existing names suggested as you type
 * (with their face), Cancel and Save. Picking a suggestion is the same as
 * typing that name: the route then offers a merge.
 */
@Composable
internal fun PersonNameDialog(
    initial: String,
    suggestions: (String) -> List<NativePersonAlbum>,
    suggestionPortrait: @Composable (NativePersonAlbum) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shown = suggestions(text).filterNot { it.name == text.trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add name" else "Edit name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 80) text = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (text.isNotBlank()) onSave(text)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .testTag("atlas-person-name-field")
                )
                shown.forEach { person ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable { text = person.name; onSave(person.name) }
                            .padding(vertical = 6.dp)
                            .testTag("atlas-person-name-suggestion"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center) { suggestionPortrait(person) }
                        Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(),
                modifier = Modifier.testTag("atlas-person-name-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.testTag("atlas-person-name-dialog")
    )
}

/** "Merge with…": every other named person as a round face with a name. */
@Composable
internal fun PersonMergePicker(
    people: List<NativePersonAlbum>,
    portrait: @Composable (NativePersonAlbum) -> Unit,
    onDismiss: () -> Unit,
    onPick: (NativePersonAlbum) -> Unit
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("atlas-person-merge-picker"),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                AtlasGalleryHeader(title = "Merge with", onBack = onDismiss)
                if (people.isEmpty()) {
                    Text("No other named people yet", Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(people, key = { it.person.value }) { person ->
                            Column(
                                Modifier.clickable { onPick(person) }
                                    .testTag("atlas-person-merge-candidate"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center) { portrait(person) }
                                Text(person.name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One plain confirmation, as Samsung asks before merging two people. */
@Composable
internal fun PersonMergeConfirm(
    currentName: String?,
    into: NativePersonAlbum,
    onDismiss: () -> Unit,
    onMerge: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge with ${into.name}?") },
        text = {
            Text(if (currentName == null) "These photos will be added to ${into.name}."
                else "$currentName and ${into.name} will become one person.")
        },
        confirmButton = {
            TextButton(onClick = onMerge, modifier = Modifier.testTag("atlas-person-merge-confirm")) {
                Text("Merge")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.testTag("atlas-person-merge-dialog")
    )
}

/**
 * People ⋮ → Hidden: the hidden people as round faces; tapping one opens its
 * page, where ⋮ → Show this person brings it back.
 */
@Composable
internal fun HiddenPeopleScreen(
    people: List<NativePersonAlbum>,
    portrait: @Composable (NativePersonAlbum) -> Unit,
    onBack: () -> Unit,
    onOpen: (NativePersonAlbum) -> Unit
) {
    // AtlasGalleryHeader already clears the status bar here (People's own inset).
    Column(Modifier.fillMaxSize().background(DeepBg).testTag("atlas-people-hidden")) {
        AtlasGalleryHeader(title = "Hidden", onBack = onBack)
        if (people.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hidden people", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(people, key = { it.person.value }) { person ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(person) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("atlas-people-hidden-person"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(Modifier.size(56.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center) { portrait(person) }
                    Text(person.name, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(Modifier.padding(start = 92.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}
