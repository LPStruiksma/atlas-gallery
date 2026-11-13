package nl.atlas.gallery.featurealbums

import org.junit.Assert.*
import org.junit.Test

class AlbumQuickFindTest {
    private fun album(name: String, path: String, label: String? = null) =
        AlbumSummary(path, name, 1, 1, 0, null, false, 123L,
            locationLabel = label)

    @Test fun accentAndCaseInsensitiveTokensAcrossVisibleCaptions() {
        val source = listOf(
            album("Café Été 10", "Fiction/Cafe 10/", "Summer Trips"),
            album("Camera", "DCIM/Camera/", "Device storage"),
            album("Cafe 2", "Fiction/Cafe 2/", "Winter")
        )
        assertEquals(listOf(source[0]),
            findAlbumsByVisibleText(source, "  ÉTÉ   summer ").matches)
        assertEquals(listOf(source[0], source[2]),
            findAlbumsByVisibleText(source, "cafe").matches)
        assertEquals(listOf(source[1]),
            findAlbumsByVisibleText(source, "device CAMERA").matches)
    }

    @Test fun invisibleParentPathNeverMatchesAndRoutesRemainUntouched() {
        val privateParent = album("Weekend", "Private/Archive/Weekend/")
        val twoSameNames = listOf(
            privateParent,
            album("Weekend", "Public/Weekend/", "Public")
        )
        assertTrue(findAlbumsByVisibleText(twoSameNames, "private").matches.isEmpty())
        val matched = findAlbumsByVisibleText(twoSameNames, "public").matches.single()
        assertSame(twoSameNames[1], matched)
        assertEquals("Public/Weekend/", matched.relativePath)
    }

    @Test fun emptyQueryPreservesExactInputIdentityAndStableOrder() {
        val source = listOf(album("Z", "Z/"), album("A", "A/"))
        for (query in listOf("", " ", "\t \n")) {
            val result = findAlbumsByVisibleText(source, query)
            assertSame(source, result.matches)
            assertTrue(result.isCurrent(source, query))
            assertFalse(result.isCurrent(source.toList().toMutableList(), query))
            assertFalse(result.isCurrent(source, query + "x"))
        }
    }

    @Test fun exactQueryAndReferenceGuardRejectsStaleAsyncResults() {
        val album = album("Camera", "DCIM/Camera/")
        val source = listOf(album)
        val result = findAlbumsByVisibleText(source, "cam")
        assertTrue(result.isCurrent(source, "cam"))
        assertFalse(result.isCurrent(source, "camera"))
        assertFalse(result.isCurrent(listOf(album), "cam"))
        assertSame(album, result.matches.single())
    }

    @Test fun cancellationDuringLargeSearchNeverPublishesPartialResult() {
        val source = (0 until 10_000).map { album("Fiction $it", "Fiction/$it/") }
        var checks = 0
        val cancelled = runCatching {
            findAlbumsByVisibleText(source, "fiction") {
                if (++checks == 3) throw IllegalStateException("cancelled")
            }
        }
        assertTrue(cancelled.isFailure)
        assertEquals("cancelled", cancelled.exceptionOrNull()?.message)
        assertEquals(3, checks)
    }

    @Test fun largeCataloguePreservesOrderAndOnlyVisibleMatches() {
        val source = (0 until 150_000).map {
            album(if (it % 25000 == 0) "Séries $it" else "Folder $it",
                "Fiction/$it/")
        }
        var checks = 0
        val result = findAlbumsByVisibleText(source, "series") { checks++ }
        assertEquals(listOf(0, 25000, 50000, 75000, 100000, 125000),
            result.matches.map { it.title.substringAfter(' ').toInt() })
        assertTrue(checks >= 500)
        assertTrue(result.isCurrent(source, "series"))
    }
}
