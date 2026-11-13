package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class ThumbnailBenchmarkStatsTest {
    @Test fun firstSampleNeverContaminatesWarmPercentiles() {
        val samples = listOf(50_000L) + (1L..20L).map { it * 1_000L }
        val summary = summarizeThumbnailBenchmark(samples)
        assertEquals(50_000L, summary.firstNs)
        assertEquals(20, summary.warmCount)
        assertEquals(10_000L, summary.warmP50Ns)
        assertEquals(19_000L, summary.warmP95Ns)
    }

    @Test fun inputOrderAndLargeOutlierDoNotInventAverages() {
        val warm = (1L..19L).map { it * 100L } + 5_000_000L
        val stats = summarizeThumbnailBenchmark(listOf(41L) + warm.reversed())
        assertEquals(41L, stats.firstNs)
        assertEquals(1_000L, stats.warmP50Ns)
        assertEquals(1_900L, stats.warmP95Ns)
        assertEquals(5_000_000L, warm.maxOrNull())
    }

    @Test fun missingFailedOrTooFewSamplesMustNeverProduceMisleadingP95() {
        for (samples in listOf(
            emptyList(), listOf(1L), listOf(1L, 2L),
            listOf(1L, 0L, 2L), listOf(1L, -1L, 2L)
        )) {
            try {
                summarizeThumbnailBenchmark(samples)
                fail("Untrustworthy timing list was accepted")
            } catch (expected: IllegalArgumentException) {
                // A failed decoder is not a successful zero-duration sample.
            }
        }
    }

    @Test fun uniformSuccessfulWarmSeriesReportsSameP50AndP95() {
        val stats = summarizeThumbnailBenchmark(listOf(5L) + List(20) { 777L })
        assertEquals(777L, stats.warmP50Ns)
        assertEquals(777L, stats.warmP95Ns)
    }
}
