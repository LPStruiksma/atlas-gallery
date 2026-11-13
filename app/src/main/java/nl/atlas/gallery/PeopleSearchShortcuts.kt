package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
    onSelect: (Long) -> Unit
) {
    val matches = findPeopleForSearch(people, query)
    if (matches.isEmpty()) return
    // Once per MediaStore catalogue, not once per person or text keystroke.
    val byPath = remember(media) { media.associateBy { it.absolutePath } }
    Column(Modifier.fillMaxWidth().testTag("atlas-search-people")) {
        Text(
            "People · reviewed labels",
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 7.dp),
            style = MaterialTheme.typography.labelMedium
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(matches, key = { it.id }) { person ->
                // Identity remains the stable person ID. Only confirmed V4
                // photo paths that resolve to this catalogue are counted.
                val cover = person.imagePaths.firstNotNullOfOrNull { byPath[it] }
                val accessible = person.imagePaths.count { it in byPath }
                SuggestionChip(
                    onClick = { onSelect(person.id) },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .testTag("atlas-search-person-cover-${person.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (cover != null) {
                                    MediaPreview(
                                        cover, Modifier.fillMaxSize(),
                                        ContentScale.Crop, 120
                                    )
                                } else {
                                    Icon(Icons.Filled.Person, null, Modifier.size(24.dp))
                                }
                            }
                            Column {
                                Text(person.name, maxLines = 1)
                                Text(
                                    "${accessible} accessible " +
                                        if (accessible == 1) "photo" else "photos",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("atlas-search-person-count-${person.id}")
                                )
                            }
                        }
                    },
                    modifier = Modifier.testTag("atlas-search-person-${person.id}")
                )
            }
        }
    }
}
