package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #602 evidence on the real API35 MediaProvider: a cold start that resumes
 * from the cached checkpoint ([GalleryRepository.loadSinceCheckpoint]) against
 * the full rescan it replaces ([GalleryRepository.loadWithCheckpoint] with the
 * cached catalogue as `previous`, exactly what a cold start ran before #602),
 * on the same 2,000 invented images. Both arms alternate, n = 7 after warm-up.
 *
 * Also proves on the real provider that the delta equals a full scan after
 * adds and deletes, and times the cache decode with and without the
 * checkpoint (the only #602 change on the A1 "App open → cached grid" path).
 *
 * Numbers go to logcat tag ATLAS_PERF602 (printed by the emulator script).
 * Emulator only: it writes disposable synthetic media and deletes it again.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartCheckpointBenchmarkTest {
    private val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("#602 benchmark writes disposable synthetic media only on API35 emulators",
                    Build.VERSION.SDK_INT == 35 &&
                        (Build.HARDWARE.contains("ranchu", true) ||
                            Build.HARDWARE.contains("goldfish", true)) &&
                        (Build.FINGERPRINT.contains("generic", true) ||
                            Build.PRODUCT.contains("sdk", true)))
                base.evaluate()
            }
        }
    }
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(FIXTURE_COUNT) { 16 to 12 },
        folderOf = { "ATLAS-ColdScan-602-Fixture" })
    @get:Rule val chain: RuleChain = RuleChain.outerRule(emulatorOnly).around(fixtures)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun coldDeltaBeatsTheFullRescanAndMatchesItOnTheSameFixture() {
        val repo = GalleryRepository(context)
        // Warm-up and the "cached catalogue" a cold start would have on screen.
        // The provider may still touch the fresh rows (scanner, thumbnails):
        // rescan (each read takes real time) until two reads in a row see the
        // same generations, or give up after 15 s and measure what is there.
        var cached = repo.loadWithCheckpoint()
        val settleBy = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < settleBy) {
            val again = repo.loadWithCheckpoint(previous = cached.items)
            val settled = again.checkpoint == cached.checkpoint
            cached = again
            if (settled) break
        }
        assertTrue("fixture not visible: ${cached.items.size} rows",
            cached.items.size >= FIXTURE_COUNT)
        assertTrue("checkpoint not usable on API35: ${cached.checkpoint}",
            coldDeltaCheckpointUsable(cached.checkpoint))
        repeat(2) {
            repo.loadWithCheckpoint(previous = cached.items)
            repo.loadSinceCheckpoint(cached.items, cached.checkpoint)
        }

        // Unchanged library: alternate arms so drift hits both equally.
        val before = ArrayList<Long>()
        val after = ArrayList<Long>()
        repeat(RUNS) {
            var t = SystemClock.elapsedRealtimeNanos()
            val full = repo.loadWithCheckpoint(previous = cached.items)
            before += (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            t = SystemClock.elapsedRealtimeNanos()
            val delta = repo.loadSinceCheckpoint(cached.items, cached.checkpoint)
            after += (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            assertSameCatalogue("unchanged library", full.items, delta.items)
        }
        val beforeP50 = p50(before)
        val afterP50 = p50(after)
        val reduction = 100.0 * (beforeP50 - afterP50) / beforeP50
        Log.i(TAG, "event=cold_scan_unchanged rows=${cached.items.size} n=$RUNS " +
            "before_full_us=${before.sorted()} after_delta_us=${after.sorted()} " +
            "before_p50_ms=${ms(beforeP50)} after_p50_ms=${ms(afterP50)} " +
            "reduction_pct=${"%.1f".format(reduction)}")

        // Changed library: deletes of cached rows plus new rows after the checkpoint.
        val extras = ArrayList<Uri>()
        try {
            repeat(30) { extras += insertImage("IMG_602_EXTRA_A_$it.png") }
            val base = repo.loadWithCheckpoint()
            val gone = extras.take(10)
            gone.forEach { assertEquals(1, context.contentResolver.delete(it, null, null)) }
            extras.removeAll(gone.toSet())
            repeat(20) { extras += insertImage("IMG_602_EXTRA_B_$it.png") }
            var t = SystemClock.elapsedRealtimeNanos()
            val full = repo.loadWithCheckpoint(previous = base.items)
            val changedFullUs = (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            t = SystemClock.elapsedRealtimeNanos()
            val delta = repo.loadSinceCheckpoint(base.items, base.checkpoint)
            val changedDeltaUs = (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            assertSameCatalogue("10 deleted + 20 added", full.items, delta.items)
            assertEquals(base.items.size + 10, delta.items.size)
            Log.i(TAG, "event=cold_scan_changed rows=${delta.items.size} deleted=10 added=20 " +
                "full_ms=${ms(changedFullUs)} delta_ms=${ms(changedDeltaUs)}")
        } finally {
            extras.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }

        // A1 path: decode of the cached catalogue, v3 with checkpoint vs without.
        val grant = GalleryMediaGrant(true, true, false)
        val withCp = encode(grant, cached.items, cached.checkpoint)
        val withoutCp = encode(grant, cached.items, null)
        repeat(3) { decode(withCp); decode(withoutCp) }
        val decodeWith = ArrayList<Long>()
        val decodeWithout = ArrayList<Long>()
        repeat(RUNS) {
            var t = SystemClock.elapsedRealtimeNanos()
            requireNotNull(decode(withoutCp))
            decodeWithout += (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            t = SystemClock.elapsedRealtimeNanos()
            val snapshot = requireNotNull(decode(withCp))
            decodeWith += (SystemClock.elapsedRealtimeNanos() - t) / 1_000L
            assertEquals(cached.checkpoint, snapshot.checkpoint)
        }
        Log.i(TAG, "event=a1_cache_decode rows=${cached.items.size} n=$RUNS " +
            "bytes_without=${withoutCp.size} bytes_with=${withCp.size} " +
            "without_p50_ms=${ms(p50(decodeWithout))} with_p50_ms=${ms(p50(decodeWith))}")

        // Guard the direction only; the measured size of the win is in the log.
        assertTrue("cold delta p50 ${ms(afterP50)} ms is not below full rescan p50 " +
            "${ms(beforeP50)} ms", afterP50 < beforeP50)
    }

    /** Same list as the full scan: identities, revisions and order (#602, review of #977). */
    private fun assertSameCatalogue(label: String, full: List<MediaRecord>, delta: List<MediaRecord>) {
        assertEquals("$label: same identities in the same order",
            full.map { GalleryMediaDeltaKey.of(it) }, delta.map { GalleryMediaDeltaKey.of(it) })
        full.indices.forEach { i ->
            assertEquals("$label: same record at $i", full[i], delta[i])
        }
    }

    private fun encode(grant: GalleryMediaGrant, items: List<MediaRecord>,
                       checkpoint: GalleryMediaCheckpoint?) =
        ByteArrayOutputStream().also {
            GalleryInventoryCacheCodec.encode(it, grant, items, checkpoint = checkpoint)
        }.toByteArray()

    private fun decode(bytes: ByteArray) =
        GalleryInventoryCacheCodec.decode(ByteArrayInputStream(bytes))

    private fun insertImage(name: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-ColdScan-602-Fixture/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        try {
            val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(40, 120, 200))
                requireNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun p50(samples: List<Long>): Long = samples.sorted()[(samples.size - 1) / 2]
    private fun ms(us: Long): String = "%.1f".format(us / 1000.0)

    private companion object {
        const val TAG = "ATLAS_PERF602"
        const val FIXTURE_COUNT = 2_000
        const val RUNS = 7
    }
}
