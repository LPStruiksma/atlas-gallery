package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.FaceVisibleMedia
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #703 (goal #31, found by #620 / PR #624 (b2)): while a one-row gallery
 * change restages an authorized, previously indexed People/visual catalogue
 * behind the readable one, both visual progress reads keep a known
 * `total`/`done`:
 *  - the People/Settings panel read, `status(galleryGrant).visual`, and
 *  - the direct `visualStatus(faceEpoch)` probe for the store's face epoch.
 * Every count is a store read of a visual epoch the host authorizes at that
 * read; a revoked grant, an opt-out or a visual failure shows none.
 *
 * Deterministic, like [PeopleRestageReadableTest]: the host calls the
 * injected photo-grant check at every step of a publication (staging, the
 * face store commit, the directory build, the swap, the visual catalogue
 * stage and commit), so the probe samples both reads at each of those
 * points. A second "reader" thread pauses inside a visual status read at its
 * own grant check (after it took the visual scope, before its store read)
 * while the publisher commits the next visual catalogue: the cross-thread
 * hand-off a People screen read can meet during a delete.
 *
 * Out of scope (spec rev 2, gap 3): a delete that lands while another
 * restage is still staging takes the `Carry.NONE` path, where the store holds
 * no counts. Each case asserts it ran the single-row staged-behind carry
 * (`publication_done mode=staged-behind`) and prints the host's own
 * `publication_carry` / `publication_done` lines with its counts.
 *
 * Invented data only: synthetic face drawings keyed by invented MediaStore
 * ids, app-private test databases. Logs and failure messages carry counts,
 * phases, booleans and carry modes, never ids, names, faces or vectors.
 */
@RunWith(AndroidJUnit4::class)
class PeopleVisualStatusDeleteRetentionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private var dir = freshDir()
    private lateinit var faces: FaceIndexStore
    private lateinit var visual: VisualIndexStore
    private lateinit var host: PeopleDiscoveryHost

    private val memory = object : PeopleOptInMemory {
        @Volatile var optIn: PeopleOptIn? = null
        @Volatile var visualOn = false
        @Volatile var published: PublishedFaceScope? = null
        @Volatile var publishedVisual: PublishedVisualScope? = null
        override fun load() = optIn
        override fun save(value: PeopleOptIn?) { optIn = value }
        override fun loadVisual() = visualOn
        override fun saveVisual(enabled: Boolean) { visualOn = enabled }
        override fun loadPublished() = published
        override fun savePublished(value: PublishedFaceScope?) { published = value }
        override fun loadPublishedVisual() = publishedVisual
        override fun savePublishedVisual(value: PublishedVisualScope?) { publishedVisual = value }
    }

    /** The injected Android photo grant; a flip to false is the simulated revoke (gap 4). */
    @Volatile private var grantOn = true
    /** Runs on the publishing thread at each grant check. */
    @Volatile private var probe: (() -> Unit)? = null
    /** Runs once on [readers] threads at their first grant check inside a status read. */
    private val seams = java.util.concurrent.ConcurrentHashMap<Thread, () -> Unit>()
    private val inProbe = ThreadLocal<Boolean>()

    /**
     * A reader pauses only at a grant check made inside the host's visual
     * status read (after it took the visual scope), not at status()'s own
     * earlier face-side checks.
     */
    private fun insideVisualStatus(): Boolean =
        Thread.currentThread().stackTrace.any { it.methodName == "visualStatus" }

    private fun grantCheck(): Boolean {
        if (inProbe.get() != true) {
            val t = Thread.currentThread()
            val reader = t in readerThreads
            val action = (if (reader && insideVisualStatus()) seams.remove(t) else null)
                ?: probe.takeIf { !reader }
            if (action != null) {
                inProbe.set(true)
                try { action() } finally { inProbe.set(false) }
            }
        }
        return grantOn
    }

    private val readerThreads = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Thread, Boolean>())

    private fun freshDir() = File(target.cacheDir, "people-visual-703-" + UUID.randomUUID())

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L + id, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    private fun spec(id: Long): SyntheticFace.Spec? = if (id in 1L..FACES)
        SyntheticFace.Spec(480, 480, 0.4f + 0.02f * id, 0.5f, 0.8f + 0.01f * id) else null

    private val base = (1L..FACES).map(::photo) + photo(FILLER)
    private val deleted = base.filter { it.id != FILLER }

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

    private fun install() {
        dir.mkdirs()
        faces = FaceIndexStore(dbContext(File(dir, "faces.db")))
        visual = VisualIndexStore(dbContext(File(dir, "visual.db")))
        host = PeopleDiscoveryHost(
            store = faces,
            visualStore = visual,
            optInMemory = memory,
            photoGrantCurrent = ::grantCheck,
            photoGrantToken = { if (grantOn) "synthetic-full-grant" else null },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = directory.authorizedUri(lease.media).substringAfterLast('/').toLong()
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(
                        spec(id) ?: throw FaceSourceSkipped("NOT_A_FACE_FIXTURE")))
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
                    override fun embed(bitmap: Bitmap): FloatArray = FloatArray(8) { 0.35355338f }
                    override fun close() = Unit
                }
            },
            visualDecoderFor = { directory ->
                { media ->
                    val id = directory.authorizedUri(FaceMediaKey(media.key, media.revision))
                        .substringAfterLast('/').toLongOrNull()
                    SyntheticFace.bitmap(id?.let(::spec) ?: spec(1L)!!)
                }
            },
            visualModelVersion = MODEL,
            visualDimension = 8
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    private fun teardown() {
        probe = null
        seams.clear()
        readerThreads.clear()
        PeopleDiscoveryHost.replaceAppForTest(null)
        PeopleScanWorkRuntime.clear()
        runCatching { faces.close() }
        runCatching { visual.close() }
        dir.deleteRecursively()
    }

    @After fun cleanup() = teardown()

    /** A fresh host, stores and choices for the next case. */
    private fun reset() {
        teardown()
        dir = freshDir()
        grantOn = true
        memory.optIn = null; memory.visualOn = false
        memory.published = null; memory.publishedVisual = null
    }

    private fun drain() {
        var guard = 0
        while (guard++ < 60) {
            val epoch = faces.activeEpoch() ?: continue
            val stop = runBlocking { runPeopleScanBatch(target) { false } }?.stop
            val visualEpoch = memory.publishedVisual?.epoch
            val visualQueued = visualEpoch?.let { visual.progress(it) }
                ?.let { it.queued + it.running } ?: 0
            if (stop == FaceBatchStop.NO_PENDING && visualQueued == 0 &&
                faces.activeEpoch() == epoch) return
        }
        throw AssertionError("scan did not drain")
    }

    /** Opt in, scan, name the largest group, visual on and drained: a previously indexed catalogue. */
    private fun seed(grant: String) {
        install()
        host.start(grant, base)
        drain()
        val epoch = requireNotNull(host.displayEpoch(grant))
        val review = requireNotNull(host.reviewState(epoch))
        val group = review.clusters.unknownClusters.maxBy { it.members.size }
        val person = PersonKey("native-" + group.id.anchor.localHex)
        assertTrue("name the synthetic group", host.review(PeopleClusterReviewCommand(review,
            PeopleReviewAction.NameCluster(group.id, person, NAME))))
        assertTrue("visual index on", host.setVisualIndex(true))
        drain()
        assertNotNull("a published face scope to carry", memory.published)
        assertNotNull("a published visual scope to carry", memory.publishedVisual)
        assertNotNull("seed: panel visual total known",
            host.status(grant).visual?.total)
    }

    // ---- counts-only evidence ---------------------------------------------------------

    /** The host's own carry/publication log lines since [marker], counts and modes only. */
    private fun hostLines(marker: String): List<String> {
        val pfd = instrumentation.uiAutomation.executeShellCommand(
            "logcat -d -v raw -s ATLAS_FACE:I $TAG:I")
        val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        val lines = text.lines()
        val from = lines.indexOfLast { it.contains(marker) }
        return (if (from >= 0) lines.drop(from + 1) else emptyList()).filter {
            it.startsWith("publication_carry") || it.startsWith("publication_done")
        }
    }

    private fun mark(label: String): String {
        val marker = "case_start label=$label id=" + UUID.randomUUID()
        Log.i(TAG, marker)
        return marker
    }

    private fun carryMode(lines: List<String>): String =
        lines.lastOrNull { it.startsWith("publication_done") }
            ?.substringAfter("mode=")?.substringBefore(' ') ?: "unlogged"

    private class Gaps {
        var samples = 0
        var afterCommit = 0
        var panelTotalUnknown = 0
        var directTotalUnknown = 0
        var namedMissing = 0
        val notes = ArrayList<String>()
        override fun toString() = "samples=$samples after_commit=$afterCommit " +
            "panel_total_unknown=$panelTotalUnknown direct_total_unknown=$directTotalUnknown " +
            "named_missing=$namedMissing ${notes.take(10)}"
    }

    private fun named(status: PeopleDiscoveryStatus?) =
        status?.people?.people?.values?.any { it.name == NAME } == true

    // ---- Done 1 + 2: one-row delete (and insert) keeps both reads known --------------

    /**
     * One gallery publication of [inventory] under [newGrant], sampling the
     * panel read and the direct probe at every grant check.
     */
    private fun restage(label: String, oldGrant: String, newGrant: String,
                        inventory: List<FaceVisibleMedia>): Gaps {
        val before = faces.activeEpoch()
        var held = shownPeopleStatus(host, null, host.status(oldGrant))
        assertTrue("$label: named before the change", named(held))
        val gaps = Gaps()
        val marker = mark(label)
        probe = {
            val active = faces.activeEpoch()
            val panel = host.status(newGrant)
            val direct = active?.let { host.visualStatus(it) }
            if (host.serves(newGrant)) held = shownPeopleStatus(host, held, panel)
            gaps.samples++
            val commit = active != before
            if (commit) gaps.afterCommit++
            val panelKnown = panel.visual?.total != null
            val directKnown = direct?.total != null
            if (!panelKnown) gaps.panelTotalUnknown++
            if (!directKnown) gaps.directTotalUnknown++
            if (!named(held)) gaps.namedMissing++
            if (!panelKnown || !directKnown) gaps.notes += "s${gaps.samples}:" +
                "commit=$commit serves_new=${host.serves(newGrant)} phase=${panel.ui.phase} " +
                "display=${when (panel.displayEpoch) { before -> "old"; active -> "new"
                    else -> "other" }} panel_total=${panel.visual?.total} " +
                "direct_total=${direct?.total} store_visual_published=" +
                "${memory.publishedVisual?.epoch?.let { visual.progress(it)?.total }}"
        }
        try {
            host.onGalleryPublication(newGrant) { inventory }
        } finally {
            probe = null
        }
        held = shownPeopleStatus(host, held, host.status(newGrant))
        val lines = hostLines(marker)
        val mode = carryMode(lines)
        Log.i(TAG, "case=$label carry=$mode $gaps host=$lines")
        assertEquals("$label: the single-row staged-behind carry ran, not Carry.NONE " +
            "(out of scope, spec gap 3); host=$lines $gaps", "staged-behind", mode)
        assertNotEquals("$label: the change restaged the catalogue", before, faces.activeEpoch())
        assertTrue("$label: the probe saw the new face epoch committed ($gaps)", gaps.afterCommit > 0)
        assertTrue("$label: named after the publication", named(held))
        return gaps
    }

    @Test fun deleteKeepsPanelAndDirectVisualCountsAtEveryFrame() {
        seed("gallery-1")
        val face = faces.progress(faces.activeEpoch()!!)!!
        val visualBefore = visual.progress(memory.publishedVisual!!.epoch)!!
        val gaps = restage("delete", "gallery-1", "gallery-2", deleted)
        assertEquals("delete: the People/Settings panel read kept visual counts ($gaps)",
            0, gaps.panelTotalUnknown)
        assertEquals("delete: the direct visual probe kept counts ($gaps)",
            0, gaps.directTotalUnknown)
        assertEquals("delete: named People stayed readable ($gaps)", 0, gaps.namedMissing)
        val after = faces.progress(faces.activeEpoch()!!)!!
        assertEquals("delete: no face row queued", 0, after.pending + after.running)
        assertEquals("delete: only the plain photo's face row goes",
            face.processed + face.failed - 1, after.processed + after.failed)
        val visualAfter = visual.progress(memory.publishedVisual!!.epoch)!!
        assertEquals("delete: no visual row queued (no full restage)",
            0, visualAfter.queued + visualAfter.running)
        assertEquals("delete: only the plain photo's visual row goes",
            visualBefore.done - 1, visualAfter.done)
        assertEquals("delete: the panel shows the new store total",
            visualAfter.total, host.status("gallery-2").visual?.total)
    }

    @Test fun insertKeepsPanelAndDirectVisualCountsAtEveryFrame() {
        seed("gallery-1")
        val gaps = restage("insert", "gallery-1", "gallery-2", base + photo(FILLER + 1))
        assertEquals("insert: panel kept visual counts ($gaps)", 0, gaps.panelTotalUnknown)
        assertEquals("insert: direct probe kept counts ($gaps)", 0, gaps.directTotalUnknown)
        val visualAfter = visual.progress(memory.publishedVisual!!.epoch)!!
        assertEquals("insert: only the added photo is queued", 1,
            visualAfter.queued + visualAfter.running)
    }

    // ---- Done 2, critic hint: reader takes the scope, the successor commits --------------

    private class Paused(val label: String, val read: () -> VisualIndexStatus?) {
        val inSeam = CountDownLatch(1)
        val finished = CountDownLatch(1)
        /** Paused at the seam or finished, whichever comes first. */
        val arrived = CountDownLatch(1)
        @Volatile var result: VisualIndexStatus? = null
        @Volatile var seamReached = false
        @Volatile var error: Throwable? = null
        var thread: Thread? = null
    }

    /**
     * Starts each reader on its own thread; it pauses at its first grant check
     * inside the read (after taking the visual scope) until [release] is
     * counted down. Returns once every reader is paused or has finished.
     */
    private fun startReaders(readers: List<Paused>, release: CountDownLatch) {
        for (r in readers) {
            val t = Thread {
                try { r.result = r.read() } catch (e: Throwable) { r.error = e }
                finally { r.finished.countDown(); r.arrived.countDown() }
            }
            r.thread = t
            readerThreads += t
            seams[t] = {
                r.seamReached = true
                r.inSeam.countDown()
                r.arrived.countDown()
                assertTrue("${r.label}: released", release.await(60, TimeUnit.SECONDS))
            }
            t.start()
        }
        // Each reader counts [Paused.arrived] down at its seam or when it finishes.
        for (r in readers) r.arrived.await(20, TimeUnit.SECONDS)
    }

    private fun finish(readers: List<Paused>) {
        for (r in readers) {
            assertTrue("${r.label}: reader finished", r.finished.await(60, TimeUnit.SECONDS))
            r.error?.let { throw AssertionError("${r.label}: reader threw", it) }
        }
    }

    /** True at the publisher's first grant check after the host swapped to the new face epoch. */
    private fun faceSwapped(before: String?, newGrant: String): Boolean {
        val active = faces.activeEpoch()
        return active != null && active != before &&
            host.serves(newGrant) &&
            host.displayEpoch(newGrant) == active
    }

    /**
     * The delete publishes on a background thread; at the visual pre-commit
     * boundary each reader takes the old visual scope and pauses, the
     * publisher commits the successor catalogue and finishes, then the
     * readers resume their store reads.
     */
    private fun commitRace(label: String, oldGrant: String, newGrant: String,
                           inventory: List<FaceVisibleMedia>,
                           readers: (before: String) -> List<Paused>): List<Paused> {
        val before = requireNotNull(faces.activeEpoch())
        val oldVisual = requireNotNull(memory.publishedVisual).epoch
        assertNotNull("$label: counts before", host.status(oldGrant).visual?.total)
        val release = CountDownLatch(1)
        val started = readers(before)
        var fired = false
        val marker = mark(label)
        probe = {
            if (!fired && faceSwapped(before, newGrant) && visual.progress(oldVisual) != null) {
                fired = true
                startReaders(started, release)
            }
        }
        var error: Throwable? = null
        val publisher = Thread {
            try { host.onGalleryPublication(newGrant) { inventory } }
            catch (e: Throwable) { error = e }
        }
        publisher.start()
        publisher.join(120_000)
        probe = null
        release.countDown()
        finish(started)
        error?.let { throw AssertionError("$label: publisher threw", it) }
        val lines = hostLines(marker)
        val mode = carryMode(lines)
        val summary = started.joinToString(" ") {
            "${it.label}:seam=${it.seamReached} total=${it.result?.total}"
        }
        Log.i(TAG, "case=$label carry=$mode fired=$fired $summary host=$lines")
        assertTrue("$label: the visual pre-commit boundary was reached; host=$lines", fired)
        assertEquals("$label: single-row staged-behind carry; host=$lines", "staged-behind", mode)
        assertNull("$label: the old visual epoch is gone after the successor commit",
            visual.progress(oldVisual))
        return started
    }

    @Test fun visualCommitBetweenScopeReadAndStoreReadKeepsCounts() {
        seed("gallery-1")
        // (1) a direct read still bound to the face epoch the swap replaced (#699 clause).
        val delete = commitRace("race_delete", "gallery-1", "gallery-2", deleted) { before ->
            listOf(Paused("direct_replaced_face_epoch") { host.visualStatus(before) })
        }
        val d = delete.single()
        assertTrue("race_delete: the reader paused between its scope read and store read", d.seamReached)
        assertNotNull("race_delete: a read that took the old visual scope keeps counts " +
            "after the successor committed (seam=${d.seamReached} total=${d.result?.total})",
            d.result?.total)
        drain()
        // (2) the live face epoch's direct read and the People/Settings panel read.
        val insert = commitRace("race_insert", "gallery-2", "gallery-3", base) { _ ->
            listOf(
                Paused("direct_live_face_epoch") {
                    faces.activeEpoch()?.let { host.visualStatus(it) } },
                Paused("panel") { host.status("gallery-3").visual })
        }
        for (r in insert) {
            assertNotNull("race_insert ${r.label}: counts kept across the successor commit " +
                "(seam=${r.seamReached} total=${r.result?.total})", r.result?.total)
            assertTrue("race_insert ${r.label}: the reader paused at the hand-off " +
                "(seam=${r.seamReached})", r.seamReached)
        }
    }

    // ---- Done 3: the opposite fence --------------------------------------------------------

    private enum class Fence { GRANT, OPT_OUT, FAILURE }
    private enum class Boundary { BEFORE_VISUAL_COMMIT, BETWEEN_SCOPE_AND_PROGRESS, AFTER_SWAP }

    private class InjectedVisualFailure : RuntimeException("injected synthetic visual failure")

    private fun inject(fence: Fence) {
        when (fence) {
            Fence.GRANT -> grantOn = false
            Fence.OPT_OUT -> host.setVisualIndex(false)
            // Thrown from the publisher's grant check inside the visual catalogue
            // stage: the host's own failVisual path sets visualFailure.
            Fence.FAILURE -> throw InjectedVisualFailure()
        }
    }

    private fun fenced(fence: Fence, boundary: Boundary): String {
        reset()
        seed("gallery-1")
        val label = "fence_${fence.name.lowercase()}_${boundary.name.lowercase()}"
        val before = requireNotNull(faces.activeEpoch())
        val oldVisual = requireNotNull(memory.publishedVisual).epoch
        val newGrant = "gallery-2"
        val inProbe = ArrayList<String>()
        val release = CountDownLatch(1)
        val readers = listOf(
            Paused("direct") { faces.activeEpoch()?.let { host.visualStatus(it) } },
            Paused("direct_replaced") { host.visualStatus(before) },
            Paused("panel") { host.status(newGrant).visual })
        var fired = false
        fun sampleNow(tag: String) {
            val active = faces.activeEpoch()
            inProbe += "$tag panel_total=${host.status(newGrant).visual?.total} " +
                "direct_total=${active?.let { host.visualStatus(it) }?.total} " +
                "direct_replaced_total=${host.visualStatus(before)?.total}"
        }
        probe = {
            if (!fired && faceSwapped(before, newGrant)) {
                val committed = visual.progress(oldVisual) == null
                val at = when (boundary) {
                    Boundary.BEFORE_VISUAL_COMMIT, Boundary.BETWEEN_SCOPE_AND_PROGRESS -> !committed
                    Boundary.AFTER_SWAP -> committed
                }
                if (at) {
                    fired = true
                    if (boundary == Boundary.BETWEEN_SCOPE_AND_PROGRESS) startReaders(readers, release)
                    if (fence != Fence.FAILURE) {
                        inject(fence)
                        sampleNow("in_probe")
                    } else inject(fence)
                }
            }
        }
        val publisher = Thread {
            try { host.onGalleryPublication(newGrant) { deleted } } catch (_: Throwable) { }
        }
        publisher.start()
        publisher.join(120_000)
        probe = null
        release.countDown()
        if (boundary == Boundary.BETWEEN_SCOPE_AND_PROGRESS) finish(readers)
        sampleNow("after")
        val summary = "$label fired=$fired failure=${host.visualStatus(before)?.failure != null} " +
            "optin=${memory.visualOn} grant=$grantOn $inProbe " +
            readers.joinToString(" ") { "${it.label}:seam=${it.seamReached} total=${it.result?.total}" }
        Log.i(TAG, "case=$summary")
        assertTrue("$label: boundary reached ($summary)", fired)
        for (line in inProbe) {
            assertTrue("$label: no visual count may show after the fence ($summary)",
                !Regex("total=[0-9]").containsMatchIn(line))
        }
        if (boundary == Boundary.BETWEEN_SCOPE_AND_PROGRESS) {
            for (r in readers) {
                assertNull("$label ${r.label}: a read paused between its scope read and " +
                    "store read shows no count after the fence ($summary)", r.result?.total)
            }
            assertTrue("$label: the replaced-epoch reader paused at the hand-off ($summary)",
                readers[1].seamReached)
        }
        return summary
    }

    @Test fun injectedGrantLossHidesVisualCountsAtEveryBoundary() {
        Boundary.values().forEach { fenced(Fence.GRANT, it) }
    }

    @Test fun optOutHidesVisualCountsAtEveryBoundary() {
        Boundary.values().forEach { fenced(Fence.OPT_OUT, it) }
    }

    @Test fun visualFailureHidesVisualCountsAtEveryBoundary() {
        Boundary.values().forEach { fenced(Fence.FAILURE, it) }
    }

    /** A first-ever, unpublished visual catalogue has no counts to show; none are invented. */
    @Test fun firstEverCatalogueStaysUnknownUntilItCommits() {
        install()
        host.start("gallery-1", base)
        drain()
        val epoch = requireNotNull(faces.activeEpoch())
        assertNull("before opt-in: panel", host.status("gallery-1").visual?.total)
        val seen = ArrayList<String>()
        probe = {
            // Until the first catalogue commits the host serves no visual scope
            // (visualSearchSource is null) and the store has no counts.
            if (host.visualSearchSource(epoch) == null) {
                val panel = host.status("gallery-1").visual?.total
                val direct = host.visualStatus(epoch)?.total
                if (panel != null || direct != null) seen += "panel=$panel direct=$direct"
            }
        }
        try {
            assertTrue("visual index on", host.setVisualIndex(true))
        } finally { probe = null }
        assertTrue("first-ever catalogue showed invented counts before its commit: $seen", seen.isEmpty())
        assertNotNull("after the first commit the store's counts show",
            host.status("gallery-1").visual?.total)
    }

    private companion object {
        const val TAG = "ATLAS_VIS703"
        const val FACES = 8L
        const val FILLER = 50L
        const val NAME = "Synthetic Visual Retention Person"
        const val MODEL = "ci-vis703-visual-v1"
    }
}
