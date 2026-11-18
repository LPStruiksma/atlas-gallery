package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * PERF-32 (#334): the pure rules behind full-resolution zoom tiles.
 * Synthetic dimensions only; no file, Bitmap or decoder.
 */
class ViewerTileZoomTest {
    private val viewW = 1_080
    private val viewH = 2_340
    private val mp50 = 8_160 to 6_120
    private val base50 = 2_400 to 1_800 // Coil's 2400 px base of that photo

    @Test fun tilesStartWhereTheBaseSoftensAndOnlyForLargerSources() {
        // Base fit is 0.45 screen px per base px on a 1080 px wide phone.
        assertFalse(viewerTilesWanted(1f, viewW, viewH, base50.first, base50.second, mp50.first, mp50.second))
        assertFalse(viewerTilesWanted(2.5f, viewW, viewH, base50.first, base50.second, mp50.first, mp50.second))
        assertTrue(viewerTilesWanted(3.4f, viewW, viewH, base50.first, base50.second, mp50.first, mp50.second))
        assertTrue(viewerTilesWanted(12f, viewW, viewH, base50.first, base50.second, mp50.first, mp50.second))
        // A photo no larger than its base decode never tiles.
        assertFalse(viewerTilesWanted(12f, viewW, viewH, 2_000, 1_500, 2_000, 1_500))
        assertFalse(viewerTilesWanted(12f, viewW, viewH, 2_400, 1_800, 2_800, 2_100))
        // Unknown sizes fail closed.
        assertFalse(viewerTilesWanted(8f, viewW, viewH, 0, 0, mp50.first, mp50.second))
        assertFalse(viewerTilesWanted(Float.NaN, viewW, viewH, 2_400, 1_800, 8_160, 6_120))
    }

    @Test fun zoomLimitRisesOnlyWithATiledSource() {
        assertEquals(5f, viewerMaxZoom(viewW, viewH, 0, 0), 0f)
        assertEquals(15.1f, viewerMaxZoom(viewW, viewH, 8_160, 6_120), 0.05f)
        assertEquals(7.4f, viewerMaxZoom(viewW, viewH, 4_000, 3_000), 0.05f)
        assertEquals(16f, viewerMaxZoom(viewW, viewH, 16_320, 12_240), 0f)
        assertEquals(5f, viewerMaxZoom(viewW, viewH, 1_000, 750), 0f)
        // The rebound after a decode or rotation keeps a legal 12x pose.
        val pose = ViewerZoomPosition(12f, 100f, -200f)
        assertEquals(12f, viewerReboundZoom(pose, viewW, viewH, 2_400, 1_800,
            viewerMaxZoom(viewW, viewH, 8_160, 6_120)).scale, 0f)
        assertEquals(5f, viewerReboundZoom(pose, viewW, viewH, 2_400, 1_800).scale, 0f)
    }

    @Test fun visibleRegionMapsToThePlannersTilesAndTheyCoverTheScreen() {
        val budget = viewerTilePlanBudgetBytes(viewerTileCacheBytes(512L * 1024 * 1024))
        for (exif in 1..8) {
            val o = requireNotNull(viewerOrientationFromExif(mp50.first, mp50.second, exif))
            val uw = o.uprightWidth
            val uh = o.uprightHeight
            var previous: ViewerOrientedTilePlan? = null
            for (zoom in listOf(3.5f, 6f, 8f, 12f, 15f)) for (pan in listOf(-0.9f, -0.3f, 0f, 0.4f, 1f)) {
                // Real poses: let the viewer's own clamp bound the translation.
                val pose = viewerZoomGesture(ViewerZoomPosition(zoom), 1f, pan * 10_000f, pan * 7_000f,
                    viewW / 2f, viewH / 2f, viewW, viewH, uw, uh, maxScale = 16f)
                val plan = requireNotNull(viewerTilePlanForPose(o, viewW, viewH, pose, budget, previous))
                assertEquals(planViewerOrientedTiles(o, viewW, viewH, pose.scale, pose.translationX,
                    pose.translationY, budget), plan)
                assertTrue(plan.upright.tiles.size <= 12)
                for (i in plan.rawTiles.indices) {
                    assertEquals(plan.upright.tiles[i], viewerUprightRegionForRawTile(plan.rawTiles[i], o))
                }
                assertScreenCovered(plan, pose, uw, uh)
                previous = plan
            }
        }
    }

    /** Every on-screen pixel of the photo lies in exactly one drawn tile. */
    private fun assertScreenCovered(plan: ViewerOrientedTilePlan, pose: ViewerZoomPosition, uw: Int, uh: Int) {
        val rects = plan.upright.tiles.map { tile ->
            val r = requireNotNull(viewerTileDrawRect(tile, uw, uh, viewW, viewH))
            val (l, t) = viewerLayerToScreen(r.left, r.top, pose, viewW, viewH)
            val (rr, b) = viewerLayerToScreen(r.right, r.bottom, pose, viewW, viewH)
            floatArrayOf(l, t, rr, b)
        }
        val image = requireNotNull(viewerTileDrawRect(ViewerSourceRect(0, 0, uw, uh), uw, uh, viewW, viewH))
        val (il, it) = viewerLayerToScreen(image.left, image.top, pose, viewW, viewH)
        val (ir, ib) = viewerLayerToScreen(image.right, image.bottom, pose, viewW, viewH)
        val left = max(0f, il); val top = max(0f, it)
        val right = min(viewW.toFloat(), ir); val bottom = min(viewH.toFloat(), ib)
        assertTrue(right > left && bottom > top)
        for (gy in 0 until 47) for (gx in 0 until 23) {
            val x = left + (right - left) * (gx + 0.5f) / 23f
            val y = top + (bottom - top) * (gy + 0.5f) / 47f
            val hits = rects.count { x >= it[0] && x < it[2] && y >= it[1] && y < it[3] }
            assertEquals("screen ($x,$y) at zoom ${pose.scale}", 1, hits)
        }
    }

    @Test fun rapidPanCancelsStaleDecodesAndNeverStartsATileTwice() {
        val o = requireNotNull(viewerOrientationFromExif(mp50.first, mp50.second, 1))
        val source = ViewerTileSource("content://synthetic/50mp", 7L, 0L)
        val budget = viewerTilePlanBudgetBytes(32L * 1024 * 1024)
        val inFlight = LinkedHashSet<ViewerTileKey>()
        val resident = HashSet<ViewerTileKey>()
        var started = 0
        var cancelled = 0
        var previous: ViewerOrientedTilePlan? = null
        var activeKeys = emptySet<ViewerTileKey>()
        // A fast fling across the photo at 8x: 60 frames, one decode finishes
        // every third frame (decodes are much slower than a frame).
        for (frame in 0 until 60) {
            val pose = viewerZoomGesture(ViewerZoomPosition(8f), 1f, -4_000f + frame * 140f, 0f,
                viewW / 2f, viewH / 2f, viewW, viewH, o.uprightWidth, o.uprightHeight, 16f)
            val plan = requireNotNull(viewerTilePlanForPose(o, viewW, viewH, pose, budget, previous))
            previous = plan
            val batch = viewerTileRequests(source, o, plan, resident)
            activeKeys = batch.activeVisibleKeys
            resident.retainAll(activeKeys) // the cache shows only the visible set
            val work = viewerTileWork(batch.missing.map { it.key }, activeKeys, inFlight)
            for (key in work.start) assertFalse("started twice", key in inFlight)
            inFlight.removeAll(work.cancel.toSet()); cancelled += work.cancel.size
            inFlight.addAll(work.start); started += work.start.size
            assertTrue("stale decode kept running", activeKeys.containsAll(inFlight))
            if (frame % 3 == 2) inFlight.firstOrNull()?.let { inFlight.remove(it); resident += it }
        }
        assertTrue("a fling must cancel stale work, cancelled=$cancelled", cancelled > 0)
        // Once the finger stops, the remaining work completes the viewport.
        while (inFlight.isNotEmpty()) inFlight.first().let { inFlight.remove(it); resident += it }
        assertEquals(activeKeys, resident)
        assertTrue(started >= activeKeys.size)
    }

    @Test fun tileMemoryIsBoundedByTheHeapAndAlwaysHoldsOneViewport() {
        val mb = 1024L * 1024
        assertEquals(4 * mb, viewerTileCacheBytes(0L))
        assertEquals(4 * mb, viewerTileCacheBytes(16 * mb))
        assertEquals(8 * mb, viewerTileCacheBytes(64 * mb))
        assertEquals(32 * mb, viewerTileCacheBytes(256 * mb))
        assertEquals(32 * mb, viewerTileCacheBytes(4_096 * mb))
        for (heap in listOf(32 * mb, 64 * mb, 192 * mb, 512 * mb)) {
            val cap = viewerTileCacheBytes(heap)
            val budget = viewerTilePlanBudgetBytes(cap)
            assertTrue(budget <= cap && budget <= 16 * mb)
            val cache = ViewerTileResidentCache<Any>(cap) { }
            val source = ViewerTileSource("content://synthetic/200mp", 3L, 0L)
            for (exif in listOf(1, 6)) {
                val o = requireNotNull(viewerOrientationFromExif(16_320, 12_240, exif))
                var previous: ViewerOrientedTilePlan? = null
                for (step in 0 until 40) {
                    val zoom = 2f + step * 0.35f
                    val pose = viewerZoomGesture(ViewerZoomPosition(zoom), 1f, step * 90f - 1_500f,
                        step * 60f - 900f, viewW / 2f, viewH / 2f, viewW, viewH,
                        o.uprightWidth, o.uprightHeight, 16f)
                    val plan = viewerTilePlanForPose(o, viewW, viewH, pose, budget, previous) ?: continue
                    previous = plan
                    val batch = viewerTileRequests(source, o, plan, cache.residentKeys())
                    cache.show(source, batch.activeVisibleKeys)
                    for (request in batch.missing) {
                        // Worst case: the decoder returns the full planned size.
                        assertTrue(cache.offer(request.key, Any(), request.maximumArgbBytes))
                        assertTrue(cache.usedBytes <= cap)
                    }
                    // The whole viewport fits: nothing visible was evicted.
                    assertEquals(batch.activeVisibleKeys, cache.residentKeys())
                }
            }
            cache.close()
            assertEquals(0L, cache.usedBytes)
        }
    }

    @Test fun pixelTransformMatchesTheGeometryForAllEightExifOrientations() {
        val rawW = 64
        val rawH = 48
        val tile = ViewerSourceRect(16, 8, 23, 13) // 7 x 5 raw pixels
        for (exif in 1..8) {
            val o = requireNotNull(viewerOrientationFromExif(rawW, rawH, exif))
            val uprightTile = requireNotNull(viewerUprightRegionForRawTile(tile, o))
            val affine = viewerTilePixelTransform(o, tile.right - tile.left, tile.bottom - tile.top)
            for (y in 0 until tile.bottom - tile.top) for (x in 0 until tile.right - tile.left) {
                val pixel = ViewerSourceRect(tile.left + x, tile.top + y, tile.left + x + 1, tile.top + y + 1)
                val expected = requireNotNull(viewerUprightRegionForRawTile(pixel, o))
                val (ux, uy) = affine.map(x + 0.5f, y + 0.5f)
                assertEquals("EXIF $exif x", expected.left - uprightTile.left + 0.5f, ux, 1e-4f)
                assertEquals("EXIF $exif y", expected.top - uprightTile.top + 0.5f, uy, 1e-4f)
            }
        }
    }

    @Test fun orientationIsRefusedWhenItDisagreesWithTheBase() {
        val portraitRaw = requireNotNull(viewerOrientationFromExif(8_160, 6_120, 6)) // upright 6120 x 8160
        assertTrue(viewerTileSourceMatchesBase(portraitRaw, 1_800, 2_400))
        assertFalse(viewerTileSourceMatchesBase(portraitRaw, 2_400, 1_800))
        assertFalse(viewerTileSourceMatchesBase(portraitRaw, 0, 0))
        // A base larger than the source has nothing to gain.
        val small = ViewerSourceOrientation(1_200, 900, 0)
        assertFalse(viewerTileSourceMatchesBase(small, 2_400, 1_800))
        assertEquals(1, viewerExifForDegrees(0))
        assertEquals(6, viewerExifForDegrees(90))
        assertEquals(3, viewerExifForDegrees(-180))
        assertEquals(8, viewerExifForDegrees(270))
        assertEquals(0, viewerExifForDegrees(45))
        assertNull(viewerOrientationFromExif(8_160, 6_120, viewerExifForDegrees(45)))
    }
}
