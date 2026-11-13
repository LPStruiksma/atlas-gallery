package nl.atlas.gallery.featuremedia

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #555: micro-thumbnail store, keys, sizes and the access rule (pure JVM). */
class MicroThumbnailTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo(
        generation: Long = 7L, width: Int = 4000, height: Int = 3000, orientation: Int = 0,
        version: String? = "v1"
    ) = MediaRecord(
        id = 42L, uri = "content://media/external/images/media/42", name = "IMG_42.jpg",
        relativePath = "DCIM/Camera/", sizeBytes = 3_000_000L, takenAtMillis = 1_700_000_000_000L,
        isVideo = false, modifiedAtSeconds = 1_700_000_000L, volumeName = "external_primary",
        providerVersion = version, generationModified = generation,
        widthPixels = width, heightPixels = height, orientationDegrees = orientation
    )

    @Test fun storeWritesReadsAndRemovesAtomically() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 1_000)
        assertNull(store.read("k1"))
        assertFalse(store.contains("k1"))
        assertTrue(store.write("k1", byteArrayOf(1, 2, 3)))
        assertTrue(store.contains("k1"))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read("k1"))
        // No temp files left behind; one file in a two-hex-digit subfolder.
        val files = store.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertTrue(files[0].name.endsWith(".jpg"))
        assertEquals(2, files[0].parentFile!!.name.length)
        assertFalse(store.write("k2", ByteArray(0)))
        store.remove("k1")
        assertFalse(store.contains("k1"))
    }

    @Test fun sizeIsTrackedAfterScanAndBudgetMarksFull() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 100)
        store.write("a", ByteArray(40))
        assertEquals(-1L, store.bytesUsed) // unknown until scanned
        assertFalse(store.full())
        assertEquals(40L, store.scan())
        store.write("b", ByteArray(40))
        assertEquals(80L, store.bytesUsed)
        store.write("b", ByteArray(30)) // replacing counts the difference
        assertEquals(70L, store.bytesUsed)
        store.write("c", ByteArray(40))
        assertTrue(store.full())
        store.clear()
        assertEquals(0L, store.bytesUsed)
        assertFalse(store.contains("a"))
    }

    @Test fun retainOnlyDeletesCopiesOfPhotosThatAreGone() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 100)
        for (i in 0 until 4) store.write("k$i", ByteArray(20))
        assertEquals(2, store.retainOnly(listOf("k1", "k3", "missing")))
        assertFalse(store.contains("k0"))
        assertTrue(store.contains("k1"))
        assertFalse(store.contains("k2"))
        assertTrue(store.contains("k3"))
        assertEquals(40L, store.bytesUsed)
    }

    /** Photos taken one minute apart, newest last in the list (the warmer sorts). */
    private fun library(count: Int) = (0 until count).map { i ->
        photo().copy(id = 1_000L + i, uri = "content://media/external/images/media/${1_000 + i}",
            takenAtMillis = 1_700_000_000_000L + i * 60_000L, generationModified = 100L + i)
    }

    private fun fill(store: MicroThumbnailStore, items: List<MediaRecord>, made: MutableList<Long>) =
        kotlinx.coroutines.runBlocking {
            fillMicroStore(store, items, active = { true }, yieldWhile = { false }) { media, key ->
                made += media.id
                store.write(key, ByteArray(10))
            }
        }

    @Test fun fillStopsAtBudgetKeepingTheNewest() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 100)
        val items = library(15)
        val made = ArrayList<Long>()
        val report = fill(store, items, made)
        assertEquals(10, report.generated)
        assertEquals((1_014L downTo 1_005L).toList(), made) // newest first
        assertTrue(store.full())
        // Full and nothing new: no writes and no deletes.
        made.clear()
        assertEquals(0, fill(store, items, made).generated)
        assertTrue(made.isEmpty())
        items.takeLast(10).forEach { assertTrue(store.contains(microThumbnailDiskKey(it)!!)) }
    }

    @Test fun aNewPhotoInAFullStoreEvictsOnlyTheOldestTaken() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 100)
        val items = library(15)
        fill(store, items, ArrayList())
        val newest = library(16).last()
        val made = ArrayList<Long>()
        val report = fill(store, items + newest, made)
        assertEquals(listOf(newest.id), made)
        assertEquals(1, report.generated)
        assertTrue(store.contains(microThumbnailDiskKey(newest)!!))
        // Exactly one copy went: the oldest stored one (1005); every newer one stays.
        assertFalse(store.contains(microThumbnailDiskKey(items[5])!!))
        items.subList(6, 15).forEach { assertTrue(store.contains(microThumbnailDiskKey(it)!!)) }
        assertEquals(100L, store.bytesUsed)
    }

    @Test fun aFullStoreOfDeletedPhotosIsSweptOnce() {
        val store = MicroThumbnailStore(tmp.newFolder("micro"), budgetBytes = 100)
        fill(store, library(10), ArrayList()) // these photos are later deleted
        assertTrue(store.full())
        val others = library(20).drop(10).map { it.copy(generationModified = it.generationModified + 1_000) }
        val made = ArrayList<Long>()
        assertEquals(10, fill(store, others, made).generated)
        others.forEach { assertTrue(store.contains(microThumbnailDiskKey(it)!!)) }
    }

    @Test fun diskKeySurvivesRestartsButNotEditsMemoryKeyIsPerPublication() {
        val p = photo()
        val disk = microThumbnailDiskKey(p)!!
        assertEquals(disk, microThumbnailDiskKey(p.copy()))
        // Epoch changes on every catalogue re-read: memory key changes, disk key does not.
        assertNotEquals(microThumbnailMemoryKey(p, 1L), microThumbnailMemoryKey(p, 2L))
        assertTrue(microThumbnailMemoryKey(p, 1L)!!.startsWith("atlas-micro|"))
        assertTrue(disk.startsWith("atlas-micro-v1|"))
        // An edit (new generation), rotation or resize is a new file.
        assertNotEquals(disk, microThumbnailDiskKey(photo(generation = 8L)))
        assertNotEquals(disk, microThumbnailDiskKey(photo(orientation = 90)))
        assertNotEquals(disk, microThumbnailDiskKey(photo(width = 3000)))
        // Unknown provider revision: no durable copy at all (fail closed).
        assertNull(microThumbnailDiskKey(photo(version = null)))
        assertNull(microThumbnailDiskKey(photo(generation = 0L)))
    }

    @Test fun onlyFullAccessKeepsCopies() {
        assertTrue(microThumbnailsAllowed(images = true, videos = true, selected = false))
        assertTrue(microThumbnailsAllowed(images = true, videos = false, selected = false))
        assertTrue(microThumbnailsAllowed(images = true, videos = true, selected = true))
        assertFalse(microThumbnailsAllowed(images = false, videos = false, selected = true))
        assertFalse(microThumbnailsAllowed(images = true, videos = false, selected = true))
        assertFalse(microThumbnailsAllowed(images = false, videos = false, selected = false))
    }

    @Test fun subsampleKeepsShortestSideAtLeastMicro() {
        assertEquals(16, microSampleSize(4000, 3000, 128)) // 3000/16 = 187
        assertEquals(4, microSampleSize(1280, 960, 128)) // 960/4 = 240, /8 = 120 < 128
        assertEquals(1, microSampleSize(200, 150, 128))
        assertEquals(1, microSampleSize(0, 0, 128))
        assertEquals(64, microSampleSize(100_000, 100_000, 128))
    }

    @Test fun targetSizeKeepsAspectAndSkipsSmallImages() {
        assertEquals(171 to 128, microTargetSize(320, 240, 128))
        assertEquals(128 to 228, microTargetSize(540, 960, 128))
        assertNull(microTargetSize(160, 120, 128)) // already micro-sized
        assertNull(microTargetSize(0, 100, 128))
    }

    @Test fun embeddedPreviewOnlyAtTheRightShape() {
        val landscape = expectedOrientedAspect(photo())!!
        assertEquals(4.0 / 3.0, landscape, 1e-9)
        assertEquals(3.0 / 4.0, expectedOrientedAspect(photo(orientation = 90))!!, 1e-9)
        assertNull(expectedOrientedAspect(photo(width = 0)))
        assertNull(expectedOrientedAspect(photo(orientation = 45)))
        assertTrue(exifPreviewUsable(512, 384, landscape, 128))
        assertTrue(exifPreviewUsable(160, 120, landscape, 128)) // 120 >= 96
        assertFalse(exifPreviewUsable(384, 512, landscape, 128)) // wrong orientation
        assertFalse(exifPreviewUsable(512, 288, landscape, 128)) // 16:9 crop of a 4:3 photo
        assertFalse(exifPreviewUsable(80, 60, landscape, 128)) // too small
    }

    @Test fun diskBudgetScalesWithStorage() {
        val mb = 1024L * 1024L
        assertEquals(64 * mb, microThumbnailDiskBudgetBytes(0L))
        assertEquals(64 * mb, microThumbnailDiskBudgetBytes(1024L * mb))
        assertEquals(768 * mb, microThumbnailDiskBudgetBytes(256L * 1024 * mb))
        assertEquals(16L * 1024 * mb / 33, microThumbnailDiskBudgetBytes(16L * 1024 * mb))
    }

    @Test fun fastScrollHasHysteresis() {
        val fast = ThumbnailPipeline.FAST_ROWS_PER_SECOND
        assertFalse(nextFastScroll(false, fast, scrolling = true)) // must exceed
        assertTrue(nextFastScroll(false, fast + 1f, scrolling = true))
        assertTrue(nextFastScroll(false, -(fast + 1f), scrolling = true)) // either direction
        assertTrue(nextFastScroll(true, fast / 2, scrolling = true)) // stays fast down to half
        assertFalse(nextFastScroll(true, fast / 2 - 0.1f, scrolling = true))
        assertFalse(nextFastScroll(true, fast * 3, scrolling = false)) // stopped = slow
        assertFalse(nextFastScroll(true, Float.NaN, scrolling = true))
    }
}
