package nl.atlas.gallery.featurevisual.nls.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCandidateRankingTest {
    private fun vector(index: Int = 0): FloatArray =
        FloatArray(SceneCandidateRanking.DIMENSION) { if (it == index) 1f else 0f }

    private fun row(key: String, vector: FloatArray, frame: Long? = null) =
        SceneCandidateRanking.Candidate(key, "revision-1", frame, vector)

    private fun ranked(
        rows: Iterator<SceneCandidateRanking.Candidate>, limit: Int = 100,
        authorized: () -> Boolean = { true }, cancelled: () -> Boolean = { false }
    ) = SceneCandidateRanking.rank(vector(), rows, limit, authorized, cancelled)

    @Test fun deterministicBestFirstAndFramePreserved() {
        val rows = listOf(row("z", vector()), row("b", vector(), 900L),
            row("b", vector(), 100L), row("a", vector(1))).iterator()
        val result = ranked(rows, 3) as SceneCandidateRanking.Result.Ranked
        assertEquals(listOf("b", "b", "z"), result.hits.map { it.mediaKey })
        assertEquals(listOf(100L, 900L, null), result.hits.map { it.frameMs })
        assertEquals(listOf(1f, 1f, 1f), result.hits.map { it.cosine })
        assertEquals(4, result.scanned)
    }

    @Test fun sparseAndInvalidVectorsDoNotPoisonRanking() {
        val sparse = FloatArray(768).apply { this[0] = 2f }
        val bad = FloatArray(768).apply { this[0] = Float.NaN }
        val result = ranked(listOf(row("valid", sparse), row("zero", FloatArray(768)),
            row("nan", bad), row("short", FloatArray(2)), row("other", vector(3)))
            .iterator()) as SceneCandidateRanking.Result.Ranked
        assertEquals(listOf("valid", "other"), result.hits.map { it.mediaKey })
        assertEquals(3, result.skippedInvalid)
    }

    @Test fun revokeOrCancellationDiscardsWholePartialResult() {
        var checks = 0
        assertEquals(SceneCandidateRanking.Result.Aborted,
            ranked(generateSequence(0) { it + 1 }.map { row("$it", vector()) }.iterator(),
                authorized = { ++checks < 30 }))
        var cancelChecks = 0
        assertEquals(SceneCandidateRanking.Result.Aborted,
            ranked(generateSequence(0) { it + 1 }.map { row("$it", vector()) }.iterator(),
                cancelled = { ++cancelChecks > 20 }))
        assertEquals(SceneCandidateRanking.Result.Aborted,
            ranked(listOf(row("first", vector())).iterator(), authorized = { false }))
    }

    @Test fun inputValidationAndLimitBounds() {
        var failed = false
        try {
            SceneCandidateRanking.rank(FloatArray(768), emptyList<SceneCandidateRanking.Candidate>().iterator(),
                stillAuthorized = { true })
        } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        failed = false
        try { ranked(emptyList<SceneCandidateRanking.Candidate>().iterator(), limit = 101) }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun bounded250kSyntheticJVMWorkload() {
        // One scratch row per iteration; no 250k-item vector cache or library copy.
        val reusable = vector()
        var index = 0
        val input = object : Iterator<SceneCandidateRanking.Candidate> {
            override fun hasNext() = index < 250_000
            override fun next(): SceneCandidateRanking.Candidate {
                val n = index++
                return row("synthetic-%06d".format(n), reusable)
            }
        }
        val start = System.nanoTime()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val result = ranked(input) as SceneCandidateRanking.Result.Ranked
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val durationMs = (System.nanoTime() - start) / 1_000_000
        println("NLS-1D CI host synthetic 250000: elapsed_ms=$durationMs heap_delta_bytes=${after - before} (not phone performance)")
        assertEquals(250_000, result.scanned)
        assertEquals(100, result.hits.size)
        assertEquals("synthetic-000000", result.hits.first().mediaKey)
        assertEquals("synthetic-000099", result.hits.last().mediaKey)
        assertFalse(result.hits.any { it.frameMs != null })
    }
}
