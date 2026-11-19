package nl.atlas.gallery.featureidentity.install

import java.io.ByteArrayInputStream
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
        val set = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("no fetch") }, IdentityModelManifest.entries) { 0 }
        // 402.7 MB swap model + 160.3 MB archive range + 174.4 MB extracted encoder beside it.
        assertEquals(737_387_516L, set.peakBytesNeeded())
        assertEquals(563_003_656L, set.totalBytes)
        assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NOT_ENOUGH_SPACE, retryable = false), set.run({ false }) { })
        val justEnough = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("no fetch") }, IdentityModelManifest.entries) {
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
        val set = ModelSetInstall(store, listOf(ea, eb)) { Long.MAX_VALUE }
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
        val set = ModelSetInstall(IdentityModelStore(root) { _, _ -> error("blocked entries are never fetched") }, listOf(e)) { Long.MAX_VALUE }
        assertTrue(set.blocked())
        assertEquals(ModelSetInstall.Outcome.Blocked, set.run({ false }) { })
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
