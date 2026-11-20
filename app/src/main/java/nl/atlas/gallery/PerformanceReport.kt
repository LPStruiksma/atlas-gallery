package nl.atlas.gallery

import java.util.Locale

/**
 * PERF-0 (#121, design brief revision 2): the read-only Settings → Advanced →
 * Performance copy, built from numeric aggregates only. Pure so JVM tests pin
 * the wording. Internal keys (A1–A22, snake_case) never reach the UI.
 */
internal object PerformanceReport {
    const val NO_DATA = "No data yet"
    const val NOT_MEASURED = "Not measured"

    /** [hasData] false: "No data yet", or "Not measured" for actions with no source hook (A12–A22). */
    data class Row(val tag: String, val label: String, val value: String, val hasData: Boolean)

    /** Highest action number with a live source hook; later rows are "Not measured". */
    const val INSTRUMENTED_ACTIONS = 11

    val actionLabels = listOf(
        "Open app to cached photos", "Grid scroll and fling", "Open a photo",
        "Swipe in the viewer", "Face and visual indexing", "New photo appears",
        "Return from the viewer", "Memory sample", "Open People",
        "Open a person and scroll", "Open Search", "Pinch-zoom the grid",
        "Full-resolution zoom", "Video first frame", "Switch tabs and albums",
        "Select photos", "Bulk actions", "People review", "Stories",
        "Details and favourite", "Duplicate and trash review", "Sort and Settings"
    )

    /** The six headline rows shown first, in the brief's order. */
    fun overview(p: PerformanceAggregates): List<Row> {
        val grid = p.snapshot("grid_frame_ms")
        val overBudget = p.positiveFraction("grid_frame_over_budget")
        val gridValue = if (grid.count == 0) NO_DATA else buildString {
            append(distribution(grid, ::ms))
            if (overBudget != null) {
                append(" · ")
                append(String.format(Locale.US, "%.1f%% over frame budget", overBudget * 100.0))
            }
        }
        val heap = p.snapshot("heap_fraction")
        val heapValue = if (heap.count == 0) NO_DATA else
            String.format(Locale.US, "Peak %.0f%% of maximum", (heap.max ?: 0.0) * 100.0) +
                " · " + samples(heap.count)
        return listOf(
            metric(p, "cold_start_ms", "Cold start to cached photos", ::ms),
            metric(p, "viewer_ms", "Viewer preview", ::ms),
            Row("atlas-perf-grid_frame_ms", "Grid frame time", gridValue, grid.count > 0),
            metric(p, "face_photos_min", "Face scanning", ::perMinute),
            metric(p, "visual_photos_min", "Visual indexing", ::perMinute),
            Row("atlas-perf-heap_fraction", "Java heap / maximum", heapValue, heap.count > 0)
        )
    }

    /** Per-action details, below the overview. */
    fun actions(p: PerformanceAggregates): List<Row> = actionLabels.mapIndexed { index, label ->
        val number = index + 1
        val key = "action_a${number}_ms"
        val tag = "atlas-perf-$key"
        if (number > INSTRUMENTED_ACTIONS) Row(tag, label, NOT_MEASURED, false)
        else metric(p, key, label, ::ms).copy(tag = tag)
    }

    private fun metric(
        p: PerformanceAggregates, key: String, label: String, unit: (Double) -> String
    ): Row {
        val s = p.snapshot(key)
        return Row("atlas-perf-$key", label,
            if (s.count == 0) NO_DATA else distribution(s, unit), s.count > 0)
    }

    private fun distribution(s: PerformanceAggregates.Summary, unit: (Double) -> String) =
        "p50 ${unit(s.p50!!)} · p90 ${unit(s.p90!!)} · p95 ${unit(s.p95!!)} · ${samples(s.count)}"

    fun samples(count: Int) = if (count == 1) "1 sample" else "$count samples"

    fun ms(value: Double): String =
        if (value < 10.0) String.format(Locale.US, "%.1f ms", value)
        else String.format(Locale.US, "%.0f ms", value)

    fun perMinute(value: Double): String = String.format(Locale.US, "%.0f photos/min", value)
}
