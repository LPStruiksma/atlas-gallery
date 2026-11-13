package nl.atlas.gallery.featuredesign

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurealbums.AlbumBucketKey
import nl.atlas.gallery.featurealbums.albumPinRank
import nl.atlas.gallery.featurealbums.customAlbumOrder
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumRoute
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featurealbums.naturalAlbumCompare

/** No real MediaStore links, private filenames, person identities or network images. */
private val syntheticAlbums = listOf(
    AlbumSummary("Fiction/Camera/", "Camera", 128, 124, 4, null, false, 300L),
    AlbumSummary("Fiction/Black and White/", "Black and White", 62, 62, 0, null, false, 100L),
    AlbumSummary("Fiction/Weekend 2/", "Weekend 2", 16, 15, 1, null, false, 200L),
    AlbumSummary("Fiction/Weekend 10/", "Weekend 10", 9, 9, 0, null, false, 400L)
)

/**
 * Opt-in UI demonstration with fictional album counts and neutral empty cover
 * placeholders. It proves navigation/sort callbacks are connected, NOT that
 * thumbnails, People inference, Android performance or live data were tested.
 */
@Composable
fun AtlasDesignSyntheticShowcase(dark: Boolean = true) {
    var destination by remember { mutableStateOf(AtlasDestination.ALBUMS) }
    var sort by remember { mutableStateOf(AlbumSort.RECENT) }
    var albumRoute by remember { mutableStateOf<AlbumRoute?>(null) }
    var mediaFilter by remember { mutableStateOf(AlbumMediaFilter.ALL) }
    var customOrder by remember { mutableStateOf(syntheticAlbums.map { AlbumBucketKey(it.volumeName, it.relativePath) }) }
    var showStatus by remember { mutableStateOf(false) }

    val ordered = remember(sort, mediaFilter, customOrder) {
        val filtered = syntheticAlbums.mapNotNull { album ->
            when (mediaFilter) {
                AlbumMediaFilter.ALL -> album
                AlbumMediaFilter.PHOTOS ->
                    album.takeIf { it.photoCount > 0 }?.copy(
                        mediaCount = album.photoCount, videoCount = 0)
                AlbumMediaFilter.VIDEOS ->
                    album.takeIf { it.videoCount > 0 }?.copy(
                        mediaCount = album.videoCount, photoCount = 0, coverIsVideo = true)
            }
        }
        when (sort) {
            AlbumSort.RECENT -> filtered.sortedWith(compareBy<AlbumSummary> { albumPinRank(it.title) }.thenByDescending { it.latestAtMillis })
            AlbumSort.NAME -> filtered.sortedWith(compareBy<AlbumSummary> { albumPinRank(it.title) }.thenComparator { a, b -> naturalAlbumCompare(a.title, b.title) })
            AlbumSort.COUNT -> filtered.sortedWith(compareBy<AlbumSummary> { albumPinRank(it.title) }.thenByDescending { it.mediaCount })
            AlbumSort.CUSTOM -> filtered.sortedWith(compareBy<AlbumSummary> { albumPinRank(it.title) }.thenBy { album ->
                customOrder.indexOf(AlbumBucketKey(album.volumeName, album.relativePath)).let { if (it < 0) Int.MAX_VALUE else it }
            })
        }
    }

    AtlasGalleryTheme(dark = dark) {
        Scaffold(
            bottomBar = {
                AtlasGalleryNavigation(selected = destination, onSelect = {
                    destination = it
                    albumRoute = null
                    showStatus = false
                })
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    showStatus -> {
                        TextButton(onClick = { showStatus = false }) { Text("Back to gallery") }
                        AtlasEmptyState(
                            title = "Library activity",
                            explanation = "In the app, refresh and permission status live here. " +
                                "This showcase uses only fictional albums."
                        )
                    }
                    albumRoute != null -> {
                        TextButton(onClick = { albumRoute = null }) { Text("Back to albums") }
                        AtlasEmptyState(
                            title = syntheticAlbums.firstOrNull {
                                it.relativePath == albumRoute?.relativePath &&
                                    it.volumeName == albumRoute?.volumeName
                            }?.title ?: "Album",
                            explanation = "Synthetic navigation destination. " +
                                "The integrator supplies the actual photo grid."
                        )
                    }
                    destination == AtlasDestination.ALBUMS -> AtlasAlbumsScreen(
                        albums = ordered,
                        sort = sort,
                        onSort = { sort = it },
                        onReorderAlbums = { reordered ->
                            customOrder = customAlbumOrder(reordered)
                            sort = AlbumSort.CUSTOM
                        },
                        onOpenAlbum = { albumRoute = it },
                        mediaFilter = mediaFilter,
                        onMediaFilterChange = { mediaFilter = it },
                        onOpenLibraryStatus = { showStatus = true },
                        coverIllustration = { AtlasSyntheticAlbumArtwork(it) }
                    )
                    destination == AtlasDestination.PHOTOS -> {
                        AtlasGalleryHeader(
                            "Photos", subtitle = "Your timeline", onOpenLibraryStatus = {
                                showStatus = true
                            }
                        )
                        AtlasEmptyState(
                            title = "Your moments, in one place",
                            explanation = "Synthetic placeholder. Your photos appear here " +
                                "after you grant local media access.",
                            icon = Icons.Filled.PhotoLibrary
                        )
                    }
                    destination == AtlasDestination.PEOPLE -> {
                        AtlasGalleryHeader("People", onOpenLibraryStatus = { showStatus = true })
                        AtlasEmptyState(
                            title = "People you have reviewed",
                            explanation = "Native face discovery is not yet available. " +
                                "Confirmed V4 People can be connected separately.",
                            icon = Icons.Filled.People
                        )
                    }
                    else -> {
                        AtlasGalleryHeader("Search", onOpenLibraryStatus = { showStatus = true })
                        AtlasEmptyState(
                            title = "Find a moment",
                            explanation = "This synthetic screen does not search. " +
                                "Real search uses locally available photos and confirmed labels.",
                            icon = Icons.Filled.Search
                        )
                    }
                }
            }
        }
    }
}
