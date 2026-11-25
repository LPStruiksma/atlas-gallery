package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-A1A: intentionally excluded from regular connected instrumentation by
 * the required atlasPerfA1Phase argument. The host owns real force-stops and
 * launches. No test method tries to force-stop its instrumentation process.
 * Every fixture is invented, bounded, and lives on an isolated CI emulator.
 * Task #1051: investigate unmatched cached draws; retain exact completed-frame
 * attribution, distinct cold PIDs and fail-closed sample admission.
 */
@RunWith(AndroidJUnit4::class)
class PerfA1CachedFirstFrameBenchmarkTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = "ATLAS-PerfA1A"
    private val prefix = "IMG_PERFA1A_"
    private val marker = File(target.noBackupFilesDir, "atlas-perf-a1-enabled")
    private val inventoryFile = File(target.noBackupFilesDir, "gallery-inventory.bin")

    private fun phase(expected: String) {
        assumeTrue(
            "PERF-A1A is host-invoked only, never part of connectedDebugAndroidTest",
            InstrumentationRegistry.getArguments().getString("atlasPerfA1Phase") == expected
        )
        assertTrue("Only an Android emulator may run PERF-A1A",
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator"))
        assertTrue("Images grant is needed before a cached frame",
            readGalleryMediaGrant(target).images)
    }

    /**
     * MediaStore may still advance its generation immediately after fixture
     * writes. Preserve the strict coherent-snapshot check and retry only that
     * typed race, bounded to five scans outside any timed cold sample.
     * This follows the existing PeopleScanProcessKillTest settled-load pattern.
     */
    private fun settledLoad(): List<MediaRecord> {
        repeat(4) {
            try { return GalleryRepository(target).load() }
            catch (e: GalleryProviderSnapshotChanged) {
                // Wait for a provider change when possible; rescan after a
                // bounded timeout if publication raced observer registration.
                val changed = java.util.concurrent.CountDownLatch(1)
                val observer = object : android.database.ContentObserver(
                    android.os.Handler(android.os.Looper.getMainLooper())
                ) {
                    override fun onChange(selfChange: Boolean) { changed.countDown() }
                }
                target.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
                try {
                    changed.await(500L, java.util.concurrent.TimeUnit.MILLISECONDS)
                } finally {
                    target.contentResolver.unregisterContentObserver(observer)
                }
            }
        }
        return GalleryRepository(target).load()
    }

    private fun rows(): List<MediaRecord> = settledLoad()
        .filter { it.relativePath == "Pictures/$folder/" && it.name.startsWith(prefix) }

    /** Six deterministic, made-up PNGs; no personal media or identifiers. */
    private fun insertFixture() {
        assertEquals("Preexisting PERF-A1A rows risk destructive cleanup", 0, rows().size)
        for (index in 1..6) {
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix$index.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
            try {
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).drawColor(
                        Color.rgb(index * 27, 90 + index * 13, 220 - index * 17))
                    requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
                assertEquals(1, target.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null))
            } catch (error: Throwable) {
                // A failed image write must not strand an IS_PENDING row that
                // the normal MediaStore gallery query/cleanup cannot see.
                target.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        assertEquals(6, rows().size)
    }

    private fun assertFixtureAndCache() {
        val fixtures = rows()
        assertEquals("All six synthetic Pictures images must remain granted", 6, fixtures.size)
        assertEquals(6, fixtures.map { it.id }.distinct().size)
        fixtures.forEach { row ->
            target.contentResolver.openInputStream(android.net.Uri.parse(row.uri)).use { stream ->
                assertNotNull("Fixture bitmap became inaccessible", stream)
                assertNotNull("Fixture bitmap became invalid",
                    BitmapFactoryCompat.decode(stream))
            }
        }
        val snapshot = GalleryInventoryCacheFile(inventoryFile).read()
        assertNotNull("Synthetic cached catalogue missing", snapshot)
        val cached = requireNotNull(snapshot)
        assertEquals("Cached inventory must still contain six grant-correct tiles", 6,
            cached.items.count { candidate -> fixtures.any { it.id == candidate.id &&
                it.uri == candidate.uri } })
    }

    @Test fun seedCachedPicturesOutsideTimedWindow() {
        phase("seed")
        if (!marker.exists()) {
            // Mark ownership first: even a failed partial insert is removable
            // in the host's unconditional cleanup path.
            assertTrue(marker.createNewFile())
            insertFixture()
        }
        val all = rows()
        assertEquals(6, all.size)
        GalleryInventoryCacheFile(inventoryFile).write(
            GalleryMediaGrant(images = true, videos = true, selected = false), all)
        assertFixtureAndCache()
    }

    @Test fun verifyFreshPicturesAndCacheAfterColdFrame() {
        phase("verify")
        assertTrue("Host measurement marker disappeared", marker.isFile)
        assertFixtureAndCache()
    }

    @Test fun cleanupOnlyPerfA1Fixture() {
        phase("cleanup")
        assertTrue("Cleanup requires its own fixture marker", marker.isFile)
        val fixtures = rows()
        assertTrue("Cleanup refuses an oversized fixture", fixtures.size <= 6)
        fixtures.forEach { row ->
            assertTrue("Only one invented MediaStore row may be deleted",
                target.contentResolver.delete(android.net.Uri.parse(row.uri), null, null) == 1)
        }
        assertEquals(0, rows().size)
        // The host also pm-clears this isolated emulator after the test.
        assertTrue(marker.delete())
        if (inventoryFile.isFile) assertTrue(inventoryFile.delete())
    }

    private object BitmapFactoryCompat {
        fun decode(stream: java.io.InputStream?): Bitmap? {
            if (stream == null) return null
            return android.graphics.BitmapFactory.decodeStream(stream)?.also { it.recycle() }
        }
    }
}
