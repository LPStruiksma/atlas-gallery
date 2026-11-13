package nl.atlas.gallery.featurevisual.search

import nl.atlas.gallery.featurevisual.pose.PoseDescriptor
import nl.atlas.gallery.featurevisual.pose.PoseRerank
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCodec
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** VS-S2a/VS-S2c (#468): ranking, bands, duplicate filter, version fence and pose, on synthetic vectors only. */
class VisualQueryEngineTest {
    private val model = "siglip-test:1"
    private val dim = 16
    private val probe = FloatArray(dim).also { it[0] = 1f }

    /** A unit vector whose cosine to [probe] is exactly [c], orthogonal part on axis [axis]. */
    private fun at(c: Float, axis: Int = 1): FloatArray =
        FloatArray(dim).also { it[0] = c; it[1 + (axis - 1) % (dim - 1)] = sqrt(1f - c * c) }

    private fun media(n: Int, frame: Long? = null, rev: String = "r1") =
        VisualMedia("content://synthetic/%04d".format(n), rev, frame)

    /** Sorted keyset pages, like VisualIndexStore.loadActiveVectors, with call counting. */
    private class FakeSource(
        override val modelVersion: String,
        rows: List<Pair<VisualMedia, FloatArray>>,
        private val rowModel: String = modelVersion,
        /** Pages served before the epoch is superseded; like the store, it then answers nothing. */
        private val supersedeAfterPages: Int = Int.MAX_VALUE
    ) : VisualVectorSource {
        val sorted = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        var pages = 0
        var lookups = 0
        var currentChecks = 0
        private val live get() = pages <= supersedeAfterPages
        override fun isCurrent(): Boolean { currentChecks++; return live && sorted.isNotEmpty() }
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            pages++
            if (!live) return emptyList()
            return sorted.filter { (m, _) ->
                after == null || m.key > after.mediaKey ||
                    (m.key == after.mediaKey && (m.frameMs ?: -1L) > (after.frameMs ?: -1L))
            }.take(limit).map { (m, v) ->
                VisualIndexedVector(m, rowModel, v, VisualVectorCursor("e1", rowModel, m.key, m.frameMs))
            }
        }
        override fun lookup(media: VisualMedia): FloatArray? {
            lookups++
            return sorted.firstOrNull { it.first == media }?.second
        }
    }

    private fun query(ref: VisualReference, limit: Int = VisualQuery.DEFAULT_LIMIT) =
        VisualQuery(model, ref, limit)

    private fun fresh(vector: FloatArray = probe) = VisualReference(embed = { vector })

    @Test fun ranksByCosineWithBandsAndDropsBelowFloor() {
        val source = FakeSource(model, listOf(
            media(1) to at(0.78f, 1), media(2) to at(0.92f, 2), media(3) to at(0.70f, 3),
            media(4) to at(VisualQueryBands.STRONG, 4), media(5) to at(VisualQueryBands.FLOOR, 5),
            media(6, 2000L) to at(0.88f, 6)))
        val result = VisualQueryEngine(source).search(query(fresh()))
        assertEquals(VisualQueryStatus.OK, result.status)
        assertEquals(listOf(2, 6, 4, 1, 5), result.hits.map { it.media.key.takeLast(4).toInt() })
        assertEquals(listOf(VisualMatchBand.STRONG, VisualMatchBand.STRONG, VisualMatchBand.STRONG,
            VisualMatchBand.RELATED, VisualMatchBand.RELATED), result.hits.map { it.band })
        assertEquals(2000L, result.hits[1].media.frameMs)
        assertEquals(6, result.scanned)
        assertFalse(result.truncated)
        assertFalse(result.reusedStoredVector)
        result.hits.forEach { assertEquals(it.semantic, it.score, 0f) }
    }

    @Test fun calibratedBandsAreOrderedAndWiderThanQuantization() {
        // VS-S2c: docs/visual-search/VS_S2C_CALIBRATION.md
        assertEquals(0.74f, VisualQueryBands.FLOOR, 0f)
        assertEquals(0.84f, VisualQueryBands.STRONG, 0f)
        assertEquals(0.95f, VisualQueryBands.NEAR_DUPLICATE, 0f)
        val gaps = listOf(VisualQueryBands.STRONG - VisualQueryBands.FLOOR,
            VisualQueryBands.NEAR_DUPLICATE - VisualQueryBands.STRONG)
        assertTrue(gaps.all { it > 5 * VisualQueryBands.QUANTIZATION_TOLERANCE })
    }

    @Test fun bandBoundaries() {
        assertEquals(VisualMatchBand.STRONG, VisualQueryBands.bandOf(VisualQueryBands.STRONG))
        assertEquals(VisualMatchBand.RELATED, VisualQueryBands.bandOf(VisualQueryBands.STRONG - 1e-4f))
        assertEquals(VisualMatchBand.RELATED, VisualQueryBands.bandOf(VisualQueryBands.FLOOR))
        assertNull(VisualQueryBands.bandOf(VisualQueryBands.FLOOR - 1e-4f))
    }

    @Test fun excludesReferenceAndNearDuplicates() {
        val ref = media(1)
        val source = FakeSource(model, listOf(
            ref to probe, media(1, 0L) to at(0.80f, 1), media(2) to at(0.98f, 2),
            media(3) to at(VisualQueryBands.NEAR_DUPLICATE, 3), media(4) to at(0.94f, 4)))
        val result = VisualQueryEngine(source).search(query(VisualReference(ref)))
        assertEquals(VisualQueryStatus.OK, result.status)
        // The image itself is out; a keyframe of the same key is a different item and stays.
        assertEquals(listOf(media(4), media(1, 0L)), result.hits.map { it.media })
        assertEquals(2, result.nearDuplicates)
        assertTrue(result.reusedStoredVector)
    }

    @Test fun reusesStoredVectorOnlyAtSameRevision() {
        var embeds = 0
        val ref = media(1, rev = "r2")
        val source = FakeSource(model, listOf(media(1, rev = "r1") to at(0.2f, 1), media(2) to at(0.8f)))
        val result = VisualQueryEngine(source).search(query(VisualReference(ref) { embeds++; probe }))
        assertEquals(1, embeds)
        assertFalse(result.reusedStoredVector)
        assertEquals(listOf(media(2)), result.hits.map { it.media })

        embeds = 0
        val indexed = VisualQueryEngine(source).search(query(VisualReference(media(2)) { embeds++; probe }))
        assertEquals(0, embeds)
        assertTrue(indexed.reusedStoredVector)
    }

    @Test fun versionFenceFailsClosed() {
        var embeds = 0
        val ref = VisualReference { embeds++; probe }
        val other = FakeSource("siglip-other:2", listOf(media(1) to at(0.9f)))
        assertEquals(VisualQueryStatus.MODEL_MISMATCH, VisualQueryEngine(other).search(query(ref)).status)
        assertEquals(0, embeds)

        val mixedRows = FakeSource(model, listOf(media(1) to at(0.9f)), rowModel = "siglip-other:2")
        val mixed = VisualQueryEngine(mixedRows).search(query(ref))
        assertEquals(VisualQueryStatus.MODEL_MISMATCH, mixed.status)
        assertTrue(mixed.hits.isEmpty())

        val wrongDim = FakeSource(model, listOf(media(1) to at(0.9f), media(2) to FloatArray(8) { 0.35f }))
        val dims = VisualQueryEngine(wrongDim).search(query(ref))
        assertEquals(VisualQueryStatus.MODEL_MISMATCH, dims.status)
        assertTrue(dims.hits.isEmpty())
    }

    @Test fun revokedBeforeBetweenAndAfterPagesShowsNothing() {
        val rows = (0 until VisualQueryEngine.PAGE_SIZE + 10).map { media(it) to at(0.8f, it + 1) }
        val before = FakeSource(model, rows)
        assertEquals(VisualQueryStatus.REVOKED, VisualQueryEngine(before) { false }.search(query(fresh())).status)
        assertEquals(0, before.pages)

        for (allowed in 1..3) {
            var checks = 0
            val source = FakeSource(model, rows)
            val result = VisualQueryEngine(source) { ++checks <= allowed }.search(query(fresh()))
            assertEquals("grant lost after $allowed checks", VisualQueryStatus.REVOKED, result.status)
            assertTrue(result.hits.isEmpty())
        }
        val ok = VisualQueryEngine(FakeSource(model, rows)).search(query(fresh()))
        assertEquals(rows.size, ok.scanned)
    }

    @Test fun epochSupersededBetweenPagesIsStaleNotPartialOk() {
        // VS-S2c (#481 receipt): page 1 is full, then a rescan publishes a new
        // snapshot and the old epoch answers an empty page.
        val rows = (0 until 2 * VisualQueryEngine.PAGE_SIZE + 10).map { media(it) to at(0.8f, it + 1) }
        for (servedPages in 1..2) {
            val source = FakeSource(model, rows, supersedeAfterPages = servedPages)
            val result = VisualQueryEngine(source).search(query(fresh()))
            assertEquals("superseded after $servedPages pages", VisualQueryStatus.STALE, result.status)
            assertTrue(result.hits.isEmpty())
            assertEquals(servedPages * VisualQueryEngine.PAGE_SIZE, result.scanned)
            assertEquals(1, source.currentChecks)
            assertEquals(VisualSearchUiState.NotIndexed, VisualSearchUiState.of(result))
        }
        // The same index, never superseded, is complete and OK.
        val stable = FakeSource(model, rows)
        val ok = VisualQueryEngine(stable).search(query(fresh()))
        assertEquals(VisualQueryStatus.OK, ok.status)
        assertEquals(rows.size, ok.scanned)
        assertEquals(1, stable.currentChecks)
        // A grant lost at the end still wins over the epoch check.
        var checks = 0
        val both = FakeSource(model, rows, supersedeAfterPages = 1)
        val revoked = VisualQueryEngine(both) { ++checks <= 3 }.search(query(fresh()))
        assertEquals(VisualQueryStatus.REVOKED, revoked.status)
        assertEquals(0, both.currentChecks)
    }

    @Test fun notIndexedWhenEmptyOrNoReferenceVector() {
        assertEquals(VisualQueryStatus.NOT_INDEXED,
            VisualQueryEngine(FakeSource(model, emptyList())).search(query(fresh())).status)
        val source = FakeSource(model, listOf(media(2) to at(0.9f)))
        assertEquals(VisualQueryStatus.NOT_INDEXED,
            VisualQueryEngine(source).search(query(VisualReference(media(1)))).status)
        assertEquals(VisualQueryStatus.NOT_INDEXED,
            VisualQueryEngine(source).search(query(VisualReference(media(1)) { null })).status)
    }

    @Test fun onlyMatchesOfReferenceGiveEmptyOkResult() {
        val result = VisualQueryEngine(FakeSource(model, listOf(media(1) to probe, media(2) to at(0.99f))))
            .search(query(VisualReference(media(1))))
        assertEquals(VisualQueryStatus.OK, result.status)
        assertTrue(result.hits.isEmpty())
        assertEquals(1, result.nearDuplicates)
        assertTrue(VisualSearchUiState.of(result) is VisualSearchUiState.Results)
    }

    @Test fun limitKeepsBestAndTiesByKey() {
        val rows = (0 until 40).map { media(39 - it) to at(if (it % 2 == 0) 0.80f else 0.76f, it + 1) }
        val result = VisualQueryEngine(FakeSource(model, rows)).search(query(fresh(), limit = 5))
        assertTrue(result.truncated)
        assertEquals(5, result.hits.size)
        assertTrue(result.hits.all { abs(it.semantic - 0.80f) < 1e-5f })
        val keys = result.hits.map { it.media.key }
        assertEquals(keys.sorted(), keys)
        assertEquals(media(1).key, keys.first())
    }

    @Test fun pagesAreStreamedNotLoadedAtOnce() {
        val rows = (0 until 3 * VisualQueryEngine.PAGE_SIZE + 1).map { media(it) to at(0.3f, it + 1) }
        val source = FakeSource(model, rows)
        val result = VisualQueryEngine(source).search(query(fresh()))
        assertEquals(4, source.pages)
        assertEquals(rows.size, result.scanned)
        assertTrue(result.hits.isEmpty())
    }

    @Test fun uiStateMapping() {
        assertEquals(VisualSearchUiState.NotIndexed,
            VisualSearchUiState.of(VisualQueryResult.empty(VisualQueryStatus.NOT_INDEXED)))
        assertEquals(VisualSearchUiState.VersionMismatch,
            VisualSearchUiState.of(VisualQueryResult.empty(VisualQueryStatus.MODEL_MISMATCH)))
        assertEquals(VisualSearchUiState.Revoked,
            VisualSearchUiState.of(VisualQueryResult.empty(VisualQueryStatus.REVOKED)))
        assertEquals(VisualSearchUiState.NotIndexed,
            VisualSearchUiState.of(VisualQueryResult.empty(VisualQueryStatus.STALE)))
    }

    @Test fun storedInt8VectorsStayWithinQuantizationTolerance() {
        val rng = Random(2026)
        var worst = 0f
        repeat(500) {
            val a = VisualQueryEngine.unit(FloatArray(768) { rng.nextFloat() * 2 - 1 })
            val b = VisualQueryEngine.unit(FloatArray(768) { a[it] + (rng.nextFloat() * 2 - 1) * rng.nextFloat() })
            val q = VisualVectorCodec.encode(b)
            val decoded = VisualVectorCodec.decode(q.bytes, q.scale, 768)
            worst = maxOf(worst, abs(VisualQueryEngine.dot(a, b) - VisualQueryEngine.dot(a, decoded)))
        }
        println("VS-S2a int8 worst cosine error: $worst")
        assertTrue("worst $worst", worst < VisualQueryBands.QUANTIZATION_TOLERANCE)
    }

    // --- pose emphasis ---

    /** A descriptor whose 24 bone directions (pose and mirror) all point along [axis]. */
    private fun pose(axis: Int) = FloatArray(PoseDescriptor.SIZE).also { d ->
        for (bone in 0 until PoseDescriptor.SIZE / 3) d[bone * 3 + axis] = 1f
    }

    private fun semantic(vararg cosines: Float): VisualQueryResult {
        val rows = cosines.mapIndexed { i, c -> media(i) to at(c, i + 1) }
        return VisualQueryEngine(FakeSource(model, rows)).search(query(fresh()))
    }

    @Test fun poseReranksTopAndKeepsSemanticBands() {
        val base = semantic(0.82f, 0.80f, 0.76f)
        assertEquals(listOf(0, 1, 2), base.hits.map { it.media.key.takeLast(4).toInt() })
        val poses = mapOf(media(0) to pose(1), media(1) to pose(0))
        val result = VisualQueryEngine(FakeSource(model, emptyList()))
            .emphasisePose(base, { pose(0) }, { poses[it] })
        assertEquals(VisualPoseState.APPLIED, result.pose)
        assertEquals(listOf(1, 0, 2), result.hits.map { it.media.key.takeLast(4).toInt() })
        val first = result.hits[0]
        assertEquals(0.80f + PoseRerank.WEIGHT * (1f - PoseRerank.POSE_PRIOR), first.score, 1e-5f)
        assertEquals(1f, first.pose!!, 1e-6f)
        assertEquals(VisualMatchBand.RELATED, first.band)
        assertEquals(0.80f, first.semantic, 1e-5f)
        // No body in a candidate counts as pose 0.
        assertNull(result.hits[2].pose)
        assertEquals(0.76f - PoseRerank.WEIGHT * PoseRerank.POSE_PRIOR, result.hits[2].score, 1e-5f)
    }

    @Test fun referenceWithoutBodyKeepsSemanticOrder() {
        val base = semantic(0.82f, 0.80f)
        var described = 0
        val result = VisualQueryEngine(FakeSource(model, emptyList()))
            .emphasisePose(base, { null }, { described++; pose(0) })
        assertEquals(VisualPoseState.NO_BODY, result.pose)
        assertEquals(base.hits, result.hits)
        assertEquals(0, described)
    }

    @Test fun failingPoseModelKeepsSemanticOrder() {
        val base = semantic(0.82f, 0.80f)
        val engine = VisualQueryEngine(FakeSource(model, emptyList()))
        val unavailable = engine.emphasisePose(base, { throw IllegalStateException("closed") }, { pose(0) })
        assertEquals(VisualPoseState.UNAVAILABLE, unavailable.pose)
        assertEquals(base.hits, unavailable.hits)

        val partial = engine.emphasisePose(base, { pose(0) },
            { if (it == media(0)) throw IllegalStateException("decode") else pose(0) })
        assertEquals(VisualPoseState.APPLIED, partial.pose)
        assertEquals(listOf(1, 0), partial.hits.map { it.media.key.takeLast(4).toInt() })
    }

    @Test fun poseDescribesOnlySemanticTop50() {
        val base = semantic(*FloatArray(60) { 0.93f - it * 0.003f })
        val described = ArrayList<VisualMedia>()
        val result = VisualQueryEngine(FakeSource(model, emptyList()))
            .emphasisePose(base, { pose(0) }, { described += it; pose(0) })
        assertEquals(PoseRerank.TOP_K, described.size)
        assertEquals(base.hits.take(PoseRerank.TOP_K).map { it.media }, described)
        assertEquals(base.hits.drop(PoseRerank.TOP_K), result.hits.drop(PoseRerank.TOP_K).map { it.copy(score = it.semantic) })
    }

    @Test fun poseRechecksGrant() {
        val base = semantic(0.82f, 0.80f)
        var checks = 0
        val result = VisualQueryEngine(FakeSource(model, emptyList())) { ++checks < 2 }
            .emphasisePose(base, { pose(0) }, { pose(0) })
        assertEquals(VisualQueryStatus.REVOKED, result.status)
        assertTrue(result.hits.isEmpty())
    }

    @Test fun poseInputIsDownscaled() {
        assertEquals(400 to 300, VisualPoseInput.targetSize(400, 300))
        assertEquals(768 to 576, VisualPoseInput.targetSize(4000, 3000))
        assertEquals(432 to 768, VisualPoseInput.targetSize(2250, 4000))
        assertEquals(768 to 1, VisualPoseInput.targetSize(20_000, 2))
    }
}
