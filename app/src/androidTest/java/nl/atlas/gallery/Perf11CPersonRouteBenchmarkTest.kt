package nl.atlas.gallery

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.OnDeviceFaceLocator
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleHomeGroup
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.personPageFacts
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * PERF-11C (#687, parent #140, experiment record #664): times the person
 * open path the app ships since #681, split into stages, on the same
 * synthetic scale as the #549 witness ([Perf11PeopleFrameBenchmarkTest],
 * which stays unchanged). Measurement only: nothing is asserted about time.
 *
 * Fixture (built once, before any measurement window): a real
 * [PeopleDiscoveryHost] over an app-private [FaceIndexStore]. "Discover
 * faces" is started on a synthetic inventory of [CATALOGUE] canonical
 * MediaStore-shaped photo rows (no MediaStore rows exist behind them; no
 * real photo, face, name or embedding), so the host's live scope and its
 * authorized media directory are the production ones. Then 50,000 synthetic
 * faces are written against the published catalogue exactly as the witness
 * does: 500 people "Synthetic NNN", one person (person-000) on 5,000 items,
 * one-byte placeholder embeddings, catalogue rows marked processed and the
 * cluster generation marked clean. Face detection never runs (the locator
 * finds nothing and the scheduler is inert).
 *
 * Stages, each 1 first + 7 warm samples, all in ONE test method so they share
 * one emulator (CI shards split by method):
 *  1. [PeopleDiscoveryHost.personAlbumUris]: the eager
 *     [FaceIndexStore.personMedia] read plus per-key grant resolution, on
 *     the test thread (the route runs it on IO). Stage 1a times the bare
 *     store read alone so the two halves can be told apart.
 *  2. [selectPersonPageMedia] over the full catalogue record list.
 *  3. [NativePersonAlbumRoute] composed with the real host, the synthetic
 *     grant and a fresh opening per sample: open → first draw of the page
 *     (`first_any_draw`) and open → first draw whose grid has laid out a
 *     photo tile (`open`, the #140 signal, same detector as the witness).
 *     Case A passes no People summary, so the review snapshot never loads
 *     and the quick path (stages 1+2) is the only content source: this is
 *     "People snapshot still loading". Case B passes the People summary
 *     with the review snapshot already cached in the host, so the quick and
 *     the full path race as on a warm reopen; it is skipped (and says why)
 *     if the synthetic index cannot produce a review snapshot.
 *  4. Context: the witness's paged read (53 keyset pages of 96 through
 *     [FaceIndexStore.personMediaPage]) on this same emulator.
 * Thumbnails: the synthetic URIs have no MediaStore row, so tiles draw their
 * placeholder; the tile signal is layout-based and does not wait for pixels
 * (the witness's signal does not either).
 *
 * Report: logcat `ATLAS_SCALE` unit `PERF-11C-person-route`, printed by the
 * CI emulator script. Optional larger catalogue:
 *   -e atlasPerf11Catalogue 250000
 * Run on a supported API 35 emulator:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=nl.atlas.gallery.Perf11CPersonRouteBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class Perf11CPersonRouteBenchmarkTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "perf-11c-" + UUID.randomUUID())
    private var store: FaceIndexStore? = null

    /** Route content is composed only while this holds; see [cleanUp]. */
    private var composing by mutableStateOf(true)

    @After fun cleanUp() {
        // Dispose the route first: its effects still read the store.
        composing = false
        compose.waitForIdle()
        PeopleScanWorkRuntime.clear()
        store?.let { runCatching { it.close() } }
        dir.deleteRecursively()
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

    private fun refreshRateHz(): Float =
        compose.activity.window.decorView.display?.refreshRate?.takeIf { it > 1f } ?: 60f

    private fun device(): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("fingerprint", Build.FINGERPRINT)
        .put("api", Build.VERSION.SDK_INT)
        .put("emulator", BenchmarkReport.isEmulator())
        .put("cpus", Runtime.getRuntime().availableProcessors())
        .put("display", "1080x2340 @450dpi")
        .put("refresh_hz", BenchmarkReport.round1(refreshRateHz().toDouble()))

    /** p50/p90 over the warm samples, the first sample apart, all raw values. */
    private fun stage(ms: List<Double>, thresholdMs: Double? = null): JSONObject {
        val warm = ms.drop(1).toDoubleArray()
        val p50 = BenchmarkReport.percentile(warm, 50.0)
        return JSONObject()
            .put("n", ms.size)
            .put("first_ms", BenchmarkReport.round1(ms.first()))
            .put("warm_samples", warm.size)
            .put("warm_p50_ms", BenchmarkReport.round1(p50))
            .put("warm_p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(warm, 90.0)))
            .put("warm_max_ms", BenchmarkReport.round1(warm.maxOrNull() ?: Double.NaN))
            .put("raw_ms", JSONArray(ms.map { BenchmarkReport.round1(it) }))
            .also { json ->
                if (thresholdMs != null) {
                    json.put("threshold_p50_ms", thresholdMs)
                        .put("warm_p50_meets_140", !p50.isNaN() && p50 <= thresholdMs)
                        .put("warm_p50_over_by_ms",
                            BenchmarkReport.round1((p50 - thresholdMs).coerceAtLeast(0.0)))
                }
            }
    }

    private inline fun timed(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    private val timelineTile = SemanticsMatcher("timeline photo tile") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-timeline-media-") == true
    }

    /** As the witness: a photo tile is one of at least three columns. */
    private fun LazyGridLayoutInfo.holdsPhotoTile(): Boolean =
        visibleItemsInfo.any { it.size.width in 1 until viewportSize.width / 2 }

    @Test fun shippedPersonOpenStages() {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
        val requested = InstrumentationRegistry.getArguments()
            .getString("atlasPerf11Catalogue")?.toIntOrNull()
        val catalogue = (requested ?: CATALOGUE).coerceIn(FACES, 250_000)
        val fx = buildFixture(catalogue)
        val host = fx.host
        val epoch = fx.epoch

        // Stage 1a: the bare eager store read, for the split.
        val rawRead = ArrayList<Double>()
        repeat(SAMPLES) {
            var n = 0
            rawRead += timed { n = fx.store.personMedia(epoch, BIG_PERSON).size }
            assertEquals(BIG_PERSON_ITEMS, n)
        }
        // Stage 1: personAlbumUris (read + per-key grant resolution).
        val albumUris = ArrayList<Double>()
        var uris: Set<String> = emptySet()
        repeat(SAMPLES) {
            albumUris += timed { uris = requireNotNull(host.personAlbumUris(GRANT, BIG_PERSON)) }
            assertEquals(BIG_PERSON_ITEMS, uris.size)
        }
        // Stage 2: selectPersonPageMedia over the catalogue.
        val select = ArrayList<Double>()
        repeat(SAMPLES) {
            var n = 0
            select += timed { n = selectPersonPageMedia(fx.records, uris).size }
            assertEquals(BIG_PERSON_ITEMS, n)
        }
        // Stage 4 (context): the witness's keyset-paged read.
        val paged = ArrayList<Double>()
        repeat(SAMPLES) {
            var n = 0
            paged += timed { n = pagedRead(fx.store, epoch) }
            assertEquals(BIG_PERSON_ITEMS, n)
        }

        // Stage 3, case A: no People summary, so only the quick path feeds the page.
        composeRoute(host, fx.records)
        val caseA = routeOpens(people = null)

        // Stage 3, case B: People summary given, review snapshot cached in the host.
        val home = loadHome(fx.store, epoch)
        var review: PeopleClusterReviewState? = null
        val reviewColdMs = timed { review = runCatching { host.reviewState(epoch) }.getOrNull() }
        val facts = review?.let { personPageFacts(it, PersonPageTarget.Named(BIG_PERSON)) }
        val caseBSkip = when {
            home == null -> "no People summary from the synthetic index"
            review == null -> "host.reviewState returned null or threw on the synthetic index"
            facts == null -> "review snapshot has no page facts for person-000"
            else -> null
        }
        val caseB = if (caseBSkip == null) routeOpens(home) else null

        val routeA = JSONObject()
            .put("case", "A: people=null; review snapshot never loads; quick path only " +
                "(personAlbumUris on IO, then selectPersonPageMedia on Default)")
            .put("open", stage(caseA.tile, 200.0))
            .put("first_any_draw", stage(caseA.anyDraw))
        val routeB = if (caseB != null) JSONObject()
            .put("case", "B: People summary given, review snapshot cached in the host; " +
                "quick and full (personPageFaces) paths race as on a warm reopen")
            .put("open", stage(caseB.tile, 200.0))
            .put("first_any_draw", stage(caseB.anyDraw))
            .put("review_snapshot_cold_load_ms", BenchmarkReport.round1(reviewColdMs))
        else JSONObject().put("skipped", caseBSkip)
            .put("review_snapshot_cold_load_ms", BenchmarkReport.round1(reviewColdMs))

        val json = JSONObject()
            .put("unit", "PERF-11C-person-route")
            .put("schema", 1)
            .put("device", device())
            .put("inputs", "people=$PEOPLE faces=$FACES person_items=$BIG_PERSON_ITEMS " +
                "catalogue=$catalogue; real PeopleDiscoveryHost scope over a synthetic " +
                "inventory; placeholder thumbnails; signal=first draw pass whose grid " +
                "holds a photo tile")
            .put("stage1_personAlbumUris", stage(albumUris))
            .put("stage1a_personMedia_read_only", stage(rawRead))
            .put("stage2_selectPersonPageMedia", stage(select))
            .put("stage3_route_open_case_A", routeA)
            .put("stage3_route_open_case_B", routeB)
            .put("stage4_witness_paged_read", stage(paged)
                .put("pages", "keyset pages of $PAGE through personMediaPage"))
            .toString()
        println("${BenchmarkReport.TAG} PERF-11C-person-route json $json")
        Log.i(BenchmarkReport.TAG, "PERF-11C-person-route json $json")
    }

    private class Opens(val tile: List<Double>, val anyDraw: List<Double>)

    // Route state, composed once per test (setContent may run only once).
    private var request by mutableStateOf<NativePersonAlbumRequest?>(null)
    private var routePeople by mutableStateOf<PeopleHomeSummary?>(null)
    private val tile = AtomicLong(0L)
    private val anyDraw = AtomicLong(0L)

    private fun composeRoute(host: PeopleDiscoveryHost, records: List<MediaRecord>) {
        compose.setContent {
            AtlasEvidenceHarness {
                val opened = request
                if (composing && opened != null) {
                    // New per open: a kept state would still hold the
                    // previous open's layout before this grid exists.
                    val gridState = androidx.compose.runtime.key(opened.opening) {
                        rememberLazyGridState()
                    }
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        val now = System.nanoTime()
                        anyDraw.compareAndSet(0L, now)
                        // Reading layoutInfo here redraws this box when the
                        // grid lays out (#564), as in the witness.
                        if (gridState.layoutInfo.holdsPhotoTile()) tile.compareAndSet(0L, now)
                    }) {
                        androidx.compose.runtime.key(opened.opening) {
                            NativePersonAlbumRoute(host, GRANT, opened, routePeople, records,
                                gridState, CATALOGUE_EPOCH, onBack = {}, onOpen = { _, _ -> })
                        }
                    }
                }
            }
        }
    }

    /** [SAMPLES] opens of the composed route, each a new opening and a new grid state. */
    private fun routeOpens(people: PeopleHomeSummary?): Opens {
        compose.runOnIdle { request = null; routePeople = people }
        compose.waitForIdle()
        val tiles = ArrayList<Double>()
        val draws = ArrayList<Double>()
        repeat(SAMPLES) {
            compose.runOnIdle { request = null; tile.set(0L); anyDraw.set(0L) }
            compose.waitForIdle()
            var t0 = 0L
            compose.runOnIdle {
                t0 = System.nanoTime()
                request = NativePersonAlbumRequest(BIG_PERSON, "Synthetic 000")
            }
            compose.waitUntil(120_000L) { tile.get() != 0L }
            tiles += (tile.get() - t0) / 1e6
            draws += (anyDraw.get() - t0) / 1e6
            compose.waitForIdle()
            assertTrue("first person frame shows grid tiles",
                compose.onAllNodes(timelineTile, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
        }
        compose.runOnIdle { request = null }
        compose.waitForIdle()
        return Opens(tiles, draws)
    }

    private class Fixture(
        val store: FaceIndexStore,
        val host: PeopleDiscoveryHost,
        val epoch: String,
        val records: List<MediaRecord>
    )

    private fun photo(n: Int): FaceVisibleMedia {
        val id = n + 1L
        return FaceVisibleMedia(
            uri = "content://media/external_primary/images/media/$id",
            volumeName = "external_primary", isVideo = false, id = id,
            providerVersion = "synthetic-provider-1", generationModified = 10 + id,
            sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
            widthPixels = 480, heightPixels = 480, orientationDegrees = 0,
            takenAtMillis = takenMs(n))
    }

    /**
     * The host publishes the synthetic inventory through its own "Discover
     * faces" path, then the faces are written as the witness writes them,
     * against the catalogue keys and revisions the publication staged.
     */
    private fun buildFixture(catalogue: Int): Fixture {
        check(dir.mkdirs())
        val faceStore = FaceIndexStore(dbContext(File(dir, "synthetic.db"))).also { store = it }
        val host = PeopleDiscoveryHost(
            store = faceStore,
            photoGrantCurrent = { true },
            inferenceFactory = { OnDeviceFaceLocator { emptyList() } },
            readerFor = { FaceFrameReader { _, _ -> throw AssertionError("nothing is scanned") } },
            power = { PeoplePowerState(charging = false, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            }
        )
        val inventory = (0 until catalogue).map(::photo)
        host.start(GRANT, inventory)
        val epoch = checkNotNull(host.displayEpoch(GRANT))
        check(host.serves(GRANT)) { "synthetic publication is not live" }

        val db = faceStore.writableDatabase
        val revisions = HashMap<String, String>(catalogue * 2)
        db.rawQuery("SELECT media_key,revision FROM catalog", null).use { c ->
            while (c.moveToNext()) revisions[c.getString(0)] = c.getString(1)
        }
        assertEquals("every synthetic row staged", catalogue, revisions.size)
        db.beginTransaction()
        try {
            val personInsert = db.compileStatement(
                "INSERT INTO people_names(person_key,name) VALUES(?,?)")
            for (p in 0 until PEOPLE) {
                personInsert.clearBindings()
                personInsert.bindString(1, "person-%03d".format(p))
                personInsert.bindString(2, "Synthetic %03d".format(p))
                personInsert.executeInsert()
            }
            val faceInsert = db.compileStatement(
                "INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b) VALUES(?,0,?,0.1,0.1,0.5,0.5)")
            val embeddingInsert = db.compileStatement(
                "INSERT INTO embeddings(face_uid,source_revision,model_version,vector) VALUES(?,?,?,X'00')")
            val confirmedInsert = db.compileStatement(
                "INSERT INTO people_confirmed(face_uid,person_key) VALUES(?,?)")
            val processed = db.compileStatement(
                "UPDATE catalog SET state='processed',taken_ms=? WHERE media_key=?")
            for (n in 0 until FACES) {
                val person = if (n < BIG_PERSON_ITEMS) 0 else 1 + (n - BIG_PERSON_ITEMS) % (PEOPLE - 1)
                val uid = "%032x".format(n + 1)
                val key = inventory[n].toDurableFaceKey().canonicalMediaKey
                val revision = checkNotNull(revisions[key]) { "row $n not staged" }
                processed.clearBindings()
                processed.bindLong(1, takenMs(n))
                processed.bindString(2, key)
                check(processed.executeUpdateDelete() == 1)
                faceInsert.clearBindings()
                faceInsert.bindString(1, key)
                faceInsert.bindString(2, uid)
                faceInsert.executeInsert()
                embeddingInsert.clearBindings()
                embeddingInsert.bindString(1, uid)
                embeddingInsert.bindString(2, revision)
                embeddingInsert.bindString(3, SFaceModel.EMBEDDER_VERSION)
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
        // The app's catalogue order: newest first (three photos a day).
        val records = inventory.mapIndexed { n, m ->
            MediaRecord(m.id, m.uri, "Synthetic $n", "DCIM/ATLAS-TEST/", m.sizeBytes,
                takenMs(n), false, volumeName = "external_primary")
        }
        return Fixture(faceStore, host, epoch, records)
    }

    /** The witness's read: keyset pages of [PAGE] until an empty page. */
    private fun pagedRead(store: FaceIndexStore, epoch: String): Int {
        var total = 0
        var cursor: String? = null
        while (true) {
            val page = store.personMediaPage(epoch, BIG_PERSON, cursor, PAGE)
            if (page.isEmpty()) break
            total += page.size
            cursor = page.last().key
        }
        return total
    }

    /** The host's People home mapping (as the witness's loadHome). */
    private fun loadHome(store: FaceIndexStore, epoch: String): PeopleHomeSummary? {
        val stored = store.peopleHome(epoch) ?: return null
        return PeopleHomeSummary(epoch, "$epoch|${stored.generation}",
            stored.people.mapValues { (_, person) ->
                PeopleHomePerson(person.name, person.faceCount, person.coverFaceUid,
                    person.mediaCount, person.firstAppearanceMs, person.mostRecentAppearanceMs,
                    person.photoCount, person.videoCount)
            }, stored.unnamed.map { PeopleHomeGroup(it.anchorUid, it.faceCount) })
    }

    companion object {
        private const val PEOPLE = 500
        private const val FACES = 50_000
        private const val CATALOGUE = FACES
        private const val BIG_PERSON_ITEMS = 5_000
        private val BIG_PERSON = PersonKey("person-000")
        private const val PAGE = 96
        private const val SAMPLES = 8 // 1 first + 7 warm
        /** The route's grant token; it equals the catalogue epoch, as in the journeys. */
        private const val GRANT = "1"
        private const val CATALOGUE_EPOCH = 1L

        /** Three photos a day, newest first, like a camera roll (as the witness). */
        private fun takenMs(n: Int): Long = 1_700_000_000_000L - (n / 3) * 86_400_000L
    }
}
