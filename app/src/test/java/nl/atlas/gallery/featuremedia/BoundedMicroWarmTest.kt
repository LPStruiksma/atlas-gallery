package nl.atlas.gallery.featuremedia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic data only: a full second catalogue is never allocated by these tests. */
class BoundedMicroWarmTest {
    @get:Rule val temp = TemporaryFolder()

    private fun photo(id: Long) = MediaRecord(
        id = id, uri = "content://media/external_primary/images/media/" + id,
        name = "invented-" + id + ".jpg", relativePath = "DCIM/Test/",
        sizeBytes = 1234L, takenAtMillis = id * 1_000L, isVideo = false,
        modifiedAtSeconds = 1_700_000_000L, volumeName = "external_primary",
        providerVersion = "synthetic-v1", generationModified = 2L,
        widthPixels = 1000, heightPixels = 800, orientationDegrees = 0
    )

    private class SyntheticPages(
        private val count: Int, private val pageSize: Int,
        private val row: (Int) -> MediaRecord
    ) : MicroWarmPageReader {
        var next = 0
        var maxPage = 0
        override fun nextPage(): List<MediaRecord> {
            if (next >= count) return emptyList()
            val end = (next + pageSize).coerceAtMost(count)
            val page = (next until end).map(row)
            next = end
            maxPage = maxOf(maxPage, page.size)
            return page
        }
        override fun close() {}
    }

    @Test fun streams250kRowsNewestFirstWithAtMost128RecordsInMemory() = runBlocking {
        val store = MicroThumbnailStore(temp.newFolder("large"), Long.MAX_VALUE)
        val newest = SyntheticPages(250_000, 128) { i -> photo((250_000 - i).toLong()) }
        var oldestOpened = false
        val first = ArrayList<Long>()
        val report = newest.use {
            fillMicroStorePaged(store, it, totalItems = 250_000,
                oldest = {
                    oldestOpened = true
                    SyntheticPages(250_000, 128) { i -> photo((i + 1).toLong()) }
                }, active = { true }, yieldWhile = { false },
                make = { media, _ ->
                    if (first.size < 5) first += media.id
                    true
                })
        }
        assertEquals(250_000, report.checked)
        assertEquals(250_000, report.generated)
        assertEquals(listOf(250_000L, 249_999L, 249_998L, 249_997L, 249_996L), first)
        assertTrue(newest.maxPage <= 128)
        assertFalse(oldestOpened) // no eviction work when no budget pressure
    }

    @Test fun smallLibraryMatchesOldestFirstEvictionAndDoesNotRewarmCachedItems() = runBlocking {
        val items = (1L..15L).map(::photo)
        val baseline = MicroThumbnailStore(temp.newFolder("baseline"), 100)
        val paged = MicroThumbnailStore(temp.newFolder("paged"), 100)
        val oldMade = mutableListOf<Long>()
        val newMade = mutableListOf<Long>()
        fun write(store: MicroThumbnailStore, item: MediaRecord, key: String, made: MutableList<Long>): Boolean {
            made += item.id
            return store.write(key, ByteArray(10))
        }
        fillMicroStore(baseline, items, { true }, { false }) { item, key ->
            write(baseline, item, key, oldMade)
        }
        suspend fun runPaged() {
            val desc = items.sortedByDescending { it.takenAtMillis }
            val newest = SyntheticPages(desc.size, 4) { i -> desc[i] }
            newest.use { reader ->
                fillMicroStorePaged(paged, reader, desc.size,
                    oldest = { SyntheticPages(desc.size, 4) { i -> desc[desc.size - 1 - i] } },
                    active = { true }, yieldWhile = { false },
                    make = { item, key -> write(paged, item, key, newMade) })
            }
        }
        runPaged()
        assertEquals(oldMade, newMade)
        oldMade.clear()
        newMade.clear()
        fillMicroStore(baseline, items, { true }, { false }) { item, key ->
            write(baseline, item, key, oldMade)
        }
        runPaged()
        assertTrue(oldMade.isEmpty())
        assertTrue(newMade.isEmpty())
        val newer = photo(16)
        val desc = (items + newer).sortedByDescending { it.takenAtMillis }
        fillMicroStore(baseline, items + newer, { true }, { false }) { item, key ->
            write(baseline, item, key, oldMade)
        }
        SyntheticPages(desc.size, 4) { desc[it] }.use { reader ->
            fillMicroStorePaged(paged, reader, desc.size,
                oldest = { SyntheticPages(desc.size, 4) { i -> desc[desc.size - 1 - i] } },
                active = { true }, yieldWhile = { false },
                make = { item, key -> write(paged, item, key, newMade) })
        }
        assertEquals(oldMade, newMade)
        assertEquals(listOf(16L), newMade)
        items.forEach {
            assertEquals(baseline.contains(microThumbnailDiskKey(it)!!),
                paged.contains(microThumbnailDiskKey(it)!!))
        }
    }

    @Test fun onlyCompleteCatalogueMaySweepOrphansAndNoOtherPageIsDeleted() = runBlocking {
        val store = MicroThumbnailStore(temp.newFolder("orphans"), 40)
        val live = (1L..4L).map(::photo)
        val valid = live.map { microThumbnailDiskKey(it)!! }
        val orphan = "synthetic-deleted-photo"
        valid.forEach { store.write(it, ByteArray(10)) }
        store.write(orphan, ByteArray(10))
        val desc = live.reversed()
        var sweeps = 0
        SyntheticPages(4, 2) { desc[it] }.use { reader ->
            fillMicroStorePaged(store, reader, 4,
                oldest = { SyntheticPages(4, 2) { i -> live[i] } },
                active = { true }, yieldWhile = { false },
                sweepOrphans = {
                    sweeps++
                    store.retainOnly(valid)
                    true
                },
                make = { _, _ -> true })
        }
        assertEquals(0, sweeps) // all current entries are hits: never purge
        assertTrue(store.contains(orphan))
        assertTrue(store.contains(valid.first()))
    }

    @Test fun completeCatalogueSweepReclaimsDeletedRevisionsBeforeNewWrite() = runBlocking {
        val store = MicroThumbnailStore(temp.newFolder("sweep"), 30)
        val deletedKeys = listOf("removed-1", "removed-2", "removed-3")
        deletedKeys.forEach { store.write(it, ByteArray(10)) }
        val live = (1L..3L).map(::photo)
        val kept = live.map { microThumbnailDiskKey(it)!! }
        var sweeps = 0
        val made = mutableListOf<Long>()
        SyntheticPages(3, 1) { i -> live[2 - i] }.use { reader ->
            fillMicroStorePaged(store, reader, 3,
                oldest = { SyntheticPages(3, 1) { i -> live[i] } },
                active = { true }, yieldWhile = { false },
                sweepOrphans = {
                    sweeps++
                    store.retainOnly(kept)
                    true
                },
                make = { media, key ->
                    made += media.id
                    store.write(key, ByteArray(10))
                })
        }
        assertEquals(1, sweeps)
        assertEquals(listOf(3L, 2L, 1L), made)
        deletedKeys.forEach { assertFalse(store.contains(it)) }
        kept.forEach { assertTrue(store.contains(it)) }
    }

    @Test fun grantLossStopsBeforeNextPageOrWrite() = runBlocking {
        val store = MicroThumbnailStore(temp.newFolder("grant"), Long.MAX_VALUE)
        var grant = true
        var made = 0
        SyntheticPages(300, 16) { photo((300 - it).toLong()) }.use { reader ->
            fillMicroStorePaged(store, reader, 300,
                oldest = { error("no eviction expected") },
                active = { true }, yieldWhile = { false }, gate = { grant },
                make = { _, _ ->
                    made++
                    if (made == 19) grant = false
                    true
                })
        }
        assertEquals(19, made)
    }

    @Test fun cancellationSignalsInFlightProviderCallAndThumbnailDecode() = runBlocking {
        // No Android runtime or real media required: both operations emulate a
        // blocking provider call that returns only when its signal is cancelled.
        for (phase in listOf("provider-query", "thumbnail-decode")) {
            val entered = CountDownLatch(1)
            val signalCancelled = CountDownLatch(1)
            val releaseBlockingCall = CountDownLatch(1)
            val worker = launch(Dispatchers.IO) {
                val watcher = CoroutineScope(coroutineContext)
                    .watchMicroWarmCancellation {
                        signalCancelled.countDown()
                        releaseBlockingCall.countDown()
                    }
                try {
                    entered.countDown()
                    check(releaseBlockingCall.await(10, TimeUnit.SECONDS)) {
                        "$phase remained blocked after cancellation"
                    }
                    coroutineContext.ensureActive()
                } finally {
                    watcher.cancel()
                }
            }
            try {
                assertTrue("$phase did not start", entered.await(3, TimeUnit.SECONDS))
                worker.cancel()
                assertTrue("$phase was not signalled while blocked",
                    signalCancelled.await(3, TimeUnit.SECONDS))
            } finally {
                releaseBlockingCall.countDown()
                worker.cancelAndJoin()
            }
        }
    }

    @Test fun coroutineCancellationStopsMidStream() = runBlocking {
        val store = MicroThumbnailStore(temp.newFolder("cancel"), Long.MAX_VALUE)
        var made = 0
        lateinit var child: Job
        child = launch(start = CoroutineStart.LAZY) {
            SyntheticPages(1000, 16) { photo((1000 - it).toLong()) }.use { reader ->
                fillMicroStorePaged(store, reader, 1000,
                    oldest = { error("no eviction expected") },
                    active = { true }, yieldWhile = { false },
                    make = { _, _ ->
                        made++
                        if (made == 23) child.cancel()
                        true
                    })
            }
        }
        child.start()
        child.join()
        assertTrue(child.isCancelled)
        assertEquals(23, made)
    }
}
