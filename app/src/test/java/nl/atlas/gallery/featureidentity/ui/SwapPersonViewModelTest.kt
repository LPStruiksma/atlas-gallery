package nl.atlas.gallery.featureidentity.ui

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.FakeIdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.FakeIdentityEngineConfig
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.TargetFace
import nl.atlas.gallery.featureidentity.target.TargetFaceSelection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** IDS-8 (#477): the Swap person flow end to end on FakeIdentityEditEngine, synthetic data only. */
class SwapPersonViewModelTest {
    private lateinit var root: File

    private fun face(left: Int, top: Int, size: Int = 32) = TargetFace(
        FaceBox(left, top, left + size, top + size),
        listOf(FacePoint(left + 10f, top + 12f), FacePoint(left + 22f, top + 12f), FacePoint(left + 16f, top + 18f),
            FacePoint(left + 11f, top + 25f), FacePoint(left + 21f, top + 25f))
    )

    private val source = EditImage("synthetic://source", 96, 64, ImageFormat.PNG,
        List(96 * 64) { (0xff shl 24) or ((it * 7) and 0xffffff) })
    private val faceA = face(8, 8)
    private val faceB = face(56, 16)

    private fun person(id: String, name: String?, photos: Int, recent: Int? = null, looks: List<SwapLook> = listOf(SwapLook("cur", "Current"))) =
        SwapPersonChoice(id.padEnd(32, '0'), name, photos, recent, looks, null)

    private inner class Host : SwapPersonHost {
        var people = listOf(
            person("a", "Ada", 40), person("b", "Bo", 90), person("c", "Cy", 10, recent = 0),
            person("d", null, 300),
            person("e", "Eli", 20, looks = listOf(SwapLook("cur", "Current"), SwapLook("gl", "Glasses")))
        )
        var selection: TargetFaceSelection = TargetFaceSelection.Auto(faceA)
        var setup: SwapSetupStatus = SwapSetupStatus.Ready
        var downloadOk = true
        var tooFew = emptySet<String>()
        val used = ArrayList<String>()
        val forgotten = ArrayList<String>()
        var matchResult: Int? = null
        val pickPhotos = listOf(
            SwapPickPhoto("p1", source, listOf(faceA.box)),
            SwapPickPhoto("p2", source, listOf(faceA.box, faceB.box)),
            SwapPickPhoto("p3", source, listOf(faceB.box)),
            SwapPickPhoto("p4", source, listOf(faceA.box))
        )
        override fun people() = people
        override fun references(profileId: String, lookId: String?) =
            if (profileId in tooFew) SwapReferences.TooFew(1)
            else SwapReferences.Ready(IdentityReferenceSet(profileId, lookId,
                List(3) { EditImage("synthetic://ref/$profileId/$lookId/$it", 8, 8, ImageFormat.PNG) }))
        override fun findFaces() = selection
        override fun usableFaces() = listOf(faceA, faceB)
        override fun setupStatus() = setup
        override fun download(onProgress: (Float) -> Unit): Boolean { onProgress(.5f); onProgress(1f); return downloadOk }
        override fun photosToPick() = pickPhotos
        override fun matchFace(photo: SwapPickPhoto, chosen: List<Pair<SwapPickPhoto, FaceBox>>) = matchResult
        override fun createProfile(faces: List<Pair<SwapPickPhoto, FaceBox>>, name: String?) =
            person("new", name ?: "", faces.size).also { people = people + it }
        override fun addReferences(profileId: String, faces: List<Pair<SwapPickPhoto, FaceBox>>) {}
        override fun referencePhotos(profileId: String) =
            List(4) { SwapReferencePhoto("r$it", SwapFace(source, faceA.box)) }
        override fun removeReference(profileId: String, referenceId: String) {}
        override fun forget(profileId: String) { forgotten += profileId }
        override fun markUsed(profileId: String) { used += profileId }
    }

    private class Saver : SwapPersonSaver {
        val saved = ArrayList<Triple<IdentityCandidate, String?, String?>>()
        var ok = true
        override fun save(candidate: IdentityCandidate, profileId: String, variantId: String?,
                          refinementParent: String?, done: (SwapSaveOutcome) -> Unit) {
            saved += Triple(candidate, variantId, refinementParent)
            done(if (ok) SwapSaveOutcome.Saved("content://synthetic/${saved.size}") else SwapSaveOutcome.Failed)
        }
    }

    private lateinit var host: Host
    private lateinit var saver: Saver

    private fun machine(
        config: FakeIdentityEngineConfig = FakeIdentityEngineConfig(),
        keepDir: File = File(root, "kept")
    ) = SwapPersonMachine(
        host = host, engine = FakeIdentityEditEngine(config), source = source,
        workspaceRoot = File(root, "work"), saver = saver, keeper = FolderVersionKeeper(keepDir),
        worker = { it.run() }, main = { it.run() }, sessionSeed = { 42L }
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("swap-person").toFile()
        host = Host(); saver = Saver()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun personSheetListsRecentsThenPhotoCountAndHidesUnnamed() {
        val m = machine(); m.start()
        assertEquals(listOf("Cy", "Bo", "Ada", "Eli"), m.state.listedPeople.map { it.name })
        assertFalse(m.state.showSearch)
        m.search("e")
        assertEquals(listOf("Eli"), m.state.listedPeople.map { it.name })
        host.people = List(13) { person("p$it", "Person $it", it) }
        m.start()
        assertTrue(m.state.showSearch)
    }

    @Test fun autoTargetMakesFourMixedVersionsAndPreselectsTheFirst() {
        val m = machine(); m.start()
        m.pickPerson(m.state.listedPeople.first { it.name == "Ada" })
        val s = m.state
        assertEquals(SwapStage.STUDIO, s.stage)
        assertEquals(4, s.ready.size)
        // Head-capable engine: face, hair, face, hair.
        assertEquals(listOf(false, true, false, true), s.versions.map { it.hair })
        assertEquals(s.versions.first().key, s.selected)
        assertEquals(listOf(null) + s.versions.map { it.key }, s.pages)
        assertEquals("Version 4 ready", s.announcement)
        assertEquals(listOf(host.people[0].profileId), host.used)
    }

    @Test fun faceOnlyEngineNeverMakesHairVersions() {
        val m = machine(FakeIdentityEngineConfig(supportedModes = setOf(IdentityEditMode.FACE))); m.start()
        m.pickPerson(host.people[0])
        assertFalse(m.state.headCapable)
        assertTrue(m.state.versions.none { it.hair })
        m.refine(SwapRefine.HAIR)
        assertEquals(4, m.state.versions.size)
    }

    @Test fun askTargetWaitsForTapAndSmallFaceAsksFirst() {
        host.selection = TargetFaceSelection.Ask(listOf(faceA, faceB))
        val m = machine(); m.start()
        m.pickPerson(host.people[0])
        assertEquals(SwapStage.TARGET, m.state.stage)
        assertEquals(2, m.state.faces.size)
        m.tapFace(faceB)
        assertEquals(faceB, m.state.target)
        assertEquals(4, m.state.ready.size)

        host.selection = TargetFaceSelection.TooSmall(faceA)
        val small = machine(keepDir = File(root, "kept2")); small.start()
        small.pickPerson(host.people[0])
        assertTrue(small.state.notice is SwapNotice.FaceSmall)
        small.tryAnyway()
        assertEquals(4, small.state.ready.size)

        host.selection = TargetFaceSelection.NoFace
        val none = machine(keepDir = File(root, "kept3")); none.start()
        none.pickPerson(host.people[0])
        assertEquals(SwapNotice.NoFace, none.state.notice)
    }

    @Test fun tooFewPhotosOffersPhotosUsed() {
        host.tooFew = setOf(host.people[0].profileId)
        val m = machine(); m.start()
        m.pickPerson(host.people[0])
        assertEquals(SwapNotice.TooFewPhotos("Ada"), m.state.notice)
        m.openPhotosUsed()
        assertEquals(SwapSheet.PHOTOS_USED, m.state.sheet)
        assertEquals(4, m.state.photosUsed?.size)
    }

    @Test fun setupOfferDownloadsThenContinues() {
        host.setup = SwapSetupStatus.Needed(120)
        val m = machine(); m.start()
        m.pickPerson(host.people[0])
        assertEquals(SwapStage.SETUP, m.state.stage)
        assertEquals(SwapSetupUi.Offer(120), m.state.setup)
        host.downloadOk = false
        m.download()
        assertEquals(SwapSetupUi.Failed(120), m.state.setup)
        host.downloadOk = true
        m.download()
        assertNull(m.state.setup)
        assertEquals(4, m.state.ready.size)
    }

    @Test fun swipeOrderSelectionCompareAndSplitRules() {
        val m = machine(); m.start(); m.pickPerson(host.people[0])
        val keys = m.state.versions.map { it.key }
        m.select(null)
        assertNull(m.state.selected)
        m.select(keys[2])
        assertEquals(keys[2], m.state.selected)
        m.openCompareAll()
        assertEquals(SwapOverlay.CompareAll, m.state.overlay)
        m.select(keys[1])
        assertEquals(SwapOverlay.None, m.state.overlay)
        m.split(keys[3])
        assertEquals(SwapOverlay.Split(keys[1], keys[3]), m.state.overlay)
        // A third pick replaces the side shown longest.
        m.split(keys[0])
        assertEquals(SwapOverlay.Split(keys[3], keys[0]), m.state.overlay)
        m.close()
        assertEquals(SwapOverlay.None, m.state.overlay)
        assertEquals(SwapStage.STUDIO, m.state.stage)
    }

    @Test fun moreRefineHairAndLookChipsAddVersions() {
        val m = machine(); m.start()
        m.pickPerson(host.people.first { it.name == "Eli" })
        val first = m.state.versions.first()
        m.more()
        assertEquals(6, m.state.ready.size)
        m.refine(SwapRefine.MORE_NATURAL)
        val natural = m.state.versions.takeLast(2)
        assertTrue(natural.all { it.request.preferences.moreNatural && it.request.refinedFrom == first.key })
        m.refine(SwapRefine.HAIR)
        assertTrue(m.state.versions.takeLast(2).all { it.hair })
        assertEquals(listOf("Glasses"), m.state.otherLooks.map { it.label })
        m.useLook(SwapLook("gl", "Glasses"))
        assertTrue(m.state.versions.takeLast(2).all { it.request.lookId == "gl" })
        assertEquals(12, m.state.ready.size)
        // All appended to batch 1: no gap.
        assertTrue(m.state.versions.all { it.batch == 1 })
    }

    @Test fun lookChipAfterChangePersonUsesTheNewPersonEvenWithAnOldVersionSelected() {
        val m = machine(); m.start(); m.pickPerson(host.people.first { it.name == "Ada" })
        val adaVersion = m.state.versions.first().key
        m.changePerson()
        val eli = host.people.first { it.name == "Eli" }
        m.pickPerson(eli)
        // Pick one of Ada's versions, still in the strip, then Eli's "Glasses" chip.
        m.select(adaVersion)
        assertEquals(listOf("Glasses"), m.state.otherLooks.map { it.label })
        m.useLook(SwapLook("gl", "Glasses"))
        val made = m.state.versions.takeLast(2)
        assertTrue(made.all { it.request.profileId == eli.profileId && it.request.lookId == "gl" })
        assertTrue(made.all { it.status == VersionStatus.READY })
    }

    @Test fun finishedVersionsAreCopiedOnTheWorkerNotTheMainThread() {
        val onWorker = ThreadLocal.withInitial { false }
        val keptOn = ArrayList<Boolean>()
        val keeper = object : SwapVersionKeeper {
            val folder = FolderVersionKeeper(File(root, "kept"))
            override fun keep(key: Int, file: File): File? { keptOn += onWorker.get(); return folder.keep(key, file) }
            override fun clear() = folder.clear()
        }
        val m = SwapPersonMachine(
            host = host, engine = FakeIdentityEditEngine(FakeIdentityEngineConfig()), source = source,
            workspaceRoot = File(root, "work"), saver = saver, keeper = keeper,
            worker = { r -> val was = onWorker.get(); onWorker.set(true); try { r.run() } finally { onWorker.set(was) } },
            main = { r -> val was = onWorker.get(); onWorker.set(false); try { r.run() } finally { onWorker.set(was) } },
            sessionSeed = { 42L }
        )
        m.start(); m.pickPerson(host.people[0])
        assertEquals(4, m.state.ready.size)
        assertEquals(List(4) { true }, keptOn)
        assertTrue(m.state.ready.all { it.image?.handle?.contains("kept") == true })
    }

    @Test fun changePersonAndFaceKeepVersionsAndAppendABatch() {
        val m = machine(); m.start(); m.pickPerson(host.people[0])
        val firstBatch = m.state.versions.map { it.key }
        m.changePerson()
        assertEquals(SwapStage.PERSON, m.state.stage)
        m.pickPerson(host.people[1])
        assertEquals(8, m.state.ready.size)
        assertEquals(firstBatch, m.state.versions.take(4).map { it.key })
        assertEquals(2, m.state.versions.last().batch)
        assertEquals(m.state.versions[4].key, m.state.selected)
        m.changeFace()
        assertEquals(SwapStage.TARGET, m.state.stage)
        m.tapFace(faceB)
        assertEquals(12, m.state.ready.size)
        assertEquals(3, m.state.versions.last().batch)
        assertEquals(m.state.versions[8].key, m.state.selected)
    }

    @Test fun closeRuleLeavesAtOnceWithoutVersionsAndAsksWithThem() {
        val empty = machine(); empty.start()
        empty.close()
        assertEquals(SwapStage.FINISHED, empty.state.stage)

        val m = machine(keepDir = File(root, "k2")); m.start(); m.pickPerson(host.people[0])
        m.close()
        assertTrue(m.state.discardDialog)
        m.keepEditing()
        assertFalse(m.state.discardDialog)
        assertEquals(SwapStage.STUDIO, m.state.stage)
        m.close(); m.discard()
        assertEquals(SwapStage.FINISHED, m.state.stage)
        assertFalse(File(root, "k2").exists())
    }

    @Test fun oneFailedTileRetriesAndAllFailedOffersTryAgain() {
        val m = machine(FakeIdentityEngineConfig(failOnCall = mapOf(1 to IdentityFailureKind.UNKNOWN))); m.start()
        m.pickPerson(host.people[0])
        assertEquals(3, m.state.ready.size)
        val failed = m.state.versions.single { it.status == VersionStatus.FAILED }
        assertNull(m.state.notice)
        m.retry(failed.key)
        assertEquals(4, m.state.ready.size)

        val all = machine(FakeIdentityEngineConfig(failOnCall = (0..3).associateWith { IdentityFailureKind.UNKNOWN }),
            File(root, "k2")); all.start()
        all.pickPerson(host.people[0])
        assertEquals(SwapNotice.AllFailed, all.state.notice)
        all.tryAgain()
        assertEquals(4, all.state.ready.size)
        assertNull(all.state.notice)
    }

    @Test fun outOfMemoryStopsTheBatchWithPlainNotice() {
        val m = machine(FakeIdentityEngineConfig(outOfMemoryOnCall = 1)); m.start()
        m.pickPerson(host.people[0])
        assertEquals(SwapNotice.OutOfMemory, m.state.notice)
        assertEquals(1, m.state.ready.size)
        assertEquals(0, m.state.pending)
    }

    @Test fun saveUsesSelectedVersionWithLookAndRefineParent() {
        val m = machine(); m.start(); m.pickPerson(host.people.first { it.name == "Eli" })
        m.refine(SwapRefine.STRONGER_LIKENESS)
        val refined = m.state.versions.last()
        m.select(refined.key)
        m.save()
        assertEquals(SwapSave.Saved("content://synthetic/1"), m.state.save)
        val (candidate, variant, parent) = saver.saved.single()
        assertEquals(refined.candidate!!.id, candidate.id)
        assertEquals("cur", variant)
        assertEquals(m.state.versions.first().candidate!!.id, parent)
        // Saved: ✕ leaves without asking.
        m.close()
        assertEquals(SwapStage.FINISHED, m.state.stage)

        saver.ok = false
        val f = machine(keepDir = File(root, "k2")); f.start(); f.pickPerson(host.people[0])
        f.save()
        assertEquals(SwapSave.Failed, f.state.save)
    }

    @Test fun choosePhotosAsksWhichFaceThenNamesAndContinues() {
        val m = machine(); m.start()
        m.choosePhotos()
        val photos = m.state.pick!!.photos
        // The first photo has nothing to match against: a multi-face photo always asks.
        m.togglePick(photos[1])
        assertEquals(SwapSheet.WHICH_FACE, m.state.sheet)
        m.answerWhichFace(faceB.box)
        assertEquals(SwapSheet.CHOOSE_PHOTOS, m.state.sheet)
        m.togglePick(photos[0])
        assertFalse(m.state.pick!!.canContinue)
        host.matchResult = 0
        m.togglePick(photos[3])
        assertTrue(m.state.pick!!.canContinue)
        m.pickNext()
        assertTrue(m.state.pick!!.naming)
        m.nameChanged("Synthetic Sam")
        m.pickDone()
        assertEquals(SwapSheet.NONE, m.state.sheet)
        assertEquals("Synthetic Sam", m.state.person?.name)
        assertEquals(4, m.state.ready.size)
    }

    @Test fun photosUsedRemoveAndForget() {
        val m = machine(); m.start()
        m.openPhotosUsed(host.people[0])
        m.removeReference("r1")
        assertEquals(listOf("r0", "r2", "r3"), m.state.photosUsed!!.map { it.id })
        m.askForget()
        assertTrue(m.state.forgetDialog)
        m.forget()
        assertEquals(listOf(host.people[0].profileId), host.forgotten)
        assertTrue(m.state.listedPeople.none { it.name == "Ada" })
    }

    @Test fun snapshotRestoresVersionsAndChosenVersionAfterProcessDeath() {
        val keep = File(root, "kept")
        val m = machine(keepDir = keep); m.start(); m.pickPerson(host.people[0])
        val chosen = m.state.versions[2]
        m.select(chosen.key)
        val snap = m.snapshot()
        // Process death: sessions are gone, the kept files stay.
        val restored = machine(keepDir = keep)
        restored.restore(snap)
        val s = restored.state
        assertEquals(SwapStage.STUDIO, s.stage)
        assertEquals(chosen.key, s.selected)
        assertEquals(4, s.ready.size)
        assertEquals(chosen.candidate!!.id, s.selectedVersion!!.candidate!!.id)
        assertEquals(chosen.candidate!!.variation, s.selectedVersion!!.candidate!!.variation)
        assertNotNull(s.person)
        // Still savable, and More works again after the restart.
        restored.save()
        assertEquals(chosen.candidate!!.id, saver.saved.single().first.id)
        restored.more()
        assertEquals(6, restored.state.ready.size)
    }
}
