package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceReportTest {
    @Test fun emptyAggregatesSayNoDataYetNeverZero() {
        val rows = PerformanceReport.overview(PerformanceAggregates())
        assertEquals(listOf("Cold start to cached photos", "Grid frame time",
            "Face scanning", "Visual indexing", "Java heap / maximum"), rows.map { it.label })
        rows.forEach {
            assertEquals(PerformanceReport.NO_DATA, it.value)
            assertFalse(it.hasData)
        }
    }

    @Test fun instrumentedActionsSayNoDataYetAndUnhookedSayNotMeasured() {
        val rows = PerformanceReport.actions(PerformanceAggregates())
        assertEquals(22, PerformanceReport.actionLabels.size)
        assertEquals(18, rows.size)
        assertEquals((1..22).filterNot { it in setOf(1, 2, 5, 8) }.map { "atlas-perf-action_a${it}_ms" }, rows.map { it.tag })
        rows.take(7).forEach { assertEquals(PerformanceReport.NO_DATA, it.value) }
        rows.drop(7).forEach { assertEquals(PerformanceReport.NOT_MEASURED, it.value) }
        assertEquals("atlas-perf-action_a3_ms", rows.first().tag)
        assertEquals("atlas-perf-action_a22_ms", rows.last().tag)
    }

    @Test fun labelsAreHumanWithoutInternalKeys() {
        val p = PerformanceAggregates()
        val labels = (PerformanceReport.overview(p) + PerformanceReport.actions(p)).map { it.label }
        labels.forEach {
            assertFalse("internal id in label: $it", Regex("""\bA\d{1,2}\b""").containsMatchIn(it))
            assertFalse("snake_case in label: $it", it.contains('_'))
        }
    }

    @Test fun populatedValuesShowUnitsPercentilesAndSampleCounts() {
        val p = PerformanceAggregates()
        listOf(412.0, 380.0, 690.0).forEach { p.record("cold_start_ms", it) }
        p.record("action_a3_ms", 8.25)
        listOf(9.0, 18.0, 30.0, 12.0).forEach { p.record("grid_frame_ms", it) }
        listOf(0.0, 1.0, 0.0, 0.0).forEach { p.record("grid_frame_over_budget", it) }
        p.record("face_photos_min", 45.4)
        listOf(0.2, 0.41, 0.33).forEach { p.record("heap_fraction", it) }
        val rows = PerformanceReport.overview(p).associateBy { it.tag }
        assertEquals("p50 412 ms · p90 690 ms · p95 690 ms · 3 samples",
            rows.getValue("atlas-perf-cold_start_ms").value)
        assertEquals("p50 8.3 ms · p90 8.3 ms · p95 8.3 ms · 1 sample",
            PerformanceReport.actions(p).first().value)
        assertEquals("p50 12 ms · p90 30 ms · p95 30 ms · 4 samples · 25.0% over frame budget",
            rows.getValue("atlas-perf-grid_frame_ms").value)
        assertEquals("p50 45 photos/min · p90 45 photos/min · p95 45 photos/min · 1 sample",
            rows.getValue("atlas-perf-face_photos_min").value)
        assertEquals("Peak 41% of maximum · 3 samples",
            rows.getValue("atlas-perf-heap_fraction").value)
        assertEquals(PerformanceReport.NO_DATA, rows.getValue("atlas-perf-visual_photos_min").value)
        assertTrue(rows.getValue("atlas-perf-cold_start_ms").hasData)
    }
}
