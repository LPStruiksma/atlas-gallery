package nl.atlas.gallery.featurevisual

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import kotlin.random.Random
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import nl.atlas.gallery.featurevisual.tags.TagMatrix
import nl.atlas.gallery.featurevisual.tags.TagMatrixCodec
import nl.atlas.gallery.featurevisual.tags.TagScorer
import nl.atlas.gallery.featurevisual.tags.TagHit
import nl.atlas.gallery.featurevisual.tags.store.TagRowKey
import nl.atlas.gallery.featurevisual.tags.store.TagScope
import nl.atlas.gallery.featurevisual.tags.store.TaggedRow
import nl.atlas.gallery.featurevisual.tags.store.TagSweepStop
import nl.atlas.gallery.featurevisual.tags.store.TaggingIndexPort
import nl.atlas.gallery.featurevisual.tags.store.VisualTagHook
import nl.atlas.gallery.featurevisual.tags.store.VisualTagStore
import nl.atlas.gallery.featurevisual.tags.store.VisualTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TAGS-1 (#732) on real SQLite with the shipped TAGS-0 matrix: a synthetic
 * library of 2,000 indexed items (opaque keys, synthetic SigLIP-sized vectors;
 * tags need no pixels, so no image is decoded). Half arrive through the scan
 * hook, half are pre-existing vectors the backfill must find. Logs a 250k
 * extrapolation of time and storage under tag `AtlasTags1`.
 */
@RunWith(AndroidJUnit4::class)
class VisualTagBackfillAndroidTest {
    private val items = 2_000
    private val epoch = "tags1-epoch"

    private fun dbContext(dir: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = File(dir, name)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(File(dir, name), factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                File(dir, name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }

    private fun matrix(): TagMatrix = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open(VisualTagHook.MATRIX_ASSET).use { TagMatrixCodec.decode(it.readBytes()) }

    /** Unit vector at cosine ~0.35 to one on-by-default tag row, the rest noise. */
    private fun vectorFor(m: TagMatrix, onTags: List<Int>, n: Int): FloatArray {
        val r = Random(n)
        val row = m.row(onTags[n % onTags.size])
        val noise = FloatArray(m.dimension) { r.nextFloat() * 2f - 1f }
        val nn = sqrt(noise.sumOf { it.toDouble() * it }).toFloat()
        val v = FloatArray(m.dimension) { 0.35f * row[it] + 0.94f * noise[it] / nn }
        val vn = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(m.dimension) { v[it] / vn }
    }

    private fun dbBytes(dir: File, name: String): Long =
        listOf(name, "$name-wal", "$name-shm").sumOf { File(dir, it).takeIf(File::exists)?.length() ?: 0L }

    @Test fun backfillCompletesCountsStayStableAndEstimatesTwoHundredFiftyThousand() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-tags1-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        try {
            val context = dbContext(dir)
            val m = matrix()
            val onTags = m.tags.indices.filter { m.tags[it].defaultOn }
            val media = (0 until items).map { VisualMedia("content://synthetic/tags1/%05d".format(it), "generation:1") }
            VisualIndexStore(context).use { index ->
                VisualTagStore(context).use { tagStore ->
                    var wall = 1_000_000L
                    val tagger = VisualTagger(TagScorer(m), tagStore,
                        clockElapsedMs = { SystemClock.elapsedRealtime() }, wallClockMs = { wall })
                    index.beginSnapshot(epoch)
                    media.chunked(VisualIndexStore.MAX_BATCH).forEach { index.stageBatch(epoch, it) }
                    index.commitSnapshot(epoch, m.imageModelVersion, m.dimension)

                    // Even items: the scan hook (incremental). Odd: vectors that predate TAGS-1.
                    val hooked = TaggingIndexPort(index) { w, v -> tagger.tagPublished(w, v) }
                    media.chunked(500).forEach { chunk ->
                        val leases = index.claimAuthorized(epoch, m.imageModelVersion, chunk, chunk.size)
                        assertEquals(chunk.size, leases.size)
                        val (even, odd) = leases.partition { media.indexOf(it.media) % 2 == 0 }
                        fun writes(l: List<nl.atlas.gallery.featurevisual.store.VisualIndexLease>) =
                            l.map { VisualVectorWrite(it, vectorFor(m, onTags, media.indexOf(it.media))) }
                        assertTrue(hooked.completeBatch(writes(even), emptyList()))
                        assertTrue(index.completeBatch(writes(odd), emptyList()))
                    }
                    assertEquals(items / 2, tagStore.counts().rows)

                    val read = { after: nl.atlas.gallery.featurevisual.store.VisualVectorCursor?, limit: Int ->
                        index.loadActiveVectors(epoch, m.imageModelVersion, after, limit)
                    }
                    val started = SystemClock.elapsedRealtime()
                    var steps = 0
                    var scored = 0
                    var examined = 0
                    do {
                        val r = tagger.sweepStep(epoch, m.imageModelVersion, read) { true }
                        steps++; scored += r.scored; examined += r.examined
                    } while (r.moreWork)
                    val backfillMs = SystemClock.elapsedRealtime() - started
                    assertEquals(items, examined)
                    assertEquals("only pre-existing vectors are scored", items / 2, scored)
                    val first = tagStore.counts()
                    assertEquals(items, first.rows)
                    assertTrue("most synthetic items carry a tag: $first", first.taggedMedia >= items * 9 / 10)
                    val dog = tagStore.mediaForTag("dog", TagScope.Library, items)
                    assertTrue(dog.isNotEmpty())
                    assertTrue(tagStore.tagsFor(dog.first()).any { it.id == "dog" })
                    assertEquals(TagSweepStop.IDLE, tagger.sweepStep(epoch, m.imageModelVersion, read) { true }.stop)

                    // Re-scan a day later: everything read, nothing rescored, same counts.
                    wall += 24L * 60 * 60 * 1000
                    val again = SystemClock.elapsedRealtime()
                    var rescored = 0
                    do {
                        val r = tagger.sweepStep(epoch, m.imageModelVersion, read) { true }
                        rescored += r.scored
                    } while (r.moreWork)
                    val resweepMs = SystemClock.elapsedRealtime() - again
                    assertEquals(0, rescored)
                    assertEquals(first, tagStore.counts())

                    // Scoring alone, on this device's CPU.
                    val scorer = TagScorer(m)
                    val probe = (0 until 500).map { vectorFor(m, onTags, it) }
                    val t0 = System.nanoTime()
                    probe.forEach { scorer.score(it, m.imageModelVersion) }
                    val scoreUs = (System.nanoTime() - t0) / 1_000.0 / probe.size

                    tagStore.close()
                    val bytes = dbBytes(dir, VisualTagStore.DB_NAME)
                    val perItemBytes = bytes.toDouble() / items
                    val line = "TAGS1 items=$items steps=$steps backfill_ms=$backfillMs " +
                        "(scored=${items / 2}) resweep_ms=$resweepMs score_us_per_item=${"%.1f".format(scoreUs)} " +
                        "rows=${first.rows} hits=${first.hits} tagged_media=${first.taggedMedia} " +
                        "db_bytes=$bytes bytes_per_item=${"%.0f".format(perItemBytes)} | 250k estimate: " +
                        "full_backfill_s=${"%.0f".format((resweepMs.toDouble() / items + scoreUs / 1000) * 250_000 / 1000)} " +
                        "daily_resweep_s=${"%.0f".format(resweepMs.toDouble() / items * 250_000 / 1000)} " +
                        "storage_mb=${"%.1f".format(perItemBytes * 250_000 / 1_048_576)}"
                    Log.i("AtlasTags1", line)
                    println(line)
                    assertTrue("bounded storage: $line", perItemBytes < 400)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Review #764: the scope is applied before any limit, beyond 50k global matches. */
    @Test fun scopedTagQueryFindsMatchesPastFiftyThousandBetterOnesAndLibraryHonoursLimit() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-tags1-scope-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        try {
            VisualTagStore(dbContext(dir)).use { store ->
                val n = 50_010
                fun key(i: Int) = "content://synthetic/scope/%06d".format(i)
                // Margin falls with i: item 0 is the best "dog", item n-1 the worst.
                (0 until n).chunked(5_000).forEach { chunk ->
                    store.putRows(chunk.map { i ->
                        TaggedRow(TagRowKey(key(i), TagRowKey.IMAGE_FRAME), "r1",
                            listOf(TagHit("dog", 0.5f, 0.4f - i * 1e-6f)))
                    })
                }
                val worst = key(n - 1)
                assertEquals(listOf(worst),
                    store.mediaForTag("dog", TagScope.Media(setOf(worst, "content://synthetic/scope/none")), 10))
                // A scope larger than one SQL chunk: the best three of it, in order.
                val scope = (n - 1_200 until n).map(::key).toSet()
                assertEquals((n - 1_200 until n - 1_197).map(::key),
                    store.mediaForTag("dog", TagScope.Media(scope), 3))
                assertEquals(n, store.mediaForTag("dog", TagScope.Library, 60_000).size)
                assertEquals((0 until 10).map(::key), store.mediaForTag("dog", TagScope.Library, 10))
                assertEquals(emptyList<String>(), store.mediaForTag("cat", TagScope.Library, 10))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
