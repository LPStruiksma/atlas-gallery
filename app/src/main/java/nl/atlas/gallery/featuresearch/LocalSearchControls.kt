package nl.atlas.gallery.featuresearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.PersonEntry
import nl.atlas.gallery.CatalogSourceKey

/**
 * No search engine inside Compose. Lane I drives an off-main-thread,
 * permission-generation-bound searchWithFacets call and uses results to
 * render the existing GalleryGrid. Never recompute 150k rows on a keystroke.
 */
@Composable
fun LocalSearchControls(
    filters: LocalSearchFacets,
    people: List<PersonEntry>,
    history: EphemeralSearchHistory,
    onlyTheseFacesAvailable: Boolean,
    onFiltersChange: (LocalSearchFacets) -> Unit,
    onSubmittedQuery: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    /** Prepared on a worker: reviewed People + accessible album summaries only. */
    suggestionSources: List<SearchSuggestionSource> = emptyList(),
    /** Optional exact-volume album navigation, supplied only by Lane I. */
    onOpenAlbumSuggestion: ((String, String?) -> Unit)? = null,
    /** Required to display formerly entered queries; null means none are trusted. */
    currentPermissionEpoch: String? = null,
    /** Testable presentation clock; only affects offered calendar choices. */
    dateNowMillis: Long = System.currentTimeMillis(),
    /** VS-F2b (#468): opens search by face (choose a reference photo); null hides it. */
    onSearchByFace: (() -> Unit)? = null,
    /** VS-S2b (#468): opens Find similar (choose a reference photo); null hides it. */
    onFindSimilar: (() -> Unit)? = null,
    /** #554: native People with a name, for name suggestions and person chips. */
    namedPeople: List<SearchPerson> = emptyList(),
    /** #554: the person's round face, filling the given circle. */
    personAvatar: @Composable (SearchPerson) -> Unit = {
        Icon(Icons.Filled.Person, null, Modifier.fillMaxSize().padding(6.dp))
    },
    /**
     * #644: the current query has results. The All · Photos · Videos filter
     * shows only then (or while a type is picked, so it can be undone).
     */
    hasResults: Boolean = false,
    /** #644: Search has no title bar, so its one ⋮ also opens Library status. */
    onOpenLibraryStatus: (() -> Unit)? = null
) {
    // This private, transient picker input must not survive either a photo
    // regrant OR a reviewed People-catalog replacement. The identity wrapper
    // makes the key O(1), never List.equals over a large publication.
    var personQuery by remember(currentPermissionEpoch, CatalogSourceKey(people)) {
        mutableStateOf("")
    }
    val currentRecents = history.forGrant(currentPermissionEpoch)
    var extraFilters by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().testTag("atlas-local-search"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Samsung Search: one rounded field with ⋮ beside it; × resets.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filters.text,
                onValueChange = {
                    onFiltersChange(filters.copy(text = boundSearchQueryInput(it)))
                },
                placeholder = { Text("Search") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                // Reset must also be available after clearing all advanced facets:
                // the query itself can still be active when the filters close.
                trailingIcon = if (filters != LocalSearchFacets()) ({
                    IconButton(onClick = { onFiltersChange(LocalSearchFacets()) },
                        modifier = Modifier.testTag("atlas-search-reset-all")) {
                        Icon(Icons.Filled.Close, contentDescription = "Reset search and filters")
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // "Susanne 2019" + Search: the full name becomes her chip.
                    val exact = exactPersonInQuery(
                        namedPeople.filter { it.key !in filters.nativePersonKeys }, filters.text)
                    val next = if (exact != null) filters.copy(text = exact.remainder,
                        nativePersonKeys = filters.nativePersonKeys + exact.person.key) else filters
                    if (exact != null) onFiltersChange(next)
                    onSubmittedQuery(personSearchLabel(namedPeople, next))
                }),
                modifier = Modifier.weight(1f).testTag("atlas-search-field")
            )
            Box {
                IconButton(onClick = { overflowOpen = true },
                    modifier = Modifier.testTag("atlas-search-overflow")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More search options")
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(text = { Text("Filters") },
                        onClick = { overflowOpen = false; extraFilters = !extraFilters },
                        modifier = Modifier.testTag("atlas-search-more-filters"))
                    if (onOpenLibraryStatus != null) DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { overflowOpen = false; onOpenLibraryStatus() },
                        modifier = Modifier.testTag("atlas-library-status"))
                }
            }
        }
        // #554: picked people sit as removable chips right under the field.
        val pickedPeople = remember(namedPeople, filters.nativePersonKeys) {
            filters.nativePersonKeys.mapNotNull { key -> namedPeople.firstOrNull { it.key == key } }
        }
        if (pickedPeople.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pickedPeople.size) { index ->
                    val person = pickedPeople[index]
                    InputChip(
                        selected = true,
                        onClick = { onFiltersChange(filters.copy(
                            nativePersonKeys = filters.nativePersonKeys - person.key)) },
                        label = { Text(person.name, maxLines = 1) },
                        avatar = {
                            Box(Modifier.size(InputChipDefaults.AvatarSize).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center) { personAvatar(person) }
                        },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, "Remove ${person.name}",
                                Modifier.size(InputChipDefaults.IconSize))
                        },
                        modifier = Modifier.testTag("atlas-search-person-chip")
                    )
                }
            }
        }
        // #554: name suggestions, face circle plus name, above everything else.
        val personMatches = remember(namedPeople, filters.nativePersonKeys, filters.text) {
            matchPeopleByName(namedPeople.filter { it.key !in filters.nativePersonKeys },
                filters.text, limit = 5)
        }
        if (personMatches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().testTag("atlas-search-person-suggestions")) {
                personMatches.forEach { match ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                val next = filters.copy(text = match.remainder,
                                    nativePersonKeys = filters.nativePersonKeys + match.person.key)
                                onFiltersChange(next)
                                onSubmittedQuery(personSearchLabel(namedPeople, next))
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("atlas-search-person-suggestion"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center) { personAvatar(match.person) }
                        Text(match.person.name, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (filters.text.isBlank() && pickedPeople.isEmpty() && currentRecents.terms.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Recent searches", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onClearHistory) { Text("Clear") }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Query text is potentially a private Person name. Never put it in
                // a LazySaveableStateHolder key or Android saved-state Bundle.
                // These recents are stateless chips, so position keys suffice.
                items(currentRecents.terms.take(10)) { term ->
                    AssistChip(onClick = {
                        // A recent "Susanne 2019" comes back as her chip plus "2019".
                        val exact = exactPersonInQuery(namedPeople, term)
                        onFiltersChange(if (exact != null) filters.copy(text = exact.remainder,
                            nativePersonKeys = setOf(exact.person.key))
                            else filters.copy(text = term, nativePersonKeys = emptySet()))
                        onSubmittedQuery(term)
                    }, label = { Text(term, maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-recent"))
                }
            }
        }
        if (filters.text.length > MAX_SEARCH_QUERY_CHARS ||
            filters.text.trim().split(Regex("\\s+"))
                .count { it.isNotEmpty() } > MAX_SEARCH_QUERY_TERMS
        ) {
            Text(
                "Search is limited to $MAX_SEARCH_QUERY_CHARS characters and " +
                    "$MAX_SEARCH_QUERY_TERMS words. Shorten the query to see matches.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp)
                    .testTag("atlas-search-query-limit")
            )
        }
        val suggestions = remember(CatalogSourceKey(suggestionSources), filters.text) {
            planLocalSearchSuggestions(suggestionSources, filters.text)
        }
        // #644: at rest, Search by face and Find similar are Search suggestions
        // (Samsung's place for them), not a row of extra chrome above results.
        val atRest = filters == LocalSearchFacets() && personMatches.isEmpty()
        val showFace = atRest && onSearchByFace != null
        val showSimilar = atRest && onFindSimilar != null
        if (suggestions.isNotEmpty() || showFace || showSimilar) {
            Text("Suggestions",
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Stateless chips intentionally use Compose's default numeric
                // position keys. A custom SearchSuggestionSource data-class key
                // is not Bundle-saveable and can crash lazy item state restore;
                // it also risks writing private album paths into saved keys.
                if (showFace) item {
                    AssistChip(
                        onClick = { onSearchByFace?.invoke() },
                        label = { Text("Search by face") },
                        leadingIcon = { Icon(Icons.Filled.Face, null) },
                        modifier = Modifier.testTag("atlas-search-by-face"))
                }
                if (showSimilar) item {
                    AssistChip(
                        onClick = { onFindSimilar?.invoke() },
                        label = { Text("Find similar") },
                        leadingIcon = { Icon(Icons.Filled.ImageSearch, null) },
                        modifier = Modifier.testTag("atlas-search-find-similar"))
                }
                items(suggestions) { suggestion ->
                    val source = suggestion.source
                    AssistChip(
                        onClick = {
                            if (source.kind == SearchSuggestionKind.CONFIRMED_PERSON) {
                                onFiltersChange(filters.copy(
                                    confirmedPersonIds = filters.confirmedPersonIds +
                                        requireNotNull(source.personId),
                                    onlyTheseIndexedFaces = false))
                            } else {
                                val path = requireNotNull(source.albumRelativePath)
                                val volume = source.albumVolume
                                if (onOpenAlbumSuggestion != null) {
                                    onOpenAlbumSuggestion.invoke(path, volume)
                                } else if (volume != null) {
                                    onFiltersChange(filters.copy(albumLocations =
                                        filters.albumLocations + SearchAlbumLocation(volume, path)))
                                } else {
                                    // Explicitly all provider volumes, never a claimed
                                    // uniquely identified album from one particular mount.
                                    onFiltersChange(filters.copy(albumPaths = filters.albumPaths + path))
                                }
                            }
                        },
                        label = { Text(source.title, maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-suggestion")
                    )
                }
            }
        }
        if (hasResults || filters.kind != SearchMediaKind.ALL) LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SearchMediaKind.entries) { kind ->
                FilterChip(
                    selected = filters.kind == kind,
                    onClick = { onFiltersChange(filters.copy(kind = kind)) },
                    label = { Text(when (kind) {
                        SearchMediaKind.ALL -> "All"
                        SearchMediaKind.PHOTOS -> "Photos"
                        SearchMediaKind.VIDEOS -> "Videos"
                    }) },
                    modifier = Modifier.testTag("atlas-search-kind-${kind.name}")
                )
            }
        }
        if (extraFilters || filters.hasActiveFilterFacets() || filters.dateWindow != null || filters.confirmedPersonIds.isNotEmpty()) {
        val dateOptions = localSearchDateOptions(dateNowMillis)
        if (dateOptions.size > 1) {
            Text(
                "Media date · unknown dates are excluded when filtered",
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(dateOptions) { option ->
                    FilterChip(
                        selected = filters.dateWindow == option.window,
                        onClick = {
                            onFiltersChange(filters.copy(dateWindow = option.window))
                        },
                        label = { Text(option.label, maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-date-${option.choice.name}")
                    )
                }
            }
        }
        if (filters.dateWindow != null) {
            // The saved interval remains absolute across a New Year, even if
            // none of today's year chips match it. Never render an apparently
            // unfiltered state without offering a way to clear that interval.
            if (dateOptions.none { it.window == filters.dateWindow }) {
                Text(
                    "An earlier date selection is still active.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp)
                        .testTag("atlas-search-date-historical")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onFiltersChange(filters.clearDateFilter()) },
                    modifier = Modifier.testTag("atlas-search-date-clear")
                ) { Text("Clear date") }
            }
        }
        if (filters.hasActiveFilterFacets()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onFiltersChange(filters.clearFiltersKeepingQuery()) },
                    modifier = Modifier.testTag("atlas-search-clear-filters")
                ) { Text("Clear filters · keep search words") }
            }
        }
        if (filters.albumPaths.isNotEmpty() || filters.albumLocations.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Selected albums · any", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    onFiltersChange(filters.copy(albumPaths = emptySet(),
                        albumLocations = emptySet()))
                }) { Text("Clear albums") }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters.albumLocations.sortedWith(
                    compareBy<SearchAlbumLocation> { it.volumeName }
                        .thenBy { it.relativePath }
                )) { location ->
                    FilterChip(
                        selected = true,
                        onClick = { onFiltersChange(filters.copy(
                            albumLocations = filters.albumLocations - location)) },
                        label = { Text(
                            "${location.relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { "Root" }} · ${location.volumeName}",
                            maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-album-facet")
                    )
                }
                items(filters.albumPaths.sorted()) { path ->
                    FilterChip(
                        selected = true,
                        onClick = {
                            onFiltersChange(filters.copy(albumPaths = filters.albumPaths - path))
                        },
                        label = { Text(
                            "${path.trimEnd('/').substringAfterLast('/').ifEmpty { "Root" }} · all volumes",
                            maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-album-facet")
                    )
                }
            }
        }
        if (people.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("People together · all selected", style = MaterialTheme.typography.labelMedium)
                if (filters.confirmedPersonIds.isNotEmpty()) {
                    TextButton(onClick = {
                        onFiltersChange(filters.copy(confirmedPersonIds = emptySet(),
                            onlyTheseIndexedFaces = false))
                    }) { Text("Clear people") }
                }
            }
            OutlinedTextField(
                value = personQuery,
                onValueChange = { personQuery = boundSearchQueryInput(it) },
                label = { Text("Find a person to combine") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("atlas-search-people-picker")
            )
            // Use the SAME accent/term admission as Search. A 161-char paste
            // leaves the overflow sentinel; no truncated restrictive suffix
            // can broaden the visible picker. All selected IDs remain visible.
            if (admittedSearchQueryWordsOrNull(personQuery) == null) {
                Text(
                    "Use searchable name text (up to 160 characters and 12 words).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 18.dp)
                        .testTag("atlas-people-picker-limit")
                )
            }
            val visiblePeople = remember(
                CatalogSourceKey(people), filters.confirmedPersonIds, personQuery
            ) {
                selectReviewedPeopleForPicker(
                    people, filters.confirmedPersonIds, personQuery
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visiblePeople, key = { it.id }) { person ->
                    val selected = person.id in filters.confirmedPersonIds
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val selectedIds = if (selected)
                                filters.confirmedPersonIds - person.id
                            else filters.confirmedPersonIds + person.id
                            onFiltersChange(filters.copy(confirmedPersonIds = selectedIds,
                                onlyTheseIndexedFaces = false))
                        },
                        label = { Text(person.name, maxLines = 1) },
                        modifier = Modifier.testTag("atlas-search-person-facet-${person.id}")
                    )
                }
            }
        }
        if (filters.confirmedPersonIds.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FilterChip(
                    selected = filters.onlyTheseIndexedFaces && onlyTheseFacesAvailable,
                    enabled = onlyTheseFacesAvailable,
                    onClick = { onFiltersChange(filters.copy(
                        onlyTheseIndexedFaces = !filters.onlyTheseIndexedFaces)) },
                    label = { Text("Only these indexed faces") },
                    modifier = Modifier.testTag("atlas-search-indexed-faces")
                )
                Text(
                    if (onlyTheseFacesAvailable)
                        "Uses verified V4 detected-face totals, not proof that all faces in a photo were found."
                    else "Requires a verified, matching V4 face-count sidecar; unavailable without one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (filters.onlyTheseIndexedFaces && !onlyTheseFacesAvailable) {
                    Text(
                        "This filter is no longer verified for your current photo access or media revisions. Results remain restricted until you remove it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("atlas-search-stale-face-filter")
                    )
                    TextButton(
                        onClick = {
                            onFiltersChange(filters.copy(onlyTheseIndexedFaces = false))
                        }
                    ) { Text("Remove unavailable filter") }
                }
            }
        }
        }
    }
}

/**
 * #554: what a person search reads like as a recent search, e.g.
 * "Susanne 2019": picked names in pick order of [people], then the words.
 */
internal fun personSearchLabel(people: List<SearchPerson>, filters: LocalSearchFacets): String =
    (people.filter { it.key in filters.nativePersonKeys }.map { it.name } +
        listOf(filters.text.trim())).filter { it.isNotEmpty() }.joinToString(" ")
