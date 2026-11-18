package nl.atlas.gallery.featurevisual.nls.facets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneFacetGateTest {
    private data class Scene(val id: String, val frameMs: Long?, val score: Float) : SceneFacetGate.HasMediaKey {
        override val mediaKey: String get() = id
    }
    private fun scene(id: String, frameMs: Long? = null, score: Float = 0.9f) =
        Scene(id, frameMs, score)

    private val scope = SceneFacetGate.Scope("grant-1", "people-1")
    private val choices = SceneFacetGate.Selection(person = "p", date = "2026-11-01",
        album = "album", place = "place")
    private fun facts(id: String, persons: Set<String> = setOf("p"),
                      dates: Set<String> = setOf("2026-11-01"),
                      albums: Set<String> = setOf("album"),
                      places: Set<String> = setOf("place")) =
        SceneFacetGate.Membership(id, persons, dates, albums, places)

    @Test fun allFacetsIntersectWithoutReorderingOrChangingVideoFrames() {
        val video = scene("video", 1200L, 0.99f)
        val sameVideo = scene("video", 9500L, 0.81f)
        val still = scene("still", score = 0.68f)
        val missingPlace = scene("outside")
        val rows = listOf(video, missingPlace, sameVideo, still)
        val membership = mapOf("video" to facts("video"), "still" to facts("still"),
            "outside" to facts("outside", places = emptySet()))
        val gate = SceneFacetGate(scope)
        val result = gate.filter(rows.iterator(), choices, scope,
            { membership[it] }, { scope }) as SceneFacetGate.Result.Filtered
        assertEquals(listOf(video, sameVideo, still), result.hits)
        assertSame(video, result.hits[0])
        assertEquals(4, result.scanned)
    }

    @Test fun unknownMissingOrDeniedMembershipCannotPassSelectedFacet() {
        val rows = listOf(scene("yes"), scene("missing"), scene("denied"), scene("unknown"))
        val map = mapOf("yes" to facts("yes"),
            "denied" to facts("denied", persons = emptySet()),
            "unknown" to facts("unknown", dates = emptySet()))
        val result = SceneFacetGate(scope).filter(rows.iterator(), choices, scope,
            { map[it] }, { scope }) as SceneFacetGate.Result.Filtered
        assertEquals(listOf("yes"), result.hits.map { it.id })
        assertEquals(4, result.scanned)
    }

    @Test fun evenNoSelectedFacetsRequiresLiveAuthorizedScope() {
        val rows = listOf(scene("a"), scene("b"))
        val result = SceneFacetGate(scope).filter(rows.iterator(),
            SceneFacetGate.Selection(), scope, { null }, { scope })
        assertEquals(rows, (result as SceneFacetGate.Result.Filtered).hits)
        val blocked = SceneFacetGate(scope).filter(rows.iterator(),
            SceneFacetGate.Selection(), scope, { null }, { null })
        assertEquals(SceneFacetGate.Result.Aborted, blocked)
    }

    @Test fun staleEpochAndPeopleGenerationAbortWithoutPartialHits() {
        val rows = listOf(scene("a"), scene("b"))
        val gate = SceneFacetGate(scope)
        assertEquals(SceneFacetGate.Result.Aborted, gate.filter(rows.iterator(), choices,
            SceneFacetGate.Scope("old-grant", "people-1"), { facts(it) }, { scope }))
        var checks = 0
        assertEquals(SceneFacetGate.Result.Aborted, gate.filter(rows.iterator(), choices,
            scope, { facts(it) }, {
                if (++checks < 5) scope else SceneFacetGate.Scope("grant-1", "people-2")
            }))
        assertEquals(SceneFacetGate.Result.Aborted, gate.filter(rows.iterator(), choices,
            scope, { facts(it) }, { scope }, isCancelled = { true }))
    }

    @Test fun explicitlyInvalidatedGateNeverReleasesResults() {
        val gate = SceneFacetGate(scope)
        gate.invalidate()
        assertEquals(SceneFacetGate.Result.Aborted, gate.filter(
            listOf(scene("a")).iterator(), choices, scope, { facts(it) }, { scope }))
    }

    @Test fun unavailableFacetSourceAbortsRatherThanTreatingItAsEmptyMatches() {
        val gate = SceneFacetGate(scope)
        val rows = listOf(scene("first"), scene("second"))
        var calls = 0
        val result = gate.filter(rows.iterator(), choices, scope, {
            if (++calls == 2) throw SceneFacetGate.UnavailableFacetData()
            facts(it)
        }, { scope })
        assertEquals(SceneFacetGate.Result.Aborted, result)
    }

    @Test fun synthetic250kStreamingFilterKeepsOnlyMatches() {
        val gate = SceneFacetGate(scope)
        var index = 0
        val iterator = object : Iterator<Scene> {
            override fun hasNext() = index < 250_000
            override fun next() = scene("synthetic-${index++}")
        }
        val result = gate.filter(iterator, SceneFacetGate.Selection(person = "p"), scope,
            { key -> if (key.removePrefix("synthetic-").toInt() % 1000 == 0) facts(key) else facts(key, persons = emptySet()) },
            { scope }, maxResults = 250) as SceneFacetGate.Result.Filtered
        assertEquals(250_000, result.scanned)
        assertEquals(250, result.hits.size)
        assertEquals("synthetic-0", result.hits.first().id)
        assertEquals("synthetic-249000", result.hits.last().id)
        assertTrue(result.hits.all { it.id.removePrefix("synthetic-").toInt() % 1000 == 0 })
    }
}
