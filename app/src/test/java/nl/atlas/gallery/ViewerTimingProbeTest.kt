package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTimingProbeTest {
    @Test fun oneSelectionMeasuresPageAndPreviewUsingMonotonicClock() {
        var nanos = 0L
        val trace = ViewerTimingProbe(nowNanos = { nanos })
        trace.selectionRequested(7)
        nanos += 4_000_000
        trace.pagerSelected(7)
        nanos += 25_000_000
        trace.firstPreviewReady(7)
        assertEquals(listOf(ViewerTimingProbe.Sample(7, 4.0, 29.0)), trace.snapshot())
        assertEquals(29.0, trace.aggregate()["preview_p95_ms"]!!, 0.0)
        assertEquals(4.0, trace.aggregate()["page_p50_ms"]!!, 0.0)
    }

    @Test fun previewArrivingBeforePageStateIsHandledWithoutLying() {
        var nanos = 0L
        val trace = ViewerTimingProbe(nowNanos = { nanos })
        trace.selectionRequested(3)
        nanos += 2_000_000
        trace.firstPreviewReady(3)
        nanos += 1_000_000
        trace.pagerSelected(3)
        assertEquals(1, trace.snapshot().size)
        assertEquals(2.0, trace.snapshot().single().firstPreviewMs, 0.0)
        assertEquals(3.0, trace.snapshot().single().pagerSelectedMs, 0.0)
    }

    @Test fun rapidScrubDiscardsStaleSelectionsAndNeverAcceptsLateDecode() {
        var nanos = 0L
        val trace = ViewerTimingProbe(nowNanos = { nanos }, maxSamples = 2)
        trace.selectionRequested(1)
        nanos += 1_000_000
        trace.selectionRequested(4)
        trace.pagerSelected(1)
        trace.firstPreviewReady(1)
        assertTrue(trace.snapshot().isEmpty())
        nanos += 5_000_000
        trace.pagerSelected(4)
        trace.firstPreviewReady(4)
        assertEquals(listOf(4), trace.snapshot().map { it.selectedIndex })
        assertEquals(1, trace.superseded)

        for (index in 6..8) {
            trace.selectionRequested(index)
            nanos += 1_000_000
            trace.pagerSelected(index)
            nanos += 1_000_000
            trace.firstPreviewReady(index)
        }
        assertEquals(listOf(7, 8), trace.snapshot().map { it.selectedIndex })
        assertEquals(2.0, trace.aggregate()["samples"]!!, 0.0)
    }

    @Test fun reportsAreIndexOnlyAndMemoryBounded() {
        val t = ViewerTimingProbe(maxSamples = 1)
        t.selectionRequested(-99)
        assertTrue(t.snapshot().isEmpty())
        val all = t.aggregate().keys
        assertEquals(setOf("samples","superseded","page_p50_ms","page_p95_ms",
            "preview_p50_ms","preview_p95_ms"), all)
        assertFalse(t.toString().contains("content://"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedStorage() {
        ViewerTimingProbe(maxSamples = Int.MAX_VALUE)
    }
}
