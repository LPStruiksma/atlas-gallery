package nl.atlas.gallery.featuresearch.face

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.UnknownFaceBox
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** VS-F2b (#468) session logic on synthetic vectors only; no photos, faces or Android. */
class FaceSearchSessionTest {
    private val model = SFaceModel.EMBEDDER_VERSION
    private val dim = SFaceModel.DIMENSION
    private val rng = Random(4682)
    private val ada = PersonKey("native-ada")

    private fun gaussian(): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
    private fun unit(v: FloatArray) = SFaceModel.l2Normalize(v)
    private fun random() = unit(FloatArray(dim) { gaussian() })
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    /** A unit vector whose cosine to unit [base] is exactly [c]. */
    private fun at(base: FloatArray, c: Float): FloatArray {
        val r = random()
        val p = dot(r, base)
        val orth = unit(FloatArray(dim) { r[it] - p * base[it] })
        val s = sqrt(1f - c * c)
        return unit(FloatArray(dim) { c * base[it] + s * orth[it] })
    }

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun key(n: Int) = "atlas-face-media-v1:%064x".format(n)
    private fun uri(n: Int) = "content://media/external_primary/images/media/$n"
    private fun media(n: Int) = IndexedMedia(key(n), "rev$n")
    private fun box(n: Int) = IndexedFaceBox(0.1f * (n % 5), 0.1f, 0.1f * (n % 5) + 0.2f, 0.5f)
    private fun record(n: Int, video: Boolean = false) = MediaRecord(n.toLong(), uri(n),
        "synthetic-$n.jpg", "DCIM/Camera/", 1000L + n, n * 1000L, video)

    private val query = random()

    private class Row(val face: Int, val media: Int, val vector: FloatArray, val anchor: Int? = null,
                      val frameMs: Long? = null)

    private fun corpus(rows: List<Row>, facts: ClusterReviewFacts = ClusterReviewFacts.NONE,
                       version: String = model) =
        FaceSearchCorpus.Builder(version).apply {
            for (r in rows) add(id(r.face), media(r.media), box(r.face), r.anchor?.let { id(it) }, r.vector,
                r.frameMs)
        }.build(facts, clustersPublished = true)

    private inner class FakePort(var corpus: FaceSearchCorpus?) : FaceSearchPort {
        var epoch: String? = "epoch-1"
        var granted = true
        var source: QueryFaceSource? = null
        var capture: QueryFaceCapture? = null
        var onQuerySource: () -> Unit = {}
        val unauthorized = HashSet<Int>()
        val names = HashMap<PersonKey, String>()
        var onCorpus: () -> Unit = {}
        override fun liveEpoch() = epoch?.takeIf { granted }
        override fun scopeCurrent(epoch: String) = granted && epoch == this.epoch
        override fun corpus(epoch: String): FaceSearchCorpus? {
            onCorpus()
            return corpus.takeIf { scopeCurrent(epoch) }
        }
        override fun authorizedUri(epoch: String, media: IndexedMedia): String? {
            if (!scopeCurrent(epoch)) return null
            val n = media.revision.removePrefix("rev").toInt()
            return if (n in unauthorized || media != media(n)) null else uri(n)
        }
        override fun personName(epoch: String, person: PersonKey) = names[person]
        override fun queryCapture(epoch: String): QueryFaceCapture? = capture.takeIf { scopeCurrent(epoch) }
        override fun querySource(epoch: String, photo: MediaRecord): QueryFaceSource? {
            onQuerySource()
            return source.takeIf { scopeCurrent(epoch) }
        }
        override fun indexedFaceCount(epoch: String, photo: MediaRecord) = 0
    }

    private val gallery = (1..20).associate { uri(it) to record(it, video = it == 7) }

    private fun session(port: FaceSearchPort) = FaceSearchSession(port,
        records = { uris -> gallery.filterKeys { it in uris } },
        io = Dispatchers.Unconfined, cpu = Dispatchers.Unconfined)

    private fun queryFace(version: String = model) =
        QueryFace(query, version, UnknownFaceBox(0.2f, 0.2f, 0.4f, 0.5f))

    private fun FaceSearchSession.searchNow(face: QueryFace = queryFace()) =
        runBlocking { search(face) }.let { state.value }

    @Test fun rankedResultsGroupTiersAndNeverShowLookalikes() {
        val port = FakePort(corpus(listOf(
            Row(1, 1, at(query, 0.80f)),
            Row(2, 2, at(query, 0.62f)),
            Row(3, 3, at(query, 0.42f)),          // PROBABLE
            Row(4, 4, at(query, 0.31f)),          // look-alike: never shown
            Row(5, 5, at(query, 0.05f)),          // unrelated
            Row(6, 2, at(query, 0.55f)),          // second face on photo 2
            Row(7, 7, at(query, 0.90f)),          // a video row: left out
            Row(8, 8, at(query, 0.70f))           // outside the grant: left out
        )))
        port.unauthorized += 8
        val state = session(port).searchNow() as FaceSearchState.Results
        assertEquals(listOf(uri(1), uri(2), uri(3)), state.records.map { it.uri })
        assertEquals(listOf(FaceMatchTier.SAME_PERSON, FaceMatchTier.SAME_PERSON, FaceMatchTier.PROBABLE),
            state.hits.map { it.tier })
        assertEquals(listOf(uri(1), uri(2)), state.sure.map { it.record.uri })
        assertEquals(listOf(uri(3)), state.possible.map { it.record.uri })
        // Both matched faces of photo 2 are highlighted, best first.
        assertEquals(listOf(box(2), box(6)), state.boxesByUri[uri(2)])
        assertTrue(state.hits.none { it.record.uri == uri(4) || it.record.uri == uri(5) })
        assertNull("no confirmed person, no album offer", state.person)
        assertFalse(state.truncated)
    }

    @Test fun confirmedPersonOffersTheirAlbumOnlyWithAName() {
        val facts = ClusterReviewFacts(confirmed = mapOf(id(1) to ada, id(2) to ada))
        val rows = listOf(Row(1, 1, at(query, 0.9f)), Row(2, 2, at(query, 0.2f)),
            Row(3, 3, at(query, 0.1f)))
        val port = FakePort(corpus(rows, facts))
        val unnamed = session(port).searchNow() as FaceSearchState.Results
        assertNull("a person without a reviewed name gets no album button", unnamed.person)
        port.names[ada] = "Synthetic Ada"
        val named = session(port).searchNow() as FaceSearchState.Results
        assertEquals(ada, named.person)
        assertEquals("Synthetic Ada", named.personName)
        // The confirmed face is found although its vector alone is far away.
        assertEquals(setOf(uri(1), uri(2)), named.records.map { it.uri }.toSet())
    }

    @Test fun cannotLinkFactsExcludeAFaceWhateverItsSimilarity() {
        val facts = ClusterReviewFacts(cannotLink = setOf(FacePair.of(id(1), id(2))))
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.95f)), Row(2, 2, at(query, 0.9f))), facts))
        val state = session(port).searchNow() as FaceSearchState.Results
        assertEquals(listOf(uri(1)), state.records.map { it.uri })
    }

    @Test fun typedStatesForNoIndexEmptyMismatchAndFailure() {
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.1f)))))
        assertTrue(session(port).searchNow() is FaceSearchState.Empty)
        assertEquals(FaceSearchState.VersionMismatch,
            session(port).searchNow(queryFace("sface-some-other-version")))
        port.corpus = corpus(emptyList())
        assertEquals(FaceSearchState.NotIndexed, session(port).searchNow())
        port.epoch = null
        assertEquals("not opted in", FaceSearchState.NotIndexed, session(port).searchNow())
        port.epoch = "epoch-1"
        port.onCorpus = { error("corrupt index") }
        assertEquals(FaceSearchState.Failed, session(port).searchNow())
    }

    @Test fun revokeOrANewEpochDropsResults() {
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.9f)))))
        val session = session(port)
        assertTrue(session.searchNow() is FaceSearchState.Results)
        assertTrue(session.revalidate())
        assertTrue(session.state.value is FaceSearchState.Results)
        port.epoch = "epoch-2" // rescan or regrant: a new single-use epoch
        assertFalse(session.revalidate())
        assertEquals(FaceSearchState.Revoked, session.state.value)

        port.epoch = "epoch-1"
        assertTrue(session.searchNow() is FaceSearchState.Results)
        port.granted = false
        assertFalse(session.revalidate())
        assertEquals(FaceSearchState.Revoked, session.state.value)
        assertEquals("a revoked scope reads nothing", FaceSearchState.NotIndexed, session.searchNow())
    }

    @Test fun revokeDuringTheSearchNeverPublishesResults() {
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.9f)))))
        port.onCorpus = { port.granted = false }
        assertEquals(FaceSearchState.Revoked, session(port).searchNow())
    }

    @Test fun aClosedRouteCannotReceiveALateResult() {
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.9f)))))
        val session = session(port)
        port.onCorpus = { session.clear() }
        assertEquals(FaceSearchState.Idle, session.searchNow())
    }

    @Test fun mediaLimitTruncatesTheGrid() {
        val rows = (1..6).map { Row(it, it, at(query, 0.9f - it * 0.01f)) }
        val state = runBlocking {
            session(FakePort(corpus(rows))).let { s -> s.search(queryFace(), mediaLimit = 3); s.state.value }
        } as FaceSearchState.Results
        assertEquals(3, state.hits.size)
        assertTrue(state.truncated)
    }

    // #478 follow-up: video moments get their own bounded share of the limit.
    @Test fun matchingVideoMomentsNeverCrowdPhotosOutOfTheLimit() {
        // Video items 30..39 (ten moments each) all match better than any photo.
        val videos = (30..39).flatMap { v ->
            (0 until 10).map { k -> Row(v * 100 + k, v, at(query, 0.95f - k * 0.001f), frameMs = k * 1000L) }
        }
        val photos = (1..6).map { Row(it, it, at(query, 0.80f - it * 0.01f)) }
        val c = corpus(videos + photos)

        val split = FaceQueryEngine.search(c, FaceQuery(model, listOf(query), mediaLimit = 4, videoMediaLimit = 3))
        val photoHits = split.hits.filterNot { it.isVideo }
        val videoHits = split.hits.filter { it.isVideo }
        assertEquals("every photo slot holds a photo", 4, photoHits.size)
        assertEquals(listOf(1, 2, 3, 4).map { key(it) }, photoHits.map { it.media.key })
        assertEquals("one hit per video, at most the video share", 3, videoHits.size)
        assertEquals(3, videoHits.map { it.media.key }.distinct().size)
        assertTrue(videoHits.all { it.bestFrameMs == 0L })
        assertTrue(split.truncated)
        assertTrue(split.videosTruncated)
        // Ranked order is kept across both kinds: the videos matched best.
        assertTrue(split.hits.take(3).all { it.isVideo })

        val photosOnly = FaceQueryEngine.search(c, FaceQuery(model, listOf(query), mediaLimit = 6, videoMediaLimit = 0))
        assertEquals(6, photosOnly.hits.size)
        assertTrue(photosOnly.hits.none { it.isVideo })
        assertFalse("all six photos fit", photosOnly.truncated)
    }

    @Test fun theSessionKeepsAllPhotoSlotsForPhotos() {
        val videos = (30..39).map { v -> Row(v, v, at(query, 0.95f), frameMs = 0L) }
        val photos = (1..3).map { Row(it, it, at(query, 0.80f - it * 0.01f)) }
        val state = runBlocking {
            session(FakePort(corpus(videos + photos))).let { s -> s.search(queryFace(), mediaLimit = 3); s.state.value }
        } as FaceSearchState.Results
        assertEquals(listOf(uri(1), uri(2), uri(3)), state.records.map { it.uri })
        assertFalse("ten matching videos do not truncate a photo grid", state.truncated)
    }

    @Test fun theDefaultLimitsAreSeparate() {
        val q = FaceQuery(model, listOf(query))
        assertEquals(FaceQuery.DEFAULT_MEDIA_LIMIT, q.mediaLimit)
        assertEquals(FaceQuery.DEFAULT_VIDEO_MEDIA_LIMIT, q.videoMediaLimit)
        assertTrue(runCatching { FaceQuery(model, listOf(query), videoMediaLimit = -1) }.isFailure)
    }

    // #478 follow-up: a reference that can't be searched ends in a typed state.
    @Test fun aReferenceThatCannotBeSearchedYetIsTyped() {
        val port = FakePort(corpus(listOf(Row(1, 1, query))))
        val photo = record(1)
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED),
            prepareFaceReference(port, null, photo))
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_IN_CATALOGUE),
            prepareFaceReference(port, "epoch-1", photo))
        port.source = QueryFaceSource(FaceMediaKey(key(1), "rev1"), "epoch-1")
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.MODELS_NOT_READY),
            prepareFaceReference(port, "epoch-1", photo))
        assertEquals("a replaced scope", FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED),
            prepareFaceReference(port, "epoch-0", photo))
        port.granted = false
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED),
            prepareFaceReference(port, "epoch-1", photo))
        port.granted = true
        port.onQuerySource = { throw IllegalStateException("store broke") }
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.FAILED),
            prepareFaceReference(port, "epoch-1", photo))
        port.onQuerySource = { throw SecurityException("grant gone") }
        assertEquals(FaceReferencePreparation.Unavailable(FaceReferenceProblem.NOT_INDEXED),
            prepareFaceReference(port, "epoch-1", photo))
    }

    /** A memory failure must not escape the session or strand the searching spinner. */
    @Test fun corpusOutOfMemoryIsRetryable() {
        val port = FakePort(corpus(listOf(Row(1, 1, at(query, 0.90f)))))
        val search = session(port)
        port.onCorpus = { throw OutOfMemoryError("synthetic constrained heap") }
        assertEquals(FaceSearchState.Unavailable, search.searchNow())
        port.onCorpus = {}
        assertTrue("the user may try again", search.searchNow() is FaceSearchState.Results)
    }
}
