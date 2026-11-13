package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The tested production composables, not mock UIs:
 * reviewed Search -> Person album -> exact B&W folder -> GalleryViewer
 * -> viewer Back -> album Back -> same Search results.
 * Synthetic photo URIs live ONLY inside disposable emulator app cache.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedPeopleJourneyTest {
    @get:Rule val rule = createComposeRule()

    private fun fixture(id: Long, folder: String): MediaRecord {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "atlas-journey-$id.png")
        if (!file.exists()) {
            val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(if (id % 2 == 0L) Color.CYAN else Color.MAGENTA)
                FileOutputStream(file).use { stream ->
                    check(bmp.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            } finally {
                bmp.recycle()
            }
        }
        // URI points at a synthetic app-cache file. Folder and native V4
        // memberships are also fictional and only used for pure path joins.
        return MediaRecord(
            id = id,
            uri = Uri.fromFile(file).toString(),
            name = "synthetic-$id.png",
            relativePath = folder,
            sizeBytes = file.length(),
            takenAtMillis = id,
            isVideo = false
        )
    }

    @Test fun searchReviewedPersonThenBwThenViewerThenBackRetainsRoute() {
        val blackWhite = fixture(701, "DCIM/Black and White/")
        val colored = fixture(702, "DCIM/Other photos/")
        val another = fixture(703, "DCIM/Black and White/")
        val archive = listOf(blackWhite, colored, another)
        val people = listOf(
            PersonEntry(41, "Alex Example", setOf(another.absolutePath)),
            PersonEntry(77, "Alex Example", setOf(
                blackWhite.absolutePath, colored.absolutePath
            ))
        )
        rule.setContent {
            var selectedPerson by remember { mutableStateOf<Long?>(null) }
            var bwOnly by remember { mutableStateOf(false) }
            var viewerUri by remember { mutableStateOf<String?>(null) }
            var viewerItems by remember { mutableStateOf<List<MediaRecord>?>(null) }
            val personGridState = rememberLazyGridState()
            MaterialTheme {
                if (viewerUri != null && viewerItems != null) {
                    GalleryViewer(
                        items = requireNotNull(viewerItems),
                        startUri = requireNotNull(viewerUri)
                    ) {
                        viewerUri = null
                        viewerItems = null
                    }
                } else if (selectedPerson != null) {
                    val identity = people.single { it.id == selectedPerson }
                    val visible = selectPersonPhotos(archive, identity.imagePaths, bwOnly)
                    PeopleAlbumContent(
                        visible = visible,
                        gridState = personGridState,
                        busy = false,
                        blackAndWhiteFolderOnly = bwOnly,
                        onBack = { selectedPerson = null },
                        onToggleBlackAndWhite = { bwOnly = !bwOnly },
                        onOpen = { photo ->
                            viewerItems = visible
                            viewerUri = photo.uri
                        }
                    )
                } else {
                    PeopleSearchShortcuts(people, "alex") {
                        selectedPerson = it
                        bwOnly = false
                    }
                }
            }
        }

        // Names may collide, but the selected identity must be the exact ID.
        rule.onNodeWithTag("atlas-search-person-77").performClick()
        rule.onNodeWithTag("atlas-person-album-count")
            .assertTextEquals("2 linked photos and videos")
        rule.onNodeWithTag("atlas-grid-media-701-photo").assertExists()
        rule.onNodeWithTag("atlas-grid-media-702-photo").assertExists()
        rule.onNodeWithTag("atlas-grid-media-703-photo").assertDoesNotExist()

        // B&W is an *exact folder* filter, never an inferred AI property.
        rule.onNodeWithTag("atlas-person-album-bw").performClick()
        rule.onNodeWithTag("atlas-person-album-count")
            .assertTextEquals("1 linked B&W-folder photos")
        rule.onNodeWithTag("atlas-grid-media-701-photo").performClick()

        // Same full-screen viewer and filmstrip used elsewhere in ATLAS.
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 1")
        rule.onNodeWithTag("atlas-filmstrip").assertExists()
        rule.onNodeWithTag("atlas-back").performClick()

        // Back restores the previously active B&W filter, then Search.
        rule.onNodeWithTag("atlas-person-album-count")
            .assertTextEquals("1 linked B&W-folder photos")
        rule.onNodeWithTag("atlas-person-album-back").performClick()
        rule.onNodeWithTag("atlas-search-person-77").assertExists()
        rule.onNodeWithTag("atlas-search-person-41").assertExists()
    }
}
