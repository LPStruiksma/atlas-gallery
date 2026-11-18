package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-12: synthetic-only, exact-order proof for narrowing reviewed paths. */
class ReviewedPersonIntersectionPerfTest {
    private fun media(i: Int): MediaRecord =
        MediaRecord(i.toLong(), "content://media/external/images/media/$i",
            "synthetic_$i.jpg", "Pictures/Fictional/", 100, 42, false)

    @Test fun disjointPeopleDoNotLeakMetadataAndRetainOrder() {
        val rows = List(4096) { i -> media(i) }
        val p1 = PersonEntry(1, "Fictional Ada", rows.take(1800).map { it.absolutePath }.toSet())
        val p2 = PersonEntry(2, "Fictional Bea", rows.drop(1500).take(2000).map { it.absolutePath }.toSet())
        val filters = LocalSearchFacets(confirmedPersonIds = setOf(1, 2))
        val found = searchWithFacets(rows, listOf(p1, p2), filters,
            mediaVolumeOf = { it.volumeName })
        assertEquals(rows.subList(1500, 1800), found)
        assertEquals(emptyList<MediaRecord>(), searchWithFacets(rows, listOf(p1,
            PersonEntry(3, "Fictional Cy", setOf("/storage/emulated/0/DCIM/nope.jpg"))),
            filters.copy(confirmedPersonIds = setOf(1, 3)), mediaVolumeOf = { it.volumeName }))
        // The lexical path still cannot infer a Person identity from a video.
        assertTrue(searchWithFacets(rows.take(2).map { it.copy(isVideo = true) },
            listOf(p1), LocalSearchFacets(text = "ada"),
            mediaVolumeOf = { it.volumeName }).isEmpty())
    }

    @Test fun synthetic250kReviewedFacetTimingAndExactOrder() {
        val rows = List(250_000) { i -> media(i) }
        val first = rows.asSequence().filterIndexed { i, _ -> i % 3 == 0 }
            .map { it.absolutePath }.toSet()
        val second = rows.asSequence().filterIndexed { i, _ -> i % 5 == 0 }
            .map { it.absolutePath }.toSet()
        val people = listOf(PersonEntry(1, "Fictional Ada", first),
            PersonEntry(2, "Fictional Bea", second))
        val filters = LocalSearchFacets(confirmedPersonIds = setOf(1, 2))
        val expected = rows.filterIndexed { i, _ -> i % 15 == 0 }
        val start = System.nanoTime()
        val found = searchWithFacets(rows, people, filters,
            mediaVolumeOf = { it.volumeName })
        val ms = (System.nanoTime() - start) / 1e6
        assertEquals(expected, found)
        println("PERF-12 reviewed facet n=250000 ms=%.2f matches=%d"
            .format(ms, found.size))
    }
}
