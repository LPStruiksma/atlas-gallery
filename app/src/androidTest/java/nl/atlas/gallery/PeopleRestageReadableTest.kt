package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #684 (goal #31, found by #620's resume test): while a gallery change
 * restages the face catalogue behind the readable one (same full photo
 * grant), People must keep showing its named people at every moment, and
 * the visual index must keep its counts. The host is the real
 * [PeopleDiscoveryHost] over app-private test databases; photos are
 * [SyntheticFace] drawings keyed by invented MediaStore rows.
 *
 * Deterministic: the photo-grant check is called by the host at every step of
 * a publication (staging, the store commit, building the new authorized
 * directory, the swap, the visual catalogue), so the probe below reads
 * People exactly as the screen would at each of those points, including the
 * moment after the store committed the new epoch and before the host swapped
 * to it. Counts, phases and booleans only; no media id, face or name is logged.
 */
@RunWith(AndroidJUnit4::class)
class PeopleRestageReadableTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-restage-readable-" + UUID.randomUUID())
    private lateinit var faces: FaceIndexStore
    private lateinit var visual: VisualIndexStore
    private lateinit var host: PeopleDiscoveryHost

    /** Kept across nothing but this test: the published scopes a carry needs. */
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

    @Volatile private var probe: (() -> Unit)? = null
    private var probing = false

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L + id, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    /** Ids 1..FACES draw one face each; [FILLER] is a plain photo (a typed skip). */
    private fun spec(id: Long): SyntheticFace.Spec? = if (id in 1L..FACES)
        SyntheticFace.Spec(480, 480, 0.4f + 0.02f * id, 0.5f, 0.8f + 0.01f * id) else null

    private val base = (1L..FACES).map(::photo) + photo(FILLER)

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

    private fun grantCheck(): Boolean {
        val read = probe
        if (read != null && !probing) {
            probing = true
            try { read() } finally { probing = false }
        }
        return true
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
            photoGrantToken = { "synthetic-full-grant" },
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

    @After fun cleanup() {
        probe = null
        PeopleDiscoveryHost.replaceAppForTest(null)
        runCatching { faces.close() }
        runCatching { visual.close() }
        dir.deleteRecursively()
    }

    /** Runs the worker's own batch until faces and the visual queue are drained. */
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

    /** Opt in, scan, name the largest group, visual on and drained. */
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
    }

    private class Gaps {
        var samples = 0
        var afterCommit = 0
        var namedMissing = 0
        var emptyShown = 0
        var visualTotalUnknown = 0
        /** Counts, phases and booleans of each gap sample, for the failure message. */
        val notes = ArrayList<String>()
        override fun toString() = "samples=$samples after_commit=$afterCommit " +
            "named_missing=$namedMissing empty_shown=$emptyShown " +
            "visual_total_unknown=$visualTotalUnknown ${notes.take(8)}"
    }

    private fun named(status: PeopleDiscoveryStatus?) =
        status?.people?.people?.values?.any { it.name == NAME } == true

    /**
     * One gallery publication, read the way People and the visual panel read
     * it: the screen holds its last value through [shownPeopleStatus] (what
     * rememberPeopleDiscoveryOf does) and shows "No people" when the held
     * value has no people and is not restoring.
     */
    private fun restage(label: String, oldGrant: String, newGrant: String,
                        inventory: List<FaceVisibleMedia>): Gaps {
        val before = faces.activeEpoch()
        var held = shownPeopleStatus(host, null, host.status(oldGrant))
        assertTrue("$label: named before the change", named(held))
        val gaps = Gaps()
        probe = {
            val active = faces.activeEpoch()
            val read = host.status(newGrant)
            // The screen's first read before a publication is taken only
            // while the host serves the new gallery grant (rememberPeopleDiscoveryOf);
            // before the carry-over it keeps what it showed.
            val taken = host.serves(newGrant)
            if (taken) held = shownPeopleStatus(host, held, read)
            gaps.samples++
            if (active != before) gaps.afterCommit++
            val visualKnown = active == null || host.visualStatus(active)?.total != null
            if (!named(held) || !visualKnown) gaps.notes += "s${gaps.samples}:" +
                "taken=$taken commit=${active != before} phase=${read.ui.phase} " +
                "read_people=${read.people != null} pending=${read.peoplePending} " +
                "display=${when (read.displayEpoch) { before -> "old"; active -> "new"
                    else -> "other" }} " +
                "visual_known=$visualKnown"
            if (!named(held)) gaps.namedMissing++
            if (!named(held) && !peopleHomeRestoring(true, true, held)) gaps.emptyShown++
            if (!visualKnown) gaps.visualTotalUnknown++
        }
        try {
            host.onGalleryPublication(newGrant) { inventory }
        } finally {
            probe = null
        }
        held = shownPeopleStatus(host, held, host.status(newGrant))
        Log.i(TAG, "case=$label $gaps named_after=${named(held)}")
        assertNotEquals("$label: the change restaged the catalogue", before, faces.activeEpoch())
        assertTrue("$label: the probe saw the new epoch committed ($gaps)", gaps.afterCommit > 0)
        assertTrue("$label: named after the publication", named(held))
        return gaps
    }

    /** #620 (b2): a deleted plain photo prunes one catalogue row. */
    @Test fun deleteWhileBackgroundedKeepsNamedPeopleReadable() {
        seed("gallery-1")
        val done = faces.progress(faces.activeEpoch()!!)!!
        val visualBefore = visual.progress(memory.publishedVisual!!.epoch)!!
        val gaps = restage("delete", "gallery-1", "gallery-2", base.filter { it.id != FILLER })
        assertEquals("delete: People never lost the named person ($gaps)", 0, gaps.namedMissing)
        assertEquals("delete: People never showed an empty state ($gaps)", 0, gaps.emptyShown)
        assertEquals("delete: visual counts stayed known ($gaps)", 0, gaps.visualTotalUnknown)
        // The delete removes exactly that one row and requeues nothing.
        val after = faces.progress(faces.activeEpoch()!!)!!
        assertEquals("delete: no face row queued", 0, after.pending + after.running)
        assertEquals("delete: processed face rows survive", done.processed, after.processed)
        assertEquals("delete: only the plain photo's row goes",
            done.processed + done.failed - 1, after.processed + after.failed)
        val visualAfter = visual.progress(memory.publishedVisual!!.epoch)!!
        assertEquals("delete: no visual row queued", 0, visualAfter.queued + visualAfter.running)
        assertEquals("delete: only the plain photo's visual row goes",
            visualBefore.done - 1, visualAfter.done)
    }

    /** #620 (b1): an added plain photo queues one row behind the readable catalogue. */
    @Test fun insertWhileBackgroundedKeepsNamedPeopleReadable() {
        seed("gallery-1")
        val gaps = restage("insert", "gallery-1", "gallery-2", base + photo(FILLER + 1))
        assertEquals("insert: People never lost the named person ($gaps)", 0, gaps.namedMissing)
        assertEquals("insert: People never showed an empty state ($gaps)", 0, gaps.emptyShown)
        assertEquals("insert: visual counts stayed known ($gaps)", 0, gaps.visualTotalUnknown)
        val after = faces.progress(faces.activeEpoch()!!)!!
        assertEquals("insert: only the added photo is queued", 1, after.pending + after.running)
    }

    private companion object {
        const val TAG = "ATLAS_RESTAGE684"
        const val FACES = 8L
        const val FILLER = 50L
        const val NAME = "Synthetic Restage Person"
        const val MODEL = "ci-restage684-visual-v1"
    }
}
