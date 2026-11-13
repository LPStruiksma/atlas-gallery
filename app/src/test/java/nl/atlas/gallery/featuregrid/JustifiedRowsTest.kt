package nl.atlas.gallery.featuregrid

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.displayAspectRatioOrNull
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class JustifiedRowsTest {
    private val options = RatioLayoutOptions(420, 810)

    @Test fun sourceOrderAndRowCoverageRemainExact() {
        val source = List(1_007) { i -> RatioMedia("content://item/$i", if (i % 4 == 0) 2.0f else 0.6f) }
        val layout = planRatioRows(source, options)
        var next = 0
        for ((rowIndex, row) in layout.rows.withIndex()) {
            assertEquals(next, row.firstMediaIndex)
            assertTrue(row.mediaCount in 1..options.maxItemsPerRow)
            row.cellWidthsPx.forEach { assertTrue(it > 0) }
            assertTrue(row.cellWidthsPx.sum() + options.gapPx * (row.mediaCount - 1) <= options.contentWidthPx)
            for (index in row.firstMediaIndex until row.firstMediaIndex + row.mediaCount) {
                assertEquals(rowIndex, layout.rowIndexOf(index))
            }
            next += row.mediaCount
        }
        assertEquals(source.size, next)
        assertEquals(source.size, layout.mediaCount)
        assertEquals(layout.rows.last().topPx + layout.rows.last().heightPx, layout.contentHeightPx)
    }

    @Test fun emptyAndSingleImageDoNotInventOrStretchPhotos() {
        val empty = planRatioRows(emptyList(), options)
        assertTrue(empty.rows.isEmpty())
        assertEquals(0L, empty.contentHeightPx)
        val single = planRatioRows(listOf(RatioMedia("one", 0.5f)), options)
        assertEquals(1, single.rows.size)
        assertTrue(single.rows.single().heightPx <= options.targetRowHeightPx)
        assertTrue(single.rows.single().cellWidthsPx.single() < options.contentWidthPx)
        assertTrue(!single.rows.single().spansFullWidth)
    }

    @Test fun panoramasAndVeryTallMediaStayWithinWidthWithoutCrop() {
        for (ratio in listOf(0.08f, 0.25f, 0.75f, 1f, 2f, 5f, 12f, 16f)) {
            val layout = planRatioRows(List(31) { RatioMedia("$ratio-$it", ratio) }, options)
            assertTrue(layout.rows.isNotEmpty())
            for (row in layout.rows) {
                assertTrue(row.heightPx > 0)
                assertTrue(row.heightPx <= options.maxRowHeightPx)
                assertTrue(row.cellWidthsPx.sum() + options.gapPx * (row.mediaCount - 1) <= options.contentWidthPx)
            }
        }
    }

    @Test fun malformedDimensionsAreNeutralAndCannotBreakThePlanner() {
        val values = listOf(null, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
            0f, -1f, 0.00001f, 100000f)
        val layout = planRatioRows(values.mapIndexed { i, x -> RatioMedia("$i", x) }, options)
        assertEquals(values.size, layout.rows.sumOf { it.mediaCount })
        assertTrue(layout.rows.all { it.cellWidthsPx.all { width -> width > 0 } })
    }

    @Test fun anchorAlwaysResolvesToSourceRowWithSmallErrorAwayFromBoundaries() {
        val source = List(15_000) { i -> RatioMedia("id$i", listOf(.5f, 1f, 1.33f, 2.5f)[i % 4]) }
        val layout = planRatioRows(source, options)
        for (i in 200 until 14_800 step 37) {
            val fraction = (i % 100) / 100f
            val focus = 400f
            val target = layout.scrollTarget(i, fraction, focus)
            assertTrue(!target.clampedByBounds)
            val row = layout.rows[layout.rowIndexOf(i)]
            val scrollStart = layout.rows[target.firstRowIndex].topPx + target.firstRowScrollOffsetPx
            val result = row.topPx.toDouble() + row.heightPx * fraction - scrollStart - focus
            assertTrue("index=$i, focal_error=$result", abs(result) <= 0.52)
            assertTrue(target.firstRowScrollOffsetPx >= 0)
        }
    }

    @Test fun hugeCatalogRowsAreIndexableAndDoNotAccumulateFloatPixelDrift() {
        val source = List(150_000) { i -> RatioMedia("media://$i", listOf(.35f, .75f, 1.25f, 1.8f)[i % 4]) }
        val layout = planRatioRows(source, options)
        assertEquals(150_000, layout.rows.sumOf { it.mediaCount })
        for (i in 100 until 149_000 step 7919) {
            val target = layout.scrollTarget(i, .35f, 435f)
            assertTrue(!target.clampedByBounds)
            val row = layout.rows[layout.rowIndexOf(i)]
            val position = layout.rows[target.firstRowIndex].topPx + target.firstRowScrollOffsetPx
            val error = row.topPx.toDouble() + row.heightPx * .35 - position - 435
            assertTrue("i=$i drift=$error", abs(error) < .55)
        }
    }

    @Test fun atStartAndEndClampingIsHonestAndSafe() {
        val layout = planRatioRows(List(600) { RatioMedia("$it", 1.0f) }, options)
        val start = layout.scrollTarget(0, 0f, 500f)
        assertEquals(0, start.firstRowIndex)
        assertEquals(0, start.firstRowScrollOffsetPx)
        assertTrue(start.clampedByBounds)
        val end = layout.scrollTarget(599, 1f, 0f)
        assertTrue(end.clampedByBounds)
        assertTrue(end.firstRowIndex <= layout.rows.lastIndex)
        assertTrue(end.firstRowScrollOffsetPx >= 0)
    }

    @Test fun cancelledBuildDoesNotPublishPartialRows() {
        var checkpoints = 0
        try {
            planRatioRows(List(10_000) { RatioMedia("$it", 1f) }, options) {
                checkpoints++
                if (checkpoints == 3) throw IllegalStateException("superseded")
            }
            throw AssertionError("Expected cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("superseded", expected.message)
        }
        assertEquals(3, checkpoints)
    }

    @Test fun catalogueReorderAffectsOnlyCurrentIndexAndStableMediaKeyStaysExternal() {
        val items = List(70) { RatioMedia("uri:$it", 1.5f) }
        val reordered = items.reversed()
        val original = planRatioRows(items, options)
        val revised = planRatioRows(reordered, options)
        assertEquals("uri:69", reordered[0].key)
        assertEquals(0, revised.rowIndexOf(0))
        assertTrue(original.rowIndexOf(69) > revised.rowIndexOf(0))
    }

    /** #504: Lukas's S24+ viewport (1080 px wide, 2.8125 density), real MediaStore shapes. */
    @Test fun samsungRowsFillTheWholeWidthForMixedRotatedAndUnknownMedia() {
        val options = samsungRatioLayoutOptions(1080, 2340, 2.8125f)
        assertEquals(0, options.horizontalInsetPx)
        assertEquals(6, options.gapPx)
        assertEquals(1080, options.contentWidthPx)
        // width, height, orientation (MIN_VALUE = NULL column: PNG/screenshot/EXIF-less JPEG)
        val shapes = listOf(
            Triple(4000, 3000, 0), Triple(4000, 3000, 90), Triple(1080, 2340, Int.MIN_VALUE),
            Triple(3000, 4000, 270), Triple(1920, 1080, Int.MIN_VALUE), Triple(0, 0, 0),
            Triple(12000, 2000, 0), Triple(1000, 6000, 180), Triple(1600, 1200, 45),
            Triple(1080, 1080, Int.MIN_VALUE), Triple(2268, 4032, 0)
        )
        val records = List(5_003) { i ->
            val (w, h, o) = shapes[(i * 7 + i / 3) % shapes.size]
            MediaRecord(i.toLong(), "content://media/external/images/media/$i", "IMG_$i.jpg",
                "DCIM/Camera/", 1L, 1_700_000_000_000L - i, false,
                widthPixels = w, heightPixels = h, orientationDegrees = o)
        }
        val layout = planRatioRows(records.map { RatioMedia(it.uri, it.displayAspectRatioOrNull()) }, options)
        assertEquals(records.size, layout.rows.sumOf { it.mediaCount })
        for ((index, row) in layout.rows.withIndex()) {
            val used = row.cellWidthsPx.sum() + options.gapPx * (row.mediaCount - 1)
            if (index < layout.rows.lastIndex) {
                assertEquals("row $index must be edge to edge", options.contentWidthPx, used)
                assertTrue(row.spansFullWidth)
            } else {
                assertTrue(used <= options.contentWidthPx)
            }
            assertTrue(row.heightPx <= options.maxRowHeightPx)
        }
        // The common camera shapes keep their real proportions (within rounding).
        val camera = planRatioRows(List(9) { i ->
            RatioMedia("$i", if (i % 3 == 1) 0.75f else 4f / 3f) }, options)
        val first = camera.rows.first()
        for (j in 0 until first.mediaCount) {
            val ratio = first.cellWidthsPx[j].toDouble() / first.heightPx
            val expected = if (j % 3 == 1) 0.75 else 4.0 / 3.0
            assertEquals(expected, ratio, 0.02)
        }
    }

    @Test fun cappedRowsOfVeryTallMediaStillFillAndCropRatherThanLeaveABar() {
        val layout = planRatioRows(List(40) { RatioMedia("$it", 0.08f) }, options)
        for (row in layout.rows.dropLast(1)) {
            assertEquals(options.maxRowHeightPx, row.heightPx)
            assertEquals(options.contentWidthPx,
                row.cellWidthsPx.sum() + options.gapPx * (row.mediaCount - 1))
        }
    }

    /**
     * #527 on the owner's S24+ geometry (1080x2340 px at 2.8125 dp): a mixed
     * landscape/portrait album keeps every tile within 2% of its photo's own
     * ratio and every row but the last spans the full width, so a centre crop
     * removes at most 2% and nothing is ever letterboxed.
     */
    @Test fun s24MixedAlbumKeepsEveryTileWithinTwoPercentOfItsPhoto() {
        val s24 = samsungRatioLayoutOptions(1080, 2340, 2.8125f)
        val shapes = listOf(4f / 3f, 3f / 4f, 16f / 9f, 1f, 3f / 2f, 2f / 3f, 0.35f, 9f / 16f)
        val ratios = List(400) { shapes[(it * 5 + it / 8) % shapes.size] }
        val layout = planRatioRows(ratios.mapIndexed { i, r -> RatioMedia("m$i", r) }, s24)
        for (row in layout.rows) {
            val last = row === layout.rows.last()
            if (!last) assertEquals(s24.contentWidthPx,
                row.cellWidthsPx.sum() + s24.gapPx * (row.mediaCount - 1))
            if (row.heightPx >= s24.maxRowHeightPx) continue // capped: cropped, still no bar
            row.cellWidthsPx.forEachIndexed { j, width ->
                val real = ratios[row.firstMediaIndex + j].toDouble()
                assertEquals("row ${row.firstMediaIndex} tile $j", real,
                    width.toDouble() / row.heightPx, real * 0.02)
            }
        }
    }

    @Test fun learnedPreviewRatioIsOrientedSizeAndRejectsNonsense() {
        assertEquals(4f / 3f, learnedRatioOrNull(400, 300)!!, 1e-6f)
        assertEquals(0.35f, learnedRatioOrNull(35, 100)!!, 1e-6f)
        assertNull(learnedRatioOrNull(0, 300))
        assertNull(learnedRatioOrNull(300, -1))
    }

    /** A learned ratio replaces the neutral square and the row re-plans to it. */
    @Test fun learnedRatioReplacesNeutralSquareOnReplan() {
        val s24 = samsungRatioLayoutOptions(1080, 2340, 2.8125f)
        val unknown = listOf(RatioMedia("a", 16f / 9f), RatioMedia("b", null),
            RatioMedia("c", 4f / 3f), RatioMedia("d", 1f))
        val before = planRatioRows(unknown, s24)
        val after = planRatioRows(unknown.map {
            if (it.key == "b") it.copy(displayAspectRatio = learnedRatioOrNull(90, 160)) else it
        }, s24)
        fun ratioOf(layout: RatioLayout, index: Int): Double {
            val row = layout.rows[layout.rowIndexOf(index)]
            return row.cellWidthsPx[index - row.firstMediaIndex].toDouble() / row.heightPx
        }
        assertEquals(1.0, ratioOf(before, 1), 0.1)
        assertEquals(9.0 / 16.0, ratioOf(after, 1), 9.0 / 16.0 * 0.02)
    }
}
