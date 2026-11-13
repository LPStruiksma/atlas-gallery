package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleHomePerson
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import org.junit.Rule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepersistence.ClusterFrameKey
import nl.atlas.gallery.featurepersistence.ClusterPublication
import nl.atlas.gallery.featurepersistence.ClusterPublicationCadence
import nl.atlas.gallery.featurepersistence.FaceEmbeddingVectorCodec
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D2-5p (#419) API35 synthetic SQLite scale and equivalence witnesses
 * (folded from #425). No photos, URIs, permissions, identities or network:
 * every scenario owns a throwaway cache database and synthetic unit vectors.
 *
 * Each completed photo is followed by [FaceIndexStore.publishClustersIfDue],
 * so the measured cost includes the named publication cadence
 * ([ClusterPublicationCadence]). [FaceIndexStore.Work] counts SQL statements
 * issued, cursor rows read and rows written (SQLite total_changes()).
 *
 * Asserted: per-photo completion work is the same at every library size, the
 * not-due cadence check is constant, and all publications of a scan together
 * stay linear in photos. Timings and heap are logged (ATLAS_SCALE lines, dumped
 * by scripts/ci_android_upgrade_emulator.sh), not asserted.
 *
 * 150k runs only with `-e atlasScale150k true`: it does not fit the 40-minute
 * emulator job next to the rest of the suite. 50k runs only with
 * `-e atlasScale50k true` (Actions-minutes audit 2026-11-12: ~280 s of every
 * PR emulator run for a witness whose asserted invariants 1k and 10k already
 * check). Without them, larger libraries are extrapolated from the 1k/10k
 * lines, which is stated where it is used.
 */
@RunWith(AndroidJUnit4::class)
class FaceIndexStoreScaleAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private val version = SFaceModel.EMBEDDER_VERSION
    private val epoch = "synthetic-scale-epoch"
    private val detector = "synthetic-detector-v1"

    private fun dbContext(file: File): Context {
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
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler
            )
        }
    }

    private fun key(i: Int) = IndexedMedia("content://synthetic/primary/image/$i#photo", "generation:1")

    private fun axis(i: Int): FloatArray =
        FloatArray(SFaceModel.DIMENSION) { a -> if (a == i % SFaceModel.DIMENSION) 1f else 0f }

    private fun angle(degrees: Double): FloatArray {
        val r = degrees * PI / 180.0
        return FloatArray(SFaceModel.DIMENSION) {
            when (it) { 0 -> cos(r).toFloat(); 1 -> sin(r).toFloat(); else -> 0f }
        }
    }

    private fun log(vararg fields: Pair<String, Any>) {
        val line = fields.joinToString(" ") { (k, v) -> "$k=$v" }
        Log.i("ATLAS_SCALE", line)
        println("ATLAS_SCALE $line")
    }

    private inline fun <T> withStore(block: (FaceIndexStore, File) -> T): T {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-scale-" + UUID.randomUUID())
        check(dir.mkdirs())
        val file = File(dir, "face_fixture.db")
        try {
            return FaceIndexStore(dbContext(file)).use { block(it, file) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun stage(store: FaceIndexStore, photos: Int) {
        store.beginSnapshot(epoch)
        for (offset in 0 until photos step 1024) {
            store.stageBatch(epoch, (offset until minOf(offset + 1024, photos)).map(::key))
        }
        store.commitSnapshot(epoch, detector, version)
    }

    private data class Bucket(var statementsMin: Int = Int.MAX_VALUE, var statementsMax: Int = 0,
                              var writtenMin: Long = Long.MAX_VALUE, var writtenMax: Long = 0,
                              var readMax: Long = 0, var wallMsSum: Double = 0.0,
                              var wallMsMax: Double = 0.0, var n: Int = 0) {
        fun add(w: FaceIndexStore.Work, wallMs: Double) {
            statementsMin = minOf(statementsMin, w.statements)
            statementsMax = maxOf(statementsMax, w.statements)
            writtenMin = minOf(writtenMin, w.rowsWritten)
            writtenMax = maxOf(writtenMax, w.rowsWritten)
            readMax = maxOf(readMax, w.rowsRead)
            wallMsSum += wallMs; wallMsMax = maxOf(wallMsMax, wallMs); n++
        }
    }

    private fun scenario(photos: Int, cohort: String) = withStore { store, file ->
        val setupStart = SystemClock.elapsedRealtimeNanos()
        stage(store, photos)
        val setupMs = (SystemClock.elapsedRealtimeNanos() - setupStart) / 1e6
        val completion = Bucket()
        val notDue = Bucket()
        var publications = 0
        var pubRead = 0L
        var pubWritten = 0L
        var pubStatements = 0L
        var pubWallMs = 0.0
        var pubWallMsMax = 0.0
        var peakHeap = 0L
        var splitReadAllowance = 0L
        val firstQuarter = Bucket()
        val lastQuarter = Bucket()
        for (i in 0 until photos) {
            val lease = requireNotNull(store.claim(epoch, 1_000L + i)) { "SCALE_CLAIM_FAILED at=$i" }
            val v = if (cohort == "single") axis(0) else axis(i)
            var t = SystemClock.elapsedRealtimeNanos()
            check(store.completeEmbeddings(lease, listOf(box), listOf(v), version)) {
                "SCALE_COMPLETE_FAILED at=$i"
            }
            val completeMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            val cw = requireNotNull(store.lastCompletionWork)
            completion.add(cw, completeMs)
            if (i < photos / 4) firstQuarter.add(cw, completeMs)
            if (i >= photos - photos / 4) lastQuarter.add(cw, completeMs)

            // #508: a split publication's next part re-reads the seed (at most
            // the faces completed so far); allowed on top of the linear bound.
            val continuing = store.clusterPublicationContinues(epoch)
            t = SystemClock.elapsedRealtimeNanos()
            val published = store.publishClustersIfDue(epoch, scanFinished = i == photos - 1)
            if (published && continuing) splitReadAllowance += 3L * (i + 1)
            val publishMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            val pw = requireNotNull(store.lastPublicationWork)
            if (published) {
                publications++
                pubRead += pw.rowsRead; pubWritten += pw.rowsWritten
                pubStatements += pw.statements
                pubWallMs += publishMs; pubWallMsMax = maxOf(pubWallMsMax, publishMs)
                peakHeap = maxOf(peakHeap, Runtime.getRuntime().totalMemory() -
                    Runtime.getRuntime().freeMemory())
                log("phase" to "publication", "photos" to photos, "cohort" to cohort,
                    "after_photo" to i + 1, "statements" to pw.statements,
                    "rows_read" to pw.rowsRead, "rows_written" to pw.rowsWritten,
                    "wall_ms" to "%.1f".format(publishMs))
            } else {
                notDue.add(pw, publishMs)
            }
        }
        val state = requireNotNull(store.clusterState(epoch)) { "Final publication missing" }
        assertEquals(photos, state.faceCount)
        val dbBytes = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
            .filter { it.exists() }.sumOf { it.length() }
        log("phase" to "completed", "photos" to photos, "cohort" to cohort,
            "clusters" to state.unknownClusters.size,
            "setup_wall_ms" to "%.0f".format(setupMs),
            "photo_statements" to "${completion.statementsMin}..${completion.statementsMax}",
            "photo_rows_written" to "${completion.writtenMin}..${completion.writtenMax}",
            "photo_rows_read_max" to completion.readMax,
            "photo_wall_ms_mean" to "%.3f".format(completion.wallMsSum / completion.n),
            "photo_wall_ms_mean_first_quarter" to "%.3f".format(firstQuarter.wallMsSum / maxOf(1, firstQuarter.n)),
            "photo_wall_ms_mean_last_quarter" to "%.3f".format(lastQuarter.wallMsSum / maxOf(1, lastQuarter.n)),
            "photo_wall_ms_max" to "%.1f".format(completion.wallMsMax),
            "check_statements" to "${notDue.statementsMin}..${notDue.statementsMax}",
            "check_rows_written_max" to notDue.writtenMax,
            "check_wall_ms_mean" to "%.3f".format(notDue.wallMsSum / maxOf(1, notDue.n)),
            "publications" to publications,
            "publication_rows_read_total" to pubRead,
            "publication_rows_written_total" to pubWritten,
            "publication_statements_total" to pubStatements,
            "publication_rows_read_per_photo" to "%.2f".format(pubRead.toDouble() / photos),
            "publication_wall_ms_total" to "%.0f".format(pubWallMs),
            "publication_wall_ms_max" to "%.0f".format(pubWallMsMax),
            "split_read_allowance" to splitReadAllowance,
            "heap_bytes_peak_at_publication" to peakHeap,
            "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "db_bytes" to dbBytes)

        // Per-photo work: one face per photo; exactly the same at every size.
        assertEquals(PHOTO_STATEMENTS, completion.statementsMin)
        assertEquals(PHOTO_STATEMENTS, completion.statementsMax)
        assertEquals(PHOTO_ROWS_WRITTEN, completion.writtenMin)
        assertEquals(PHOTO_ROWS_WRITTEN, completion.writtenMax)
        assertEquals(0L, completion.readMax)
        // Not-due cadence check: three reads, nothing written.
        if (notDue.n > 0) {
            assertEquals(3, notDue.statementsMax)
            assertEquals(0L, notDue.writtenMax)
        }
        // All publications together: linear in photos (see ClusterPublicationCadence).
        // Each reads old rows, seed and verification (~3 per published face) plus pending.
        assertTrue("publication reads $pubRead for $photos photos",
            pubRead <= 3L * (ClusterPublicationCadence.GROWTH_DIVISOR + 1) * photos + 3L * photos +
                splitReadAllowance)
        assertTrue("publication writes $pubWritten for $photos photos",
            pubWritten <= 2L * (ClusterPublicationCadence.GROWTH_DIVISOR + 1) * photos)
    }

    @Test fun scale1kOneIdentity() = scenario(1_000, "single")
    @Test fun scale10kManySmallClusters() = scenario(10_000, "small")
    @Test fun scale50kOneIdentity() {
        assumeTrue("50k is opt-in: -e atlasScale50k true",
            InstrumentationRegistry.getArguments().getString("atlasScale50k") == "true")
        scenario(50_000, "single")
    }
    @Test fun scale150kManySmallClusters() {
        assumeTrue("150k is opt-in: -e atlasScale150k true",
            InstrumentationRegistry.getArguments().getString("atlasScale150k") == "true")
        scenario(150_000, "small")
    }

    /**
     * #508 whole-library witness: a 300k-photo catalogue (photo cap 400k)
     * staged in shuffled gallery order with capture times, claimed newest
     * first, and a gallery-like face population (by default a quarter of the
     * photos hold faces, 30% of them strangers: about 86k faces) published
     * from scratch in bounded parts
     * ([FaceIndexStore.MAX_PUBLICATION_FACES] each), then read back as the
     * People screen reads it. The first [REAL_PHOTOS] photos go through the
     * real claim and completeEmbeddings path; the others' faces are written
     * straight into the throwaway database in one transaction (their per-photo
     * cost is the constant one the scenarios above assert), so the run fits
     * the emulator job. Wall time, heap and DB size go to ATLAS_SCALE lines.
     * `-e atlasScalePhotos N` and `-e atlasScaleFaceShare F` (0..1) change
     * the size; 0.4 (about 137k faces) adds roughly ten minutes on the API35
     * emulator, too long for the shared 40-minute job.
     */
    @Test fun scale300kLibraryNewestFirst() {
        // ~12 min of the 40-min emulator-ui job: opt-in like 150k (#509 budget).
        assumeTrue("300k is opt-in: -e atlasScale300k true",
            InstrumentationRegistry.getArguments().getString("atlasScale300k") == "true")
        val photos = InstrumentationRegistry.getArguments().getString("atlasScalePhotos")
            ?.toIntOrNull() ?: 300_000
        val faceShare = InstrumentationRegistry.getArguments().getString("atlasScaleFaceShare")
            ?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.25
        val runtime = Runtime.getRuntime()
        fun heap(): Long { System.gc(); return runtime.totalMemory() - runtime.freeMemory() }
        withStore { store, file ->
            val rng = java.util.Random(508)
            val order = (0 until photos).toMutableList().also { java.util.Collections.shuffle(it, rng) }
            val taken = { i: Int -> 1_500_000_000_000L + i * 60_000L } // photo i taken i minutes in
            var t = SystemClock.elapsedRealtimeNanos()
            store.beginSnapshot(epoch)
            for (offset in 0 until photos step 1024) {
                val ids = order.subList(offset, minOf(offset + 1024, photos))
                store.stageBatch(epoch, ids.map(::key), LongArray(ids.size) { taken(ids[it]) })
            }
            store.commitSnapshot(epoch, detector, version)
            val stageMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6

            // Real path: the newest photos come first, one by one.
            var claimNs = 0L
            var completeNs = 0L
            for (n in 0 until REAL_PHOTOS) {
                t = SystemClock.elapsedRealtimeNanos()
                val lease = requireNotNull(store.claim(epoch, 1_000L + n))
                claimNs += SystemClock.elapsedRealtimeNanos() - t
                assertEquals("claim $n is the next newest photo", key(photos - 1 - n).key, lease.media.key)
                t = SystemClock.elapsedRealtimeNanos()
                check(store.completeEmbeddings(lease, emptyList(), emptyList(), version))
                completeNs += SystemClock.elapsedRealtimeNanos() - t
            }

            // Bulk faces for the rest: deterministic gallery-like vectors.
            val people = maxOf(1, photos / 250)
            val centres = Array(people) { unitVector(rng) }
            val weights = DoubleArray(people) { 1.0 / (it + 1) }
            val cumulative = DoubleArray(people).also { c ->
                var acc = 0.0; val sum = weights.sum()
                for (i in weights.indices) { acc += weights[i] / sum; c[i] = acc }
            }
            fun personVector(): FloatArray {
                val r = rng.nextDouble()
                var p = java.util.Arrays.binarySearch(cumulative, r)
                if (p < 0) p = -p - 1
                val c = centres[minOf(p, people - 1)]
                val sigma = 0.8f / kotlin.math.sqrt(SFaceModel.DIMENSION.toFloat())
                return FloatArray(SFaceModel.DIMENSION) { c[it] + sigma * rng.nextGaussian().toFloat() }
            }
            t = SystemClock.elapsedRealtimeNanos()
            var faces = 0
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                var uid = 1L
                for (i in photos - 1 - REAL_PHOTOS downTo 0) {
                    val media = key(i)
                    db.execSQL("UPDATE catalog SET state='processed' WHERE media_key=?", arrayOf(media.key))
                    if (rng.nextDouble() >= faceShare) continue
                    val count = if (i % 7 == 0) 2 else 1
                    for (ordinal in 0 until count) {
                        val face = "%032x".format(uid++)
                        val vector = if (rng.nextDouble() < 0.3) unitVector(rng) else personVector()
                        db.execSQL("""INSERT INTO faces(media_key,ordinal,face_uid,l,t,r,b)
                            VALUES(?,?,?,0.1,0.2,0.5,0.8)""", arrayOf<Any>(media.key, ordinal, face))
                        db.execSQL("""INSERT INTO embeddings(face_uid,source_revision,model_version,vector)
                            VALUES(?,?,?,?)""", arrayOf<Any>(face, media.revision, version,
                            FaceEmbeddingVectorCodec.encode(vector)))
                        faces++
                    }
                }
                db.execSQL("UPDATE cluster_generation SET dirty=1,pending=? WHERE singleton=1",
                    arrayOf<Any>(photos))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val bulkMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            val heapBefore = heap()

            // Publication from scratch, in bounded parts, as the drained worker runs it.
            var parts = 0
            var partMsMax = 0.0
            var partMsTotal = 0.0
            var heapPeak = 0L
            do {
                t = SystemClock.elapsedRealtimeNanos()
                check(store.publishClustersIfDue(epoch, scanFinished = true)) { "SCALE_PART_FAILED at=$parts" }
                val ms = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                parts++
                partMsTotal += ms; partMsMax = maxOf(partMsMax, ms)
                heapPeak = maxOf(heapPeak, runtime.totalMemory() - runtime.freeMemory())
                log("phase" to "library_part", "photos" to photos, "part" to parts,
                    "wall_ms" to "%.0f".format(ms), "heap_bytes" to (runtime.totalMemory() - runtime.freeMemory()))
            } while (store.clusterPublicationContinues(epoch))
            assertEquals((faces + FaceIndexStore.MAX_PUBLICATION_FACES - 1) /
                FaceIndexStore.MAX_PUBLICATION_FACES, parts)

            t = SystemClock.elapsedRealtimeNanos()
            val reviewGeneration: String
            val reviewMs: Double
            val heapAfterReview: Long
            val clusterCount: Int
            // #532: name the NAMED_PEOPLE largest groups through the real
            // reducer and commit, as the review screen does; the commit
            // rewrites the People home summary in its transaction.
            val expectedNamed: Map<PersonKey, Pair<String, Int>>
            val commitMs: Double
            run {
                val review = requireNotNull(store.peopleReview(epoch)) { "People review unavailable" }
                reviewMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                assertEquals(faces, review.clusters.faceCount)
                heapAfterReview = heap()
                clusterCount = review.clusters.unknownClusters.size
                reviewGeneration = review.generation
                val largest = review.clusters.unknownClusters
                    .sortedWith(compareByDescending<nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster> {
                        it.members.size }.thenBy { it.id.anchor.localHex })
                    .take(NAMED_PEOPLE)
                check(largest.size == NAMED_PEOPLE) { "Fixture has too few groups" }
                var state = PeopleClusterReviewState(epoch, "$epoch|${review.generation}",
                    review.clusters, review.names)
                expectedNamed = largest.mapIndexed { i, group ->
                    val person = PersonKey("native-" + group.id.anchor.localHex)
                    val name = "Synthetic person %02d".format(i)
                    state = requireNotNull(reviewPeopleCluster(state,
                        PeopleReviewAction.NameCluster(group.id, person, name))) { "Naming $i refused" }
                    person to (name to group.members.size)
                }.toMap()
                t = SystemClock.elapsedRealtimeNanos()
                requireNotNull(store.commitPeopleReview(epoch, reviewGeneration,
                    state.clusters, state.names)) { "Review commit refused" }
                commitMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            }
            System.gc()

            // Cold People home read: no app-level cache exists for it, and the
            // SQLite connection's page cache is released first (the OS file
            // cache stays warm; a new process can't read before a new epoch).
            runCatching { db.execSQL("PRAGMA shrink_memory") }
            t = SystemClock.elapsedRealtimeNanos()
            val home = requireNotNull(store.peopleHome(epoch)) { "People home unavailable" }
            val homeReadMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            assertEquals("People home equals the committed names, counts",
                expectedNamed, home.people.mapValues { it.value.name to it.value.faceCount })
            log("phase" to "people_home_read", "photos" to photos, "faces" to faces,
                "named" to home.people.size,
                "confirmed_faces" to expectedNamed.values.sumOf { it.second },
                "review_commit_wall_ms" to "%.0f".format(commitMs),
                "wall_ms" to "%.3f".format(homeReadMs))

            // First drawn People frame, cold: the route mounts with no data,
            // reads on IO what PeopleDiscoveryHost.currentHome reads (review
            // generation, then the summary), and we stamp the first frame
            // drawn at all and the first frame drawn with the named people.
            // The test clock drives frames, so this is an upper bound.
            runCatching { db.execSQL("PRAGMA shrink_memory") }
            val firstDrawNs = AtomicLong()
            val namedDrawNs = AtomicLong()
            val mountNs = SystemClock.elapsedRealtimeNanos()
            compose.setContent {
                var summary by remember { mutableStateOf<PeopleHomeSummary?>(null) }
                LaunchedEffect(Unit) {
                    summary = withContext(Dispatchers.IO) {
                        val generation = store.peopleReviewGeneration(epoch)
                        store.peopleHome(epoch)?.takeIf { it.generation == generation }?.let {
                            PeopleHomeSummary(epoch, "$epoch|${it.generation}",
                                it.people.mapValues { (_, p) ->
                                    PeopleHomePerson(p.name, p.faceCount, p.coverFaceUid)
                                })
                        }
                    }
                }
                MaterialTheme {
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        val now = SystemClock.elapsedRealtimeNanos()
                        firstDrawNs.compareAndSet(0L, now)
                        if (summary != null) namedDrawNs.compareAndSet(0L, now)
                    }) {
                        PeopleHomeScreen(
                            state = PeopleHomeUiState(
                                discovery = FaceDiscoveryUiState(
                                    phase = FaceDiscoveryPhase.FINISHED,
                                    observedPermissionEpoch = epoch
                                )
                            ),
                            currentPermissionEpoch = epoch,
                            nativePeopleReview = summary,
                            nativePeopleSourceGeneration = summary?.sourceGeneration,
                            onOpenProfile = {}
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) { namedDrawNs.get() != 0L }
            val firstFrameMs = (firstDrawNs.get() - mountNs) / 1e6
            val namedFrameMs = (namedDrawNs.get() - mountNs) / 1e6
            log("phase" to "people_home_first_frame", "photos" to photos, "faces" to faces,
                "named" to home.people.size,
                "first_frame_ms" to "%.1f".format(firstFrameMs),
                "named_frame_ms" to "%.1f".format(namedFrameMs),
                "target_ms" to 500)
            // The names really are on the People screen (the list is lazy:
            // scroll to the first one, sorted by name).
            compose.onNodeWithTag("atlas-people-home")
                .performScrollToNode(hasText("Synthetic person 00"))
            compose.onNodeWithText("Synthetic person 00").assertExists()
            // Regression guard only (the shared emulator is noisy); the
            // #532 target of 500 ms is judged from the logged number.
            assertTrue("People named frame took $namedFrameMs ms", namedFrameMs < 2_000)

            val dbBytes = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
                .filter { it.exists() }.sumOf { it.length() }
            log("phase" to "library_completed", "photos" to photos, "faces" to faces,
                "face_share" to faceShare,
                "clusters" to clusterCount,
                "stage_wall_ms" to "%.0f".format(stageMs),
                "claim_wall_ms_mean" to "%.3f".format(claimNs / 1e6 / REAL_PHOTOS),
                "complete_wall_ms_mean" to "%.3f".format(completeNs / 1e6 / REAL_PHOTOS),
                "bulk_wall_ms" to "%.0f".format(bulkMs),
                "publication_parts" to parts,
                "publication_wall_ms_total" to "%.0f".format(partMsTotal),
                "publication_part_wall_ms_max" to "%.0f".format(partMsMax),
                "review_read_wall_ms" to "%.0f".format(reviewMs),
                "heap_bytes_before_publication" to heapBefore,
                "heap_bytes_peak_sampled" to heapPeak,
                "heap_bytes_after_review" to heapAfterReview,
                "heap_limit_bytes" to runtime.maxMemory(),
                "db_bytes" to dbBytes)
        }
    }

    private fun unitVector(rng: java.util.Random): FloatArray =
        FloatArray(SFaceModel.DIMENSION) { rng.nextGaussian().toFloat() }

    /**
     * SQL path equals the pure model: same vectors, same publication points,
     * FaceClusterer.restore round trip as in the store. Borderline angles, so
     * a wrong seed or a lost pending face would change the clusters.
     */
    @Test fun storePublicationMatchesPureModel() = withStore { store, _ ->
        val photos = 600
        stage(store, photos)
        val rng = java.util.Random(419)
        val policy = ClusterPolicy.DEFAULT // #579: what the store publishes with
        var model: FaceClusterState = FaceClusterState.empty(version, SFaceModel.DIMENSION, policy)
        val pending = ArrayList<FaceEmbedding>()
        val vectorOf = HashMap<NativeFaceObservationId, FaceEmbedding>()
        var publications = 0
        for (i in 0 until photos) {
            val lease = requireNotNull(store.claim(epoch, 1_000L + i))
            val faces = 1 + (i % 2)
            val vectors = List(faces) { angle(rng.nextDouble() * 150.0) }
            assertTrue(store.completeEmbeddings(lease, List(faces) { box }, vectors, version))
            for (r in store.embeddingRecords(epoch, lease.media.key)) {
                // As the store builds it: through the policy's metric, with the photo as frame.
                val e = policy.embedding(NativeFaceObservationId(r.nativeFaceUid), version,
                    r.vector, ClusterFrameKey.of(lease.media.key, null))
                pending += e; vectorOf[e.observation] = e
            }
            if (store.publishClustersIfDue(epoch, scanFinished = i == photos - 1)) {
                val seed = if (model.unknownClusters.isEmpty()) model else FaceClusterer.restore(
                    version, policy, model.unknownClusters.flatMap { c ->
                        c.members.map { m -> vectorOf.getValue(m) }
                    }, model.unknownClusters.map { it.members }, ClusterReviewFacts.NONE)
                model = ClusterPublication.publish(seed, pending)
                pending.clear(); publications++
            }
        }
        assertTrue("expected a mid-scan and a final publication", publications >= 2)
        val stored = requireNotNull(store.clusterState(epoch))
        assertEquals(ClusterPublication.membership(model), ClusterPublication.membership(stored))
        log("phase" to "equivalence", "photos" to photos, "publications" to publications,
            "clusters" to stored.unknownClusters.size, "faces" to stored.faceCount)
    }

    private companion object {
        /** currentEpoch, matches, model, delete, 2 inserts, 2 updates, 2 checks. */
        const val PHOTO_STATEMENTS = 10
        /** faces + embeddings insert, generation + catalog update. */
        const val PHOTO_ROWS_WRITTEN = 4L
        /** #508: photos of the 300k run that take the real claim/complete path. */
        const val REAL_PHOTOS = 500
        /** #532: People named on the 300k fixture before People home is timed. */
        const val NAMED_PEOPLE = 12
    }
}
