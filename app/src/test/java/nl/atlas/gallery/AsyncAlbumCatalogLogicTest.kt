package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Exact, fictional MediaStore metadata; no photos or People records. */
class AsyncAlbumCatalogLogicTest {
    private fun photo(index: Int, album: String) = MediaRecord(
        index.toLong(), "content://atlas.synthetic/$index",
        "fake-$index.jpg", album, 1024L, index.toLong(), false
    )

    @Test fun stableSortedExactAlbumNamesAt150kAndCancellationCheckpoints() {
        val records = (0 until 150_000).map { index ->
            photo(index, when (index % 5) {
                0 -> "DCIM/Black and White/"
                1 -> "DCIM/Black and White-ish/"
                2 -> "Pictures/Personal/"
                3 -> "DCIM/Family/"
                else -> ""
            })
        }
        var checkpoints = 0
        val paths = albumPaths(records) { checkpoints++ }
        val prior = records.map { it.relativePath }.distinct().sorted()
        assertEquals(prior, paths)
        assertTrue(checkpoints >= 293)
        assertEquals(5, paths.size)
        assertTrue(paths.contains(""))
        assertTrue(paths.contains("DCIM/Black and White-ish/"))
        val snapshot = AlbumCatalogSnapshot(records, paths)
        assertSame(paths, currentAlbumCatalogOrNull(snapshot, records))
        assertNull(currentAlbumCatalogOrNull(null, records))

        // Metadata-equivalent refreshes are still different MediaStore
        // generations. Never display stale folder chips in the meantime.
        val generation2 = records.toList()
        assertFalse(records === generation2)
        assertEquals(records, generation2)
        assertNull(currentAlbumCatalogOrNull(snapshot, generation2))
        val second = albumPaths(generation2)
        assertEquals(paths, second)
        assertSame(second, currentAlbumCatalogOrNull(
            AlbumCatalogSnapshot(generation2, second), generation2))
    }

    @Test fun rapidlySupersededLargeAlbumScanCancelsBeforePublishing() {
        val records = (0 until 150_000).map { index ->
            photo(index, "DCIM/Synthetic-${index % 999}/")
        }
        var checkpoints = 0
        try {
            albumPaths(records) {
                if (++checkpoints >= 3) throw IllegalStateException("Obsolete scan")
            }
            fail("Cancellation checkpoint failed")
        } catch (ex: IllegalStateException) {
            assertEquals("Obsolete scan", ex.message)
        }
        assertEquals(3, checkpoints)
        assertEquals(emptyList<String>(), albumPaths(emptyList()))
        assertEquals(listOf("DCIM/One/"), albumPaths(listOf(photo(1, "DCIM/One/"))))
    }

    @Test fun exactAlbumFilterAt150kPreservesGlobalOrderingAndSourceGeneration() {
        val items = (0 until 150_000).map { index ->
            photo(index, if (index % 3 == 0) "DCIM/Archive/"
                else if (index % 3 == 1) "DCIM/Other/" else "DCIM/Archive/sub/")
        }
        val sorted = sortMedia(items, SortOrder.NEWEST, 0L)
        var checkpoints = 0
        val selected = selectAlbum(sorted, "DCIM/Archive/") { checkpoints++ }
        assertEquals(sorted.filter { it.relativePath == "DCIM/Archive/" }, selected)
        assertEquals(50_000, selected.size)
        assertTrue(checkpoints >= 293)
        assertSame(sorted, selectAlbum(sorted, null))
        val snap = AlbumSelectionSnapshot(sorted, "DCIM/Archive/", selected)
        assertSame(selected, currentAlbumSelectionOrNull(snap, sorted, "DCIM/Archive/"))
        assertNull(currentAlbumSelectionOrNull(snap, sorted, "DCIM/Archive/sub/"))
        val replacedInventory = sorted.toList()
        assertFalse(replacedInventory === sorted)
        assertNull(currentAlbumSelectionOrNull(snap, replacedInventory, "DCIM/Archive/"))
        var canceled = 0
        try {
            selectAlbum(sorted, "DCIM/Archive/") {
                if (++canceled == 2) throw IllegalStateException("Canceled old folder")
            }
            fail("Superseded album filter did not stop")
        } catch (error: IllegalStateException) {
            assertEquals("Canceled old folder", error.message)
        }
        assertEquals(2, canceled)
    }

    @Test fun duplicateAndCaseSensitivePathsPreserveLegacySemantics() {
        val records = listOf(
            photo(1, "DCIM/A/"),
            photo(2, "DCIM/a/"),
            photo(3, "DCIM/A/"),
            photo(4, "DCIM/A/child/"),
            photo(5, "DCIM/A")
        )
        assertEquals(records.map { it.relativePath }.distinct().sorted(),
            albumPaths(records))
        // These are locations, not fuzzy album/person classifications.
        assertEquals(listOf(records[0], records[2]),
            selectAlbum(records, "DCIM/A/"))
    }
}
