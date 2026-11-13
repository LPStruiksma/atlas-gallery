package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Test-only fictional URI; no source photo or Android runtime required. */
class ViewerTileRequestsTest {
    private val testSource = ViewerTileSource("content://synthetic/photo/200mp", 7L, 0L)

    @Test fun stalePermissionsAndEditedContentCanNeverReuseTile() {
        val o = requireNotNull(viewerOrientationFromExif(20_000, 10_000, 6))
        val plan = requireNotNull(planViewerOrientedTiles(o, 1440, 3120, 2.5f, 0f, 0f))
        val ready = viewerTileRequests(testSource, o, plan)
        assertTrue(ready.missing.isNotEmpty())
        val old = ready.missing.first().key
        val active = ready.activeVisibleKeys
        assertEquals(ready.missing.size, active.size)
        assertTrue(viewerTileMayPublish(old, testSource, active))
        // Leaving the viewer or panning so the tile is no longer visible
        // invalidates publication even if the URI is still authorized.
        assertFalse(viewerTileMayPublish(old, testSource, emptySet()))
        assertFalse(viewerTileMayPublish(old, testSource, active - old))
        assertFalse(viewerTileMayPublish(old, null, active))
        assertFalse(viewerTileMayPublish(old, testSource.copy(grantEpoch = 0L), active))
        assertFalse(viewerTileMayPublish(old, testSource.copy(grantEpoch = 8L), active))
        assertFalse(viewerTileMayPublish(old, testSource.copy(contentRevision = 1L), active))
        assertFalse(viewerTileMayPublish(old, testSource.copy(uri = "content://synthetic/other"), active))
        assertTrue(viewerTileRequests(testSource.copy(grantEpoch = 0L), o, plan).missing.isEmpty())
        assertTrue(viewerTileRequests(testSource.copy(uri = ""), o, plan).missing.isEmpty())
        val oldResident = ready.activeVisibleKeys
        val reGranted = viewerTileRequests(testSource.copy(grantEpoch = 8L), o, plan,
            resident = oldResident)
        assertEquals(0, reGranted.cachedVisible)
        assertEquals(ready.missing.size, reGranted.missing.size)
        val edited = viewerTileRequests(testSource.copy(contentRevision = 1L), o, plan,
            resident = oldResident)
        assertEquals(0, edited.cachedVisible)
        assertEquals(ready.missing.size, edited.missing.size)
    }

    @Test fun centerFirstSchedulingIsDeterministicAndResidentScoped() {
        val o = requireNotNull(viewerOrientationFromExif(20_000, 10_000, 1))
        val plan = requireNotNull(planViewerOrientedTiles(o, 1440, 3120, 3f, 0f, 0f))
        val all = viewerTileRequests(testSource, o, plan)
        assertEquals(plan.upright.tiles.size, all.missing.size)
        assertTrue(all.admittedBytes <= plan.estimatedPeakTileBytes)
        assertEquals(all, viewerTileRequests(testSource, o, plan))
        val mid = all.missing.first().key
        val remaining = viewerTileRequests(testSource, o, plan, resident = setOf(mid))
        assertEquals(1, remaining.cachedVisible)
        assertEquals(all.missing.size - 1, remaining.missing.size)
        assertFalse(remaining.missing.any { it.key == mid })
        assertEquals(all.activeVisibleKeys, remaining.activeVisibleKeys)
        val capped = viewerTileRequests(testSource, o, plan, maxRequests = 1)
        assertEquals(1, capped.missing.size)
        assertEquals(all.missing.first().key, capped.missing.first().key)
        assertTrue(viewerTileRequests(testSource, o, plan, maxRequests = 13).missing.isEmpty())
    }

    @Test fun randomizedOrientationZoomAndGrantEpochNeverExceedBound() {
        val random = Random(20261023)
        repeat(10_000) { iteration ->
            val w = random.nextInt(1_000, 32_001)
            val h = random.nextInt(1_000, 32_001)
            val o = requireNotNull(viewerOrientationFromExif(w, h, 1 + iteration % 8))
            val zoom = listOf(1f, 1.5f, 2.5f, 5f)[iteration % 4]
            val plan = planViewerOrientedTiles(o, 1440, 3120, zoom,
                random.nextInt(-600, 601).toFloat(),
                random.nextInt(-600, 601).toFloat()) ?: return@repeat
            val source = testSource.copy(
                grantEpoch = random.nextLong(1, 100_001),
                contentRevision = random.nextLong(0, 1_000_000))
            val batch = viewerTileRequests(source, o, plan)
            assertTrue(batch.missing.size <= 12)
            assertTrue(batch.admittedBytes <= plan.estimatedPeakTileBytes)
            assertTrue(batch.admittedBytes <= 16L * 1024L * 1024L)
            val activeKeys = batch.activeVisibleKeys
            assertEquals(plan.upright.tiles.size, activeKeys.size)
            assertTrue(batch.missing.all {
                viewerTileMayPublish(it.key, source, activeKeys)
            })
            assertTrue(batch.missing.none {
                viewerTileMayPublish(it.key, source.copy(grantEpoch = source.grantEpoch + 1), activeKeys)
            })
            assertTrue(batch.missing.none {
                viewerTileMayPublish(it.key, source.copy(contentRevision = source.contentRevision + 1), activeKeys)
            })
            assertEquals(batch, viewerTileRequests(source, o, plan))
        }
    }

    @Test fun viewportCoverRejectsMissingOverlappingAndOffscreenTiles() {
        val visible = ViewerSourceRect(10, 10, 90, 80)
        val correct = listOf(
            ViewerSourceRect(0, 0, 50, 100),
            ViewerSourceRect(50, 0, 100, 100))
        assertTrue(viewerTilesExactlyCoverVisible(visible, correct, 100, 100))
        assertFalse(viewerTilesExactlyCoverVisible(visible, listOf(
            ViewerSourceRect(0, 0, 50, 100),
            ViewerSourceRect(51, 0, 100, 100)), 100, 100))
        assertFalse(viewerTilesExactlyCoverVisible(visible, listOf(
            ViewerSourceRect(0, 0, 60, 100),
            ViewerSourceRect(50, 0, 100, 100)), 100, 100))
        assertFalse(viewerTilesExactlyCoverVisible(visible, listOf(
            ViewerSourceRect(0, 0, 100, 100),
            ViewerSourceRect(101, 101, 150, 150)), 200, 200))
        assertFalse(viewerTilesExactlyCoverVisible(visible, correct, 80, 100))
        assertFalse(viewerTilesExactlyCoverVisible(visible, emptyList(), 100, 100))
    }

    @Test fun requestAdmissionRejectsSourceValidButViewportInvalidPlans() {
        val orientation = requireNotNull(viewerOrientationFromExif(1000, 1000, 1))
        val visible = ViewerSourceRect(100, 100, 800, 800)
        fun forged(tiles: List<ViewerSourceRect>): ViewerOrientedTilePlan {
            val upright = ViewerTilePlan(
                visible, 1, tiles, 16L * 1024 * 1024, false)
            return ViewerOrientedTilePlan(upright, tiles, 16L * 1024 * 1024)
        }
        val gap = forged(listOf(ViewerSourceRect(0, 0, 500, 1000),
            ViewerSourceRect(501, 0, 1000, 1000)))
        val overlap = forged(listOf(ViewerSourceRect(0, 0, 600, 1000),
            ViewerSourceRect(500, 0, 1000, 1000)))
        val offscreen = forged(listOf(ViewerSourceRect(0, 0, 1000, 1000),
            ViewerSourceRect(900, 900, 1000, 1000)))
        for (plan in listOf(gap, overlap, offscreen)) {
            val rejected = viewerTileRequests(testSource, orientation, plan)
            assertTrue(rejected.missing.isEmpty())
            assertTrue(rejected.activeVisibleKeys.isEmpty())
            assertEquals(0L, rejected.admittedBytes)
        }
    }

    @Test fun extremeSourceDimensionsAndOverflowBudgetCannotUnderflow() {
        val huge = ViewerSourceOrientation(Int.MAX_VALUE, Int.MAX_VALUE, 0)
        val raw = ViewerSourceRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        val fakePlan = ViewerTilePlan(
            visibleSource = raw, sampleSize = 1, tiles = listOf(raw),
            maxDecodedBytes = Long.MAX_VALUE,
            reducedQualityForBudget = false)
        val fakeOriented = ViewerOrientedTilePlan(
            fakePlan, listOf(raw), Long.MAX_VALUE)
        assertTrue(viewerTileRequests(
            testSource, huge, fakeOriented).activeVisibleKeys.isEmpty())

        val source = ViewerSourceOrientation(20_000, 10_000, 0)
        val oversize = ViewerSourceRect(0, 0, 20_000, 10_000)
        val oversized = ViewerOrientedTilePlan(
            ViewerTilePlan(oversize, 1, listOf(oversize),
                Long.MAX_VALUE, false),
            listOf(oversize), Long.MAX_VALUE)
        assertTrue(viewerTileRequests(
            testSource, source, oversized).activeVisibleKeys.isEmpty())
    }

    @Test fun corruptedRawMappingOrTamperedBudgetFailsClosed() {
        val o = requireNotNull(viewerOrientationFromExif(20_000, 10_000, 6))
        val plan = requireNotNull(planViewerOrientedTiles(o, 1440, 3120, 2.5f, 0f, 0f))
        val badRaw = plan.copy(rawTiles = plan.rawTiles.reversed())
        // Single-tile plan could make reversal an identity; only test this
        // when there is actual work and more than one tile.
        if (plan.rawTiles.size > 1) assertTrue(viewerTileRequests(
            testSource, o, badRaw).missing.isEmpty())
        if (plan.rawTiles.size > 1) {
            val duplicate = plan.copy(rawTiles = List(plan.rawTiles.size) {
                plan.rawTiles.first()
            })
            assertTrue(viewerTileRequests(testSource, o, duplicate).missing.isEmpty())
        }
        val badBudget = plan.copy(estimatedPeakTileBytes = 1)
        assertTrue(viewerTileRequests(testSource, o, badBudget).missing.isEmpty())
    }
}
