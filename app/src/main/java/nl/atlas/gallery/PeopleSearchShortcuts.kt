package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuresearch.SearchSectionHeader

/**
 * Search is a bridge into the *same* reviewed People albums, not a second
 * recognition engine. Duplicate names are separate identities; never merge
 * them or infer people by text similarity.
 */
internal fun findPeopleForSearch(
    people: List<PersonEntry>,
    query: String,
    limit: Int = 12
): List<PersonEntry> {
    if (limit <= 0) return emptyList()
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return people.asSequence()
        .filter { it.name.contains(term, ignoreCase = true) }
        .sortedWith(
            compareBy<PersonEntry> { !it.name.startsWith(term, ignoreCase = true) }
                .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.id }
        )
        .take(limit)
        .toList()
}

/**
 * Present reviewed People as visual destinations above matching photos.
 * Cover images are already-accessible MediaStore content URIs, not face
 * crops, raw V4 paths or predictions. A moved/inaccessible photo cannot
 * become a cover by matching its filename to another photo.
 */
@Composable
internal fun PeopleSearchShortcuts(
    people: List<PersonEntry>,
    query: String,
    media: List<MediaRecord> = emptyList(),
    /** #134: suggestion rows, not a scrolling strip: keep the list short. */
    limit: Int = 5,
    onSelect: (Long) -> Unit
) {
    val matches = findPeopleForSearch(people, query, limit)
    if (matches.isEmpty()) return
    // Once per MediaStore catalogue, not once per person or text keystroke.
    val byPath = remember(media) { media.associateBy { it.absolutePath } }
    Column(Modifier.fillMaxWidth().testTag("atlas-search-people")) {
        SearchSectionHeader("People · reviewed labels", Modifier.padding(top = 8.dp))
        matches.forEach { person ->
            // Identity remains the stable person ID. Only confirmed V4
            // photo paths that resolve to this catalogue are counted.
            val cover = person.imagePaths.firstNotNullOfOrNull { byPath[it] }
            val accessible = person.imagePaths.count { it in byPath }
            // #134: Samsung suggestion row: 56 dp, 40 dp round thumb, one line.
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(person.id) }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp)
                    .testTag("atlas-search-person-${person.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .testTag("atlas-search-person-cover-${person.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        MediaPreview(cover, Modifier.fillMaxSize(), ContentScale.Crop, 120)
                    } else {
                        Icon(Icons.Filled.Person, null, Modifier.size(24.dp))
                    }
                }
                Text(person.name, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(
                    "${accessible} accessible " + if (accessible == 1) "photo" else "photos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.testTag("atlas-search-person-count-${person.id}")
                )
            }
        }
    }
}
