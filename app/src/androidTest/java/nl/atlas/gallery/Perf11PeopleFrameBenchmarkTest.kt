package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleHomeGroup
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * PERF-11A (#545, parent #140): rendered first-frame and fling measurements
 * for People and the person page, on a synthetic index only.
 *
 * Fixture (built once per class, entirely before any measurement window):
 * 500 synthetic people, 50,000 synthetic face observations, one person with
 * 5,000 items whose media keys point at tiny locally drawn PNGs so the person
 * grid draws real thumbnails. No real photos, identities or embeddings: the
 * embedding column holds a one-byte placeholder and names are "Synthetic NNN".
 *
 * What is measured (wall clock, System.nanoTime):
 *  - People: "open" (a state flip on the main thread) until the first draw
 *    pass of the People screen that already holds the loaded grid. The open
 *    includes the grant-scoped [FaceIndexStore.peopleHome] read on IO, the
 *    composition and the first draw, like the host does it.
 *  - Person page: "open" until the first draw pass of [PersonPageScreen] with
 *    its grid, fed by keyset-paged [FaceIndexStore.personMediaPage] reads.
 *    PersonPageRoute's own resolution path (owned by PR #420) is not used.
 *  - Person grid fling: platform FrameMetrics TOTAL_DURATION per frame,
 *    frame-stepped so every animation frame is really drawn; share of frames
 *    over the display's refresh budget, and allocation counts where ART
 *    reports them.
 * Iteration 0 is reported as "first" (first open of the screen in this test,
 * new composition, JIT not warm for it); the rest are warm. Other tests may
 * have run earlier in the same process, so "first" is not a process cold start.
 *
 * Numbers are reported on logcat `ATLAS_SCALE` (the CI emulator script prints
 * that tag) and are NOT asserted: emulator timing is noise for a hard gate.
 * The #140 thresholds (People and person p50 <= 200 ms, fling jank < 1%) are
 * echoed next to each number as `*_meets_140` for Review. The #352 SQL
 * witness (PersonMediaPagePerfTest) is kept; its assertions are not repeated.
 *
 * Optional larger catalogue (not on CI by default):
 *   -e atlasPerf11Catalogue 250000   stages extra face-less catalogue rows.
 * Run on a supported API 35 emulator:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=nl.atlas.gallery.Perf11PeopleFrameBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class Perf11PeopleFrameBenchmarkTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    /** Collects FrameMetrics TOTAL_DURATION (ms) between start and stop. */
    private class FrameRecorder(private val window: Window) {
        private val thread = HandlerThread("frame-metrics-perf11a").apply { start() }
        private val samples = Collections.synchronizedList(ArrayList<Double>())
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            samples += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }

        fun start() {
            samples.clear()
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        fun stop(): DoubleArray {
            window.removeOnFrameMetricsAvailableListener(listener)
            Thread.sleep(200) // late callbacks for the last frames
            return synchronized(samples) { samples.toDoubleArray() }
        }

        fun quit() = thread.quitSafely()
    }

    private fun clearGridDensity() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun refreshRateHz(): Float =
        compose.activity.window.decorView.display?.refreshRate?.takeIf { it > 1f } ?: 60f

    private fun device(): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("api", Build.VERSION.SDK_INT)
        .put("emulator", BenchmarkReport.isEmulator())
        .put("display", "1080x2340 @450dpi")
        .put("refresh_hz", BenchmarkReport.round1(refreshRateHz().toDouble()))

    private fun opens(ms: List<Double>, thresholdMs: Double): JSONObject {
        val warm = ms.drop(1).toDoubleArray()
        val p50 = BenchmarkReport.percentile(warm, 50.0)
        return JSONObject()
            .put("first_ms", BenchmarkReport.round1(ms.first()))
            .put("warm_samples", warm.size)
            .put("warm_p50_ms", BenchmarkReport.round1(p50))
            .put("warm_p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(warm, 90.0)))
            .put("warm_max_ms", BenchmarkReport.round1(warm.maxOrNull() ?: Double.NaN))
            .put("threshold_p50_ms", thresholdMs)
            .put("warm_p50_meets_140", !p50.isNaN() && p50 <= thresholdMs)
            .put("raw_ms", JSONArray(ms.map { BenchmarkReport.round1(it) }))
    }

    private fun report(unit: String, body: JSONObject) {
        val json = body.put("unit", unit).put("schema", 1).put("device", device()).toString()
        println("${BenchmarkReport.TAG} $unit json $json")
        Log.i(BenchmarkReport.TAG, "$unit json $json")
    }

    private val nativeTile = SemanticsMatcher.expectValue(
        SemanticsProperties.TestTag, "atlas-native-person-album")

    private fun nativeTileCount(): Int =
        compose.onAllNodes(nativeTile, useUnmergedTree = true).fetchSemanticsNodes().size

    private val timelineTile = SemanticsMatcher("timeline photo tile") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-timeline-media-") == true
    }

    /** Waits until [stamp] is set by a draw pass and returns it. */
    private fun awaitDraw(stamp: AtomicLong): Long {
        compose.waitUntil(120_000L) { stamp.get() != 0L }
        return stamp.get()
    }

    @Test fun peopleGridOpenToFirstRenderedFrame() {
        val fx = fixture()
        var open by mutableStateOf(false)
        var run by mutableIntStateOf(0)
        val drawn = AtomicLong(0L)
        var loaded: PeopleHomeSummary? = null
        compose.setContent {
            AtlasEvidenceHarness {
                if (open) {
                    val summary by produceState<PeopleHomeSummary?>(null, run) {
                        value = withContext(Dispatchers.IO) { loadHome(fx.store, fx.epoch) }
                    }
                    val shown = summary
                    if (shown != null) {
                        loaded = shown
                        Box(Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            drawn.compareAndSet(0L, System.nanoTime())
                        }) {
                            PeopleHomeScreen(
                                state = PeopleHomeUiState(),
                                onOpenProfile = {},
                                currentPermissionEpoch = fx.epoch,
                                nativePeopleReview = shown,
                                nativePeopleSourceGeneration = shown.sourceGeneration,
                                onOpenNativePersonAlbum = {},
                                liveNativePeopleAtAction = { shown }
                            )
                        }
                    }
                }
            }
        }
        val samples = ArrayList<Double>()
        repeat(OPENS) { i ->
            compose.runOnIdle { open = false; drawn.set(0L) }
            compose.waitForIdle()
            var t0 = 0L
            compose.runOnIdle { run = i; t0 = System.nanoTime(); open = true }
            val t1 = awaitDraw(drawn)
            samples += (t1 - t0) / 1e6
            compose.waitForIdle()
            assertTrue("first People frame shows person tiles", nativeTileCount() > 0)
        }
        val home = requireNotNull(loaded)
        assertEquals(PEOPLE, home.people.size)
        assertEquals(BIG_PERSON_ITEMS, home.people.getValue(BIG_PERSON).mediaCount)
        report("PERF-11A-people-open", JSONObject()
            .put("inputs", "people=$PEOPLE faces=$FACES catalogue=${fx.catalogue}; " +
                "signal=first draw pass holding the loaded People grid")
            .put("open", opens(samples, 200.0)))
    }

    @Suppress("DEPRECATION") // Debug alloc counting: reported only when ART fills it
    @Test fun personPageOpenToFirstRenderedFrameAndFlingJank() {
        val fx = fixture()
        clearGridDensity()
        var open by mutableStateOf(false)
        var run by mutableIntStateOf(0)
        val drawn = AtomicLong(0L)
        val pageSizes = Collections.synchronizedList(ArrayList<Int>())
        compose.setContent {
            AtlasEvidenceHarness {
                if (open) {
                    val items by produceState<List<MediaRecord>?>(null, run) {
                        value = withContext(Dispatchers.IO) {
                            loadPerson(fx.store, fx.epoch, BIG_PERSON) { pageSizes += it }
                        }
                    }
                    val shown = items
                    if (shown != null) {
                        Box(Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            drawn.compareAndSet(0L, System.nanoTime())
                        }) {
                            PersonPageScreen(
                                ui = PersonPageUi(name = "Synthetic 000", items = shown,
                                    hidden = false, mayHide = true),
                                gridState = rememberLazyGridState(),
                                catalogueEpoch = 11L,
                                opening = run.toLong(),
                                portrait = {},
                                onBack = {},
                                onOpen = {},
                                onDialog = {},
                                nameDialog = {},
                                mergePicker = {},
                                onHide = {},
                                selectedUris = null,
                                onSelectionChange = {},
                                onRemoveSelected = null,
                                confirmMerge = {}
                            )
                        }
                    }
                }
            }
        }
        val samples = ArrayList<Double>()
        repeat(OPENS) { i ->
            compose.runOnIdle { open = false; drawn.set(0L); pageSizes.clear() }
            compose.waitForIdle()
            var t0 = 0L
            compose.runOnIdle { run = i; t0 = System.nanoTime(); open = true }
            val t1 = awaitDraw(drawn)
            samples += (t1 - t0) / 1e6
            compose.waitForIdle()
            assertTrue("first person frame shows grid tiles",
                compose.onAllNodes(timelineTile, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
            // Bounded reads: every page within its size, all 5,000 items, one
            // empty terminating page. No unbounded person read on this path.
            val pages = synchronized(pageSizes) { pageSizes.toList() }
            assertTrue("person pages bounded: $pages", pages.all { it in 0..PAGE })
            assertEquals(BIG_PERSON_ITEMS, pages.sum())
            assertEquals(0, pages.last())
        }

        // Scripted flings down the person grid, frame-stepped so each
        // animation frame is drawn and timed by the platform.
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        val recorder = FrameRecorder(compose.activity.window)
        val runtime = Runtime.getRuntime()
        val fling: DoubleArray
        val allocCount: Int
        val allocBytes: Int
        val heapDeltaKb: Long
        try {
            compose.mainClock.autoAdvance = false
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            Debug.resetAllCounts()
            Debug.startAllocCounting()
            recorder.start()
            repeat(FLINGS) {
                grid.performTouchInput { swipeUp() }
                repeat(FRAMES_PER_FLING) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                }
            }
            fling = recorder.stop()
            Debug.stopAllocCounting()
            allocCount = Debug.getGlobalAllocCount()
            allocBytes = Debug.getGlobalAllocSize()
            heapDeltaKb = (runtime.totalMemory() - runtime.freeMemory() - heapBefore) / 1024
        } finally {
            compose.mainClock.autoAdvance = true
            recorder.quit()
        }
        val budgetMs = 1000.0 / refreshRateHz()
        val slow = fling.count { it > budgetMs }
        val slowPct = if (fling.isEmpty()) Double.NaN else 100.0 * slow / fling.size
        val allocSupported = allocCount > 0 || allocBytes > 0
        report("PERF-11A-person-open-fling", JSONObject()
            .put("inputs", "person_items=$BIG_PERSON_ITEMS page=$PAGE catalogue=${fx.catalogue}; " +
                "24px synthetic PNG thumbnails; signal=first draw pass holding the person grid")
            .put("open", opens(samples, 200.0))
            .put("fling", JSONObject()
                .put("flings", FLINGS)
                .put("frames", fling.size)
                .put("budget_ms", BenchmarkReport.round1(budgetMs))
                .put("slow_frames", slow)
                .put("slow_pct", BenchmarkReport.round1(slowPct))
                .put("frozen_frames_700ms", fling.count { it > 700.0 })
                .put("median_ms", BenchmarkReport.round1(BenchmarkReport.percentile(fling, 50.0)))
                .put("p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(fling, 90.0)))
                .put("p99_ms", BenchmarkReport.round1(BenchmarkReport.percentile(fling, 99.0)))
                .put("threshold_slow_pct", 1.0)
                .put("slow_pct_meets_140", !slowPct.isNaN() && slowPct < 1.0)
                .put("alloc_supported", allocSupported)
                .put("alloc_count", if (allocSupported) allocCount else JSONObject.NULL)
                .put("alloc_bytes", if (allocSupported) allocBytes else JSONObject.NULL)
                .put("heap_delta_kb", heapDeltaKb)))
        assertTrue("fling frames were recorded", fling.isNotEmpty())
    }

    /**
     * Fail-closed grant/epoch behaviour, observed through the real screens and
     * store without changing either. Own small fixture: revoking the scope must
     * not disturb the shared one.
     */
    @Test fun revokedGrantShowsNoStalePeople() {
        val small = buildFixture(people = 30, faces = 600, bigPersonItems = 60,
            thumbnails = false, catalogue = 600)
        try {
            val store = small.store
            val oldEpoch = small.epoch
            val loadedHome = requireNotNull(loadHome(store, oldEpoch))
            assertEquals(30, loadedHome.people.size)
            var epochNow by mutableStateOf(oldEpoch)
            var shown by mutableStateOf<PeopleHomeSummary?>(loadedHome)
            compose.setContent {
                AtlasEvidenceHarness {
                    PeopleHomeScreen(
                        state = PeopleHomeUiState(),
                        onOpenProfile = {},
                        currentPermissionEpoch = epochNow,
                        nativePeopleReview = shown,
                        nativePeopleSourceGeneration = shown?.sourceGeneration,
                        onOpenNativePersonAlbum = {},
                        liveNativePeopleAtAction = { shown }
                    )
                }
            }
            compose.waitForIdle()
            assertTrue("current grant shows people", nativeTileCount() > 0)

            store.invalidatePermissionScope()
            assertNull(store.peopleHome(oldEpoch))
            assertNull(store.peopleReviewGeneration(oldEpoch))
            assertTrue(store.personMediaPage(oldEpoch, BIG_PERSON).isEmpty())

            // A new grant epoch with the previous summary still held: the
            // screen must not draw any of the old people.
            compose.runOnIdle { epochNow = "perf-11a-regrant-" + UUID.randomUUID() }
            compose.waitForIdle()
            assertEquals("stale People after regrant", 0, nativeTileCount())

            // The host's re-read under the revoked epoch yields nothing either.
            val reread = loadHome(store, oldEpoch) // test thread, not main
            compose.runOnIdle { epochNow = oldEpoch; shown = reread }
            compose.waitForIdle()
            assertNull(shown)
            assertEquals("People after revoke", 0, nativeTileCount())
        } finally {
            small.close()
        }
    }

    private class Fixture(
        val dir: File, val store: FaceIndexStore, val epoch: String, val catalogue: Int
    ) {
        fun close() {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    companion object {
        private const val PEOPLE = 500
        private const val FACES = 50_000
        private const val BIG_PERSON_ITEMS = 5_000
        private val BIG_PERSON = PersonKey("person-000")
        private const val PAGE = 96
        private const val OPENS = 8 // 1 first + 7 warm
        private const val FLINGS = 3
        private const val FRAMES_PER_FLING = 60

        private var shared: Fixture? = null

        @Synchronized private fun fixture(): Fixture = shared ?: run {
            val requested = InstrumentationRegistry.getArguments()
                .getString("atlasPerf11Catalogue")?.toIntOrNull()
            val catalogue = (requested ?: FACES).coerceIn(FACES, 250_000)
            buildFixture(PEOPLE, FACES, BIG_PERSON_ITEMS, thumbnails = true,
                catalogue = catalogue).also { shared = it }
        }

        @BeforeClass @JvmStatic fun clean() { shared?.close(); shared = null }

        @AfterClass @JvmStatic fun tearDown() { shared?.close(); shared = null }

        private fun contextFor(file: File): Context {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            return object : ContextWrapper(base) {
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
        }

        /** Tiny locally drawn PNGs for the big person's media, reused across runs. */
        private fun thumbnailUris(count: Int): List<String> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "atlas-perf11a-thumbs").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            try {
                return (0 until count).map { i ->
                    val image = File(dir, "perf11a-%05d.png".format(i))
                    if (!image.exists() || image.length() == 0L) {
                        Canvas(bitmap).drawColor(android.graphics.Color.HSVToColor(
                            floatArrayOf((i * 37f) % 360f, .5f, .8f)))
                        FileOutputStream(image).use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    Uri.fromFile(image).toString()
                }
            } finally {
                bitmap.recycle()
            }
        }

        /**
         * Synthetic index: [faces] observations, the first [bigPersonItems] on
         * person-000 and the rest spread over the other people, one face per
         * media item, all photos. Extra face-less rows pad the catalogue to
         * [catalogue]. Then marks the fixture's generation as published, like
         * PersonMediaPagePerfTest does, so People reads are servable.
         */
        private fun buildFixture(
            people: Int, faces: Int, bigPersonItems: Int, thumbnails: Boolean, catalogue: Int
        ): Fixture {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "perf-11a-" + UUID.randomUUID())
            check(dir.mkdirs())
            val epoch = "perf-11a-grant-" + UUID.randomUUID()
            val store = FaceIndexStore(contextFor(File(dir, "synthetic.db")))
            val thumbs = if (thumbnails) thumbnailUris(bigPersonItems) else emptyList()
            val media = (0 until faces).map { n ->
                IndexedMedia(thumbs.getOrNull(n) ?: "content://synthetic/perf11a/%06d".format(n), "r1")
            }
            val extra = (faces until catalogue).map { n ->
                IndexedMedia("content://synthetic/perf11a-extra/%06d".format(n), "r1")
            }
            store.beginSnapshot(epoch)
            (media + extra).chunked(500).forEach { store.stageBatch(epoch, it) }
            store.commitSnapshot(epoch, "detector", "model-v1")
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val personInsert = db.compileStatement(
                    "INSERT INTO people_names(person_key,name) VALUES(?,?)")
                for (p in 0 until people) {
                    personInsert.clearBindings()
                    personInsert.bindString(1, "person-%03d".format(p))
                    personInsert.bindString(2, "Synthetic %03d".format(p))
                    personInsert.executeInsert()
                }
                val faceInsert = db.compileStatement(
                    "INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b) VALUES(?,0,?,0.1,0.1,0.5,0.5)")
                val embeddingInsert = db.compileStatement(
                    "INSERT INTO embeddings(face_uid,source_revision,model_version,vector) VALUES(?,'r1','model-v1',X'00')")
                val confirmedInsert = db.compileStatement(
                    "INSERT INTO people_confirmed(face_uid,person_key) VALUES(?,?)")
                val processed = db.compileStatement(
                    "UPDATE catalog SET state='processed',taken_ms=? WHERE media_key=?")
                for (n in 0 until faces) {
                    val person = if (n < bigPersonItems) 0 else 1 + (n - bigPersonItems) % (people - 1)
                    val uid = "%032x".format(n + 1)
                    val key = media[n].key
                    processed.clearBindings()
                    processed.bindLong(1, takenMs(n))
                    processed.bindString(2, key)
                    processed.executeUpdateDelete()
                    faceInsert.clearBindings()
                    faceInsert.bindString(1, key)
                    faceInsert.bindString(2, uid)
                    faceInsert.executeInsert()
                    embeddingInsert.clearBindings()
                    embeddingInsert.bindString(1, uid)
                    embeddingInsert.executeInsert()
                    confirmedInsert.clearBindings()
                    confirmedInsert.bindString(1, uid)
                    confirmedInsert.bindString(2, "person-%03d".format(person))
                    confirmedInsert.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("UPDATE cluster_generation SET dirty=0,pending=0 WHERE singleton=1")
            return Fixture(dir, store, epoch, catalogue)
        }

        /** Three photos a day, newest first, like a camera roll. */
        private fun takenMs(n: Int): Long = 1_700_000_000_000L - (n / 3) * 86_400_000L

        /** The host's mapping (PeopleDiscoveryHost.currentHome), on a store read. */
        private fun loadHome(store: FaceIndexStore, epoch: String): PeopleHomeSummary? {
            val stored = store.peopleHome(epoch) ?: return null
            return PeopleHomeSummary(epoch, "$epoch|${stored.generation}",
                stored.people.mapValues { (_, person) ->
                    PeopleHomePerson(person.name, person.faceCount, person.coverFaceUid,
                        person.mediaCount, person.firstAppearanceMs, person.mostRecentAppearanceMs,
                        person.photoCount, person.videoCount)
                }, stored.unnamed.map { PeopleHomeGroup(it.anchorUid, it.faceCount) })
        }

        /** Keyset-paged person media mapped to grid records; each page size reported. */
        private fun loadPerson(
            store: FaceIndexStore, epoch: String, person: PersonKey, onPage: (Int) -> Unit
        ): List<MediaRecord> {
            val out = ArrayList<MediaRecord>(BIG_PERSON_ITEMS)
            var cursor: String? = null
            while (true) {
                val page = store.personMediaPage(epoch, person, cursor, PAGE)
                onPage(page.size)
                if (page.isEmpty()) break
                for (m in page) {
                    val n = out.size
                    out += MediaRecord(n.toLong(), m.key, "Synthetic $n", "DCIM/ATLAS-TEST/",
                        100L, takenMs(n), false)
                }
                cursor = page.last().key
            }
            return out
        }
    }
}
