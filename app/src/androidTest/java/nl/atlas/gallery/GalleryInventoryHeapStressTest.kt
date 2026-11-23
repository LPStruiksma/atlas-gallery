package nl.atlas.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.buildAlbumSummaries
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.MediaRecordFaceView
import nl.atlas.gallery.featuregrid.GridDensity
import nl.atlas.gallery.featuregrid.TimelineSort
import nl.atlas.gallery.featuregrid.buildGridTimelineIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PERF-6 (#127): model-only, deterministic 250k-row Java-heap stress.
 *
 * No real MediaStore cursor, bitmaps, permission grants or owner media are used.
 * A short-interval sampler covers transient allocations during each stage;
 * its maximum is an observed high-water mark, not an exact allocation peak.
 * Report both sampled high-water and retained-stage readings.
 */
@RunWith(AndroidJUnit4::class)
class GalleryInventoryHeapStressTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), zone)

    private fun usedBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun catalogue(n: Int): List<MediaRecord> {
        val folders = List(300) { "DCIM/Synthetic$it/" }
        val start = 1_763_000_000_000L
        return List(n) { i ->
            val id = 1_000_000L + i
            MediaRecord(
                id,
                "content://media/external_primary/images/media/$id",
                "IMG_20251103_${100_000 + i}.jpg",
                folders[i % folders.size],
                2_000_000L + i,
                start - i * 60_000L,
                isVideo = i % 10 == 0,
                modifiedAtSeconds = 1_763_000_000L,
                volumeName = "external_primary",
                providerVersion = "v1",
                generationModified = i.toLong(),
                widthPixels = 4000,
                heightPixels = 3000
            )
        }
    }

    @Test fun synthetic250kRetainedStagesAndSampledHighWater() {
        val n = 250_000
        val runtime = Runtime.getRuntime()
        val limit = runtime.maxMemory()
        assertTrue("Runtime.maxMemory must be positive", limit > 0L)
        System.gc()
        val baseline = usedBytes()
        val sampling = AtomicBoolean(true)
        val highWater = AtomicLong(baseline)
        val sampler = Thread({
            while (sampling.get()) {
                highWater.accumulateAndGet(usedBytes(), ::maxOf)
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "perf6-heap-sampler")
        sampler.isDaemon = true
        sampler.start()

        val readings = linkedMapOf<String, Long>()
        fun stage(name: String) {
            val used = usedBytes()
            highWater.accumulateAndGet(used, ::maxOf)
            readings[name] = used
        }

        try {
            val records = catalogue(n)
            stage("catalogue")
            val sorted = sortMedia(records, SortOrder.NEWEST, 0L)
            stage("sorted")
            fun timeline(density: GridDensity) = buildGridTimelineIndex(
                sorted.size, { sorted[it].takenAtMillis }, { sorted[it].uri },
                TimelineSort.NEWEST, density, zone, Locale.US, clock = clock
            )
            val medium = timeline(GridDensity.MEDIUM)
            stage("timeline_medium")
            val large = timeline(GridDensity.LARGE)
            stage("timeline_two_densities")
            val albums = buildAlbumSummaries(sorted)
            stage("albums")
            val view = MediaRecordFaceView(sorted)
            val directory = FaceAuthorizedMediaDirectory.build(
                "synthetic-grant", view, { it }, { "synthetic-grant" }, { true }
            )
            stage("people_directory")

            assertEquals(n, records.size)
            assertEquals(n, sorted.size)
            assertEquals(n, medium.mediaCount)
            assertEquals(n, large.mediaCount)
            assertEquals(300, albums.size)
            assertEquals(n, view.size)
            assertTrue(directory.mediaCount > 0)
            assertEquals("media:" + sorted[n / 2].uri,
                medium.slots[medium.entryForMedia(n / 2)].stableKey)
        } finally {
            sampling.set(false)
            sampler.join(5_000)
        }

        val observedPeak = highWater.get()
        val peakFraction = observedPeak.toDouble() / limit
        val retained = readings.getValue("people_directory")
        val perItem = (retained - baseline).toDouble() / n
        val stages = readings.entries.joinToString(" ") {
            "${it.key}_mib=${it.value / 1048576}"
        }
        Log.i("ATLAS_SCALE", "PERF-6-250k rows=$n " +
            "max_heap_mib=${limit / 1048576} " +
            "baseline_mib=${baseline / 1048576} " +
            "sampled_peak_mib=${observedPeak / 1048576} " +
            "sampled_peak_fraction=${"%.4f".format(Locale.US, peakFraction)} " +
            "retained_bytes_per_item=${"%.1f".format(Locale.US, perItem)} " +
            "sample_interval_ms=2 $stages")
        assertTrue(
            "250k synthetic model sampled heap fraction must be below 0.60; " +
                "observed=$peakFraction, maxMemory=$limit",
            peakFraction < 0.60
        )
    }
}
