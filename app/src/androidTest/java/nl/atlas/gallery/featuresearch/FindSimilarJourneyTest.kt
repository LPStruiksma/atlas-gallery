package nl.atlas.gallery.featuresearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import nl.atlas.gallery.GalleryViewer
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.ViewerFindSimilar
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featuresearch.face.FaceSearchPort
import nl.atlas.gallery.featuresearch.face.FaceSearchRoute
import nl.atlas.gallery.featuresearch.face.FaceSearchSession
import nl.atlas.gallery.featuresearch.face.FaceSearchStep
import nl.atlas.gallery.featuresearch.face.QueryFaceCapture
import nl.atlas.gallery.featuresearch.face.QueryFaceSource
import nl.atlas.gallery.featuresearch.visual.FindSimilarPort
import nl.atlas.gallery.featuresearch.visual.FindSimilarReference
import nl.atlas.gallery.featuresearch.visual.FindSimilarRoute
import nl.atlas.gallery.featuresearch.visual.FindSimilarSession
import nl.atlas.gallery.featuresearch.visual.FindSimilarStep
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * VS-S2b (#468) on API35: GalleryViewer's "Find similar" → the Find similar
 * route over the merged VisualQueryEngine and VisualSearchResultsScreen →
 * open a video hit at its moment, plus the typed "can't be searched yet"
 * states of Find similar and of search by face. Vectors are synthetic and
 * every media row is invented: no real photo, video, face or person.
 */
@RunWith(AndroidJUnit4::class)
class FindSimilarJourneyTest {
    // v2: the route resumes coroutines after withContext(IO) on the UI thread.
    @get:Rule val compose = createComposeRule()

    private val model = "siglip-journey:1"
    private val dim = 16
    private val probe = FloatArray(dim).also { it[0] = 1f }
    private fun at(c: Float, axis: Int): FloatArray =
        FloatArray(dim).also { it[0] = c; it[axis] = sqrt(1f - c * c) }

    private fun key(n: Int) = "atlas-face-media-v1:%064x".format(n)
    private fun isVideo(n: Int) = n >= 30
    private fun uri(n: Int) =
        "content://media/external_primary/${if (isVideo(n)) "video" else "images"}/media/$n"
    private fun media(n: Int, frame: Long? = null) = VisualMedia(key(n), "rev$n", frame)
    private fun record(n: Int) = MediaRecord(id = n.toLong(), uri = uri(n),
        name = "synthetic-$n.${if (isVideo(n)) "mp4" else "png"}", relativePath = "DCIM/Camera/",
        sizeBytes = 1000L + n, takenAtMillis = n * 1000L, isVideo = isVideo(n),
        modifiedAtSeconds = 1_700_000_000L, volumeName = "external_primary",
        providerVersion = "synthetic-provider-1", generationModified = 10L + n,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0,
        durationMillis = if (isVideo(n)) 10_000L else 0L)

    private val gallery = listOf(1, 2, 3, 30).map(::record)

    private class Source(override val modelVersion: String, rows: List<Pair<VisualMedia, FloatArray>>) :
        VisualVectorSource {
        private val sorted = rows.sortedWith(compareBy({ it.first.key }, { it.first.frameMs ?: -1L }))
        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> =
            sorted.filter { (m, _) ->
                after == null || m.key > after.mediaKey ||
                    (m.key == after.mediaKey && (m.frameMs ?: -1L) > (after.frameMs ?: -1L))
            }.take(limit).map { (m, v) ->
                VisualIndexedVector(m, modelVersion, v, VisualVectorCursor("e1", modelVersion, m.key, m.frameMs))
            }
        override fun lookup(media: VisualMedia): FloatArray? = sorted.firstOrNull { it.first == media }?.second
    }

    private inner class Port(
        val source: VisualVectorSource?,
        val indexed: Boolean = true,
        val embedding: FloatArray? = probe
    ) : FindSimilarPort {
        override val modelVersion = model
        override fun liveScope() = "scope-1"
        override fun scopeCurrent(scope: String) = scope == "scope-1"
        override fun source(scope: String) = source
        override fun media(scope: String, record: MediaRecord) =
            if (indexed) media(record.id.toInt()) else null
        override fun authorizedUri(scope: String, media: VisualMedia) =
            uri(media.key.takeLast(64).toInt(16))
        override fun embed(reference: FindSimilarReference) = embedding
        override fun referencePose(reference: FindSimilarReference): FloatArray? = null
        override fun candidatePose(media: VisualMedia, uri: String): FloatArray? = null
    }

    private val index = Source(model, listOf(
        media(1) to probe,                    // the reference
        media(2) to at(0.90f, 2),
        media(3) to at(0.78f, 3),
        media(30, 1000L) to at(0.80f, 4),
        media(30, 4000L) to at(0.87f, 5)      // the video's best moment
    ))

    private var openedStarts: Map<String, Long> = emptyMap()

    /** The host wiring MainActivity uses, reduced to the viewer and the route. */
    @Composable
    private fun Host(session: FindSimilarSession) {
        var step by remember { mutableStateOf<FindSimilarStep?>(null) }
        var viewer by remember { mutableStateOf<Pair<List<MediaRecord>, String>?>(gallery to uri(1)) }
        var starts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        val scope = rememberCoroutineScope()
        val open = viewer
        if (open != null) {
            GalleryViewer(open.first, open.second,
                findSimilar = if (step != null) null else ViewerFindSimilar { record, frameMs ->
                    viewer = null
                    step = FindSimilarStep.Results
                    scope.launch { session.search(FindSimilarReference(record, frameMs)) }
                },
                videoStartPositions = starts,
                onBack = { viewer = null })
            return
        }
        val route = step ?: return
        FindSimilarRoute(session, 0L, route, gallery,
            onStep = { step = it },
            onOpen = { items, record, positions ->
                starts = positions
                openedStarts = positions
                viewer = items to record.uri
            },
            onOpenSettings = {},
            onClose = { step = null; session.clear() },
            thumbnail = { null })
    }

    private fun session(port: FindSimilarPort) = FindSimilarSession(port,
        records = { uris -> gallery.filter { it.uri in uris }.associateBy { it.uri } })

    @Test fun findSimilarFromTheViewerOpensAVideoHitAtItsMoment() {
        val session = session(Port(index))
        compose.setContent { Host(session) }
        waitFor(hasTestTag("atlas-viewer-find-similar"))
        compose.onNodeWithTag("atlas-viewer-find-similar").performClick()

        waitFor(hasTestTag("atlas-visual-search-grid"))
        // Photo 2, then the video at its best moment, then photo 3; never the reference.
        compose.onNodeWithTag("atlas-visual-hit-0-strong", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("atlas-visual-hit-1-strong", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("atlas-visual-hit-2-related", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("atlas-visual-hit-3").assertDoesNotExist()

        // The pose switch: a reference without a body keeps the order, with a hint.
        compose.onNodeWithTag("atlas-visual-search-pose-toggle").performClick()
        waitFor(hasTestTag("atlas-visual-search-pose-no-body"))

        compose.onNodeWithTag("atlas-visual-hit-1").performClick()
        waitFor(hasContentDescription("Video viewer: synthetic-30.mp4"))
        assertEquals(mapOf(uri(30) to 4000L), openedStarts)
    }

    @Test fun findSimilarWithTheIndexOffEndsInATypedState() {
        val session = session(Port(source = null))
        compose.setContent { Host(session) }
        waitFor(hasTestTag("atlas-viewer-find-similar"))
        compose.onNodeWithTag("atlas-viewer-find-similar").performClick()
        waitFor(hasTestTag("atlas-find-similar-index-off"))
        compose.onNodeWithTag("atlas-visual-search-loading").assertDoesNotExist()
        compose.onNodeWithTag("atlas-find-similar-open-people").assertExists()
    }

    @Test fun aNonIndexedUnreadableReferenceEndsInATypedState() {
        val session = session(Port(index, indexed = false, embedding = null))
        compose.setContent { Host(session) }
        waitFor(hasTestTag("atlas-viewer-find-similar"))
        compose.onNodeWithTag("atlas-viewer-find-similar").performClick()
        waitFor(hasTestTag("atlas-find-similar-unreadable"))
        compose.onNodeWithTag("atlas-visual-search-loading").assertDoesNotExist()
    }

    @Test fun aFaceReferenceOutsideTheCatalogueEndsInATypedState() {
        val port = object : FaceSearchPort {
            override fun liveEpoch() = "epoch-1"
            override fun scopeCurrent(epoch: String) = epoch == "epoch-1"
            override fun corpus(epoch: String): FaceSearchCorpus? = null
            override fun authorizedUri(epoch: String, media: IndexedMedia): String? = null
            override fun personName(epoch: String, person: PersonKey): String? = null
            override fun queryCapture(epoch: String): QueryFaceCapture? = null
            // Added after the last People scan: not in face discovery's catalogue.
            override fun querySource(epoch: String, photo: MediaRecord): QueryFaceSource? = null
            override fun indexedFaceCount(epoch: String, photo: MediaRecord) = 0
        }
        val session = FaceSearchSession(port, records = { emptyMap() })
        compose.setContent {
            FaceSearchRoute(port, 0L, session, FaceSearchStep.PickFace(gallery[0], fromChooser = false),
                gallery, onStep = {}, onOpen = { _, _ -> }, onOpenAlbum = { _, _ -> },
                onOpenPeople = {}, onClose = {})
        }
        waitFor(hasTestTag("atlas-face-reference-not-in-catalogue"))
        compose.onNodeWithTag("atlas-face-search-busy").assertDoesNotExist()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(20_000L) {
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for ${matcher.description}. Screen:\n$tree", e)
        }
    }
}
