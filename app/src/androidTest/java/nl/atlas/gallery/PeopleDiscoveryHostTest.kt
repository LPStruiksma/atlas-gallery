package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepeopleui.DetectionReviewChoice
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.NativeFaceReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleActionAvailability
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #433 host wiring on API35 with the bundled YuNet + SFace models, a real
 * app-private FaceIndexStore and the real People/review Compose screens.
 * Photos are [SyntheticFace] drawings keyed by invented MediaStore rows: no
 * real photo, person or MediaStore content is read. WorkManager is replaced by
 * a recording scheduler and the test runs each worker batch itself through
 * [runPeopleScanBatch], the function the worker runs.
 */
@RunWith(AndroidJUnit4::class)
class PeopleDiscoveryHostTest {
    private val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-discovery-host-" + UUID.randomUUID())
    private lateinit var store: FaceIndexStore

    private class RecordingScheduler : PeopleScanScheduling {
        val starts = AtomicInteger()
        val nexts = AtomicInteger()
        val laters = AtomicInteger()
        val cancels = AtomicInteger()
        override fun start() { starts.incrementAndGet() }
        override fun next() { nexts.incrementAndGet() }
        override fun later() { laters.incrementAndGet() }
        override fun cancel() { cancels.incrementAndGet() }
    }

    @Volatile private var granted = true
    @Volatile private var charging = true
    @Volatile private var batteryPercent = 100
    @Volatile private var thermalStatus = 0
    private val decoded = AtomicInteger()
    private val scheduler = RecordingScheduler()

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    // VS-V1b catalogues videos with complete provenance; this photo journey
    // keeps its one video out of the catalogue the documented way (no
    // provider version), so its counts stay photo-only. Video scanning is
    // covered by VideoFaceScanAndroidTest.
    private val video = photo(99).copy(
        uri = "content://media/external_primary/video/media/99", isVideo = true,
        providerVersion = null)

    private val inventory = (1L..10L).map(::photo) + video

    /** [publish] wraps the store's cluster publication (#508 review: failure injection). */
    private fun host(
        publish: ((FaceIndexStore) -> (String, Boolean) -> Boolean)? = null,
        optIn: PeopleOptInMemory = PeopleOptInMemory.NONE
    ): PeopleDiscoveryHost {
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
                    val id = uri.substringAfterLast('/').toInt()
                    decoded.incrementAndGet()
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(SyntheticFace.Spec(
                        480, 480, 0.4f + 0.02f * id, 0.5f, 0.8f + 0.01f * id)))
                }
            },
            power = { PeoplePowerState(charging, batteryNotLow = true, storageNotLow = true,
                batteryPercent = batteryPercent, thermalStatus = thermalStatus) },
            scheduler = scheduler,
            publishClusters = publish?.invoke(store) ?: store::publishClustersIfDue,
            optInMemory = optIn
        )
    }

    /**
     * #61: a genuine fake-media host batch remains safe unplugged, and the
     * charging-constrained WorkManager chain is rearmed when manual mode ends.
     * A separate screenshot test declares the light/dark rendered proof.
     */
    @Test fun manualUnpluggedScanRearmsChargingOnlyWorkAndHonorsStops() {
        charging = false
        batteryPercent = 80
        thermalStatus = 0
        val host = host()
        val grant = "manual-unplugged"
        host.setForegroundVisible(true)
        val token = requireNotNull(host.beginManualForeground(grant, inventory))
        assertTrue(host.manualScanActive(token))
        assertTrue(scheduler.cancels.get() > 0)
        val first = runBlocking {
            runPeopleScanBatch(target, thermalAllowed = { true },
                manualForeground = true, isStopped = { false })
        }
        assertNotNull(first)
        assertTrue("synthetic photo indexed", first!!.photosIndexed > 0)
        val indexed = decoded.get()
        assertEquals(0, scheduler.nexts.get())
        assertEquals(0, scheduler.laters.get())

        batteryPercent = 29
        val low = runBlocking {
            runPeopleScanBatch(target, thermalAllowed = { true },
                manualForeground = true, isStopped = { false })
        }
        assertEquals(FaceBatchStop.HELD, low?.stop)
        assertEquals(indexed, decoded.get())
        assertEquals("Battery below 30%", host.manualHoldReason(token))

        batteryPercent = 80
        thermalStatus = 2
        val hot = runBlocking {
            runPeopleScanBatch(target, thermalAllowed = { false },
                manualForeground = true, isStopped = { false })
        }
        assertEquals(FaceBatchStop.HELD, hot?.stop)
        assertEquals("Waiting for phone to cool", host.manualHoldReason(token))
        assertEquals(indexed, decoded.get())

        thermalStatus = 0
        val startsBefore = scheduler.starts.get()
        host.setForegroundVisible(false)
        assertFalse(host.manualScanActive(token))
        assertTrue("restore charging-only worker on foreground exit",
            scheduler.starts.get() > startsBefore)
        val workerHeld = runBlocking {
            runPeopleScanBatch(target, thermalAllowed = { true }, isStopped = { false })
        }
        assertEquals(FaceBatchStop.HELD, workerHeld?.stop)
        assertEquals(indexed, decoded.get())
        // Old job's finally cannot rearm twice or end a newer token.
        val startsAfter = scheduler.starts.get()
        host.stopManualForeground(token)
        assertEquals(startsAfter, scheduler.starts.get())
    }

    @Test fun aggregateDiagnosticsAreOptedInAndGrantFenced() {
        val h = host()
        assertEquals(false, h.aggregateDiagnostics("scope").optedIn)
        assertNull(h.aggregateDiagnostics("scope").dirty)
        h.start("scope", inventory)
        val observed = h.aggregateDiagnostics("scope")
        assertEquals(true, observed.optedIn)
        assertNotNull(observed.dirty)
        assertNotNull(observed.pending)
        assertNull(h.aggregateDiagnostics("another-scope").dirty)
        granted = false
        assertNull(h.aggregateDiagnostics("scope").optedIn)
        assertNull(h.aggregateDiagnostics("scope").pending)
    }

    // The Compose rule must dispose its content before the SQLite fixture closes.
    // JUnit @After runs *before* rule teardown; closing there races the review
    // screen's outstanding database reads on API35. No keyboard slides up:
    // these tests are about naming and scans, not layout above the keyboard.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.None))
        .around(object : ExternalResource() {
        override fun after() {
            PeopleScanWorkRuntime.clear()
            if (::store.isInitialized) store.close()
            dir.deleteRecursively()
        }
    }).around(compose)

    private fun batch() = runBlocking { runPeopleScanBatch(target) { false } }

    /** #643: the scan count is the progress line's state, not text on screen. */
    private fun progress(line: String) = hasTestTag("atlas-people-progress-line") and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, line)

    /** #643: scan and review actions sit in People ⋮, a popup: wait for the item (rule 5). */
    private fun openMenuFor(tag: String) {
        compose.tap(hasTestTag("atlas-header-more"))
        compose.waitForNode(hasTestTag(tag), WAIT_MS)
    }

    @Test fun peopleUiScansWeakMetadataPhotoWithoutAttentionBanner() {
        val host = host()
        val rows = listOf(photo(5).copy(
            generationModified = 0, widthPixels = 0, heightPixels = 0))
        val grant = "gallery-weak"
        compose.setContent {
            val discovery = rememberPeopleDiscoveryOf(host, grant, rows) { it }
            PeopleHomeScreen(
                state = PeopleHomeUiState(
                    discovery = discovery.status?.ui ?: FaceDiscoveryUiState()),
                actions = discovery.status?.actions ?: PeopleActionAvailability(),
                onOpenProfile = {},
                currentPermissionEpoch = discovery.status?.displayEpoch,
                onStartDiscovery = discovery.onStart,
                onPauseDiscovery = discovery.onPause,
                onResumeDiscovery = discovery.onResume
            )
        }
        compose.waitForNode(hasText("Discover faces", substring = true), WAIT_MS)
        compose.tap(hasText("Discover faces"))
        compose.awaitThat("weak photo scanner start", WAIT_MS) { scheduler.starts.get() == 1 }
        compose.waitForNode(progress("Starting · 0 of 1"), WAIT_MS)
        assertEquals(1, host.status(grant).provenance!!.weakPhotoRows)
        assertEquals(1, host.status(grant).provenance!!.sourcePhotoRows)
        assertEquals(1, batch()!!.photosIndexed)
        compose.awaitThat("weak photo counted", WAIT_MS) { host.status(grant).ui.processed == 1 }
        assertEquals(1, decoded.get())
        assertTrue(compose.onAllNodesWithText("Face discovery needs attention")
            .fetchSemanticsNodes().isEmpty())
    }

    /**
     * #260: an already-open review must not keep the initial empty queue after
     * discovery publishes faces in the SAME permission epoch. Synthetic photos
     * only; the scheduler does not run background work in this fixture.
     */
    @Test fun openReviewReloadsAfterScanChangesSamePermissionEpoch() {
        val host = host()
        host.start("gallery-7", inventory)
        val firstEpoch = host.displayEpoch("gallery-7")
        compose.setContent {
            MaterialTheme {
                PeopleReviewRoute(host, "gallery-7", onClose = {})
            }
        }

        // Page is loaded before any batch has produced a face.
        compose.waitForNode(hasText("No unknown-face observations are awaiting review.", substring = true), WAIT_MS)
        compose.onAllNodesWithTag("atlas-unknown-identity-group")
            .fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        drain()
        assertEquals("scan stays in the same grant/index epoch", firstEpoch,
            host.displayEpoch("gallery-7"))
        compose.awaitThat("new face groups on the already-open review", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-unknown-identity-group")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No unknown-face observations are awaiting review.")
            .assertDoesNotExist()
    }

    @Test fun optInScanProgressClustersReviewThenRevokeStops() {
        val host = host()
        var grant by mutableStateOf<String?>("gallery-7")
        var reviewOpen by mutableStateOf(false)
        compose.setContent {
            val discovery = rememberPeopleDiscoveryOf(host, grant, inventory) { it }
            if (reviewOpen) {
                PeopleReviewRoute(host, grant, onClose = { reviewOpen = false })
            } else {
                PeopleHomeScreen(
                    state = PeopleHomeUiState(
                        discovery = discovery.status?.ui ?: FaceDiscoveryUiState()),
                    actions = discovery.status?.actions ?: PeopleActionAvailability(),
                    onOpenProfile = {},
                    currentPermissionEpoch = discovery.status?.displayEpoch,
                    onStartDiscovery = discovery.onStart,
                    onPauseDiscovery = discovery.onPause,
                    onResumeDiscovery = discovery.onResume,
                    onOpenReview = { reviewOpen = true }
                )
            }
        }

        // Nothing runs before the explicit opt-in.
        compose.waitForNode(hasText("Discover faces", substring = true), WAIT_MS)
        assertNull("no host session before opt-in", batch())
        assertEquals(0, scheduler.starts.get())
        assertEquals(0, decoded.get())

        compose.tap(hasText("Discover faces"))
        compose.awaitThat("scheduler start", WAIT_MS) { scheduler.starts.get() == 1 }
        compose.waitForNode(progress("Starting · 0 of 10"), WAIT_MS)
        // #664: pause/resume moved to Settings; the scan keeps progressing
        // without opening the old People overflow control here.

        // First bounded batch: some photos indexed, a next batch queued.
        val first = batch()
        assertNotNull(first)
        assertTrue("batch indexed photos: $first", first!!.photosIndexed in 1..8)
        assertTrue(first.stop == FaceBatchStop.PHOTO_LIMIT || first.stop == FaceBatchStop.TIME_LIMIT)
        assertEquals(1, scheduler.nexts.get())
        compose.waitForNode(progress("Starting · ${first.photosIndexed} of 10"), WAIT_MS)

        // Drain: the last batch publishes clusters (scanFinished) and chains nothing.
        var stop = first.stop
        var guard = 0
        while (stop != FaceBatchStop.NO_PENDING && guard++ < 20) stop = batch()!!.stop
        assertEquals(FaceBatchStop.NO_PENDING, stop)
        val nextsAtEnd = scheduler.nexts.get()
        assertEquals(10, decoded.get())
        val epoch = host.displayEpoch("gallery-7")!!
        val clusters = store.clusterState(epoch)
        assertNotNull("drained scan must publish clusters", clusters)
        assertTrue(clusters!!.unknownClusters.isNotEmpty())

        // #516/#643/#46: a drained scan keeps its truthful Finished status,
        // but the animated progress bar is reserved for active work.
        compose.waitForNode(progress("Finished · 10 of 10"), WAIT_MS)
        compose.awaitThat("animated progress bar gone", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-people-progress")
                .fetchSemanticsNodes().isEmpty()
        }
        openMenuFor("atlas-people-review")
        compose.tap(hasTestTag("atlas-people-review"))
        compose.awaitThat("unknown identity groups", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-unknown-identity-group")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // #434: identity review is connected, so the read-only note is gone.
        compose.onNodeWithTag("atlas-cluster-review-read-only").assertDoesNotExist()

        // Detection review is durable and scoped to the live epoch.
        val page = host.reviewPage("gallery-7")!!
        val before = store.reviewCounts(epoch)!!.awaitingReview
        val face = page.queue.candidates.first().key.nativeFaceUid
        assertTrue(host.decide(NativeFaceReviewCommand(epoch, face, DetectionReviewChoice.NOT_A_FACE)))
        assertEquals(before - 1, store.reviewCounts(epoch)!!.awaitingReview)

        // Revoke: grant gone. Worker refused, store hides the epoch, UI drops review.
        granted = false
        grant = null
        compose.awaitThat("scheduler cancel on revoke", WAIT_MS) { scheduler.cancels.get() >= 1 }
        // revoke() cancels the scheduler before it invalidates the store, on
        // the publication's IO thread: wait for that step too, then assert.
        compose.awaitThat("store hides the epoch on revoke", WAIT_MS) { store.progress(epoch) == null }
        assertNull(batch())
        assertNull(store.progress(epoch))
        assertNull(store.clusterState(epoch))
        compose.awaitThat("unknown groups hidden after revoke", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-unknown-identity-group")
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(nextsAtEnd, scheduler.nexts.get())
        assertEquals(10, decoded.get())
    }

    /** The gallery rows of [inventory] (same URIs), as People's album filter sees them. */
    private val records = inventory.map {
        MediaRecord(it.id, it.uri, "synthetic-${it.id}.jpg", "DCIM/Camera/", it.sizeBytes,
            it.id * 1000L, it.isVideo, volumeName = it.volumeName)
    }

    private fun drain() {
        var guard = 0
        while (batch()!!.stop != FaceBatchStop.NO_PENDING && guard++ < 20) Unit
    }

    /**
     * #434 part 2 on API35: opt-in, scan, name an unknown group in the review
     * screen, find the person on People home, open the native person album;
     * then a new process restores the name and a revoke hides everything.
     */
    @Test fun nameAGroupThenOpenThePersonAlbumSurvivesRestartAndRevoke() {
        val host = host()
        var grant by mutableStateOf<String?>("gallery-7")
        var reviewOpen by mutableStateOf(false)
        var album by mutableStateOf<NativePersonAlbumRequest?>(null)
        compose.setContent {
            val discovery = rememberPeopleDiscoveryOf(host, grant, inventory) { it }
            val grid = rememberLazyGridState()
            val opened = album
            when {
                reviewOpen -> PeopleReviewRoute(host, grant, onClose = { reviewOpen = false })
                opened != null -> NativePersonAlbumRoute(host, grant, opened,
                    discovery.status?.people, records, grid, 1L,
                    onBack = { album = null }, onOpen = { _, _ -> })
                else -> PeopleHomeScreen(
                    state = PeopleHomeUiState(
                        discovery = discovery.status?.ui ?: FaceDiscoveryUiState()),
                    actions = discovery.status?.actions ?: PeopleActionAvailability(),
                    onOpenProfile = {},
                    currentPermissionEpoch = discovery.status?.displayEpoch,
                    onStartDiscovery = discovery.onStart,
                    onOpenReview = { reviewOpen = true },
                    nativePeopleReview = discovery.status?.people,
                    nativePeopleSourceGeneration = discovery.status?.people?.sourceGeneration,
                    onOpenNativePersonAlbum = { person ->
                        discovery.status?.people?.people?.get(person)?.name?.let {
                            album = NativePersonAlbumRequest(person, it)
                        }
                    },
                    liveNativePeopleAtAction = {
                        host.liveHomePeople(discovery.status?.displayEpoch)
                    }
                )
            }
        }
        compose.waitForNode(hasText("Discover faces", substring = true), WAIT_MS)
        compose.tap(hasText("Discover faces"))
        compose.awaitThat("scheduler start", WAIT_MS) { scheduler.starts.get() == 1 }
        drain()
        assertEquals(10, decoded.get())
        val epoch = host.displayEpoch("gallery-7")!!

        // The identity section is editable: no read-only note, the rescan note instead.
        openMenuFor("atlas-people-review")
        compose.tap(hasTestTag("atlas-people-review"))
        compose.awaitThat("unknown identity groups", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-unknown-identity-group")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-cluster-review-read-only").assertDoesNotExist()
        compose.onNodeWithTag("atlas-cluster-review-rescan-note").assertExists()
        val group = host.reviewPage("gallery-7")!!.clusters!!.clusters.unknownClusters.first()
        val person = PersonKey("native-" + group.id.anchor.localHex)

        // Type into the first group's name field, then tap its Create: the
        // only enabled one, since the other groups have no name.
        val nameField = compose.onAllNodesWithTag("atlas-group-name")[0].fetchSemanticsNode().id
        compose.typeText(SemanticsMatcher("the first group's name field") { it.id == nameField },
            "Synthetic Ada")
        compose.tap(hasTestTag("atlas-group-create-person") and isEnabled())
        compose.awaitThat("name committed", WAIT_MS) {
            store.peopleReview(epoch)?.names?.get(person) == "Synthetic Ada"
        }
        val stored = store.peopleReview(epoch)!!
        assertEquals(group.members.toSet(),
            stored.clusters.reviews.confirmed.filterValues { it == person }.keys)
        compose.awaitThat("reviewed person card", WAIT_MS) {
            runCatching {
                compose.onNodeWithTag("atlas-unknown-face-review-screen")
                    .performScrollToNode(hasTestTag("atlas-reviewed-identity-album"))
            }.isSuccess
        }
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasText("Undo last People review change"))

        // People home lists the person; the album shows exactly their photos.
        // The Undo row is at the end of the list; Back is its first row.
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasText("Back to People"))
        compose.tap(hasText("Back to People"))
        compose.awaitThat("people home", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-people-home").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-people-home")
            .performScrollToNode(hasTestTag("atlas-native-person-album"))
        compose.tap(hasTestTag("atlas-native-person-album"))
        compose.awaitThat("person album", WAIT_MS) { album != null }
        val photos = store.personMedia(epoch, person).size
        assertEquals("one synthetic face per photo", group.members.size, photos)
        compose.waitForNode(hasText(nl.atlas.gallery.featurepeopleui.personPageCount(photos, 0), substring = true), WAIT_MS)
        compose.onNodeWithText("Synthetic Ada").assertExists()

        // A new process over the same private store: the opt-in is gone, the
        // name is not. After a fresh opt-in nothing is decoded again.
        compose.tap(hasTestTag("atlas-header-back"))
        compose.awaitThat("album closed", WAIT_MS) { album == null }
        grant = null
        compose.waitForIdle()
        store.close()
        val restarted = host()
        assertNull(restarted.status("gallery-8").people)
        restarted.start("gallery-8", inventory)
        drain()
        assertEquals("unchanged photos are not decoded twice", 10, decoded.get())
        val epoch2 = restarted.displayEpoch("gallery-8")!!
        assertTrue(epoch2 != epoch)
        val people = restarted.status("gallery-8").people
        assertNotNull("names restored after a restart", people)
        assertEquals("Synthetic Ada", people!!.people[person]?.name)
        assertEquals(photos, people.people[person]?.faceCount)
        assertEquals(photos, assertHomeEqualsFullRead(epoch2)[person])
        assertEquals("lean home stays on its generation",
            people.sourceGeneration, restarted.status("gallery-8").people?.sourceGeneration)
        assertEquals(photos, restarted.personAlbumUris("gallery-8", person)!!.size)

        // Revoke: no names, no album, no snapshot for a stale button.
        granted = false
        restarted.onGalleryPublication(null) { inventory }
        assertNull(restarted.status("gallery-8").people)
        assertNull(restarted.personAlbumUris("gallery-8", person))
        assertNull(restarted.liveReview(epoch2))
        assertTrue(store.personMedia(epoch2, person).isEmpty())
    }

    /**
     * #532 equality: the lean People home read equals what the full cluster
     * read shows on People home (the visible ordered list of person + name,
     * via peopleWithPresentFaces + peopleAlbumsForGrant), plus per-person
     * present-face counts (confirmed plus matched, #580) and a cover from
     * current, published person membership (COVER-2 quality ranking is tested
     * separately in BestShotCoverAndroidTest).
     */
    private fun assertHomeEqualsFullRead(epoch: String): Map<PersonKey, Int> {
        val home = store.peopleHome(epoch)!!
        val full = store.peopleReview(epoch)!!
        assertEquals("one generation", full.generation, home.generation)
        val generation = "$epoch|${full.generation}"
        val expected = peopleWithPresentFaces(
            nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState(
                epoch, generation, full.clusters, full.names))
        val presentByPerson = expected.clusters.reviews.confirmed
            .filterKeys { it in expected.clusters }.entries
            .groupBy({ it.value }, { it.key.localHex })
        // #580: matched members contribute to the face count and can supply a COVER-2 cover.
        val matchedByPerson = expected.clusters.matched.entries
            .groupBy({ it.value.person }, { it.key.localHex })
        val summary = nl.atlas.gallery.featurepeopleui.PeopleHomeSummary(epoch, generation,
            home.people.mapValues { (_, p) ->
                nl.atlas.gallery.featurepeopleui.PeopleHomePerson(p.name, p.faceCount, p.coverFaceUid)
            })
        assertEquals("visible People list",
            nl.atlas.gallery.featurepeopleui.peopleAlbumsForGrant(expected, epoch, generation)
                .map { it.person to it.name },
            nl.atlas.gallery.featurepeopleui.peopleHomeAlbumsForGrant(summary, epoch, generation)
                .map { it.person to it.name })
        assertEquals("present face counts",
            expected.names.keys.associateWith {
                (presentByPerson[it]?.size ?: 0) + (matchedByPerson[it]?.size ?: 0) },
            home.people.mapValues { it.value.faceCount })
        for ((person, entry) in home.people) {
            val present = presentByPerson[person].orEmpty().toSet() +
                matchedByPerson[person].orEmpty().toSet()
            assertTrue("cover must be a current member of $person",
                entry.coverFaceUid in present)
        }
        return home.people.mapValues { it.value.faceCount }
    }

    /** Summary served from `people_home` at the current revision (not the live fallback). */
    private fun summaryRevision(): Long? = store.readableDatabase.rawQuery(
        "SELECT revision FROM people_home_generation WHERE singleton=1", null).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }

    /**
     * Stale source and model confirmations stay in the persisted tables but
     * must not appear on People home. Each change takes the real invalidation
     * path (cluster generation dirty, then a publication bumps the revision
     * and rewrites the summary); the live fallback must agree too.
     */
    @Test fun leanHomeCountsOnlyCurrentProcessedModelAndRevision() {
        val host = host()
        host.start("gallery-lean", inventory)
        drain()
        val epoch = host.displayEpoch("gallery-lean")!!
        val baseline = store.peopleReview(epoch)!!
        val group = baseline.clusters.unknownClusters.maxBy { it.members.size }
        assertTrue("need multiple synthetic observations", group.members.size >= 3)
        val person = PersonKey("native-" + group.id.anchor.localHex)
        val state = nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState(
            epoch, "$epoch|${baseline.generation}", baseline.clusters, baseline.names)
        val named = nl.atlas.gallery.featurepeopleui.reviewPeopleCluster(
            state, nl.atlas.gallery.featurepeopleui.PeopleReviewAction.NameCluster(
                group.id, person, "Synthetic Ada"))!!
        val committed = store.commitPeopleReview(
            epoch, baseline.generation, named.clusters, named.names)
        assertNotNull(committed)
        assertEquals("commit rewrote the summary", committed, store.peopleReviewGeneration(epoch))
        assertNotNull(summaryRevision())
        assertEquals(group.members.size, assertHomeEqualsFullRead(epoch)[person])

        val db = store.writableDatabase
        fun republish() {
            db.execSQL("UPDATE cluster_generation SET dirty=1 WHERE singleton=1")
            // #39: a dirty generation keeps serving the last published People home.
            assertNotNull("dirty generation keeps People home", store.peopleHome(epoch))
            assertTrue(store.materializeClusters(epoch))
        }
        val faces = group.members.map { it.localHex }.sorted()
        // One member goes stale by source revision; the selected cover may be
        // any current quality-ranked member, not necessarily the lowest UID.
        db.execSQL("UPDATE embeddings SET source_revision=? WHERE face_uid=?",
            arrayOf("obsolete-revision", faces[0]))
        republish()
        assertEquals(group.members.size - 1, assertHomeEqualsFullRead(epoch)[person])
        val currentCover = store.peopleHome(epoch)!!.people[person]?.coverFaceUid
        assertTrue("invalidated source must not be the cover",
            currentCover in faces.drop(1))

        db.execSQL("UPDATE embeddings SET model_version=? WHERE face_uid=?",
            arrayOf("obsolete-model", faces[1]))
        republish()
        assertEquals(group.members.size - 2, assertHomeEqualsFullRead(epoch)[person])
        assertTrue("stale model must not be the cover",
            store.peopleHome(epoch)!!.people[person]?.coverFaceUid in faces.drop(2))

        // Live fallback (a file from before the summary) gives the same rows.
        val served = store.peopleHome(epoch)
        db.execSQL("UPDATE people_home_generation SET revision=NULL WHERE singleton=1")
        assertEquals(served, store.peopleHome(epoch))

        // No current faces left: the persisted name stays, the card goes.
        for (face in faces.drop(2)) {
            db.execSQL("UPDATE embeddings SET model_version=? WHERE face_uid=?",
                arrayOf("obsolete-model", face))
        }
        republish()
        assertTrue(person !in assertHomeEqualsFullRead(epoch))
        assertEquals("Synthetic Ada", store.peopleReview(epoch)!!.names[person])
    }

    /**
     * #543: the "Discover faces" choice survives a process restart. The first
     * gallery publication re-arms it under a fresh epoch and, when nothing
     * changed, People shows the names at once: no start(), no scan batch, no
     * cluster rebuild. Pause is kept; a changed source hides stale names until
     * they are rescanned; losing photo access forgets the choice.
     */
    @Test fun keptOptInShowsNamesRightAfterRestart() {
        val memory = object : PeopleOptInMemory {
            @Volatile var kept: PeopleOptIn? = null
            override fun load() = kept
            override fun save(value: PeopleOptIn?) { kept = value }
        }
        val host = host(optIn = memory)
        host.start("gallery-r", inventory)
        assertEquals(PeopleOptIn(paused = false), memory.kept)
        drain()
        val epoch = host.displayEpoch("gallery-r")!!
        val baseline = store.peopleReview(epoch)!!
        val group = baseline.clusters.unknownClusters.maxBy { it.members.size }
        val person = PersonKey("native-" + group.id.anchor.localHex)
        val named = nl.atlas.gallery.featurepeopleui.reviewPeopleCluster(
            nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState(
                epoch, "$epoch|${baseline.generation}", baseline.clusters, baseline.names),
            nl.atlas.gallery.featurepeopleui.PeopleReviewAction.NameCluster(
                group.id, person, "Synthetic Ada"))!!
        assertNotNull(store.commitPeopleReview(epoch, baseline.generation,
            named.clusters, named.names))
        val scanned = decoded.get()

        // New process: the kept choice, a new store connection, no tap.
        store.close()
        val restarted = host(optIn = memory)
        assertNull("no names before a current publication", restarted.status("gallery-r").people)
        restarted.onGalleryPublication("gallery-r") { inventory }
        val epoch2 = restarted.displayEpoch("gallery-r")!!
        assertTrue(epoch2 != epoch)
        assertEquals("Synthetic Ada", restarted.status("gallery-r").people?.people?.get(person)?.name)
        assertEquals(group.members.size, assertHomeEqualsFullRead(epoch2)[person])
        assertEquals(group.members.size, restarted.personAlbumUris("gallery-r", person)!!.size)
        assertEquals("nothing decoded again", scanned, decoded.get())
        restarted.pause()
        assertEquals(PeopleOptIn(paused = true), memory.kept)

        // Every photo edited: a real prune, so names wait for a rescan.
        store.close()
        val edited = host(optIn = memory)
        edited.onGalleryPublication("gallery-r") {
            inventory.map { it.copy(generationModified = it.generationModified + 1) }
        }
        assertNotNull("pause kept: still opted in", edited.displayEpoch("gallery-r"))
        assertNull(edited.status("gallery-r").people)

        // Photo access lost: the choice is forgotten.
        granted = false
        edited.onGalleryPublication("gallery-r") { inventory }
        assertNull(memory.kept)
    }

    /**
     * #580: after an update that changes the grouping rules, the first gallery
     * publication regroups the stored faces itself. People is readable at
     * once: nothing is scanned again and no scan batch runs, so it also works
     * off the charger.
     */
    @Test fun changedGroupingRulesRegroupAtTheFirstPublicationWithoutAScan() {
        val memory = object : PeopleOptInMemory {
            @Volatile var kept: PeopleOptIn? = null
            override fun load() = kept
            override fun save(value: PeopleOptIn?) { kept = value }
        }
        val host = host(optIn = memory)
        host.start("gallery-g", inventory)
        drain()
        val epoch = host.displayEpoch("gallery-g")!!
        fun groups(e: String) = store.peopleReview(e)?.clusters?.unknownClusters
            ?.map { it.members.toSet() }?.toSet()
        val before = requireNotNull(groups(epoch))
        assertTrue("need a group to lose", before.any { it.size >= 3 })
        val scanned = decoded.get()

        // What a build with other rules left behind: every face its own "person".
        store.writableDatabase.execSQL("UPDATE cluster_members SET anchor_uid=face_uid")
        store.writableDatabase.execSQL(
            "UPDATE cluster_policy SET fingerprint='older-rules' WHERE singleton=1")
        store.close()
        charging = false

        val restarted = host(optIn = memory)
        restarted.onGalleryPublication("gallery-g") { inventory }
        val epoch2 = restarted.displayEpoch("gallery-g")!!
        assertTrue(!store.regroupPending(epoch2))
        assertEquals("regrouped from the stored vectors", before, groups(epoch2))
        assertNotNull("People home is readable at once", store.peopleHome(epoch2))
        assertEquals("nothing decoded again", scanned, decoded.get())
    }

    @Test fun revokeMidScanStopsAndRegrantNeedsANewOptIn() {
        val host = host()
        host.start("gallery-1", inventory)
        assertEquals(1, scheduler.starts.get())
        val epoch = host.displayEpoch("gallery-1")!!
        val first = batch()!!
        val indexed = first.photosIndexed
        assertTrue(indexed in 1..8)

        // Pause holds, Resume continues, both without re-reading photos.
        host.pause()
        assertEquals(FaceDiscoveryPhase.PAUSED, host.status("gallery-1").ui.phase)
        assertNull(batch())
        assertEquals("a paused run queues no retry", 0, scheduler.laters.get())
        host.resume()
        assertEquals(2, scheduler.starts.get())

        // Unplugged: the worker holds without decoding.
        charging = false
        // Unplugged: the batch is refused at its start without decoding, and a
        // delayed retry keeps the run alive (#436 review F1).
        assertEquals(FaceBatchStop.HELD, batch()!!.stop)
        assertEquals(1, scheduler.laters.get())
        assertEquals(indexed, decoded.get())
        charging = true

        granted = false
        host.onGalleryPublication(null) { inventory }
        assertNull(batch())
        assertNull(store.progress(epoch))
        assertEquals(indexed, decoded.get())
        assertEquals("a revoked run queues no retry", 1, scheduler.laters.get())

        // Regrant alone never restarts the scan.
        granted = true
        host.onGalleryPublication("gallery-2") { inventory }
        assertEquals(FaceDiscoveryPhase.READY, host.status("gallery-2").ui.phase)
        assertNull(batch())
        assertEquals(2, scheduler.starts.get())

        // A new explicit start resumes from the checkpoint, not from zero.
        host.start("gallery-2", inventory)
        val epoch2 = host.displayEpoch("gallery-2")!!
        assertTrue(epoch2 != epoch)
        var guard = 0
        while (batch()!!.stop != FaceBatchStop.NO_PENDING && guard < 20) guard++
        assertEquals("unchanged photos are not decoded twice", 10, decoded.get())
        assertEquals(10, store.progress(epoch2)!!.processed)
    }

    /**
     * #658: overlapping gallery publications (an Activity recreate, "Discover
     * faces" racing the next refresh) must never turn discovery off. Before
     * the fix the later publication's beginSnapshot wiped the earlier one's
     * staging, whose next stageBatch threw "Stale or unstarted permission
     * snapshot", which the host reported as a broken index and dropped the
     * opt-in.
     */
    @Test fun overlappingPublicationsNeverTurnDiscoveryOff() {
        val host = host()
        host.start("grant-0", inventory)
        repeat(6) { round ->
            val gate = java.util.concurrent.CountDownLatch(1)
            val threads = (1..4).map { i ->
                Thread {
                    gate.await()
                    host.onGalleryPublication("grant-$round-$i") { inventory }
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach { it.join(60_000) }
            val last = "grant-$round-final"
            host.onGalleryPublication(last) { inventory }
            val status = host.status(last)
            assertNotEquals("round $round: ${status.ui.errorMessage}",
                FaceDiscoveryPhase.FAILED, status.ui.phase)
            assertNotNull("round $round: a scan session is served",
                PeopleScanWorkRuntime.acquire(target)?.use { it.epoch })
        }
    }

    @Test fun pendingGalleryRefreshHoldsTheRunInsteadOfRevoking() {
        val host = host()
        host.start("gallery-1", inventory)
        val epoch = host.displayEpoch("gallery-1")!!
        val indexed = batch()!!.photosIndexed
        assertTrue(indexed in 1..8)

        // Selected-photos resume or Activity recreation: the gallery grant is
        // briefly null while Android access is still granted (#436 review F2).
        host.onGalleryPublication(null) { inventory }
        assertNull("held: no session while the refresh is pending", batch())
        assertEquals(indexed, decoded.get())
        assertNotNull("held, not revoked: the store scope is kept", store.progress(epoch))
        assertEquals(1, scheduler.starts.get())

        // The refreshed publication continues the run without a new opt-in.
        host.onGalleryPublication("gallery-2") { inventory }
        assertEquals(2, scheduler.starts.get())
        val epoch2 = host.displayEpoch("gallery-2")!!
        assertTrue(epoch2 != epoch)
        var guard = 0
        while (batch()!!.stop != FaceBatchStop.NO_PENDING && guard < 20) guard++
        assertEquals("unchanged photos are not decoded twice", 10, decoded.get())
        assertEquals(10, store.progress(epoch2)!!.processed)
    }

    /**
     * #508 review: a publication that fails with a non-SQL error after the
     * photo queue drained (no pending photo left to trigger another run)
     * schedules a delayed retry, and the retry publishes the clusters.
     * Retries stop after [PeopleDiscoveryHost.MAX_PUBLICATION_RETRIES].
     */
    @Test fun failedPublicationAfterDrainRetriesLaterAndRecovers() {
        val failuresLeft = AtomicInteger(1)
        val host = host(publish = { store -> { epoch, finished ->
            if (finished && failuresLeft.getAndDecrement() > 0) {
                throw IllegalStateException("injected publication failure")
            }
            store.publishClustersIfDue(epoch, finished)
        } })
        host.start("gallery-1", inventory)
        var stop: FaceBatchStop? = null
        var guard = 0
        while (stop != FaceBatchStop.NO_PENDING && guard++ < 20) stop = batch()!!.stop
        val epoch = host.displayEpoch("gallery-1")!!
        assertNull("the failed part publishes nothing", store.clusterState(epoch))
        assertEquals("a drained queue with a failed part retries later", 1, scheduler.laters.get())
        assertEquals(FaceBatchStop.NO_PENDING, batch()!!.stop)
        assertNotNull("the retry publishes the clusters", store.clusterState(epoch))
        assertEquals(1, scheduler.laters.get())

        // Always failing: bounded, then the run stops asking for batches.
        failuresLeft.set(Int.MAX_VALUE)
        host.onGalleryPublication("gallery-2") { inventory }
        val laters = scheduler.laters.get()
        repeat(PeopleDiscoveryHost.MAX_PUBLICATION_RETRIES + 2) { batch() }
        assertEquals(laters + PeopleDiscoveryHost.MAX_PUBLICATION_RETRIES, scheduler.laters.get())
    }

    /**
     * #508: a library above the photo cap is no longer refused. It scans its
     * newest [maxCatalogPhotos] photos, newest first, whatever the gallery
     * order, and leaves the older ones out of the catalogue.
     */
    @Test fun libraryAboveTheCapScansItsNewestPhotosNewestFirst() {
        host()
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val dated = inventory.map { it.copy(takenAtMillis = 1_600_000_000_000L + it.id * 1_000L) }
            .shuffled(java.util.Random(508))
        val small = PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { true },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = directory.authorizedUri(lease.media).substringAfterLast('/').toInt()
                    if (id !in order) order += id
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(SyntheticFace.Spec(
                        480, 480, 0.4f + 0.02f * id, 0.5f, 0.8f + 0.01f * id)))
                }
            },
            power = { PeoplePowerState(true, true, true) },
            scheduler = scheduler,
            maxCatalogPhotos = 5
        )
        small.start("gallery-1", dated)
        val status = small.status("gallery-1")
        assertTrue(status.ui.phase != FaceDiscoveryPhase.FAILED)
        assertNull(status.ui.errorMessage)
        assertEquals(1, scheduler.starts.get())
        var guard = 0
        while (batch()!!.stop != FaceBatchStop.NO_PENDING && guard < 20) guard++
        assertEquals("newest five, newest first", listOf(10, 9, 8, 7, 6), order.toList())
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
