package nl.atlas.gallery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.toFaceVisibleMedia
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented metadata only; no Android provider or private media. */
class GalleryInventoryCacheTest {
    private val full = GalleryMediaGrant(true, true, false)
    private val selected = GalleryMediaGrant(false, false, true)
    private val none = GalleryMediaGrant(false, false, false)

    private fun record(id: Long, video: Boolean = false) = MediaRecord(
        id = id, uri = "content://media/external_primary/${if (video) "video" else "images"}/media/$id",
        name = "Invented $id.jpg", relativePath = "DCIM/SYNTHETIC-${id % 3}/",
        sizeBytes = id * 10, takenAtMillis = id * 1000, isVideo = video,
        durationMillis = if (video) 4200L else 0L, modifiedAtSeconds = id + 7,
        volumeName = if (id % 2 == 0L) "external_primary" else "1234-ABCD",
        providerVersion = if (id % 5 == 0L) null else "v$id".take(3),
        generationModified = id * 3, widthPixels = 4000, heightPixels = 3000,
        orientationDegrees = if (id % 4 == 0L) 90 else 0,
        isFavorite = id % 2 == 0L, favoriteKnown = id % 3 != 0L,
        dateSource = MediaDateSource.values()[(id % MediaDateSource.values().size).toInt()])

    private fun roundTrip(grant: GalleryMediaGrant, items: List<MediaRecord>) =
        ByteArrayOutputStream().also { GalleryInventoryCacheCodec.encode(it, grant, items) }
            .toByteArray()

    @Test fun revokeDuringWriteNeverPublishesLateCache() {
        val dir = Files.createTempDirectory("atlas-cache-revoke-").toFile()
        try {
            val destination = java.io.File(dir, "inventory.bin")
            val cache = GalleryInventoryCacheFile(destination)
            val encoding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val writer = Thread {
                try {
                    cache.write(full, listOf(record(1))) {
                        // Codec calls this while building its string table,
                        // after the writer has entered the serialized section.
                        encoding.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "write never released" }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            writer.start()
            assertTrue("writer did not enter encode", encoding.await(5, TimeUnit.SECONDS))
            val revoker = Thread { cache.revoke() }
            revoker.start()
            // Wait for invalidation before releasing the paused writer:
            // this forces the precise late-write race rather than relying on scheduling.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cache.isInvalidatedForTest() && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue("revoke did not invalidate", cache.isInvalidatedForTest())
            release.countDown()
            writer.join(5_000)
            revoker.join(5_000)
            assertFalse("writer stuck", writer.isAlive)
            assertFalse("revoker stuck", revoker.isAlive)
            assertNull(failure.get())
            assertFalse(destination.exists())
            assertFalse(java.io.File(dir, "inventory.bin.tmp").exists())
            cache.write(full, listOf(record(2)))
            assertFalse("stale writer revived revoked cache", destination.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun queuedRevokeCleanupCannotEraseCacheAfterRestore() {
        val dir = Files.createTempDirectory("atlas-cache-regrant-").toFile()
        try {
            val destination = java.io.File(dir, "inventory.bin")
            val cache = GalleryInventoryCacheFile(destination)

            cache.write(full, listOf(record(1)))
            assertTrue(destination.isFile)
            // Simulate the UI revoking access and queuing a cleanup that has
            // not yet started on the IO dispatcher.
            cache.invalidate()
            assertNull(cache.read())

            // New permission arrives before that queued cleanup executes.
            cache.restore()
            assertFalse("old grant bytes survived restore", destination.exists())
            cache.write(full, listOf(record(2)))
            assertEquals(listOf(record(2)), cache.read()!!.items)

            // Execute the old revoke's delayed cleanup after the new write.
            cache.deleteIfRevoked()
            assertEquals("old cleanup erased new grant", listOf(record(2)), cache.read()!!.items)
            assertFalse(java.io.File(dir, "inventory.bin.tmp").exists())

            // A later revocation still removes the cache.
            cache.invalidate()
            cache.deleteIfRevoked()
            assertFalse(destination.exists())
            assertNull(cache.read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun everyFieldSurvivesRoundTrip() {
        val items = (1L..40L).map { record(it, video = it % 7 == 0L) } +
            record(41).copy(uri = "content://odd/uri/without-id")
        val decoded = GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(roundTrip(selected, items)))!!
        assertEquals(selected, decoded.grant)
        assertEquals(items, decoded.items)
    }

    @Test fun unknownOrientationPreservesRecordAndDurableFaceRevision() {
        for (video in listOf(false, true)) {
            val original = record(if (video) 74L else 72L, video = video)
                .copy(orientationDegrees = Int.MIN_VALUE)
            val decoded = GalleryInventoryCacheCodec.decode(
                ByteArrayInputStream(roundTrip(full, listOf(original))))!!.items.single()
            assertEquals(original, decoded)
            assertEquals(
                original.toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey(),
                decoded.toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey()
            )
            // The old 16-bit cache read unknown rotation as measured zero,
            // changing the source revision even though identity was unchanged.
            val measuredZero = original.copy(orientationDegrees = 0)
                .toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey()
            val unknown = original.toFaceVisibleMedia(editorRevision = 0L).toDurableFaceKey()
            assertEquals(unknown.canonicalMediaKey, measuredZero.canonicalMediaKey)
            assertFalse(unknown.sourceRevision == measuredZero.sourceRevision)
        }
    }

    @Test fun canonicalOrientationsRoundTrip() {
        val items = listOf(0, 90, 180, 270).mapIndexed { index, orientation ->
            record(101L + index).copy(orientationDegrees = orientation)
        }
        assertEquals(items, GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(roundTrip(full, items)))!!.items)
    }

    @Test fun oldVersionOneCacheIsRejected() {
        val bytes = roundTrip(full, listOf(record(1)))
        // Header: four-byte magic followed by four-byte big-endian codec version.
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 1
        assertNull(GalleryInventoryCacheCodec.decode(ByteArrayInputStream(bytes)))
    }

    @Test fun largeLibraryStaysCompact() {
        val items = (1L..20_000L).map { record(it) }
        val bytes = roundTrip(full, items)
        // Shared folders/volumes/URI prefixes are interned once.
        assertTrue("size ${bytes.size}", bytes.size < items.size * 100)
        assertEquals(items.size, GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(bytes))!!.items.size)
    }

    @Test fun truncatedOrForeignFilesAreIgnored() {
        val bytes = roundTrip(full, (1L..10L).map { record(it) })
        assertNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(bytes.copyOf(bytes.size - 3))))
        assertNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream(bytes + byteArrayOf(1))))
        assertNull(GalleryInventoryCacheCodec.decode(ByteArrayInputStream(ByteArray(0))))
        assertNull(GalleryInventoryCacheCodec.decode(
            ByteArrayInputStream("not a cache".toByteArray())))
    }

    @Test fun cacheFillsOnlyTheInitialEmptyPublication() {
        val initial = GalleryPublishedInventory(0L, emptyList())
        assertTrue(mayShowCachedGalleryInventory(initial, full, false, full))
        assertTrue(mayShowCachedGalleryInventory(initial, selected, false, selected))
        // Never over a real scan, after revocation or without a live grant.
        val scanned = GalleryPublishedInventory(3L, listOf(record(1)), full)
        assertFalse(mayShowCachedGalleryInventory(scanned, full, false, full))
        assertFalse(mayShowCachedGalleryInventory(
            GalleryPublishedInventory(4L, emptyList(), full), full, false, full))
        assertFalse(mayShowCachedGalleryInventory(initial, full, true, full))
        assertFalse(mayShowCachedGalleryInventory(initial, none, false, full))
        // Switching between selected photos and full access waits for the scan.
        assertFalse(mayShowCachedGalleryInventory(initial, full, false, selected))
        assertFalse(mayShowCachedGalleryInventory(initial, selected, false, full))
        assertFalse(mayShowCachedGalleryInventory(initial, full, false, none))
    }

    @Test fun cachedSnapshotIsShownButNeverCountsAsAScanForTheGrant() {
        val items = listOf(record(1))
        val cached = GalleryPublishedInventory(0L, items, full, fromCache = true)
        assertTrue(cached.showable(full))
        assertTrue(cached.displayItems(full) === items)
        assertNull(cached.scanBoundGrant)
        assertFalse(cached.matchesGrantedScope(full))
        assertTrue(needsNewGrantBoundScan(full, cached.scanBoundGrant, null))
    }

    @Test fun staleOrNarrowerPublicationStaysOnScreenUntilRefreshLands() {
        val items = listOf(record(1), record(2))
        val old = GalleryPublishedInventory(7L, items, full)
        // #505: a re-picked or narrowed grant no longer blanks the gallery.
        assertTrue(old.displayItems(selected) === items)
        assertTrue(old.displayItems(GalleryMediaGrant(true, false, false)) === items)
        // Full access loss still empties it, and so do unbound placeholders.
        assertTrue(old.displayItems(none).isEmpty())
        assertTrue(GalleryPublishedInventory(8L, items).displayItems(full).isEmpty())
        assertTrue(GalleryPublishedInventory(9L, emptyList()).displayItems(full).isEmpty())
    }

    // #41: the phone's bug report has two fatal OutOfMemoryErrors in decode and
    // one in encode. The cache is an optimisation and must never end the app.
    @Test fun heapExhaustionWhileReadingMeansNoCacheNotACrash() {
        val dir = Files.createTempDirectory("atlas-cache-oom-read-").toFile()
        try {
            val cache = GalleryInventoryCacheFile(java.io.File(dir, "inventory.bin"))
            cache.write(full, listOf(record(1), record(2)))
            assertNull(cache.read { throw OutOfMemoryError("synthetic") })
            // The file is untouched: a later read with heap to spare succeeds.
            assertEquals(2, cache.read()?.items?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun heapExhaustionWhileWritingKeepsThePreviousCache() {
        val dir = Files.createTempDirectory("atlas-cache-oom-write-").toFile()
        try {
            val destination = java.io.File(dir, "inventory.bin")
            val cache = GalleryInventoryCacheFile(destination)
            cache.write(full, listOf(record(1)))
            cache.write(full, listOf(record(1), record(2), record(3))) {
                throw OutOfMemoryError("synthetic")
            }
            assertEquals(1, cache.read()?.items?.size)
            assertFalse(java.io.File(dir, "inventory.bin.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
