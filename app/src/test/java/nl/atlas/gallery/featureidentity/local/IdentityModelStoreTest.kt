package nl.atlas.gallery.featureidentity.local

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDS-9 (#475): the manifest gate, resumable install, verification and Remove; synthetic bytes only. */
class IdentityModelStoreTest {
    private val root: File = Files.createTempDirectory("ids9-models").toFile()
    @After fun tearDown() { root.deleteRecursively() }

    private val bytes = ByteArray(200_000) { (it * 31 + 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(tier: LicenceTier, decision: String? = null, hash: String = sha) = ModelEntry(
        id = "synthetic-swap", role = ModelRole.SWAP_NETWORK, fileName = "synthetic_swap.onnx",
        url = "https://example.invalid/original-host/synthetic_swap.onnx", sha256 = hash,
        sizeBytes = bytes.size.toLong(), registerRow = "synthetic row", tier = tier, ownerDecision = decision)

    private class CountingFetcher(val data: ByteArray, val honourRange: Boolean = true, val stopAfter: Int? = null) : ModelFetcher {
        var calls = 0
        val offsets = ArrayList<Long>()
        override fun open(url: String, offset: Long): FetchResponse {
            calls++; offsets += offset
            val from = if (honourRange) offset.toInt() else 0
            val end = stopAfter?.let { minOf(data.size, from + it) } ?: data.size
            return FetchResponse(ByteArrayInputStream(data, from, end - from), resumed = honourRange && offset > 0)
        }
    }

    @Test fun tierCOrEWithoutOwnerDecisionCanNeverDownload() {
        for (tier in listOf(LicenceTier.C, LicenceTier.E)) {
            val fetcher = CountingFetcher(bytes)
            val store = IdentityModelStore(root, fetcher)
            val e = entry(tier)
            assertEquals(ModelGate.NeedsOwnerDecision, ModelGatePolicy.gate(e))
            assertEquals(ModelState.Blocked(ModelGate.NeedsOwnerDecision), store.state(e))
            assertThrows(ModelGateException::class.java) { store.install(e) }
            assertEquals("no network access for a closed gate", 0, fetcher.calls)
            // A file planted under its name is still not "installed".
            File(root, IdentityModelStore.DIR).mkdirs()
            File(root, "${IdentityModelStore.DIR}/${e.fileName}").writeBytes(bytes)
            File(root, "${IdentityModelStore.DIR}/${e.fileName}.sha256").writeText(sha)
            assertNull(store.installedFile(e))
            store.remove(e)
        }
        val forbidden = entry(LicenceTier.D, decision = "https://github.com/LPStruiksma/atlas-gallery/issues/467")
        assertEquals(ModelGate.Forbidden, ModelGatePolicy.gate(forbidden))
    }

    @Test fun recordedOwnerDecisionOpensTheGate() {
        val e = entry(LicenceTier.C, "https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396")
        assertEquals(ModelGate.Allowed, ModelGatePolicy.gate(e))
        assertEquals(ModelGate.Allowed, ModelGatePolicy.gate(entry(LicenceTier.A)))
        assertThrows(IllegalArgumentException::class.java) { entry(LicenceTier.C, "OD-1 said yes") }
    }

    @Test fun installVerifiesAndRemoveDeletesEverything() {
        val fetcher = CountingFetcher(bytes)
        val store = IdentityModelStore(root, fetcher)
        val e = entry(LicenceTier.A)
        assertEquals(ModelState.NotInstalled, store.state(e))
        val file = store.install(e)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(ModelState.Installed(file), store.state(e))
        store.install(e)
        assertEquals("an installed model is not fetched again", 1, fetcher.calls)
        assertTrue(store.remove(e))
        assertEquals(ModelState.NotInstalled, store.state(e))
        assertEquals(0, File(root, IdentityModelStore.DIR).listFiles()!!.size)
    }

    @Test fun interruptedDownloadResumesFromItsOffset() {
        val e = entry(LicenceTier.B)
        val first = IdentityModelStore(root, CountingFetcher(bytes, stopAfter = 70_000))
        assertThrows(ModelVerificationException::class.java) { first.install(e) }
        assertEquals(ModelState.Partial(70_000), first.state(e))
        val fetcher = CountingFetcher(bytes)
        val file = IdentityModelStore(root, fetcher).install(e)
        assertEquals(listOf(70_000L), fetcher.offsets)
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test fun hostIgnoringRangeRestartsCleanly() {
        val e = entry(LicenceTier.A)
        assertThrows(ModelVerificationException::class.java) {
            IdentityModelStore(root, CountingFetcher(bytes, stopAfter = 50_000)).install(e)
        }
        val file = IdentityModelStore(root, CountingFetcher(bytes, honourRange = false)).install(e)
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test fun wrongHashIsDiscarded() {
        val e = entry(LicenceTier.A, hash = "0".repeat(64))
        val store = IdentityModelStore(root, CountingFetcher(bytes))
        assertThrows(ModelVerificationException::class.java) { store.install(e) }
        assertNull(store.installedFile(e))
        assertFalse(File(root, "${IdentityModelStore.DIR}/${e.fileName}").exists())
        assertFalse(File(root, "${IdentityModelStore.DIR}/${e.fileName}.part").exists())
    }

    @Test fun manifestEntriesAreAllGatedByTheirRegisterRow() {
        // Today's manifest is empty by design (IDS-17 #516 adds OD-1 entries); every future entry
        // must still pass the gate rules.
        IdentityModelManifest.entries.forEach { e ->
            assertTrue(e.registerRow.isNotBlank())
            if (e.tier == LicenceTier.C || e.tier == LicenceTier.E) assertTrue(e.ownerDecision != null)
            assertFalse(e.tier == LicenceTier.D)
        }
    }
}
