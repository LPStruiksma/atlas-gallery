package nl.atlas.gallery

import android.Manifest
import android.util.Log
import android.os.SystemClock
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #600, the People prototype acceptance journey in the REAL MainActivity at
 * the owner's S24+ size. Six invented [SyntheticFace] drawings of one
 * identity are written to MediaStore (deleted afterwards); the app's own
 * People tab, review, person page and Search drive a [PeopleDiscoveryHost]
 * with the bundled YuNet + SFace models over an app-private test database.
 *
 * Hard assertion (Lukas 11-11): one identity with N≥5 photos is ONE People
 * tile holding all N photos, never one tile per face. Then: name it, open the
 * person, find it by name in Search, survive an Activity + host restart, and
 * hide everything when photo access is lost.
 *
 * Only the six fixtures are decoded: any other emulator media (e.g. earlier
 * UI evidence) is skipped by the reader, so no other picture can add a group.
 * Scan batches run in the test (no WorkManager), exactly as the worker would.
 */
@RunWith(AndroidJUnit4::class)
class PeoplePrototypeJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "people-prototype-" + UUID.randomUUID())
    private val inserted = mutableListOf<Uri>()
    /** MediaStore id -> the face drawn into that file. */
    private val specs = HashMap<Long, SyntheticFace.Spec>()
    @Volatile private var granted = true
    private val stores = mutableListOf<FaceIndexStore>()
    /** The "Discover faces" choice survives a restart, like the app's preferences. */
    private val optIn = object : PeopleOptInMemory {
        @Volatile var value: PeopleOptIn? = null
        override fun load() = value
        override fun save(value: PeopleOptIn?) { this.value = value }
    }
    private lateinit var host: PeopleDiscoveryHost
    private lateinit var store: FaceIndexStore

    private val setup = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
                clearGalleryInventoryCacheForTest(target)
                // No earlier test's host may serve a scan session here.
                PeopleScanWorkRuntime.clear()
                try {
                    insertFaces()
                    installHost()
                    base.evaluate()
                } finally {
                    PeopleDiscoveryHost.replaceAppForTest(null)
                    PeopleScanWorkRuntime.clear()
                    stores.forEach { runCatching { it.close() } }
                    dir.deleteRecursively()
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(setup).around(compose)

    private fun insertFaces() {
        val now = System.currentTimeMillis()
        for (i in 1..PHOTOS) {
            // One identity: the same drawn face, framed a little differently each time.
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_PEOPLE_PROTOTYPE_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$FOLDER/")
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "Could not create synthetic face fixture $i" }
            inserted += uri
            val bitmap = SyntheticFace.bitmap(spec)
            try {
                requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            target.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
            }, null, null)
            specs[ContentUris.parseId(uri)] = spec
        }
    }

    /** A fresh host object over the same app-private test database: a process restart. */
    private fun installHost() {
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
        store = FaceIndexStore(context).also { stores += it }
        host = PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { granted },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = Uri.parse(directory.authorizedUri(lease.media)).lastPathSegment
                        ?.toLongOrNull()
                    val spec = id?.let { specs[it] } ?: throw FaceSourceSkipped("NOT_A_FIXTURE")
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(spec))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            faceCropDecoder = MediaStoreFaceCropDecoder(target.contentResolver),
            optInMemory = optIn
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    @Test fun oneIdentityIsOneTileThenNamedFoundAndKeptAcrossRestart() {
        // 1. People: explicit opt-in, then the scan runs to the end.
        openPeople()
        assertNull("no face session before opt-in", runBlocking { runPeopleScanBatch(target) { false } })
        startDiscovery()
        val epoch1 = drainScan()
        assertEquals("all $PHOTOS fixtures scanned", PHOTOS, store.progress(epoch1)!!.processed)

        // 2. THE assertion: one identity, N≥5 photos -> exactly one tile holding them all.
        val home = requireNotNull(store.peopleHome(epoch1))
        assertEquals("one identity must be ONE group, not one per face: ${home.unnamed}",
            1, home.unnamed.size)
        assertTrue("the group holds every photo (${home.unnamed[0].faceCount})",
            home.unnamed[0].faceCount >= PHOTOS)
        waitForTag("atlas-people-unnamed")
        compose.waitForIdle()
        assertEquals("People shows one tile for the one identity", 1,
            compose.onAllNodesWithTag("atlas-people-unnamed").fetchSemanticsNodes().size)
        settle()
        saveUiEvidence("prototype-people-unnamed.png")

        // 3. Name the group in the app's review.
        compose.onAllNodesWithTag("atlas-people-unnamed").onFirst().performClick()
        waitForText("New person's name")
        compose.onAllNodesWithText("New person's name").onFirst().performTextInput(NAME)
        compose.onAllNodesWithText("Name this group").onFirst().performClick()
        waitFor("the name is stored") { store.peopleReview(epoch1)?.names?.containsValue(NAME) == true }
        val person: PersonKey = store.peopleReview(epoch1)!!.names.entries.single { it.value == NAME }.key
        assertEquals("every photo of the identity belongs to the named person",
            specs.size, store.personMedia(epoch1, person).size)
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasText("Back to People"))
        compose.onAllNodesWithText("Back to People").onFirst().performClick()
        waitForTag("atlas-native-person-album")
        assertEquals("one named tile", 1,
            compose.onAllNodesWithTag("atlas-native-person-album").fetchSemanticsNodes().size)
        assertTrue("no unnamed tile left over",
            compose.onAllNodesWithTag("atlas-people-unnamed").fetchSemanticsNodes().isEmpty())
        settle()
        saveUiEvidence("prototype-people-named.png")

        // 4. The person page shows all of the identity's photos.
        compose.onNodeWithTag("atlas-native-person-album").performClick()
        waitForText(NAME)
        waitFor("person page shows all $PHOTOS photos") { fixtureTilesShown() == specs.keys }
        settle()
        saveUiEvidence("prototype-person-page.png")
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitForTag("atlas-native-person-album")

        // 5. Search by the name finds exactly those photos.
        compose.onNodeWithTag("atlas-nav-search").performClick()
        waitForTag("atlas-search-field")
        compose.onNodeWithTag("atlas-search-field").performTextInput(NAME.take(4))
        waitForTag("atlas-search-person-suggestion")
        compose.onAllNodesWithTag("atlas-search-person-suggestion").onFirst().performClick()
        waitForTag("atlas-search-person-chip")
        waitFor("Search by name shows the person's photos") { fixtureTilesShown() == specs.keys }
        settle()
        saveUiEvidence("prototype-search-named.png")

        // 6. Restart: a new host object over the same database, a recreated Activity.
        val before = host
        val oldStore = store
        PeopleScanWorkRuntime.clear()
        installHost()
        assertNotEquals(before, host)
        compose.activityRule.scenario.recreate()
        oldStore.close()
        openPeople()
        val epoch2 = drainUntilKept(person)
        assertNotEquals("a restart serves a new epoch", epoch1, epoch2)
        assertEquals("the person keeps all $PHOTOS photos after restart",
            PHOTOS, store.personMedia(epoch2, person).size)
        waitForTag("atlas-native-person-album")
        waitForText(NAME)
        assertEquals("still one person after restart", 1,
            compose.onAllNodesWithTag("atlas-native-person-album").fetchSemanticsNodes().size)
        settle()
        saveUiEvidence("prototype-people-restarted.png")

        // 7. Photo access lost: the next gallery publication revokes and People hides everyone.
        granted = false
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { host.onGalleryPublication("revoked") { emptyList() } }
        waitFor("People hidden after revoke") {
            compose.onAllNodesWithTag("atlas-native-person-album").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("atlas-people-unnamed").fetchSemanticsNodes().isEmpty()
        }
        assertNull("no scan session without access", runBlocking { runPeopleScanBatch(target) { false } })
        granted = true
    }

    /**
     * #643: "Discover faces" is the empty page's one button; when the grid
     * already shows people (e.g. imported profiles left by another test) it
     * sits in People ⋮ instead.
     */
    private fun startDiscovery() {
        waitForTag("atlas-people-home")
        // A tap that lands while People recomposes (tiles loading, menu
        // animating) can be dropped, and the scan then never starts. Retry
        // until the screen shows the scan running.
        repeat(3) { attempt ->
            if (attempt > 0 && scanShown()) return
            clickStart()
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                compose.waitForIdle()
                if (scanShown()) return
                Thread.sleep(200)
            }
        }
    }

    /** One tap on "Discover faces", from the empty page or from People ⋮. */
    private fun clickStart() {
        val emptyButton = compose.onAllNodesWithText("Discover faces", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (compose.onAllNodes(hasTestTag("atlas-people-empty"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() && emptyButton.isNotEmpty()
        ) {
            compose.onAllNodesWithText("Discover faces").onFirst().performClick()
            return
        }
        compose.onNodeWithTag("atlas-header-more").performClick()
        compose.waitForIdle()
        if (compose.onAllNodes(hasTestTag("atlas-people-start"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithTag("atlas-people-start").performClick()
        } else {
            // Start is gone, so the scan is no longer idle: close the menu.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    /** The scan has left idle: its progress line shows, or "Discover faces" is gone from the empty page. */
    private fun scanShown(): Boolean {
        if (compose.onAllNodes(hasTestTag("atlas-people-progress-line"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()) return true
        val empty = compose.onAllNodes(hasTestTag("atlas-people-empty"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        return empty && compose.onAllNodesWithText("Discover faces", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty()
    }

    private fun openPeople() {
        waitForTag("atlas-nav-people")
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitForTag("atlas-people-home")
    }

    /**
     * Runs scan batches, as the worker would, until nothing is pending in the
     * live epoch; returns that epoch.
     *
     * #658: the Activity may publish the gallery again at any moment (a late
     * MediaStore refresh after the fixture inserts, or the Activity's own
     * second pass after a recreate). The host then moves to a new epoch:
     * while it publishes, `acquire` returns null, and afterwards the new epoch
     * has its own pending photos. "Done" therefore means a batch reported
     * NO_PENDING for the same epoch that is still live after it, never just
     * "the last batch had nothing to do". The 60 s bound and the batch cap
     * stay as strict as before.
     */
    private fun drainScan(): String {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var batches = 0
        var moves = 0
        while (true) {
            val before = liveScanEpoch()
            val result = before?.let { runBlocking { runPeopleScanBatch(target) { false } } }
            if (result == null) {
                // The host is still publishing the gallery to its new epoch.
                check(SystemClock.elapsedRealtime() < deadline) {
                    "no scan session within 60 s (epoch moved $moves times)"
                }
                Thread.sleep(100)
                continue
            }
            if (result.stop == FaceBatchStop.NO_PENDING) {
                val after = liveScanEpoch()
                if (after == before) {
                    compose.waitForIdle()
                    return after
                }
                // A new publication arrived during or after the batch: drain it too.
                // A publication loop would be an app bug, not something to wait out.
                check(++moves <= 5) { "the gallery kept republishing: epoch moved $moves times" }
                Log.i("ATLAS_TEST", "drainScan: epoch moved during the drain ($moves)")
                continue
            }
            check(++batches <= 600) { "scan did not finish: ${result.stop}" }
        }
    }

    /** The epoch a worker would scan right now, or null while the host is publishing. */
    /**
     * #658: after a restart the Activity publishes the gallery more than once
     * (first without, then with the evidence screenshots saved earlier), so
     * the epoch one [drainScan] returns can be replaced a moment later and
     * its rows dropped. Drains again until the drained live epoch still
     * serves [person]'s photos. A person really lost across the restart
     * still fails: the live epoch then stays put with fewer photos.
     */
    private fun drainUntilKept(person: PersonKey): String {
        var epoch = drainScan()
        repeat(5) {
            if (store.personMedia(epoch, person).size == PHOTOS) return epoch
            if (liveScanEpoch() == epoch) Thread.sleep(500)
            Log.i("ATLAS_TEST", "drainUntilKept: epoch $epoch lacks the person, draining again")
            epoch = drainScan()
        }
        return epoch
    }

    private fun liveScanEpoch(): String? = PeopleScanWorkRuntime.acquire(target)?.use { it.epoch }

    /** MediaStore ids of the fixture photos currently laid out as grid tiles. */
    private fun fixtureTilesShown(): Set<Long> =
        compose.onAllNodes(SemanticsMatcher("media tile") { node ->
            tileId(node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()) != null
        }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { tileId(it.config[SemanticsProperties.TestTag]) }
            .filter { it in specs }.toSet()

    private fun tileId(tag: String): Long? = when {
        tag.startsWith("atlas-timeline-media-") -> tag.removePrefix("atlas-timeline-media-").toLongOrNull()
        tag.startsWith("atlas-grid-media-") -> tag.removePrefix("atlas-grid-media-")
            .substringBefore('-').toLongOrNull()
        else -> null
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $label. Screen:\n$tree", e)
        }
    }

    private fun waitForTag(tag: String) = waitFor("tag $tag") {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = waitFor("text '$text'") {
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    /** Let thumbnails and face crops decode before a screenshot. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(1_200)
        compose.waitForIdle()
    }

    private companion object {
        const val PHOTOS = 6
        const val NAME = "Synthetic Ada"
        const val FOLDER = "ATLAS-People-Prototype"
    }
}
