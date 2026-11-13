package nl.atlas.gallery.featuresearch.visual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurevisual.pose.PoseDescriptor
import nl.atlas.gallery.featurevisual.search.VisualMatchBand
import nl.atlas.gallery.featurevisual.search.VisualPoseState
import nl.atlas.gallery.featurevisual.search.VisualSearchUiState
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** VS-S2b (#468) Find similar session on synthetic vectors only; no photos, models or Android. */
class FindSimilarSessionTest {
    private val model = "siglip-test:1"
    private val dim = 16
    private val probe = FloatArray(dim).also { it[0] = 1f }

    /** A unit vector whose cosine to [probe] is exactly [c], orthogonal part on axis [axis]. */
    private fun at(c: Float, axis: Int = 1): FloatArray =
        FloatArray(dim).also { it[0] = c; it[1 + (axis - 1) % (dim - 1)] = sqrt(1f - c * c) }

    private fun key(n: Int) = "atlas-face-media-v1:%064x".format(n)
    private fun uri(n: Int, video: Boolean = false) =
        "content://media/external_primary/${if (video) "video" else "images"}/media/$n"
    private fun media(n: Int, frame: Long? = null) = VisualMedia(key(n), "rev$n", frame)
    private fun record(n: Int, video: Boolean = false) = MediaRecord(n.toLong(), uri(n, video),
        "synthetic-$n.${if (video) "mp4" else "png"}", "DCIM/Camera/", 1000L + n, n * 1000L, video)

    /** Videos are items 30..39; everything else is a photo. */
    private fun isVideo(n: Int) = n in 30..39
    private val gallery = (1..40).associate { uri(it, isVideo(it)) to record(it, isVideo(it)) }

    /** Sorted keyset pages like VisualIndexStore.loadActiveVectors. */
    private class FakeSource(
        override val modelVersion: String,
        rows: List<Pair<VisualMedia, FloatArray>>,
        var current: () -> Boolean = { true }
    ) : VisualVectorSource {
        val sorted = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        var lookups = 0
        override fun isCurrent() = current()
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> =
            sorted.filter { (m, _) ->
                after == null || m.key > after.mediaKey ||
                    (m.key == after.mediaKey && (m.frameMs ?: -1L) > (after.frameMs ?: -1L))
            }.take(limit).map { (m, v) ->
                VisualIndexedVector(m, modelVersion, v, VisualVectorCursor("e1", modelVersion, m.key, m.frameMs))
            }
        override fun lookup(media: VisualMedia): FloatArray? {
            lookups++
            return sorted.firstOrNull { it.first == media }?.second
        }
    }

    private inner class FakePort(var sources: () -> VisualVectorSource?) : FindSimilarPort {
        override var modelVersion = model
        var scope: String? = "epoch-1"
        var granted = true
        val unauthorized = HashSet<Int>()
        var embedded = 0
        var embedding: () -> FloatArray? = { probe }
        var releases = 0
        var referencePose: FloatArray? = null
        val poses = HashMap<Int, FloatArray>()
        var onScopeCheck: () -> Unit = {}
        private fun n(key: String) = key.takeLast(64).toInt(16)
        override fun liveScope() = scope?.takeIf { granted }
        override fun scopeCurrent(scope: String): Boolean {
            onScopeCheck()
            return granted && scope == this.scope
        }
        override fun source(scope: String) = if (scopeCurrent(scope)) sources() else null
        override fun media(scope: String, record: MediaRecord): VisualMedia? =
            if (!scopeCurrent(scope)) null else media(record.id.toInt())
        override fun authorizedUri(scope: String, media: VisualMedia): String? {
            if (!scopeCurrent(scope)) return null
            val k = n(media.key)
            return if (k in unauthorized || media.revision != "rev$k") null else uri(k, isVideo(k))
        }
        override fun embed(reference: FindSimilarReference): FloatArray? {
            embedded++
            return embedding()
        }
        override fun referencePose(reference: FindSimilarReference) = referencePose
        override fun candidatePose(media: VisualMedia, uri: String) = poses[n(media.key)]
        override fun release() { releases++ }
    }

    private fun session(port: FindSimilarPort) = FindSimilarSession(port,
        records = { uris -> gallery.filterKeys { it in uris } }, worker = Dispatchers.Unconfined)

    private fun FindSimilarSession.searchNow(reference: FindSimilarReference) =
        runBlocking { search(reference) }.let { state.value }

    private fun photoRef(n: Int = 99) = FindSimilarReference(record(n))

    @Test fun rankedHitsCollapseVideosToTheirBestMomentAndSkipTheReference() {
        val source = FakeSource(model, listOf(
            media(1) to at(0.80f, 1),
            media(2) to at(0.90f, 2),
            media(3) to at(0.60f, 3),            // below the floor
            media(4) to at(0.86f, 4),            // outside the grant: left out
            media(5) to probe,                   // the reference itself (stored vector)
            media(30, 1000L) to at(0.78f, 5),
            media(30, 4000L) to at(0.88f, 6),    // the video's best moment
            media(31, 0L) to at(0.76f, 7),
            media(6, 2000L) to at(0.89f, 8)      // a keyframe of a photo row: never shown
        ))
        val port = FakePort({ source })
        port.unauthorized += 4
        val state = session(port).searchNow(photoRef(5)) as FindSimilarState.Results
        assertEquals(listOf(uri(2), uri(30, true), uri(1), uri(31, true)), state.records.map { it.uri })
        assertEquals(listOf(null, 4000L, null, 0L), state.hits.map { it.frameMs })
        assertEquals(mapOf(uri(30, true) to 4000L, uri(31, true) to 0L), state.startPositions)
        assertEquals(VisualMatchBand.STRONG, state.hits[0].hit.band)
        // The reference's stored vector was reused: no decode, no model.
        assertEquals(0, port.embedded)
        assertTrue(state.semantic.reusedStoredVector)
        assertEquals(state.hits.map { it.hit }, state.semantic.hits)
        assertTrue(state.ui() is VisualSearchUiState.Results)
        assertEquals(state.hits[1], state.hitFor(media(30, 4000L)))
    }

    @Test fun aVideoFrameReferenceEmbedsTheFrameOnScreen() {
        val source = FakeSource(model, listOf(media(1) to at(0.9f, 1), media(30, 0L) to at(0.99f, 2),
            media(30, 9000L) to at(0.85f, 3)))
        val port = FakePort({ source })
        val frames = ArrayList<Long?>()
        val seen = object : FindSimilarPort by port {
            override fun embed(reference: FindSimilarReference): FloatArray? {
                frames += reference.frameMs
                return port.embed(reference)
            }
        }
        val state = session(seen).searchNow(FindSimilarReference(record(30, video = true), 5000L))
            as FindSimilarState.Results
        assertEquals(listOf(5000L), frames)
        // Its own other moments are not "similar photos".
        assertEquals(listOf(uri(1)), state.records.map { it.uri })
    }

    @Test fun indexOffOrDiscoveryOffIsATypedState() {
        val off = FakePort({ null })
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_OFF),
            session(off).searchNow(photoRef()))
        val noScope = FakePort({ FakeSource(model, listOf(media(1) to probe)) }).apply { scope = null }
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_OFF),
            session(noScope).searchNow(photoRef()))
        assertNull(FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_OFF).ui())
    }

    @Test fun anEmptyIndexIsNotReadyNotEmpty() {
        val port = FakePort({ FakeSource(model, emptyList()) })
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_NOT_READY),
            session(port).searchNow(photoRef()))
        assertEquals(1, port.embedded)
    }

    @Test fun anUnreadableReferenceIsATypedStateNeverASpinner() {
        val source = FakeSource(model, listOf(media(1) to at(0.9f)))
        val port = FakePort({ source }).apply { embedding = { null } }
        // Item 99 is not indexed: the engine must embed it, and the decode fails.
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.REFERENCE_UNREADABLE),
            session(port).searchNow(photoRef(99)))
        port.embedding = { throw IllegalStateException("decoder broke") }
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.REFERENCE_UNREADABLE),
            session(port).searchNow(photoRef(99)))
    }

    @Test fun aSupersededSnapshotIsQueriedOnceMore() {
        var calls = 0
        val port = FakePort({
            calls++
            val stale = calls == 1
            FakeSource(model, listOf(media(1) to at(0.9f)), current = { !stale })
        })
        val state = session(port).searchNow(photoRef()) as FindSimilarState.Results
        assertEquals(2, calls)
        assertEquals(listOf(uri(1)), state.records.map { it.uri })

        val alwaysStale = FakePort({ FakeSource(model, listOf(media(1) to at(0.9f)), current = { false }) })
        assertEquals(FindSimilarState.NotSearchable(FindSimilarProblem.INDEX_NOT_READY),
            session(alwaysStale).searchNow(photoRef()))
    }

    @Test fun versionMismatchFailsClosed() {
        val port = FakePort({ FakeSource("siglip-other:2", listOf(media(1) to probe)) })
        assertEquals(FindSimilarState.VersionMismatch, session(port).searchNow(photoRef()))
        assertEquals(VisualSearchUiState.VersionMismatch, FindSimilarState.VersionMismatch.ui())
    }

    @Test fun revokeDuringTheQueryOrAfterDropsEverything() {
        val port = FakePort({ FakeSource(model, listOf(media(1) to at(0.9f))) })
        var checks = 0
        port.onScopeCheck = { if (++checks > 4) port.granted = false }
        assertEquals(FindSimilarState.Revoked, session(port).searchNow(photoRef()))

        val later = FakePort({ FakeSource(model, listOf(media(1) to at(0.9f))) })
        val s = session(later)
        assertTrue(s.searchNow(photoRef()) is FindSimilarState.Results)
        assertTrue(s.revalidate())
        later.scope = "epoch-2"
        assertFalse(s.revalidate())
        assertEquals(FindSimilarState.Revoked, s.state.value)
    }

    @Test fun aSecurityExceptionIsRevokedAndOtherErrorsFail() {
        val revoked = FakePort({ throw SecurityException("grant gone") })
        assertEquals(FindSimilarState.Revoked, session(revoked).searchNow(photoRef()))
        val broken = FakePort({ throw IllegalStateException("corrupt index") })
        assertEquals(FindSimilarState.Failed, session(broken).searchNow(photoRef()))
    }

    @Test fun poseEmphasisReordersAndOffRestoresTheSemanticOrder() {
        val source = FakeSource(model, listOf(media(1) to at(0.90f, 1), media(2) to at(0.89f, 2)))
        val port = FakePort({ source })
        // Every bone along +x: item 2 has the query's pose (similarity 1), item 1 none (0).
        val body = FloatArray(PoseDescriptor.SIZE) { if (it % 3 == 0) 1f else 0f }
        port.referencePose = body
        port.poses[2] = body
        port.poses[1] = FloatArray(PoseDescriptor.SIZE)
        val s = session(port)
        val semantic = s.searchNow(photoRef()) as FindSimilarState.Results
        assertEquals(listOf(uri(1), uri(2)), semantic.records.map { it.uri })

        runBlocking { s.setPose(true) }
        val posed = s.state.value as FindSimilarState.Results
        assertTrue(posed.poseEnabled)
        assertEquals(VisualPoseState.APPLIED, posed.shown.pose)
        assertFalse(posed.poseRunning)
        assertEquals(listOf(uri(2), uri(1)), posed.records.map { it.uri })
        assertEquals(posed.shown.hits, posed.hits.map { it.hit })

        runBlocking { s.setPose(false) }
        val back = s.state.value as FindSimilarState.Results
        assertFalse(back.poseEnabled)
        assertEquals(listOf(uri(1), uri(2)), back.records.map { it.uri })
    }

    @Test fun aReferenceWithoutABodyKeepsTheOrderWithAHint() {
        val port = FakePort({ FakeSource(model, listOf(media(1) to at(0.90f, 1), media(2) to at(0.89f, 2))) })
        val s = session(port)
        s.searchNow(photoRef())
        runBlocking { s.setPose(true) }
        val state = s.state.value as FindSimilarState.Results
        assertEquals(VisualPoseState.NO_BODY, state.shown.pose)
        assertEquals(listOf(uri(1), uri(2)), state.records.map { it.uri })
    }

    @Test fun clearWinsOverALateSearchAndFreesTheModels() {
        lateinit var s: FindSimilarSession
        val port = FakePort({ FakeSource(model, listOf(media(1) to at(0.9f))) })
        port.embedding = { s.clear(); probe }
        s = session(port)
        runBlocking { s.search(photoRef(99)) }
        assertEquals(FindSimilarState.Idle, s.state.value)
        assertEquals(1, port.releases)
    }

    @Test fun aFrameTimeNeedsAVideo() {
        assertTrue(runCatching { FindSimilarReference(record(1), 10L) }.isFailure)
        assertTrue(runCatching { FindSimilarReference(record(30, video = true), -1L) }.isFailure)
    }
}
