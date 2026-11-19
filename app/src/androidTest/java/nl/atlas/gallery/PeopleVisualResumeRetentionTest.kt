package nl.atlas.gallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurevisual.ImageEmbedder
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #620 (goal #31): returning to ATLAS from recent apps must not look like a
 * rebuild. One process, the real MainActivity, the real MediaStore observer
 * and gallery publication, the real People host over an app-private test
 * database. Backgrounding is ON_PAUSE + ON_STOP and back (what the recents
 * switcher does to a live process); the cold-process case is #602 and
 * [PeopleForceStopRelaunchTest].
 *
 * Cases:
 *  (a) recents round trip with no provider change: the same gallery
 *      publication, face epoch, visual epoch and named person, zero restage;
 *  (b) one insert, then one delete, while backgrounded: People, the visual
 *      opt-in, Albums and Pictures stay readable the whole time, and only the
 *      changed row is queued (no face or visual catalogue restage);
 *  (c) a true Android photo-grant revoke. Revoking a runtime permission kills
 *      the app process, so it cannot share one instrumentation run: the
 *      `atlasGrantLossPhase` seed / verify / cleanup methods below are driven
 *      like the #31 force-stop phases, with `adb shell pm revoke` between seed
 *      and verify. Without that argument they skip.
 *
 * Invented PNGs only, on an emulator. Logs carry counts, epoch changes and
 * phases (tag ATLAS_RESUME620), never media IDs, names, faces or vectors.
 */
@RunWith(AndroidJUnit4::class)
class PeopleVisualResumeRetentionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val phase: String? =
        InstrumentationRegistry.getArguments().getString("atlasGrantLossPhase")
    private val root = File(target.noBackupFilesDir,
        if (phase == null) "atlas-ci-resume620" else "atlas-ci-grantloss620")
    private val prefsName =
        if (phase == null) "atlas-ci-resume620-opt-in" else "atlas-ci-grantloss620-opt-in"
    private val choices = PeopleOptInMemory.preferences(object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            target.getSharedPreferences(prefsName, mode)
    })
    private val specs = HashMap<Long, SyntheticFace.Spec>()
    /** Every MediaStore row this run wrote; all are deleted in the finally. */
    private val disposable = mutableListOf<Uri>()
    private var faces: FaceIndexStore? = null
    private var visual: VisualIndexStore? = null
    private var host: PeopleDiscoveryHost? = null

    private val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("#620 writes disposable synthetic media only on emulators",
                    Build.HARDWARE.contains("ranchu", true) ||
                        Build.HARDWARE.contains("goldfish", true) ||
                        Build.FINGERPRINT.contains("generic", true) ||
                        Build.FINGERPRINT.contains("emulator", true))
                base.evaluate()
            }
        }
    }

    private val prepare = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val resumeTest = phase == null &&
                    description.methodName == "recentsResumeKeepsPeopleVisualAndLibrary"
                val grantPhase = phase != null && description.methodName == mapOf(
                    "seed" to "grantLossSeed", "verify" to "grantLossVerify",
                    "cleanup" to "grantLossCleanup")[phase]
                if (!resumeTest && !grantPhase) {
                    base.evaluate() // the method skips itself without touching fixtures
                    return
                }
                try {
                    when {
                        resumeTest -> {
                            assertTrue("a stale fixture directory could not be removed", root.deleteRecursively())
                            clearChoices()
                            insertFaceFixtures(FOLDER)
                            installHost()
                        }
                        phase == "seed" -> {
                            assertTrue("The grant-loss seed must start empty", !root.exists())
                            insertFaceFixtures(GRANT_FOLDER)
                            installHost()
                        }
                        phase == "verify" -> installHost()
                    }
                    base.evaluate()
                } finally {
                    PeopleDiscoveryHost.replaceAppForTest(null)
                    PeopleScanWorkRuntime.clear()
                    runCatching { faces?.close() }
                    runCatching { visual?.close() }
                    if (resumeTest) {
                        disposable.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                        clearChoices()
                        root.deleteRecursively()
                    }
                }
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(emulatorOnly)
        .around(S24DisplayRule()).around(prepare).around(compose)

    private fun spec(i: Int) = SyntheticFace.Spec(
        480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)

    private fun clearChoices() {
        target.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun insertFaceFixtures(folder: String) {
        assertTrue(readGalleryMediaGrant(target).images)
        root.mkdirs()
        for (i in 1..PHOTOS) {
            val uri = insertPng(folder, "IMG_RESUME620_$i.png",
                System.currentTimeMillis() - i * 86_400_000L) { SyntheticFace.bitmap(spec(i)) }
            specs[ContentUris.parseId(uri)] = spec(i)
        }
        assertEquals(PHOTOS, specs.size)
    }

    /** A plain invented PNG with no face; it scans as a skip. */
    private fun insertPlain(name: String, takenAt: Long): Uri =
        insertPng(FOLDER, name, takenAt) {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(40, 120, 200))
            }
        }

    private fun insertPng(folder: String, name: String, takenAt: Long, draw: () -> Bitmap): Uri {
        val resolver = target.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder/")
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        if (phase == null) disposable += uri
        val bitmap = draw()
        try {
            requireNotNull(resolver.openOutputStream(uri)).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null))
        return uri
    }

    private fun dbContext(file: File): Context = object : ContextWrapper(target) {
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

    private fun installHost() {
        root.mkdirs()
        val faceStore = FaceIndexStore(dbContext(File(root, "faces.db")))
        val visualStore = VisualIndexStore(dbContext(File(root, "visual.db")))
        faces = faceStore
        visual = visualStore
        host = PeopleDiscoveryHost(
            store = faceStore,
            visualStore = visualStore,
            optInMemory = choices,
            photoGrantCurrent = { readGalleryMediaGrant(target).images },
            photoGrantToken = { galleryPhotoGrantToken(target) },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = Uri.parse(directory.authorizedUri(lease.media))
                        .lastPathSegment?.toLongOrNull()
                    val drawing = id?.let { specs[it] }
                        ?: throw FaceSourceSkipped("NOT_A_FIXTURE")
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(drawing))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            visualEmbedderFactory = {
                object : ImageEmbedder {
                    override val modelVersion = MODEL
                    override val dimension = 8
                    override fun embed(bitmap: Bitmap): FloatArray =
                        FloatArray(8) { 0.35355338f }
                    override fun close() = Unit
                }
            },
            visualDecoderFor = { directory ->
                { media ->
                    val id = Uri.parse(directory.authorizedUri(FaceMediaKey(media.key, media.revision)))
                        .lastPathSegment?.toLongOrNull()
                    id?.let { specs[it] }?.let { SyntheticFace.bitmap(it) }
                }
            },
            visualModelVersion = MODEL,
            visualDimension = 8
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    private val vm: GalleryViewModel
        get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    /** Aggregates only: what a restage would change. */
    private data class Snapshot(
        val galleryEpoch: Long,
        val galleryIncremental: Boolean,
        val galleryRows: Int,
        val faceEpoch: String?,
        val faceQueued: Int,
        val faceProcessed: Int,
        /** Processed plus skipped (a non-face photo is a typed skip). */
        val faceDone: Int,
        val faceTotal: Int,
        val visualEpoch: String?,
        val visualQueued: Int,
        val visualDone: Int,
        val visualTotal: Int,
        val named: Boolean
    )

    private fun snapshot(): Snapshot {
        val pub = vm.publishedInventory.value
        val faceEpoch = faces?.activeEpoch()
        val progress = faceEpoch?.let { faces?.progress(it) }
        val visualEpoch = choices.loadPublishedVisual()?.epoch
        val visualProgress = visualEpoch?.let { runCatching { visual?.progress(it) }.getOrNull() }
        val named = faceEpoch?.let { host?.reviewState(it)?.names?.values?.contains(NAME) } == true
        return Snapshot(
            galleryEpoch = pub.epoch, galleryIncremental = pub.incremental,
            galleryRows = pub.items.size,
            faceEpoch = faceEpoch,
            faceQueued = (progress?.pending ?: 0) + (progress?.running ?: 0),
            faceProcessed = progress?.processed ?: 0,
            faceDone = progress?.let { it.processed + it.failed } ?: 0,
            faceTotal = progress?.let { it.pending + it.running + it.processed + it.failed } ?: 0,
            visualEpoch = visualEpoch,
            visualQueued = (visualProgress?.queued ?: 0) + (visualProgress?.running ?: 0),
            visualDone = visualProgress?.done ?: 0,
            visualTotal = visualProgress?.total ?: 0,
            named = named
        )
    }

    private fun log(event: String, s: Snapshot, extra: String = "") {
        Log.i(TAG, "event=$event gallery_rows=${s.galleryRows} " +
            "gallery_incremental=${s.galleryIncremental} face_queued=${s.faceQueued} " +
            "face_processed=${s.faceProcessed} face_done=${s.faceDone} face_total=${s.faceTotal} " +
            "visual_queued=${s.visualQueued} visual_done=${s.visualDone} " +
            "visual_total=${s.visualTotal} named=${s.named} $extra".trim())
    }

    /** Opt in through the real People tab, scan, name the synthetic person, visual on. */
    private fun seedNamedPeopleAndVisual(): String {
        waitFor("Photos tab") { has("atlas-nav-photos") }
        compose.runOnUiThread { vm.refresh() }
        waitFor("synthetic photos in gallery") {
            vm.publishedInventory.value.items.count { it.id in specs } == PHOTOS
        }
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor("People tab") { has("atlas-people-home") }
        waitFor("Discover faces") {
            compose.onAllNodesWithText("Discover faces").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Discover faces").onFirst().performClick()
        val epoch = drainScan()
        val scanner = requireNotNull(host)
        val review = requireNotNull(scanner.reviewState(epoch))
        val group = review.clusters.unknownClusters.maxBy { it.members.size }
        val person = PersonKey("native-" + group.id.anchor.localHex)
        assertTrue("named identity persisted through the real host reducer",
            scanner.review(PeopleClusterReviewCommand(review,
                PeopleReviewAction.NameCluster(group.id, person, NAME))))
        assertTrue("visual toggle activates with synthetic fixture", scanner.setVisualIndex(true))
        assertNotNull(choices.loadPublishedVisual())
        drainScan()
        waitFor("named People tile") { has("atlas-native-person-album") }
        return epoch
    }

    /** Runs bounded batches until faces and the visual queue are both empty. */
    private fun drainScan(): String {
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        var batches = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val before = PeopleScanWorkRuntime.acquire(target)?.use { it.epoch }
            val result = runBlocking { runPeopleScanBatch(target) { false } }
            if (before != null && result?.stop == FaceBatchStop.NO_PENDING &&
                PeopleScanWorkRuntime.acquire(target)?.use { it.epoch } == before) {
                val progress = faces?.progress(before)
                val visualEpoch = choices.loadPublishedVisual()?.epoch
                val visualQueued = visualEpoch?.let { visual?.progress(it) }
                    ?.let { it.queued + it.running } ?: 0
                if (progress != null && progress.processed >= PHOTOS && visualQueued == 0)
                    return before
            }
            check(++batches <= 600) { "scan failed to reach a stable epoch" }
            Thread.sleep(100)
        }
        error("Timed out waiting for the synthetic face and visual scans")
    }

    /** Waits until no gallery publication happened for [quietMs]. */
    private fun awaitGalleryQuiet(quietMs: Long = 2_000L) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var epoch = vm.publishedInventory.value.epoch
        var since = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            val now = vm.publishedInventory.value.epoch
            if (now != epoch || vm.busy.value) {
                epoch = now; since = SystemClock.elapsedRealtime()
            } else if (SystemClock.elapsedRealtime() - since >= quietMs) return
        }
        error("gallery publications did not settle")
    }

    private fun background() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    }

    private fun foreground() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    /**
     * From the moment ON_RESUME returns until [settled], sample what People,
     * the visual panel and the library can read. Returns readable gaps.
     */
    private class Gaps {
        var samples = 0
        var peopleUnreadable = 0
        var namedMissing = 0
        var visualOptInOff = 0
        var visualTotalUnknown = 0
        var libraryEmpty = 0
        var faceEpochChanges = 0
        override fun toString() = "samples=$samples people_unreadable=$peopleUnreadable " +
            "named_missing=$namedMissing visual_optin_off=$visualOptInOff " +
            "visual_total_unknown=$visualTotalUnknown library_empty=$libraryEmpty " +
            "face_epoch_changes=$faceEpochChanges"
    }

    private fun sampleUntil(label: String, timeoutMs: Long, settled: (Snapshot) -> Boolean): Pair<Snapshot, Gaps> {
        val gaps = Gaps()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastEpoch: String? = faces?.activeEpoch()
        while (true) {
            val s = snapshot()
            gaps.samples++
            if (s.faceEpoch == null) gaps.peopleUnreadable++
            if (!s.named) gaps.namedMissing++
            if (s.faceEpoch != null && s.faceEpoch != lastEpoch) gaps.faceEpochChanges++
            lastEpoch = s.faceEpoch ?: lastEpoch
            val status = host?.visualStatus(s.faceEpoch)
            if (status?.enabled != true) gaps.visualOptInOff++
            if (status?.total == null) gaps.visualTotalUnknown++
            if (vm.publishedInventory.value.items.count { it.id in specs } < PHOTOS) gaps.libraryEmpty++
            if (settled(s)) return s to gaps
            if (SystemClock.elapsedRealtime() > deadline)
                throw AssertionError("$label did not settle in $timeoutMs ms; last=$s gaps=$gaps")
            Thread.sleep(25)
        }
    }

    /** First frame after resume on People: never "No people" or "Discover faces". */
    private fun assertPeopleFirstFrame(label: String): Long {
        val started = SystemClock.elapsedRealtime()
        val seen = linkedSetOf<String>()
        try {
            compose.waitUntil(FIRST_PEOPLE_MS) {
                if (has("atlas-people-empty")) seen += "No people"
                if (compose.onAllNodesWithText("Discover faces").fetchSemanticsNodes()
                        .isNotEmpty()) seen += "Discover faces"
                has("atlas-native-person-album")
            }
        } catch (error: Throwable) {
            throw AssertionError("$label: named People not shown within $FIRST_PEOPLE_MS ms; " +
                "states seen: $seen", error)
        }
        assertTrue("$label: People showed a not-started state: $seen", seen.isEmpty())
        return SystemClock.elapsedRealtime() - started
    }

    private fun assertLibraryReadable(label: String) {
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        waitFor("$label Photos grid") { has("atlas-timeline-grid") }
        compose.onNodeWithTag("atlas-first-scan").assertDoesNotExist()
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor("$label Albums first frame") { has("atlas-album-card") }
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor("$label People again") { has("atlas-native-person-album") }
    }

    @Test fun recentsResumeKeepsPeopleVisualAndLibrary() {
        assumeTrue("grant-loss phases run only their own methods", phase == null)
        seedNamedPeopleAndVisual()
        // A plain photo that case (b) deletes while backgrounded.
        val doomed = insertPlain("IMG_RESUME620_FILLER.png", System.currentTimeMillis() - 400_000_000L)
        val doomedId = ContentUris.parseId(doomed)
        waitFor("filler photo published") {
            vm.publishedInventory.value.items.any { it.id == doomedId }
        }
        // It joins the catalogue as a skipped row (no face, not a fixture).
        drainScan()
        awaitGalleryQuiet()
        val base = snapshot()
        log("baseline", base)
        assertTrue("baseline named person", base.named)
        assertTrue("baseline face scan complete", base.faceProcessed >= PHOTOS)
        assertTrue("baseline visual complete", base.visualDone >= PHOTOS)
        assertTrue("baseline visual opt-in", choices.loadVisual())

        // (a) Recents round trip, nothing changed in MediaStore.
        background()
        foreground()
        val firstA = assertPeopleFirstFrame("(a)")
        awaitGalleryQuiet()
        val (a, gapsA) = sampleUntil("(a)", 5_000L) { true }
        log("resume_unchanged", a, "first_named_ms=$firstA gallery_publications=" +
            "${a.galleryEpoch - base.galleryEpoch} $gapsA")
        assertTrue("(a) a resume with no provider change must not run a full gallery rescan " +
            "(publications=${a.galleryEpoch - base.galleryEpoch})",
            a.galleryEpoch == base.galleryEpoch || a.galleryIncremental)
        assertEquals("(a) gallery rows", base.galleryRows, a.galleryRows)
        assertEquals("(a) the face catalogue must not restage", base.faceEpoch, a.faceEpoch)
        assertEquals("(a) no face row requeued", base.faceQueued, a.faceQueued)
        assertEquals("(a) face processed rows", base.faceProcessed, a.faceProcessed)
        assertEquals("(a) face done rows", base.faceDone, a.faceDone)
        assertEquals("(a) the visual catalogue must not restage", base.visualEpoch, a.visualEpoch)
        assertEquals("(a) visual done rows", base.visualDone, a.visualDone)
        assertTrue("(a) named person", a.named)
        assertLibraryReadable("(a)")

        // (b1) One insert while backgrounded.
        background()
        val added = insertPlain("IMG_RESUME620_ADDED.png", System.currentTimeMillis())
        val addedId = ContentUris.parseId(added)
        foreground()
        val firstB1 = assertPeopleFirstFrame("(b1)")
        val (b1, gapsB1) = sampleUntil("(b1) insert reaches People", 30_000L) {
            it.faceTotal == base.faceTotal + 1 && it.visualTotal == base.visualTotal + 1 &&
                vm.publishedInventory.value.items.any { r -> r.id == addedId }
        }
        log("resume_insert", b1, "first_named_ms=$firstB1 gallery_publications=" +
            "${b1.galleryEpoch - a.galleryEpoch} $gapsB1")
        assertEquals("(b1) People must stay readable while the insert publishes", 0,
            gapsB1.peopleUnreadable)
        assertEquals("(b1) the named person must stay readable", 0, gapsB1.namedMissing)
        assertEquals("(b1) the visual opt-in must stay on", 0, gapsB1.visualOptInOff)
        assertEquals("(b1) Pictures must keep every fixture", 0, gapsB1.libraryEmpty)
        assertEquals("(b1) only the inserted face row is queued, no restage",
            base.faceQueued + 1, b1.faceQueued)
        assertEquals("(b1) processed face rows survive", base.faceProcessed, b1.faceProcessed)
        assertEquals("(b1) done face rows survive", base.faceDone, b1.faceDone)
        assertEquals("(b1) only the inserted visual row is queued",
            base.visualQueued + 1, b1.visualQueued)
        assertEquals("(b1) visual rows survive", base.visualDone, b1.visualDone)
        assertLibraryReadable("(b1)")
        drainScan()
        val afterB1 = snapshot()

        // (b2) One delete while backgrounded.
        background()
        assertEquals(1, target.contentResolver.delete(doomed, null, null))
        disposable.remove(doomed)
        foreground()
        val firstB2 = assertPeopleFirstFrame("(b2)")
        val (b2, gapsB2) = sampleUntil("(b2) delete reaches People", 30_000L) {
            it.faceTotal == afterB1.faceTotal - 1 && it.visualTotal == afterB1.visualTotal - 1 &&
                vm.publishedInventory.value.items.none { r -> r.id == doomedId }
        }
        log("resume_delete", b2, "first_named_ms=$firstB2 gallery_publications=" +
            "${b2.galleryEpoch - afterB1.galleryEpoch} $gapsB2")
        assertEquals("(b2) People must stay readable while the delete publishes", 0,
            gapsB2.peopleUnreadable)
        assertEquals("(b2) the named person must stay readable", 0, gapsB2.namedMissing)
        assertEquals("(b2) the visual opt-in must stay on", 0, gapsB2.visualOptInOff)
        assertEquals("(b2) Pictures must keep every fixture", 0, gapsB2.libraryEmpty)
        assertEquals("(b2) a delete queues no face row", afterB1.faceQueued, b2.faceQueued)
        assertEquals("(b2) processed face rows survive", afterB1.faceProcessed, b2.faceProcessed)
        assertEquals("(b2) only the deleted face row goes", afterB1.faceDone - 1, b2.faceDone)
        assertEquals("(b2) a delete queues no visual row", afterB1.visualQueued, b2.visualQueued)
        assertEquals("(b2) only the deleted visual row goes", afterB1.visualDone - 1, b2.visualDone)
        assertLibraryReadable("(b2)")
        saveUiEvidence("resume620-after-people.png")
    }

    // (c) True Android grant loss across `adb shell pm revoke` (see class doc).

    @Test fun grantLossSeed() {
        assumeTrue(phase == "seed")
        seedNamedPeopleAndVisual()
        log("grant_seed", snapshot())
    }

    @Test fun grantLossVerify() {
        assumeTrue(phase == "verify")
        assertTrue("the verify phase needs the photo grant revoked",
            !readGalleryMediaGrant(target).images)
        // The app itself must notice the revoke on launch: the opt-in and both
        // published scopes go, so a later re-grant cannot silently revive them.
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (choices.load() != null || choices.loadPublished() != null) {
            assertTrue("a revoked grant showed a named person", !has("atlas-native-person-album"))
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError(
                "the launch under a revoked grant kept the People opt-in " +
                    "(optIn=${choices.load() != null} published=${choices.loadPublished() != null})")
            Thread.sleep(100)
        }
        compose.waitForIdle()
        assertTrue("a revoked grant showed a named person", !has("atlas-native-person-album"))
        assertTrue("no photo may stay visible after revoke",
            vm.publishedInventory.value.items.none { !it.isVideo })
        assertNull("the published visual scope is dropped", choices.loadPublishedVisual())
        assertNull("no face catalogue may stay readable", faces?.activeEpoch())
        Log.i(TAG, "event=grant_verify hidden=true")
        saveUiEvidence("grantloss620-after-people.png")
    }

    @Test fun grantLossCleanup() {
        assumeTrue(phase == "cleanup")
        val matches = GalleryRepository(target).load().filter {
            it.relativePath == "Pictures/$GRANT_FOLDER/" && it.name.startsWith("IMG_RESUME620_")
        }
        matches.forEach { row ->
            assertEquals("only the exact invented fixture may be deleted", 1,
                target.contentResolver.delete(Uri.parse(row.uri), null, null))
        }
        clearChoices()
        root.deleteRecursively()
    }

    private fun has(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(60_000, predicate) }
        catch (error: Throwable) { throw AssertionError("Waiting for $label", error) }
    }

    private companion object {
        const val TAG = "ATLAS_RESUME620"
        const val PHOTOS = 6
        const val FIRST_PEOPLE_MS = 8_000L
        const val FOLDER = "ATLAS-Resume620"
        const val GRANT_FOLDER = "ATLAS-GrantLoss620"
        const val NAME = "Synthetic Resume Person"
        const val MODEL = "ci-resume620-visual-v1"
    }
}
