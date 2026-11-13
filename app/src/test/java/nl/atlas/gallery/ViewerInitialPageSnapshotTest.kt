package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class ViewerInitialPageSnapshotTest {
    private class CountedAlbum(
        override val size: Int, private val matchIndex: Int
    ) : AbstractList<MediaRecord>() {
        var reads: Int = 0
            private set

        override fun get(index: Int): MediaRecord {
            if (index !in 0 until size) throw IndexOutOfBoundsException()
            reads++
            return MediaRecord(
                index.toLong(), if (index == matchIndex) "content://atlas/target"
                    else "content://atlas/other/$index",
                "synthetic-$index.jpg", "Pictures/", 1L, 1L, false
            )
        }
    }

    @Test fun lastItemOf150kIsLocatedOnlyOnceForUnlimitedRecompositions() {
        val catalogue = CountedAlbum(150_000, 149_999)
        val entry = ViewerInitialPageSnapshot(catalogue, "content://atlas/target")
        assertEquals(149_999, entry.initialPage)
        assertEquals(150_000, catalogue.reads)
        repeat(10_000) { assertEquals(149_999, entry.initialPage) }
        assertEquals("No rescanning on repeated page access", 150_000, catalogue.reads)
    }

    @Test fun missingEntryFailsToZeroAndNeverRepeatsFullSearch() {
        val catalogue = CountedAlbum(150_000, -1)
        val entry = ViewerInitialPageSnapshot(catalogue, "content://atlas/missing")
        assertEquals(0, entry.initialPage)
        assertEquals(150_000, catalogue.reads)
        repeat(500) { assertEquals(0, entry.initialPage) }
        assertEquals(150_000, catalogue.reads)
    }

    @Test fun newSnapshotMustRecomputeForDifferentEntryOrCatalogue() {
        val old = CountedAlbum(20, 19)
        assertEquals(19, ViewerInitialPageSnapshot(old, "content://atlas/target").initialPage)
        assertEquals(0, ViewerInitialPageSnapshot(old, "content://atlas/other/0").initialPage)
        assertEquals(21, old.reads)

        val replaced = CountedAlbum(20, 1)
        assertEquals(1, ViewerInitialPageSnapshot(
            replaced, "content://atlas/target").initialPage)
        assertEquals(2, replaced.reads)
    }

    @Test fun emptyCatalogueFailsClosed() {
        val empty = CountedAlbum(0, -1)
        assertEquals(0, ViewerInitialPageSnapshot(
            empty, "content://atlas/target").initialPage)
        assertEquals(0, empty.reads)
    }
}
