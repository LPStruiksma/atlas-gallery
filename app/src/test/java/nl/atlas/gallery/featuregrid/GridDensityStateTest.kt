package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GridDensityStateTest {
    private val viewport = GridViewportGeometry(420, 820, 3, 3, 150_000)

    @Test fun focusIsTheCellUnderTheFingersNotFirstVisible() {
        val cells = listOf(
            GridVisibleCell("old", 100, 3, 20, 102, 102),
            GridVisibleCell("focus", 101, 108, 20, 102, 102),
            GridVisibleCell("other", 102, 213, 20, 102, 102)
        )
        val anchor = focalGridAnchor(cells, 185f, 92f)!!
        assertEquals("focus", anchor.mediaKey)
        assertEquals(101, anchor.itemIndex)
        assertEquals(77f / 102f, anchor.fractionX, 0.0001f)
        assertEquals(72f / 102f, anchor.fractionY, 0.0001f)
    }

    @Test fun gapChoosesNearestCellAndBadGeometryDoesNotFabricateAnchor() {
        assertNull(focalGridAnchor(emptyList(), 40f, 20f))
        assertNull(focalGridAnchor(listOf(GridVisibleCell("bad", 1, 0, 0, 0, 0)), 1f, 1f))
        val cells = listOf(GridVisibleCell("a", 0, 0, 0, 100, 100),
            GridVisibleCell("b", 1, 103, 0, 100, 100))
        assertEquals("b", focalGridAnchor(cells, 102.5f, 40f)?.mediaKey)
        assertEquals(0f, focalGridAnchor(cells, 102.5f, 40f)!!.fractionX, 0f)
    }

    @Test fun everyDensityChangeKeepsTheFocusedPixelVerticallyStable() {
        for (from in GridDensity.entries) for (to in GridDensity.entries) {
            for (idx in listOf(57, 3_007, 75_321, 149_000)) {
                val anchor = GridFocalAnchor("image-$idx", idx, 0.6f, 0.73f)
                val focusY = 357f
                val target = resolveGridAnchor(anchor, idx, to.columns, viewport, focusY)
                val stride = viewport.cellSidePx(to.columns) + viewport.gapPx
                val mediaPixel = (idx / to.columns) * stride +
                    0.73f * viewport.cellSidePx(to.columns)
                val scrollPixel = (target.firstItemIndex / to.columns) * stride +
                    target.firstItemScrollOffsetPx
                assertFalse("Unexpected clip $from to $to index $idx", target.clampedByBounds)
                assertTrue("$from to $to index $idx: $mediaPixel - $scrollPixel", abs(mediaPixel - scrollPixel - focusY) <= 1.1f)
                assertEquals(0, target.firstItemIndex % to.columns)
                assertTrue(target.firstItemScrollOffsetPx in 0 until stride)
            }
        }
    }

    @Test fun catalogScaleFocalRetentionDoesNotAllocateOrDrift() {
        // 5,000 deterministic focal moves across a 150k-item catalogue, including
        // near the final page. Only actual viewport-clamped anchors may shift.
        var checked = 0
        for (n in 0 until 5_000) {
            val index = 1_000 + (n.toLong() * 7919L % 147_000L).toInt()
            val level = GridDensity.entries[n % GridDensity.entries.size]
            val fraction = (n % 101) / 100f
            val focalY = 60f + (n % 600)
            val target = resolveGridAnchor(
                GridFocalAnchor("uri:$index", index, 0.23f, fraction),
                index, level.columns, viewport, focalY)
            val stride = viewport.cellSidePx(level.columns) + viewport.gapPx
            val expected = (index / level.columns).toDouble() * stride +
                fraction * viewport.cellSidePx(level.columns) - focalY
            val actual = (target.firstItemIndex / level.columns).toDouble() * stride +
                target.firstItemScrollOffsetPx
            if (!target.clampedByBounds) {
                assertTrue("idx $index level $level expected $expected got $actual",
                    abs(expected - actual) <= 0.51)
                checked++
            }
        }
        assertTrue(checked > 4_900)
    }

    @Test fun aReorderedKeyUsesItsNewIndexNotTheStaleCoordinate() {
        val old = GridFocalAnchor("content://media/example/9", 900, 0.2f, 0.5f)
        val moved = resolveGridAnchor(old, 6000, 7, viewport, 300f)
        val stale = resolveGridAnchor(old, 900, 7, viewport, 300f)
        assertTrue(moved.firstItemIndex > stale.firstItemIndex)
        assertEquals(0, moved.firstItemIndex % 7)
    }

    @Test fun clampAtStartAndEndPreventsInvalidScroll() {
        for (level in GridDensity.entries) {
            val first = resolveGridAnchor(GridFocalAnchor("a", 0, 0.5f, 0f), 0,
                level.columns, viewport, 500f)
            assertEquals(0, first.firstItemIndex)
            assertEquals(0, first.firstItemScrollOffsetPx)
            assertTrue(first.clampedByBounds)
            val last = resolveGridAnchor(GridFocalAnchor("z", 149_999, 0.5f, 1f),
                149_999, level.columns, viewport, 0f)
            assertTrue(last.clampedByBounds)
            assertTrue(last.firstItemIndex < 150_000)
        }
    }

    @Test fun noFalseAnchorAtZeroItems() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveGridAnchor(GridFocalAnchor("a", 0, 0f, 0f), 0, 4,
                viewport.copy(itemCount = 0), 40f)
        }
    }

    @Test fun pinchCommitAndReversalAndEventCap() {
        val tap = GridPinchAccumulator(GridDensity.MEDIUM)
        tap.addZoomFactor(1.02f)
        assertEquals(GridDensity.MEDIUM, tap.settle())
        val pinchIn = GridPinchAccumulator(GridDensity.MEDIUM)
        repeat(5) { pinchIn.addZoomFactor(0.9f) }
        assertEquals(GridDensity.SMALL, pinchIn.settle())
        val pinchOut = GridPinchAccumulator(GridDensity.MEDIUM)
        repeat(5) { pinchOut.addZoomFactor(1.1f) }
        assertEquals(GridDensity.LARGE, pinchOut.settle())
        val huge = GridPinchAccumulator(GridDensity.MEDIUM)
        huge.addZoomFactor(0.001f)
        assertEquals(0.2f, huge.progress, 0.00001f)
        assertEquals(GridDensity.MEDIUM, huge.settle())
        huge.addZoomFactor(50f)
        assertEquals(0f, huge.progress, 0.00001f)
        assertEquals(GridDensity.MEDIUM, huge.settle())
    }

    @Test fun oneSampleOrdinaryPinchCommitsDensityButTinyShakeDoesNot() {
        // High-refresh or coalesced input may provide only one meaningful
        // sample after touch slop; a real 18–20% change must remain responsive.
        val shrink = GridPinchAccumulator(GridDensity.MEDIUM)
        shrink.addZoomFactor(0.82f)
        assertEquals(GridDensity.SMALL, shrink.settle())
        val enlarge = GridPinchAccumulator(GridDensity.MEDIUM)
        enlarge.addZoomFactor(1.20f)
        assertEquals(GridDensity.LARGE, enlarge.settle())
        val shake = GridPinchAccumulator(GridDensity.MEDIUM)
        shake.addZoomFactor(1.02f)
        assertEquals(GridDensity.MEDIUM, shake.settle())
        // A single implausible jump still stays below the settle threshold.
        val outlier = GridPinchAccumulator(GridDensity.MEDIUM)
        outlier.addZoomFactor(50f)
        assertEquals(0.2f, kotlin.math.abs(outlier.progress), 0.00001f)
        assertEquals(GridDensity.MEDIUM, outlier.settle())
    }

    @Test fun densityLabelsAndFallbackAreStable() {
        assertEquals(listOf(3, 4, 7), GridDensity.entries.map { it.columns })
        assertEquals(GridDensity.MEDIUM, GridDensity.fromColumns(13))
    }

    @Test fun fixedGridRemainderPixelsNeverAccumulateAcross150kItems() {
        // Independent oracle: Compose distributes remaining pixels across the
        // first columns. A square row's height is the *largest* allocated width.
        for (screenWidth in 386..427) for (columns in listOf(3, 4, 7)) {
            val viewport = GridViewportGeometry(screenWidth, 760, 3, 3, 150_000)
            val usable = screenWidth - 6 - (columns - 1) * 3
            val widths = List(columns) { column ->
                usable / columns + if (column < usable % columns) 1 else 0
            }
            val trueRowHeight = widths.max()
            assertEquals("width=$screenWidth cols=$columns",
                trueRowHeight, viewport.cellSidePx(columns))
            val stride = trueRowHeight + viewport.gapPx
            val mediaIndex = 120_123
            val target = resolveGridAnchor(
                GridFocalAnchor("uri:$mediaIndex", mediaIndex, .6f, .4f),
                mediaIndex, columns, viewport, 310f
            )
            assertFalse(target.clampedByBounds)
            val firstRow = target.firstItemIndex / columns
            val scrollPx = firstRow.toLong() * stride + target.firstItemScrollOffsetPx
            val focusedPx = (mediaIndex / columns).toLong() * stride +
                .4 * trueRowHeight - scrollPx
            assertTrue("width=$screenWidth cols=$columns drift=$focusedPx",
                kotlin.math.abs(focusedPx - 310.0) <= 0.51)
        }
    }

    @Test fun toolbarAnchorRefusesPreviousViewportAfterRotationOrSplitScreen() {
        val original = GridViewportGeometry(420, 820, 3, 3, 150_000)
        assertTrue(original.matchesMeasuredViewport(420, 820))
        assertFalse(original.matchesMeasuredViewport(820, 420))
        assertFalse(original.matchesMeasuredViewport(419, 820))
        assertFalse(original.matchesMeasuredViewport(420, 819))
        assertFalse(original.matchesMeasuredViewport(420, 0))
        assertFalse(original.matchesMeasuredViewport(0, 820))
        assertFalse(original.matchesMeasuredViewport(-1, 820))
        // Rebuilding immutable layout bounds on a new viewport admits only its
        // matching geometry; an unchanged 150k-photo catalogue is insufficient.
        val rotated = original.copy(widthPx = 820, heightPx = 420)
        assertTrue(rotated.matchesMeasuredViewport(820, 420))
        assertFalse(rotated.matchesMeasuredViewport(420, 820))
    }

    @Test fun ultraNarrowViewportAndLargeInsetsStayBounded() {
        val skinny = GridViewportGeometry(6, 100, 3, 4, 12)
        assertEquals(1, skinny.cellSidePx(7))
        assertEquals(1, skinny.cellSidePx(4))
    }
}
