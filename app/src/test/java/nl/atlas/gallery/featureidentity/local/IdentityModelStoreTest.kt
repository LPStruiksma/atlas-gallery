package nl.atlas.gallery.featureidentity.local

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDS-9 (#475) and IDS-17 (#516): the manifest gate, host allowlist, resumable install, archive members, verification and Remove; synthetic bytes only. */
class IdentityModelStoreTest {
    private val root: File = Files.createTempDirectory("ids9-models").toFile()
    @After fun tearDown() { root.deleteRecursively() }

    private val bytes = ByteArray(200_000) { (it * 31 + 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun entry(tier: LicenceTier, decision: String? = null, hash: String = sha) = ModelEntry(
        id = "synthetic-swap", role = ModelRole.SWAP_NETWORK, fileName = "synthetic_swap.onnx",
        url = "https://github.com/facefusion/facefusion-assets/releases/download/synthetic/synthetic_swap.onnx", sha256 = hash,
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

    /**
     * An app update keeps noBackupFilesDir. The store has no version-keyed folder or wipe:
     * a new store instance (new app version) on the same folder sees the model as installed
     * without any network access. Only a changed manifest hash makes it reinstall.
     */
    @Test fun installedModelSurvivesAnAppUpdateWithoutRefetching() {
        val e = entry(LicenceTier.A)
        IdentityModelStore(root, CountingFetcher(bytes)).install(e)
        val afterUpdate = IdentityModelStore(root) { _, _ -> error("an installed model must not be fetched again") }
        val state = afterUpdate.state(e)
        assertTrue(state is ModelState.Installed)
        assertTrue(afterUpdate.install(e).readBytes().contentEquals(bytes))

        val newer = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val newerSha = MessageDigest.getInstance("SHA-256").digest(newer).joinToString("") { "%02x".format(it) }
        val updated = e.copy(sha256 = newerSha)
        assertNull("a new model version is not mistaken for the old file", afterUpdate.installedFile(updated))
        val file = IdentityModelStore(root, CountingFetcher(newer)).install(updated)
        assertTrue(file.readBytes().contentEquals(newer))
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
        IdentityModelManifest.entries.forEach { e ->
            assertTrue(e.registerRow.isNotBlank())
            if (e.tier == LicenceTier.C || e.tier == LicenceTier.E) assertTrue(e.ownerDecision != null)
            assertFalse(e.tier == LicenceTier.D)
            assertEquals(ModelGate.Allowed, ModelGatePolicy.gate(e))
        }
    }

    @Test fun manifestHasTheTwoOd1EntriesFromTheirOriginalHosts() {
        val od1 = "https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396"
        val hs = IdentityModelManifest.HYPERSWAP_1A_256
        val arc = IdentityModelManifest.INSIGHTFACE_W600K_R50
        assertEquals(listOf(hs, arc), IdentityModelManifest.entries)
        assertEquals(listOf(ModelRole.SWAP_NETWORK, ModelRole.IDENTITY_ENCODER), listOf(hs.role, arc.role))
        for (e in listOf(hs, arc)) { assertEquals(od1, e.ownerDecision); assertEquals(LicenceTier.C, e.tier) }
        assertEquals("https://github.com/facefusion/facefusion-assets/releases/download/models-3.3.0/hyperswap_1a_256.onnx", hs.url)
        assertEquals(402_742_682L, hs.sizeBytes)
        assertNull(hs.archive)
        assertEquals("https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip", arc.url)
        assertEquals("w600k_r50.onnx", arc.archive!!.member)
        assertEquals(174_383_860L, arc.sizeBytes)
        // Range = 30-byte local header + 14-byte name + 16-byte extra + deflated data.
        assertEquals(30L + 14 + 16 + arc.archive!!.compressedBytes, arc.archive!!.rangeBytes)
        assertEquals(402_742_682L + 160_260_974L, IdentityModelManifest.downloadBytes)
        assertEquals(402_742_682L + 174_383_860L, IdentityModelManifest.installedBytes)
    }

    @Test fun manifestEntryWithoutOwnerDecisionRefusesToDownload() {
        for (e in IdentityModelManifest.entries) {
            val fetcher = CountingFetcher(bytes)
            val stripped = e.copy(ownerDecision = null)
            assertEquals(ModelGate.NeedsOwnerDecision, ModelGatePolicy.gate(stripped))
            assertThrows(ModelGateException::class.java) { IdentityModelStore(root, fetcher).install(stripped) }
            assertEquals(0, fetcher.calls)
        }
    }

    @Test fun onlyOriginalHostsMayServeAModel() {
        val od1 = IdentityModelManifest.OD1
        val bad = listOf(
            "https://huggingface.co/facefusion/models-3.3.0/resolve/main/hyperswap_1a_256.onnx",   // a mirror
            "https://example.invalid/original-host/synthetic_swap.onnx",
            "https://github.com/someone/facefusion-assets/releases/download/models-3.3.0/hyperswap_1a_256.onnx",
            "https://github.com/facefusion/facefusion-assets/releases/download/",                    // no file
            "https://github.com/facefusion/facefusion-assets/releases/download/../../x/y.onnx",
            "https://github.com/facefusion/facefusion-assets/releases/download/m/x.onnx?redirect=evil",
            "https://github.com.evil.invalid/facefusion/facefusion-assets/releases/download/m/x.onnx",
            "https://user@github.com/facefusion/facefusion-assets/releases/download/m/x.onnx"
        )
        for (url in bad) {
            assertFalse(url, ModelHostPolicy.allowed(url))
            val fetcher = CountingFetcher(bytes)
            val e = entry(LicenceTier.C, od1).copy(url = url)
            assertEquals(url, ModelGate.HostNotAllowed, ModelGatePolicy.gate(e))
            assertEquals(ModelState.Blocked(ModelGate.HostNotAllowed), IdentityModelStore(root, fetcher).state(e))
            assertThrows(ModelGateException::class.java) { IdentityModelStore(root, fetcher).install(e) }
            assertEquals("no network access for a mirror", 0, fetcher.calls)
        }
        assertEquals(ModelGate.HostNotAllowed, ModelGatePolicy.gate(entry(LicenceTier.A).copy(url = bad[1])))
        IdentityModelManifest.entries.forEach { assertTrue(it.url, ModelHostPolicy.allowed(it.url)) }
    }

    @Test fun sizeCheckRefusesALargerFile() {
        val e = entry(LicenceTier.A)
        val longer = bytes + byteArrayOf(1, 2, 3)
        assertThrows(ModelVerificationException::class.java) { IdentityModelStore(root, CountingFetcher(longer)).install(e) }
        assertNull(IdentityModelStore(root, CountingFetcher(bytes)).installedFile(e))
        assertFalse(File(root, "${IdentityModelStore.DIR}/${e.fileName}.part").exists())
    }

    /** A synthetic archive shaped like buffalo_l.zip: other members around the model, streamed (data descriptors). */
    private class SyntheticArchive(model: ByteArray) {
        val bytes: ByteArray
        val member: ArchiveMember
        init {
            val out = java.io.ByteArrayOutputStream()
            var start = 0L
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("other.onnx")); zip.write(ByteArray(30_000) { (it % 13).toByte() }); zip.closeEntry()
                zip.flush(); start = out.size().toLong()
                zip.putNextEntry(ZipEntry("w600k_r50.onnx")); zip.write(model); zip.closeEntry()
                zip.putNextEntry(ZipEntry("after.onnx")); zip.write(ByteArray(10_000) { 7 }); zip.closeEntry()
            }
            bytes = out.toByteArray()
            // Find the end of the member's deflated data: the data descriptor signature that follows it.
            val nameLen = 14
            val dataStart = start + 30 + nameLen
            var end = dataStart
            while (!(bytes[end.toInt()] == 0x50.toByte() && bytes[end.toInt() + 1] == 0x4b.toByte() &&
                    bytes[end.toInt() + 2] == 0x07.toByte() && bytes[end.toInt() + 3] == 0x08.toByte())) end++
            val range = bytes.copyOfRange(start.toInt(), end.toInt())
            member = ArchiveMember("w600k_r50.onnx", start, range.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(range).joinToString("") { "%02x".format(it) }, end - dataStart)
        }
    }

    /** Incompressible model bytes, so the archive range is about as large as the model. */
    private val model = ByteArray(200_000).also { java.util.Random(516).nextBytes(it) }

    private fun archiveEntry(a: SyntheticArchive, model: ByteArray = this.model) = ModelEntry(
        id = "synthetic-encoder", role = ModelRole.IDENTITY_ENCODER, fileName = "w600k_r50.onnx",
        url = "https://github.com/deepinsight/insightface/releases/download/synthetic/buffalo_l.zip",
        sha256 = sha(model), sizeBytes = model.size.toLong(), registerRow = "synthetic row",
        tier = LicenceTier.C, ownerDecision = IdentityModelManifest.OD1, archive = a.member)

    @Test fun archiveMemberIsFetchedAsOneRangeAndExtracted() {
        val a = SyntheticArchive(model)
        val e = archiveEntry(a)
        val fetcher = CountingFetcher(a.bytes)
        val file = IdentityModelStore(root, fetcher).install(e)
        assertTrue(file.readBytes().contentEquals(model))
        assertEquals("only the member's range is requested", listOf(a.member.rangeStart), fetcher.offsets)
        assertTrue(a.member.rangeBytes < a.bytes.size)
        assertEquals(listOf("w600k_r50.onnx", "w600k_r50.onnx.sha256"), File(root, IdentityModelStore.DIR).list()!!.sorted())
        assertTrue(IdentityModelStore(root, CountingFetcher(a.bytes)).remove(e))
    }

    @Test fun archiveRangeResumesAndSurvivesAHostIgnoringRanges() {
        val a = SyntheticArchive(model)
        val e = archiveEntry(a)
        assertTrue(a.member.rangeBytes > 150_000)
        assertThrows(ModelVerificationException::class.java) {
            IdentityModelStore(root, CountingFetcher(a.bytes, stopAfter = 20_000)).install(e)
        }
        assertEquals(ModelState.Partial(20_000), IdentityModelStore(root, CountingFetcher(a.bytes)).state(e))
        val resume = CountingFetcher(a.bytes)
        assertTrue(IdentityModelStore(root, resume).install(e).readBytes().contentEquals(model))
        assertEquals(listOf(a.member.rangeStart + 20_000), resume.offsets)

        val root2 = Files.createTempDirectory("ids17-archive").toFile()
        try {
            val whole = IdentityModelStore(root2, CountingFetcher(a.bytes, honourRange = false)).install(e)
            assertTrue("whole archive sent: skipped to the member", whole.readBytes().contentEquals(model))
        } finally { root2.deleteRecursively() }
    }

    @Test fun archiveWithWrongRangeHashOrMemberHashIsDiscarded() {
        val a = SyntheticArchive(model)
        val badRange = archiveEntry(a).copy(archive = a.member.copy(rangeSha256 = "0".repeat(64)))
        assertThrows(ModelVerificationException::class.java) { IdentityModelStore(root, CountingFetcher(a.bytes)).install(badRange) }
        val badMember = archiveEntry(a).copy(sha256 = "1".repeat(64))
        assertThrows(ModelVerificationException::class.java) { IdentityModelStore(root, CountingFetcher(a.bytes)).install(badMember) }
        val wrongName = archiveEntry(a).copy(archive = a.member.copy(member = "det_10g.onnx"))
        assertThrows(ModelVerificationException::class.java) { IdentityModelStore(root, CountingFetcher(a.bytes)).install(wrongName) }
        assertEquals("nothing installed and no leftovers", 0, File(root, IdentityModelStore.DIR).listFiles()!!.size)
    }
}
