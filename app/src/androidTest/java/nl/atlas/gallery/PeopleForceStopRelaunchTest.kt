package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * #31: separate instrumentation invocations with an adb am force-stop between
 * them (scripts/ci_android_upgrade_emulator.sh). Real MainActivity, Android
 * MediaStore, gallery publication, opt-in preferences and SQLite persist across
 * an actual process boundary. Only invented PNGs and a test-only database are
 * used. The ordinary connected test suite skips these manually driven phases.
 */
@RunWith(AndroidJUnit4::class)
class PeopleForceStopRelaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val root = File(target.noBackupFilesDir, "atlas-ci-relaunch31")
    private val prefsName = "atlas-ci-relaunch31-opt-in"
    private val choices = PeopleOptInMemory.preferences(object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            target.getSharedPreferences(prefsName, mode)
    })
    private val specs = HashMap<Long, SyntheticFace.Spec>()
    private var faces: FaceIndexStore? = null
    private var visual: VisualIndexStore? = null
    private var host: PeopleDiscoveryHost? = null

    private val prepare = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val phase = InstrumentationRegistry.getArguments().getString("atlasForceStopPhase")
                if (phase !in setOf("seed", "verify", "cleanup")) {
                    base.evaluate() // The test method will skip, without touching fixtures.
                    return
                }
                try {
                    assertTrue("Only an isolated emulator may execute process phases",
                        android.os.Build.FINGERPRINT.contains("generic") ||
                            android.os.Build.FINGERPRINT.contains("emulator"))
                    if (phase == "seed") {
                        assertTrue("The seed fixture must start empty", !root.exists())
                        insertFixtures()
                    } else if (phase == "verify") {
                        restoreSpecs()
                    }
                    if (phase != "cleanup") installHost()
                    base.evaluate()
                } finally {
                    PeopleDiscoveryHost.replaceAppForTest(null)
                    PeopleScanWorkRuntime.clear()
                    runCatching { faces?.close() }
                    runCatching { visual?.close() }
                }
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(prepare).around(compose)

    private fun spec(i: Int) = SyntheticFace.Spec(
        480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)

    private fun insertFixtures() {
        assertTrue(readGalleryMediaGrant(target).images)
        root.mkdirs()
        for (i in 1..PHOTOS) {
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_RELAUNCH31_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$FOLDER/")
                    put(MediaStore.Images.Media.DATE_TAKEN,
                        System.currentTimeMillis() - i * 86_400_000L)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
            val bitmap = SyntheticFace.bitmap(spec(i))
            try {
                requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            target.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            specs[ContentUris.parseId(uri)] = spec(i)
        }
        assertEquals(PHOTOS, specs.size)
    }

    private fun restoreSpecs() {
        assertTrue("Android photo grant must survive app force-stop",
            readGalleryMediaGrant(target).images)
        val records = GalleryRepository(target).load().filter {
            it.relativePath == "Pictures/$FOLDER/" &&
                it.name.startsWith("IMG_RELAUNCH31_")
        }
        for (record in records) {
            val index = record.name.removePrefix("IMG_RELAUNCH31_")
                .removeSuffix(".png").toIntOrNull() ?: continue
            specs[record.id] = spec(index)
        }
        assertEquals("real MediaStore fixtures survived process stop", PHOTOS, specs.size)
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
                    val id = Uri.parse(directory.authorizedUri(
                        nl.atlas.gallery.featureface.FaceMediaKey(media.key, media.revision)))
                        .lastPathSegment?.toLongOrNull()
                    id?.let { specs[it] }?.let { SyntheticFace.bitmap(it) }
                }
            },
            visualModelVersion = MODEL,
            visualDimension = 8
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    @Test fun seedBeforeForceStop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlasForceStopPhase") == "seed")
        waitFor("Photos tab") { has("atlas-nav-photos") }
        val activity = compose.activity
        val vm = androidx.lifecycle.ViewModelProvider(activity)[GalleryViewModel::class.java]
        compose.runOnUiThread { vm.refresh() }
        waitFor("six synthetic photos in gallery") {
            vm.publishedInventory.value.items.count { it.id in specs } == PHOTOS
        }
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor("People tab") { has("atlas-people-home") }
        waitFor("Discover faces") {
            compose.onAllNodesWithText("Discover faces").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Discover faces").onFirst().performClick()
        val epoch = drainFaceScan()
        val scanner = requireNotNull(host)
        val review = requireNotNull(scanner.reviewState(epoch))
        val group = review.clusters.unknownClusters.maxBy { it.members.size }
        val person = PersonKey("native-" + group.id.anchor.localHex)
        assertTrue("named identity persisted through real host reducer",
            scanner.review(PeopleClusterReviewCommand(review,
                PeopleReviewAction.NameCluster(group.id, person, NAME))))
        assertEquals(NAME, scanner.reviewState(epoch)?.names?.get(person))
        assertTrue("visual toggle activates with synthetic fixture",
            scanner.setVisualIndex(true))
        assertTrue(choices.loadVisual())
        waitFor("named People tile") { has("atlas-native-person-album") }
        capture("before")
    }

    @Test fun verifyAfterForceStop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlasForceStopPhase") == "verify")
        assertNotNull("People opt-in persisted beyond the process", choices.load())
        assertTrue("visual choice persisted beyond the process", choices.loadVisual())
        // The first frame must not be replaced by the first-time inventory spinner.
        waitFor("Photos grid first frame") { has("atlas-timeline-grid") }
        compose.onNodeWithTag("atlas-first-scan").assertDoesNotExist()
        captureLibrary("after")
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor("named People after a new process") { has("atlas-native-person-album") }
        val epoch = drainFaceScan()
        val scanner = requireNotNull(host)
        val review = requireNotNull(scanner.reviewState(epoch))
        assertTrue("confirmed identity survived real process death",
            review.names.values.contains(NAME))
        val progress = requireNotNull(faces?.progress(epoch))
        assertTrue("face scan did not restart from zero", progress.processed >= PHOTOS)
        assertTrue("visual index choice remained on",
            scanner.visualStatus(epoch)?.enabled == true)
        capturePeopleAndSettings("after")
    }

    @Test fun cleanupAfterForceStop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlasForceStopPhase") == "cleanup")
        val matches = GalleryRepository(target).load().filter {
            it.relativePath == "Pictures/$FOLDER/" &&
                it.name.startsWith("IMG_RELAUNCH31_")
        }
        assertEquals(PHOTOS, matches.size)
        matches.forEach { row ->
            assertEquals("only the exact invented fixture may be deleted", 1,
                target.contentResolver.delete(Uri.parse(row.uri), null, null))
        }
        assertTrue(target.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    private fun drainFaceScan(): String {
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        var batches = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val before = PeopleScanWorkRuntime.acquire(target)?.use { it.epoch }
            val result = runBlocking { runPeopleScanBatch(target) { false } }
            if (before != null && result?.stop == FaceBatchStop.NO_PENDING &&
                PeopleScanWorkRuntime.acquire(target)?.use { it.epoch } == before) {
                val progress = faces?.progress(before)
                if (progress != null && progress.processed >= PHOTOS) return before
            }
            check(++batches <= 600) { "scan failed to reach a stable epoch" }
            Thread.sleep(100)
        }
        error("Timed out waiting for six synthetic face scans")
    }

    private fun has(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(60_000, predicate) }
        catch (error: Throwable) { throw AssertionError("Waiting for $label", error) }
    }

    private fun capture(phase: String) {
        capturePeopleAndSettings(phase)
        captureLibrary(phase)
    }

    private fun capturePeopleAndSettings(phase: String) {
        compose.waitForIdle()
        saveUiEvidence("relaunch31-$phase-people.png")
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Settings action") { has("atlas-library-status") }
        compose.onNodeWithTag("atlas-library-status").performClick()
        waitFor("real Settings") { has("atlas-settings-screen") }
        assertTrue("restored status cannot say Unavailable",
            compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().isEmpty())
        assertTrue("visual option is visible", has("atlas-people-visual-index"))
        compose.waitForIdle()
        saveUiEvidence("relaunch31-$phase-settings.png")
        compose.onNodeWithTag("atlas-header-back").performClick()
    }

    private fun captureLibrary(phase: String) {
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        waitFor("Photos grid") { has("atlas-timeline-grid") }
        compose.onNodeWithTag("atlas-first-scan").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("relaunch31-$phase-photos.png")
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor("Albums first frame") { has("atlas-album-card") }
        compose.waitForIdle()
        saveUiEvidence("relaunch31-$phase-albums.png")
    }

    private companion object {
        const val PHOTOS = 6
        const val FOLDER = "ATLAS-Relaunch31"
        const val NAME = "Synthetic Relaunch Person"
        const val MODEL = "ci-relaunch31-visual-v1"
    }
}
