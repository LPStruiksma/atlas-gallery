package nl.atlas.gallery.featurevisual.search

import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** VS-V2 (#468): a video is one result, its best moment, with up to three more. */
class VisualVideoCollapseTest {
    private val model = "siglip-test:1"
    private val dim = 8
    private val probe = FloatArray(dim).also { it[0] = 1f }

    private fun at(c: Float, axis: Int = 1): FloatArray =
        FloatArray(dim).also { it[0] = c; it[1 + (axis - 1) % (dim - 1)] = sqrt(1f - c * c) }

    private fun photo(n: Int) = VisualMedia("content://synthetic/p%03d".format(n), "r1")
    private fun moment(video: Int, frameMs: Long) =
        VisualMedia("content://synthetic/v%03d".format(video), "r1", frameMs)

    /** Keyset pages in (key, frame) order of [pageSize], like the store. */
    private class Source(
        override val modelVersion: String,
        rows: List<Pair<VisualMedia, FloatArray>>,
        private val pageSize: Int = Int.MAX_VALUE
    ) : VisualVectorSource {
        val sorted = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> =
            sorted.filter { (m, _) ->
                after == null || m.key > after.mediaKey ||
                    (m.key == after.mediaKey && (m.frameMs ?: -1L) > (after.frameMs ?: -1L))
            }.take(minOf(limit, pageSize)).map { (m, v) ->
                VisualIndexedVector(m, modelVersion, v, VisualVectorCursor("e", modelVersion, m.key, m.frameMs))
            }
        override fun lookup(media: VisualMedia): FloatArray? =
            sorted.firstOrNull { it.first == media }?.second
    }

    private fun search(rows: List<Pair<VisualMedia, FloatArray>>, limit: Int = 500) =
        VisualQueryEngine(Source(model, rows)).search(
            VisualQuery(model, VisualReference(embed = { probe }), limit))

    @Test fun aVideoIsRankedOnceByItsBestMoment() {
        val result = search(listOf(
            photo(1) to at(0.86f, 1),
            moment(1, 0) to at(0.76f, 2),
            moment(1, 2_000) to at(0.90f, 3),
            moment(1, 4_000) to at(0.80f, 4),
            moment(1, 6_000) to at(0.70f, 5), // below the floor: never a moment
            photo(2) to at(0.78f, 6)))
        assertEquals(VisualQueryStatus.OK, result.status)
        assertEquals(listOf(moment(1, 2_000), photo(1), photo(2)), result.hits.map { it.media })
        val video = result.hits[0]
        assertTrue(video.isVideoMoment)
        assertEquals(2_000L, video.frameMs)
        assertEquals(VisualMatchBand.STRONG, video.band)
        assertEquals(listOf(4_000L, 0L), video.secondary.map { it.frameMs })
        assertFalse(result.hits[1].isVideoMoment)
        assertEquals(6, result.scanned)
    }

    @Test fun atMostThreeSecondaryMomentsBestFirst() {
        val cosines = listOf(0.75f, 0.91f, 0.80f, 0.88f, 0.77f, 0.85f)
        val rows = cosines.mapIndexed { i, c -> moment(7, i * 2_000L) to at(c, i + 1) }
        val hit = search(rows).hits.single()
        assertEquals(2_000L, hit.frameMs)
        assertEquals(VisualHit.MAX_SECONDARY_MOMENTS, hit.secondary.size)
        assertEquals(listOf(6_000L, 10_000L, 4_000L), hit.secondary.map { it.frameMs })
        assertTrue(hit.secondary.all { it.secondary.isEmpty() })
    }

    @Test fun aVideoTakesOneResultSlotSoPhotosAreNotCrowdedOut() {
        // 30 matching moments of one video and 3 photos, limit 2.
        val rows = (0 until 30).map { moment(1, it * 2_000L) to at(0.95f - 0.002f * it - 0.01f, it + 1) } +
            listOf(photo(1) to at(0.92f), photo(2) to at(0.80f), photo(3) to at(0.76f))
        val result = search(rows, limit = 2)
        assertEquals(listOf(moment(1, 0), photo(1)), result.hits.map { it.media })
        assertTrue("4 items passed the floor, 2 shown", result.truncated)
    }

    @Test fun momentsSplitAcrossPagesStillCollapseToOneHit() {
        // 510 unrelated photos fill the first page, so the video's five moments
        // straddle the page boundary (510 + 2 | 3).
        val filler = (0 until 510).map { photo(it) to at(0.3f, it + 1) }
        val video = (0 until 5).map { moment(1, it * 1_000L) to at(0.80f + 0.02f * it, it + 1) }
        val result = search(filler + video)
        assertEquals(515, result.scanned)
        val hit = result.hits.single()
        assertEquals(4_000L, hit.frameMs)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), hit.secondary.map { it.frameMs })
    }

    @Test fun aNearDuplicateMomentIsCountedAndTheVideoKeepsItsNextBest() {
        val result = search(listOf(
            moment(2, 0) to at(0.98f, 1), moment(2, 2_000) to at(0.82f, 2)))
        assertEquals(1, result.nearDuplicates)
        assertEquals(listOf(moment(2, 2_000)), result.hits.map { it.media })
    }

    @Test fun collapseKeepsPoseScoresOnThePrimaryOnly() {
        val moments = listOf(
            VisualHit(moment(4, 0), 0.8f, VisualMatchBand.RELATED),
            VisualHit(moment(4, 2_000), 0.9f, VisualMatchBand.STRONG))
        val hit = VisualQueryEngine.collapseMoments(moments)
        assertEquals(2_000L, hit.frameMs)
        assertEquals(listOf(0L), hit.secondary.map { it.frameMs })
    }

    @Test fun momentTimesAreFormattedForTheBadge() {
        assertEquals("0:00", formatMomentTime(0))
        assertEquals("0:12", formatMomentTime(12_999))
        assertEquals("2:05", formatMomentTime(125_000))
        assertEquals("1:00:01", formatMomentTime(3_601_000))
    }
}
