package nl.atlas.gallery.featureidentity.install

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import nl.atlas.gallery.featureidentity.local.FetchResponse
import nl.atlas.gallery.featureidentity.local.IdentityModelManifest
import nl.atlas.gallery.featureidentity.local.IdentityModelStore
import nl.atlas.gallery.featureidentity.local.LicenceTier
import nl.atlas.gallery.featureidentity.local.ModelEntry
import nl.atlas.gallery.featureidentity.local.ModelRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IDS-19 (#619): the install runner the worker runs, with a fake in-memory fetcher (no
 * WorkManager test library on the classpath), and the state mapping the setup sheet reads.
 */
class ModelSetInstallTest {
    private val root: File = Files.createTempDirectory("ids19-set").toFile()
    @After fun cleanUp() { root.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun entry(id: String, bytes: ByteArray, tier: LicenceTier = LicenceTier.A) = ModelEntry(
        id = id, role = ModelRole.SWAP_NETWORK, fileName = "$id.onnx",
        url = "https://github.com/facefusion/facefusion-assets/releases/download/t/$id.onnx",
        sha256 = sha(bytes), sizeBytes = bytes.size.toLong(), registerRow = "test", tier = tier
    )

    @Test fun od1PeakSpaceIsAbout738MegabytesFromScratch() {
        val set = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("no fetch") }, IdentityModelManifest.entries, root) { 0 }
        // 402.7 MB swap model + 160.3 MB archive range + 174.4 MB extracted encoder beside it.
        assertEquals(737_387_516L, set.peakBytesNeeded())
        assertEquals(563_003_656L, set.totalBytes)
        assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NOT_ENOUGH_SPACE, retryable = false), set.run({ false }) { })
        val justEnough = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("no fetch") }, IdentityModelManifest.entries, root) {
            737_387_516L + ModelSetInstall.SPACE_MARGIN_BYTES - 1
        }
        assertTrue(!justEnough.enoughSpace())
    }

    @Test fun installsEverySetEntryInOrderWithOverallProgress() {
        val a = Random(1).nextBytes(2_000_000)
        val b = Random(2).nextBytes(1_500_000)
        val ea = entry("a", a)
        val eb = entry("b", b)
        val opened = mutableListOf<String>()
        val store = IdentityModelStore(root) { url, offset ->
            opened += url.substringAfterLast('/')
            val bytes = if (url.endsWith("a.onnx")) a else b
            FetchResponse(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), resumed = true)
        }
        val states = mutableListOf<InstallState>()
        val set = ModelSetInstall(store, listOf(ea, eb), root) { Long.MAX_VALUE }
        assertEquals(ModelSetInstall.Outcome.Installed, set.run({ false }) { states += it })
        assertEquals(listOf("a.onnx", "b.onnx"), opened)
        val bytes = states.filterIsInstance<InstallState.Downloading>().map { it.bytes }
        assertEquals(bytes.sorted(), bytes)
        assertTrue(states.filterIsInstance<InstallState.Downloading>().all { it.total == 3_500_000L })
        assertTrue(bytes.any { it > 2_000_000 }) // the second file counts on top of the first
        assertEquals(InstallState.Verifying, states.last())
        assertTrue(set.installed())
        assertEquals(3_500_000L, set.doneBytes())

        // Installed: a second run fetches nothing; Remove frees everything.
        assertEquals(ModelSetInstall.Outcome.Installed, set.run({ false }) { })
        assertEquals(2, opened.size)
        assertTrue(set.removeAll())
        assertEquals(0L, set.doneBytes())
    }

    @Test fun aClosedGateBlocksWithoutFetching() {
        val e = entry("c", ByteArray(10), tier = LicenceTier.C) // tier C without an owner decision
        val set = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("blocked entries are never fetched") }, listOf(e), root) { Long.MAX_VALUE }
        assertTrue(set.blocked())
        assertEquals(ModelSetInstall.Outcome.Blocked, set.run({ false }) { })
    }

    // ---- Lifecycle (review on #621): Cancel and Remove from the studio use their own store
    // instance, like IdentityModelInstaller does, while the worker's run is busy. ----

    private fun modelFiles(): List<String> =
        File(root, IdentityModelStore.DIR).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** A body that hands out [bytes] in 64 KB steps, waiting [pauseMs] per step. */
    private class SlowStream(private val bytes: ByteArray, private var pos: Int, private val pauseMs: Long) : InputStream() {
        override fun read(): Int = if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 255
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            Thread.sleep(pauseMs)
            val n = minOf(len, 64 * 1024, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n); pos += n
            return n
        }
    }

    @Test fun removeWhileDownloadingWaitsForTheWorkerAndLeavesNothing() {
        val a = Random(3).nextBytes(3_000_000)
        val ea = entry("a", a)
        val cancelled = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val downloading = CountDownLatch(1)
        val worker = ModelSetInstall(IdentityModelStore(root) { _, offset ->
            FetchResponse(SlowStream(a, offset.toInt(), pauseMs = 5), resumed = true)
        }, listOf(ea), root) { Long.MAX_VALUE }
        val thread = Thread {
            val outcome = worker.run({ cancelled.get() }) { if (it is InstallState.Downloading && it.bytes > 0) downloading.countDown() }
            events += "worker:$outcome"
        }.apply { start() }
        assertTrue(downloading.await(10, TimeUnit.SECONDS))
        cancelled.set(true) // WorkManager's cancel makes isStopped true
        val studio = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("studio never fetches") }, listOf(ea), root) { Long.MAX_VALUE }
        studio.removeAll()
        events += "removed"
        thread.join(10_000)
        assertEquals(listOf("worker:Cancelled", "removed"), events.toList())
        assertEquals(emptyList<String>(), modelFiles())
    }

    @Test fun removeDuringVerificationNeverResurrectsTheModel() {
        val a = Random(4).nextBytes(4_000_000)
        val ea = entry("a", a)
        val cancelled = AtomicBoolean(false)
        val verifying = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val worker = ModelSetInstall(IdentityModelStore(root) { _, offset ->
            FetchResponse(ByteArrayInputStream(a, offset.toInt(), a.size - offset.toInt()), resumed = true)
        }, listOf(ea), root) { Long.MAX_VALUE }
        val thread = Thread {
            val outcome = worker.run({ cancelled.get() }) {
                if (it == InstallState.Verifying) { cancelled.set(true); verifying.countDown(); Thread.sleep(100) }
            }
            events += "worker:$outcome"
        }.apply { start() }
        assertTrue(verifying.await(10, TimeUnit.SECONDS))
        val studio = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("studio never fetches") }, listOf(ea), root) { Long.MAX_VALUE }
        studio.removeAll() // blocks until the worker finished hashing and moving the file
        events += "removed"
        thread.join(10_000)
        assertEquals(listOf("worker:Cancelled", "removed"), events.toList())
        assertEquals(emptyList<String>(), modelFiles())
    }

    @Test fun cancelWhileQueuedDeletesThePartialWithoutAWorker() {
        val a = Random(5).nextBytes(500_000)
        val ea = entry("a", a)
        // A previous run lost its connection: partial on disk, work now waiting in the queue.
        File(root, IdentityModelStore.DIR).apply { mkdirs() }.let { File(it, "a.onnx.part").writeBytes(a.copyOf(200_000)) }
        val studio = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("no fetch") }, listOf(ea), root) { Long.MAX_VALUE }
        assertTrue(studio.discardUnfinished())
        assertEquals(emptyList<String>(), modelFiles())
    }

    @Test fun cancelAfterTheFirstFileDropsTheHalfInstalledSetButKeepsAFullSet() {
        val a = Random(6).nextBytes(300_000)
        val b = Random(7).nextBytes(300_000)
        val ea = entry("a", a)
        val eb = entry("b", b)
        val cancelled = AtomicBoolean(false)
        val store = IdentityModelStore(root) { url, offset ->
            if (url.endsWith("b.onnx")) cancelled.set(true) // cancel lands as the second file starts
            val bytes = if (url.endsWith("a.onnx")) a else b
            FetchResponse(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), resumed = true)
        }
        val set = ModelSetInstall(store, listOf(ea, eb), root) { Long.MAX_VALUE }
        assertEquals(ModelSetInstall.Outcome.Cancelled, set.run({ cancelled.get() }) { })
        assertTrue(store.installedFile(ea) != null)
        assertTrue(set.discardUnfinished())
        assertEquals(emptyList<String>(), modelFiles())

        // A fully installed set is never discarded by a late Cancel.
        val full = ModelSetInstall(IdentityModelStore(root) { url, offset ->
            val bytes = if (url.endsWith("a.onnx")) a else b
            FetchResponse(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), resumed = true)
        }, listOf(ea, eb), root) { Long.MAX_VALUE }
        assertEquals(ModelSetInstall.Outcome.Installed, full.run({ false }) { })
        assertFalse(full.discardUnfinished())
        assertTrue(full.installed())
    }

    @Test fun aRunCancelledBeforeItStartsTouchesNothing() {
        val ea = entry("a", ByteArray(1000) { 1 })
        val set = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("a cancelled run never fetches") }, listOf(ea), root) { Long.MAX_VALUE }
        assertEquals(ModelSetInstall.Outcome.Cancelled, set.run({ true }) { })
        assertEquals(emptyList<String>(), modelFiles())
    }

    @Test fun stateMappingCoversEverySheetState() {
        val t = 1_000L
        val m = InstallStateMapper
        assertEquals(InstallState.Blocked, m.map(InstallWork(InstallWork.Phase.RUNNING), blocked = true, installed = false, doneBytes = 0, total = t))
        assertEquals(InstallState.NotInstalled, m.map(null, false, false, 0, t))
        assertEquals(InstallState.Downloading(400, t), m.map(InstallWork(InstallWork.Phase.RUNNING, bytes = 400), false, false, 300, t))
        // Waiting for a connection (or a retry) shows what is already on disk.
        assertEquals(InstallState.Downloading(300, t), m.map(InstallWork(InstallWork.Phase.QUEUED), false, false, 300, t))
        assertEquals(InstallState.Verifying, m.map(InstallWork(InstallWork.Phase.RUNNING, verifying = true), false, false, t, t))
        assertEquals(InstallState.Installed, m.map(InstallWork(InstallWork.Phase.SUCCEEDED), false, true, t, t))
        assertEquals(InstallState.Installed, m.map(null, false, true, t, t))
        assertEquals(
            InstallState.Failed(InstallFailure.FILE_CHECK_FAILED),
            m.map(InstallWork(InstallWork.Phase.FAILED, failure = InstallFailure.FILE_CHECK_FAILED), false, false, 0, t)
        )
        assertEquals(InstallState.NotInstalled, m.map(InstallWork(InstallWork.Phase.CANCELLED), false, false, 0, t))
        // Succeeded earlier, then removed: not installed any more.
        assertEquals(InstallState.NotInstalled, m.map(InstallWork(InstallWork.Phase.SUCCEEDED), false, false, 0, t))
    }
}
