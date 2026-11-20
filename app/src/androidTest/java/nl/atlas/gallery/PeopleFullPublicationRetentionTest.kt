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
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
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
 * #699 (goal #31, found by #620's resume regression, run 44693509047 (b1)):
 * one photo added or removed while ATLAS is in the background reaches the
 * People host as a new gallery publication. Under the same full photo grant
 * the host carries the readable catalogue over and restages behind it
 * (Carry.STAGE_BEHIND); the gallery's incremental flag is not an input to
 * that choice. During that restage every read that #620 samples must stay
 * readable: the face store's active epoch, the named person read through
 * [PeopleDiscoveryHost.reviewState] for that epoch (the person page and
 * #620's `named`), and the visual opt-in and counts read through
 * [PeopleDiscoveryHost.visualStatus] for it.
 *
 * A frame is composed from reads taken a moment apart, so each probe also
 * re-reads the epoch the previous probe saw ("stale"): a person page or a
 * visual panel still bound to the catalogue a step behind must not blank.
 *
 * Deterministic: the host calls the photo-grant check at every step of a
 * publication (staging, the store commit, the new authorized directory, the
 * swap, the visual catalogue), so the probe reads exactly between those
 * steps, on the host's own thread. The real [PeopleDiscoveryHost] over
 * app-private test databases; photos are [SyntheticFace] drawings keyed by
 * invented MediaStore rows. Counts, phases and booleans only: no media id,
 * face, name or vector is logged.
 */
@RunWith(AndroidJUnit4::class)
class PeopleFullPublicationRetentionTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-full-publication-" + UUID.randomUUID())
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

    @Volatile private var probe: (() -> Unit)? = null
    @Volatile private var granted = true
    private var probing = false

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L + id, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    /** Ids 1..FACES draw one face each; anything else is a plain photo (a typed skip). */
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
        return granted
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
            photoGrantToken = { if (granted) "synthetic-full-grant" else null },
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
        var peopleUnreadable = 0
        var namedMissing = 0
        var staleNamedMissing = 0
        var visualOptInOff = 0
        var visualTotalUnknown = 0
        var staleVisualUnknown = 0
        var scanStateReset = 0
        val notes = ArrayList<String>()
        val gapCount get() = peopleUnreadable + namedMissing + staleNamedMissing +
            visualOptInOff + visualTotalUnknown + staleVisualUnknown + scanStateReset
        override fun toString() = "samples=$samples after_commit=$afterCommit " +
            "people_unreadable=$peopleUnreadable named_missing=$namedMissing " +
            "stale_named_missing=$staleNamedMissing visual_optin_off=$visualOptInOff " +
            "visual_total_unknown=$visualTotalUnknown stale_visual_unknown=$staleVisualUnknown " +
            "scan_state_reset=$scanStateReset ${notes.take(8)}"
    }

    private fun named(epoch: String?) =
        epoch != null && host.reviewState(epoch)?.names?.values?.contains(NAME) == true

    private fun visualKnown(epoch: String?) =
        host.visualStatus(epoch)?.let { it.enabled && it.total != null } == true

    /**
     * One gallery publication with the given [inventory], read at every
     * publication step as #620 reads it, plus the epoch the previous step saw.
     */
    private fun publishAndProbe(label: String, newGrant: String,
                                inventory: List<FaceVisibleMedia>): Gaps {
        val before = faces.activeEpoch()
        assertTrue("$label: named before the change", named(before))
        assertTrue("$label: visual counts before the change", visualKnown(before))
        val gaps = Gaps()
        var previous: String? = before
        probe = {
            val active = faces.activeEpoch()
            gaps.samples++
            val step = "s${gaps.samples}:" + when (active) {
                before -> "old"; null -> "none"; else -> "new"
            }
            if (active != before) gaps.afterCommit++
            if (active == null) { gaps.peopleUnreadable++; gaps.notes += "$step unreadable" }
            else {
                if (!named(active)) { gaps.namedMissing++; gaps.notes += "$step named_missing" }
                val v = host.visualStatus(active)
                if (v?.enabled != true) { gaps.visualOptInOff++; gaps.notes += "$step visual_off" }
                else if (v.total == null) {
                    gaps.visualTotalUnknown++; gaps.notes += "$step visual_unknown"
                }
            }
            val stale = previous
            if (stale != null && stale != active) {
                if (!named(stale)) { gaps.staleNamedMissing++; gaps.notes += "$step stale_named" }
                if (!visualKnown(stale)) {
                    gaps.staleVisualUnknown++; gaps.notes += "$step stale_visual"
                }
            }
            val phase = host.status(newGrant).ui.phase
            if (phase == FaceDiscoveryPhase.READY || phase == FaceDiscoveryPhase.PERMISSION_REQUIRED ||
                phase == FaceDiscoveryPhase.FAILED) {
                gaps.scanStateReset++; gaps.notes += "$step phase=$phase"
            }
            previous = active ?: previous
        }
        try {
            host.onGalleryPublication(newGrant) { inventory }
        } finally {
            probe = null
        }
        Log.i(TAG, "case=$label $gaps")
        assertNotEquals("$label: the change restaged the catalogue", before, faces.activeEpoch())
        assertTrue("$label: the probe saw the new epoch committed ($gaps)", gaps.afterCommit > 0)
        assertTrue("$label: named after the publication", named(faces.activeEpoch()))
        assertTrue("$label: visual counts after the publication", visualKnown(faces.activeEpoch()))
        return gaps
    }

    /** #620 (b1): an added plain photo queues one row, People and visual stay readable. */
    @Test fun insertKeepsNamedPeopleAndVisualReadableOnEveryStep() {
        seed("gallery-1")
        val done = faces.progress(faces.activeEpoch()!!)!!
        val visualBefore = visual.progress(memory.publishedVisual!!.epoch)!!
        val gaps = publishAndProbe("insert", "gallery-2", base + photo(FILLER + 1))
        assertEquals("insert: every step readable ($gaps)", 0, gaps.gapCount)
        val after = faces.progress(faces.activeEpoch()!!)!!
        assertEquals("insert: only the added photo is queued", 1, after.pending + after.running)
        assertEquals("insert: processed face rows survive", done.processed, after.processed)
        val visualAfter = visual.progress(memory.publishedVisual!!.epoch)!!
        assertEquals("insert: only the added visual row is queued",
            1, visualAfter.queued + visualAfter.running)
        assertEquals("insert: visual rows survive", visualBefore.done, visualAfter.done)
    }

    /** #620 (b2): a removed plain photo prunes one row, People and visual stay readable. */
    @Test fun deleteKeepsNamedPeopleAndVisualReadableOnEveryStep() {
        seed("gallery-1")
        val done = faces.progress(faces.activeEpoch()!!)!!
        val visualBefore = visual.progress(memory.publishedVisual!!.epoch)!!
        val gaps = publishAndProbe("delete", "gallery-2", base.filter { it.id != FILLER })
        assertEquals("delete: every step readable ($gaps)", 0, gaps.gapCount)
        val after = faces.progress(faces.activeEpoch()!!)!!
        assertEquals("delete: no face row queued", 0, after.pending + after.running)
        assertEquals("delete: only the plain photo's row goes",
            done.processed + done.failed - 1, after.processed + after.failed)
        val visualAfter = visual.progress(memory.publishedVisual!!.epoch)!!
        assertEquals("delete: no visual row queued", 0, visualAfter.queued + visualAfter.running)
        assertEquals("delete: only the plain photo's visual row goes",
            visualBefore.done - 1, visualAfter.done)
    }

    /**
     * A real grant loss in the middle of that restage is never masked by the
     * readable carry-over: from the first step that sees it, neither epoch
     * reads named People or visual counts.
     */
    @Test fun grantLossDuringRestageHidesBothEpochs() {
        seed("gallery-1")
        val before = faces.activeEpoch()
        var leaked = 0
        var steps = 0
        probe = {
            steps++
            if (steps == 2) granted = false
            if (!granted) {
                val active = faces.activeEpoch()
                if (named(before) || named(active) || visualKnown(before) || visualKnown(active))
                    leaked++
            }
        }
        try {
            host.onGalleryPublication("gallery-2") { base + photo(FILLER + 1) }
        } finally {
            probe = null
        }
        Log.i(TAG, "case=grant_loss steps=$steps leaked=$leaked")
        assertTrue("grant loss: the probe ran after the loss", steps >= 2)
        assertEquals("grant loss: no read survived the lost grant", 0, leaked)
        assertTrue("grant loss: no named person afterwards", !named(before) && !named(faces.activeEpoch()))
        // The next publication sees the loss and revokes, as before.
        host.onGalleryPublication("gallery-3") { emptyList() }
        assertNull("grant loss: the stored publication is dropped", memory.published)
    }

    /** Turning People off during a carried restage hides the held reads too. */
    @Test fun optOutAfterRestageHidesHeldReads() {
        seed("gallery-1")
        val before = faces.activeEpoch()
        host.onGalleryPublication("gallery-2") { base + photo(FILLER + 1) }
        val after = faces.activeEpoch()
        host.revoke() // the user's "turn off" and a real loss share this path
        assertTrue("opt-out: no named person for either epoch", !named(before) && !named(after))
        assertTrue("opt-out: no visual counts for either epoch",
            !visualKnown(before) && !visualKnown(after))
    }

    private companion object {
        const val TAG = "ATLAS_FULLPUB699"
        const val FACES = 8L
        const val FILLER = 50L
        const val NAME = "Synthetic Full Publication Person"
        const val MODEL = "ci-fullpub699-visual-v1"
    }
}
