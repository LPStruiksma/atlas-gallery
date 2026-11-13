package nl.atlas.gallery.featuresearch

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.atlas.gallery.GalleryViewer
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.NativePersonAlbumRequest
import nl.atlas.gallery.NativePersonAlbumRoute
import nl.atlas.gallery.PeopleDiscoveryHost
import nl.atlas.gallery.PeoplePowerState
import nl.atlas.gallery.PeopleScanScheduling
import nl.atlas.gallery.ViewerFaceSearch
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featuresearch.face.FaceSearchRoute
import nl.atlas.gallery.featuresearch.face.FaceSearchSession
import nl.atlas.gallery.featuresearch.face.FaceSearchState
import nl.atlas.gallery.featuresearch.face.FaceSearchStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-F2b (#468) end to end on API35: the bundled YuNet + SFace models, a real
 * app-private FaceIndexStore, the real PeopleDiscoveryHost, the search-by-face
 * route, GalleryViewer and the native person album. Photos are
 * [SyntheticFace] drawings (or a blank frame) behind invented MediaStore rows:
 * no real photo, face or person. WorkManager is replaced by a recorder and the
 * test runs each worker batch itself, as PeopleDiscoveryHostTest does.
 */
@RunWith(AndroidJUnit4::class)
class FaceSearchJourneyTest {
    // v2: the route resumes coroutines after withContext(IO) on the UI thread.
    @get:Rule val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "face-search-journey-" + UUID.randomUUID())
    private lateinit var store: FaceIndexStore
    @Volatile private var granted = true
    private val decoded = AtomicInteger()

    private class Scheduler : PeopleScanScheduling {
        val starts = AtomicInteger()
        override fun start() { starts.incrementAndGet() }
        override fun next() = Unit
        override fun later() = Unit
        override fun cancel() = Unit
    }
    private val scheduler = Scheduler()

    /** Photos 1..5 hold the same drawn face at different places; photo 6 has none. */
    private val blank = 6L
    private val records = (1L..6L).map { id ->
        MediaRecord(id = id, uri = "content://media/external_primary/images/media/$id",
            name = "synthetic-$id.png", relativePath = "DCIM/Camera/", sizeBytes = 1000 + id,
            takenAtMillis = id * 1000L, isVideo = false, modifiedAtSeconds = 1_700_000_000L,
            volumeName = "external_primary", providerVersion = "synthetic-provider-1",
            generationModified = 10 + id, widthPixels = 480, heightPixels = 480,
            orientationDegrees = 0)
    }
    // Exactly the production projection (rememberPeopleDiscovery).
    private val inventory = records.map { it.toFaceVisibleMedia(editorRevision = 0L) }

    private fun bitmap(id: Long): Bitmap =
        if (id == blank) Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(70, 90, 120)) }
        else SyntheticFace.bitmap(SyntheticFace.Spec(480, 480,
            0.38f + 0.04f * id, 0.48f + 0.01f * id, 0.85f + 0.02f * id))

    private fun host(): PeopleDiscoveryHost {
        val file = File(dir.apply { mkdirs() }, "faces.db")
        val context: Context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        store = FaceIndexStore(context)
        return PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { granted },
            inferenceFactory = {
                // The drawn face scores ~0.91; production keeps YuNet's 0.9 default.
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    // Same gate as the real reader: only a currently authorized URI.
                    val uri = directory.authorizedUri(lease.media)
                    decoded.incrementAndGet()
                    AndroidBitmapFaceFrame(bitmap(uri.substringAfterLast('/').toLong()))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = scheduler
        )
    }

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        if (::store.isInitialized) store.close()
        dir.deleteRecursively()
    }

    private fun drain() {
        var guard = 0
        while (runBlocking { runPeopleScanBatch(target) { false } }!!.stop !=
            FaceBatchStop.NO_PENDING && guard++ < 20) Unit
    }

    /** A timeout names what was awaited and dumps the screen, so CI logs say why. */
    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $label. Screen:\n$tree", e)
        }
    }
    private fun waitForTag(tag: String) = waitFor("tag '$tag'") {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * #538 CI: the query picker can legitimately return Busy while the shared
     * YuNet/SFace reservation is occupied. Exercise its actual retry action,
     * but never turn Empty, Failure or Unavailable into a passing test.
     * A persistently busy picker is still a regression, not a skipped assertion.
     */
    private fun waitForPickerConfirmation() {
        val terminal = listOf("atlas-query-face-confirm", "atlas-query-face-busy",
            "atlas-query-face-empty", "atlas-query-face-failure", "atlas-query-face-unavailable")
        repeat(3) { attempt ->
            waitFor("terminal face-picker state") {
                terminal.any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
            }
            if (compose.onAllNodesWithTag("atlas-query-face-confirm")
                    .fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("atlas-query-face-confirm").assertIsEnabled()
                return
            }
            if (compose.onAllNodesWithTag("atlas-query-face-busy")
                    .fetchSemanticsNodes().isNotEmpty() && attempt < 2) {
                compose.onNodeWithText("Try again").performClick()
                compose.waitForIdle()
            } else {
                // CI logs keep only the first line: name the state there.
                val seen = terminal.filter {
                    compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
                }
                val tree = compose.onRoot().printToString()
                throw AssertionError("Face picker did not reach a confirmable face " +
                    "(attempt ${attempt + 1}, state $seen). Screen:\n$tree")
            }
        }
        throw AssertionError("Face picker exhausted bounded busy retries")
    }

    /** Opt in, scan every photo, and name the group holding photo 1's face. */
    private fun indexAndName(host: PeopleDiscoveryHost, grant: String): PersonKey {
        host.start(grant, inventory)
        assertEquals(1, scheduler.starts.get())
        drain()
        assertEquals(6, decoded.get())
        val epoch = host.displayEpoch(grant)!!
        val firstKey = inventory[0].toDurableFaceKey()
        val face = store.embeddingRecords(epoch, firstKey.canonicalMediaKey).single().nativeFaceUid
        val state = host.reviewPage(grant)?.clusters ?: error("no published review snapshot")
        val cluster = state.clusters.unknownClusters.first { group ->
            group.members.any { it.localHex == face }
        }
        val person = PersonKey("native-" + cluster.id.anchor.localHex)
        assertTrue(host.review(PeopleClusterReviewCommand(state,
            PeopleReviewAction.NameCluster(cluster.id, person, "Synthetic Ada"))))
        return person
    }

    @Test fun searchByFaceFromSearchRanksOpensWithHighlightAndOpensTheAlbum() {
        val host = host()
        val grant = "gallery-468"
        val person = indexAndName(host, grant)
        val port = host.faceSearchPort { grant.takeIf { granted } }

        var step by mutableStateOf<FaceSearchStep?>(FaceSearchStep.ChooseReference)
        var viewer by mutableStateOf<Pair<List<MediaRecord>, String>?>(null)
        var album by mutableStateOf<NativePersonAlbumRequest?>(null)
        lateinit var session: FaceSearchSession
        compose.setContent {
            val version by host.changes.collectAsState()
            session = remember {
                FaceSearchSession(port, records = { uris ->
                    records.filter { it.uri in uris }.associateBy { it.uri }
                })
            }
            val state by session.state.collectAsState()
            val opened = viewer
            val albumRequest = album
            val currentStep = step
            when {
                opened != null -> GalleryViewer(opened.first, opened.second,
                    faceHighlights = (state as? FaceSearchState.Results)?.boxesByUri.orEmpty()
                ) { viewer = null }
                albumRequest != null -> NativePersonAlbumRoute(host, grant, albumRequest,
                    host.liveHomePeople(host.displayEpoch(grant)), records,
                    rememberLazyGridState(), 1L, onBack = { album = null }, onOpen = { _, _ -> })
                currentStep != null -> FaceSearchRoute(port, version, session, currentStep,
                    records, onStep = { step = it },
                    onOpen = { items, record -> viewer = items to record.uri },
                    onOpenAlbum = { p, name -> album = NativePersonAlbumRequest(p, name) },
                    onOpenPeople = {}, onClose = { step = null })
            }
        }

        // Pick the reference photo from the gallery, then confirm its one face.
        waitForTag("atlas-face-reference-chooser")
        waitForTag("atlas-face-reference-0")
        compose.onNodeWithTag("atlas-face-reference-0").performClick()
        waitForTag("atlas-query-face-confirm")
        compose.onNodeWithTag("atlas-query-face-confirm").performClick()

        // Ranked results: the reference itself first, never the blank photo.
        waitForTag("atlas-face-results-grid")
        val results = session.state.value as FaceSearchState.Results
        assertEquals(records[0].uri, results.hits.first().record.uri)
        assertEquals(FaceMatchTier.SAME_PERSON, results.hits.first().tier)
        assertTrue(results.records.none { it.id == blank })
        assertTrue("same drawn face found on other photos: ${results.records.map { it.id }}",
            results.records.size >= 2)
        compose.onNodeWithTag("atlas-face-results-sure-header").assertExists()
        assertEquals(person, results.person)
        compose.onAllNodesWithText("Open Synthetic Ada's album").fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }

        // Open a result: the viewer shows the matched face highlighted.
        compose.onNodeWithTag("atlas-face-result-1").performClick()
        waitFor("viewer") { viewer != null }
        // The overlay sits inside the photo surface, whose semantics merge
        // their descendants (one TalkBack target), so look in the unmerged tree.
        waitFor("face highlight") {
            compose.onAllNodesWithTag("atlas-viewer-face-highlight", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(results.records[1].uri, viewer!!.second)
        assertTrue(results.boxesByUri[viewer!!.second]!!.isNotEmpty())
        compose.onNodeWithTag("atlas-back").performClick()
        waitForTag("atlas-face-results-grid")

        // Open the confirmed person's album from the results.
        compose.onNodeWithTag("atlas-face-results-album").performClick()
        waitFor("album") { album != null }
        waitFor("album title") {
            compose.onAllNodesWithText("Synthetic Ada").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(person, album!!.person)
        album = null
        waitForTag("atlas-face-results-grid")

        // Revoke photo access: results are dropped, not kept on screen.
        granted = false
        host.onGalleryPublication(null) { inventory }
        waitForTag("atlas-face-results-revoked")
        assertEquals(FaceSearchState.Revoked, session.state.value)
        compose.onNodeWithTag("atlas-face-results-grid").assertDoesNotExist()
    }

    @Test fun viewerOffersFindThisPersonOnlyOnPhotosWithFaces() {
        val host = host()
        val grant = "gallery-468b"
        val port = host.faceSearchPort { grant.takeIf { granted } }
        var step by mutableStateOf<FaceSearchStep?>(null)
        var start by mutableStateOf(records[1].uri)
        compose.setContent {
            val version by host.changes.collectAsState()
            val session = remember {
                FaceSearchSession(port, records = { uris ->
                    records.filter { it.uri in uris }.associateBy { it.uri }
                })
            }
            val currentStep = step
            if (currentStep != null) {
                FaceSearchRoute(port, version, session, currentStep, records,
                    onStep = { step = it }, onOpen = { _, _ -> }, onOpenAlbum = { _, _ -> },
                    onOpenPeople = {}, onClose = { step = null })
            } else {
                GalleryViewer(records, start, faceSearch = ViewerFaceSearch(
                    hasFaces = { photo ->
                        withContext(Dispatchers.IO) {
                            port.liveEpoch()?.let { port.indexedFaceCount(it, photo) > 0 } == true
                        }
                    },
                    onFindPerson = { step = FaceSearchStep.PickFace(it, fromChooser = false) }
                )) {}
            }
        }
        // Before the opt-in nothing is indexed: no action.
        waitForTag("atlas-main-pager")
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-viewer-find-person").assertDoesNotExist()

        host.start(grant, inventory)
        drain()
        // A photo with an indexed face offers the action (recomposed per photo).
        start = records[2].uri
        waitForTag("atlas-viewer-find-person")
        compose.onNodeWithTag("atlas-viewer-find-person").performClick()
        waitForTag("atlas-query-face-picker")
        waitForPickerConfirmation()
        compose.onNodeWithTag("atlas-query-face-back").performClick()
        waitFor("back to viewer") { step == null }

        // The blank photo has no indexed face: no action.
        start = records[blank.toInt() - 1].uri
        waitForTag("atlas-main-pager")
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-viewer-find-person").assertDoesNotExist()
    }
}
