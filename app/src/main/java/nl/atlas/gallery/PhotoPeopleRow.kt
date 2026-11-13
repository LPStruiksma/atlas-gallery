package nl.atlas.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.IndexedPhotoFace

/**
 * #553: one person in the photo shown in the viewer's details sheet. [face]
 * is the observation whose crop is drawn; [epoch] is the face scope it was
 * read under (a crop or a name is only ever asked for under that scope).
 */
sealed interface PhotoPerson {
    val epoch: String
    val face: String

    /** A human-confirmed, named person: tapping opens that person. */
    data class Named(
        override val epoch: String, override val face: String,
        val person: PersonKey, val name: String
    ) : PhotoPerson

    /** A published unknown cluster, keyed by its anchor face: "+" names it. */
    data class Unnamed(
        override val epoch: String, override val face: String,
        val clusterAnchor: String
    ) : PhotoPerson
}

/**
 * Pure (#553): the photo's faces as the People row shows them, Samsung's
 * way: named people first by name, then unnamed groups left to right; one
 * circle per person or group (its left-most face). A confirmed face without
 * a name, or a face whose cluster is not published, shows nothing.
 */
internal fun photoPeopleOf(epoch: String, faces: List<IndexedPhotoFace>): List<PhotoPerson> {
    val leftToRight = faces.sortedWith(compareBy<IndexedPhotoFace> { it.box.left }
        .thenBy { it.box.top }.thenBy { it.nativeFaceUid })
    val named = LinkedHashMap<String, PhotoPerson.Named>()
    val unnamed = LinkedHashMap<String, PhotoPerson.Unnamed>()
    for (face in leftToRight) {
        val person = face.person
        if (person != null) {
            val name = face.name?.takeIf { it.isNotBlank() } ?: continue
            if (person !in named) named[person] =
                PhotoPerson.Named(epoch, face.nativeFaceUid, PersonKey(person), name)
        } else {
            val anchor = face.clusterAnchor ?: continue
            if (anchor !in unnamed) unnamed[anchor] =
                PhotoPerson.Unnamed(epoch, face.nativeFaceUid, anchor)
        }
    }
    return named.values.sortedWith(compareBy<PhotoPerson.Named> {
        it.name.lowercase(Locale.ROOT) }.thenBy { it.person.value }) + unnamed.values
}

/**
 * The name typed in the "+" dialog, trimmed, or null when the review store
 * would refuse it (empty, over 80 characters, or with control characters).
 */
internal fun photoPersonNameOrNull(raw: String): String? {
    val name = raw.trim()
    return name.takeIf {
        it.isNotEmpty() && it.length <= 80 && it.none { c -> Character.isISOControl(c) }
    }
}

/**
 * What the details sheet needs for its People row (#553). The app wires it
 * to [PeopleDiscoveryHost]; tests pass invented people and crops. Null on
 * the sheet hides the row.
 */
class ViewerPhotoPeople internal constructor(
    /** Bumped when faces, names or the scope change; the row reloads. */
    val changes: StateFlow<Long>,
    /** Off main. Null or empty: no row. */
    val load: suspend (MediaRecord) -> List<PhotoPerson>?,
    /** Off main. A small face crop, or null for the neutral circle. */
    val crop: suspend (PhotoPerson) -> ImageBitmap?,
    val onOpen: (PhotoPerson.Named) -> Unit,
    /** Off main. True once the name was stored. */
    val onName: suspend (PhotoPerson.Unnamed, String) -> Boolean
)

/** Binds the row to the app's face host for the gallery publication [galleryGrant]. */
internal fun viewerPhotoPeople(
    host: PeopleDiscoveryHost,
    galleryGrant: () -> String?,
    onOpen: (PhotoPerson.Named) -> Unit
) = ViewerPhotoPeople(
    changes = host.changes,
    load = { photo -> withContext(Dispatchers.IO) { host.photoPeople(galleryGrant(), photo) } },
    crop = { person ->
        withContext(Dispatchers.IO) { host.faceCrop(person.epoch, person.face) }?.asImageBitmap()
    },
    onOpen = onOpen,
    onName = { person, name -> withContext(Dispatchers.IO) { host.nameUnknownCluster(person, name) } }
)

/** The details sheet's People section: loads, shows the row and owns the name dialog. */
@Composable
internal fun ViewerPhotoPeopleSection(media: MediaRecord, port: ViewerPhotoPeople) {
    val version by port.changes.collectAsState()
    val people by produceState<List<PhotoPerson>?>(null, media.uri, version) {
        value = port.load(media)
    }
    var naming by remember(media.uri) { mutableStateOf<PhotoPerson.Unnamed?>(null) }
    val shown = people
    if (!shown.isNullOrEmpty()) {
        PhotoPeopleRow(shown, face = { person -> PhotoPersonCircleImage(person, port) },
            onOpen = port.onOpen, onAddName = { naming = it })
    }
    naming?.let { target ->
        PhotoPersonNameDialog(
            onDismiss = { naming = null },
            onDone = { name -> port.onName(target, name).also { if (it) naming = null } }
        )
    }
}

@Composable
private fun PhotoPersonCircleImage(person: PhotoPerson, port: ViewerPhotoPeople) {
    val crop by produceState<ImageBitmap?>(null, person.epoch, person.face) {
        value = port.crop(person)
    }
    val image = crop
    if (image != null) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Icon(Icons.Filled.Person, null, Modifier.fillMaxSize().padding(12.dp),
            tint = Color(0xFF8B95A1))
    }
}

/**
 * Stateless row (#553): Samsung's People line in the details panel, a
 * "People" label over round face crops with the name under each, or "+" for
 * an unnamed group. Scrolls sideways when the photo holds many people.
 */
@Composable
internal fun PhotoPeopleRow(
    people: List<PhotoPerson>,
    face: @Composable (PhotoPerson) -> Unit,
    onOpen: (PhotoPerson.Named) -> Unit,
    onAddName: (PhotoPerson.Unnamed) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).testTag("atlas-details-people")) {
        Text("People", style = MaterialTheme.typography.labelMedium, color = Color(0xFFADBAC7))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            people.forEachIndexed { index, person ->
                val click = when (person) {
                    is PhotoPerson.Named -> { -> onOpen(person) }
                    is PhotoPerson.Unnamed -> { -> onAddName(person) }
                }
                Column(
                    Modifier.width(64.dp).clickable(onClick = click)
                        .testTag("atlas-details-person-${index + 1}"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF2A303A))) {
                        face(person)
                    }
                    Spacer(Modifier.height(6.dp))
                    when (person) {
                        is PhotoPerson.Named -> Text(person.name, fontSize = 12.sp,
                            color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        is PhotoPerson.Unnamed -> Text("+", fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("atlas-details-person-add"))
                    }
                }
            }
        }
    }
}

/** Samsung's "Add name" dialog: one field, Cancel and Done. */
@Composable
internal fun PhotoPersonNameDialog(onDismiss: () -> Unit, onDone: suspend (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = photoPersonNameOrNull(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("atlas-details-name-dialog"),
        title = { Text("Add name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 80) { text = it; failed = false } },
                singleLine = true, placeholder = { Text("Name") },
                isError = failed,
                // The first save can read the whole People state (seconds on a
                // large library); a store refusal (renamed elsewhere, access
                // changed) says so instead of silently re-enabling Done.
                supportingText = when {
                    saving -> { { LinearProgressIndicator(Modifier.fillMaxWidth()
                        .testTag("atlas-details-name-saving")) } }
                    failed -> { { Text("Couldn't save this name") } }
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().testTag("atlas-details-name-field")
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(
                enabled = name != null && !saving,
                modifier = Modifier.testTag("atlas-details-name-done"),
                onClick = {
                    val chosen = name ?: return@TextButton
                    saving = true
                    failed = false
                    scope.launch {
                        if (!onDone(chosen)) { saving = false; failed = true }
                    }
                }
            ) { Text("Done") }
        }
    )
}
