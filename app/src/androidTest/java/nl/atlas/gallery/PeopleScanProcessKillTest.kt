package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #43: real process death mid-scan, driven by scripts/ci_android_upgrade_emulator.sh
 * in separate instrumentation invocations. No Activity is ever started here.
 *
 * - seed: invented PNGs in MediaStore, the gallery cache written under today's
 *   grant, and an opt-in through the app's own [PeopleDiscoveryHost.forApp]
 *   while the emulator is unplugged, so the WorkManager chain is enqueued but
 *   no batch runs yet. Nothing is processed.
 * - (script) the finished instrumentation force-stops the package, which
 *   cancels its jobs, so ATLAS is opened once, as a user would, to arm the
 *   chain; Home, then SIGKILL. Charging lets WorkManager start a worker
 *   process; it is killed with SIGKILL mid-run, and WorkManager must start
 *   another that carries on. No Activity starts after the first SIGKILL.
 * - verify: only the real app database and preferences are read. The
 *   published catalogue made progress, all of it in worker processes.
 * - cleanup: deletes exactly the invented rows.
 *
 * The ordinary connected suite skips every phase.
 */
@RunWith(AndroidJUnit4::class)
class PeopleScanProcessKillTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase: String? = InstrumentationRegistry.getArguments().getString("atlasKillPhase")
    private val record = File(target.noBackupFilesDir, "atlas-ci-kill43.txt")

    private fun requireEmulator() {
        assertTrue("Only an isolated emulator may execute process phases",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
                android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.FINGERPRINT.contains("emulator"))
    }

    /**
     * Fixture setup only: right after the seed inserts, MediaProvider may still
     * be publishing the rows, so a scan sees its generation move and the strict
     * snapshot check refuses it (GalleryProviderSnapshotChanged, seen on CI).
     * Rescan a bounded number of times, as the app would; the check stays strict.
     */
    private fun settledLoad(): List<MediaRecord> {
        repeat(SETTLE_TRIES - 1) {
            try { return GalleryRepository(target).load() }
            catch (e: GalleryProviderSnapshotChanged) { Thread.sleep(500) }
        }
        return GalleryRepository(target).load()
    }

    private fun fixtures() = settledLoad().filter {
        it.relativePath == "Pictures/$FOLDER/" && it.name.startsWith(PREFIX)
    }

    @Test fun seedBeforeKill() {
        assumeTrue(phase == "seed")
        requireEmulator()
        assertTrue("seed starts from cleared app data", !record.exists())
        assertTrue(readGalleryMediaGrant(target).images)
        assertNotNull("a full photo grant has a token", galleryPhotoGrantToken(target))
        assertTrue("the fixture must start empty", fixtures().isEmpty())
        for (i in 1..PHOTOS) {
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$PREFIX$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$FOLDER/")
                    put(MediaStore.Images.Media.DATE_TAKEN,
                        System.currentTimeMillis() - i * 3_600_000L)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
            val bitmap = SyntheticFace.bitmap(SyntheticFace.Spec(
                480, 480, 0.35f + 0.005f * i, 0.5f, 0.75f + 0.004f * i))
            try {
                requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            target.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        assertEquals(PHOTOS, fixtures().size)
        // What GalleryViewModel keeps on disk (#505), under the grant of now.
        GalleryInventoryCacheFile(File(target.noBackupFilesDir, GALLERY_INVENTORY_CACHE_FILE))
            .write(readGalleryMediaGrant(target), settledLoad())
        val inventory = requireNotNull(cachedFaceInventory(target)) { "cache not trusted" }
        // The app's own host: real preferences, real database, real WorkManager.
        val host = PeopleDiscoveryHost.forApp(target)
        host.start("ci-kill43-seed", inventory)
        val epoch = requireNotNull(host.displayEpoch("ci-kill43-seed")) { "not published" }
        assertEquals(epoch, PeopleOptInMemory.preferences(target).loadPublished()?.epoch)
        record.writeText("$epoch\n${inventory.size}\n")
        android.util.Log.i("ATLAS_KILL43", "seeded items=${inventory.size}")
    }

    @Test fun verifyAfterKill() {
        assumeTrue(phase == "verify")
        requireEmulator()
        val (seeded, items) = record.readLines().let { it[0] to it[1].toInt() }
        val memory = PeopleOptInMemory.preferences(target)
        assertNotNull("still opted in", memory.load())
        val epoch = requireNotNull(memory.loadPublished()?.epoch) { "a published scope survived" }
        android.util.Log.i("ATLAS_KILL43", "verify carried=${epoch == seeded}")
        val store = FaceIndexStore(target)
        try {
            assertEquals("the last epoch is the seeded one", epoch, store.reviveDormantEpoch())
            val progress = requireNotNull(store.progress(epoch))
            android.util.Log.i("ATLAS_KILL43", "verify processed=${progress.processed} " +
                "failed=${progress.failed} pending=${progress.pending} items=$items")
            assertTrue("worker processes scanned photos the seed never touched",
                progress.processed > 0)
        } finally {
            store.close()
        }
    }

    @Test fun cleanupAfterKill() {
        assumeTrue(phase == "cleanup")
        requireEmulator()
        val rows = fixtures()
        assertEquals(PHOTOS, rows.size)
        rows.forEach { row ->
            assertEquals("only the exact invented fixture may be deleted", 1,
                target.contentResolver.delete(Uri.parse(row.uri), null, null))
        }
        record.delete()
    }

    private companion object {
        const val PHOTOS = 60
        const val SETTLE_TRIES = 10
        const val FOLDER = "ATLAS-Kill43"
        const val PREFIX = "IMG_KILL43_"
    }
}
