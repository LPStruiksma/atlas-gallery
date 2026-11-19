package nl.atlas.gallery.featureidentity.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
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
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.hideKeyboard
import nl.atlas.gallery.testkit.typeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * IDS-8 (#477): every Swap person screen and state from design brief rev 2, on the CI emulator at
 * S24+ size, in light and dark at 100% and 200% font (canvas always black). Fully synthetic:
 * drawn cartoon faces, invented names and [FakeIdentityEditEngine]; no personal photo, model or
 * network. Renders are declared in scripts/ui-evidence.d/467-swap-person.txt.
 */
@RunWith(AndroidJUnit4::class)
class SwapPersonEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)

    // ---- synthetic pictures ----

    private val cache get() = compose.activity.cacheDir

    private fun pixels(bitmap: Bitmap): List<Int> =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }.toList()

    private fun save(bitmap: Bitmap, name: String): File = File(cache, name).also { f ->
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A flat cartoon face: skin oval, hair cap, two eyes and a smile. Clearly an illustration. */
    private fun Canvas.cartoon(box: RectF, skin: Int, hair: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = hair; drawOval(RectF(box.left - box.width() * .12f, box.top - box.height() * .18f,
            box.right + box.width() * .12f, box.top + box.height() * .55f), p)
        p.color = skin; drawOval(box, p)
        p.color = 0xFF1E2230.toInt()
        drawCircle(box.left + box.width() * .32f, box.top + box.height() * .42f, box.width() * .06f, p)
        drawCircle(box.left + box.width() * .68f, box.top + box.height() * .42f, box.width() * .06f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = box.width() * .05f
        drawArc(RectF(box.left + box.width() * .3f, box.top + box.height() * .5f,
            box.right - box.width() * .3f, box.top + box.height() * .78f), 20f, 140f, false, p)
    }

    private fun scene(w: Int, h: Int, faces: List<RectF>, tint: Int = 0): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        for (y in 0 until h) {
            val t = y / h.toFloat()
            p.color = if (t < .62f) android.graphics.Color.rgb(120 + (60 * t).toInt() + tint, 168 + tint / 2, 214)
            else android.graphics.Color.rgb(98 + tint, 140, 92)
            c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
        }
        val skins = listOf(0xFFE8B996.toInt(), 0xFFB57A55.toInt(), 0xFFF2D0B5.toInt())
        val hairs = listOf(0xFF3B2A20.toInt(), 0xFF101114.toInt(), 0xFFB8862E.toInt())
        faces.forEachIndexed { i, r ->
            p.color = 0xFF34507A.toInt()
            c.drawRoundRect(RectF(r.left - r.width() * .35f, r.bottom - r.height() * .05f,
                r.right + r.width() * .35f, h.toFloat() + 20f), r.width() * .4f, r.width() * .4f, p)
            c.cartoon(r, skins[(i + tint) % 3], hairs[(i + tint) % 3])
        }
        return b
    }

    private fun box(r: RectF) = FaceBox(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    private fun face(r: RectF) = TargetFace(box(r), listOf(
        FacePoint(r.left + r.width() * .32f, r.top + r.height() * .42f), FacePoint(r.left + r.width() * .68f, r.top + r.height() * .42f),
        FacePoint(r.centerX(), r.top + r.height() * .55f), FacePoint(r.left + r.width() * .38f, r.top + r.height() * .68f),
        FacePoint(r.left + r.width() * .62f, r.top + r.height() * .68f)))

    private val faceRectA = RectF(48f, 104f, 128f, 194f)
    private val faceRectB = RectF(170f, 124f, 230f, 192f)
    private val sourceImage by lazy {
        val b = scene(270, 360, listOf(faceRectA, faceRectB))
        val f = save(b, "ids8-source.png")
        EditImage(f.path, b.width, b.height, ImageFormat.PNG, pixels(b))
    }
    private val faceA by lazy { face(faceRectA) }
    private val faceB by lazy { face(faceRectB) }

    private fun avatar(i: Int): SwapFace {
        val r = RectF(16f, 20f, 48f, 56f)
        val b = scene(64, 64, listOf(r), tint = (i * 23) % 60)
        val f = save(b, "ids8-avatar-$i.png")
        return SwapFace(EditImage(f.path, 64, 64, ImageFormat.PNG), box(r))
    }

    private val pickPhotos by lazy {
        List(9) { i ->
            val faces = if (i == 1 || i == 5) listOf(RectF(14f, 40f, 54f, 86f), RectF(70f, 46f, 104f, 86f)) else listOf(RectF(36f, 36f, 84f, 92f))
            val b = scene(120, 160, faces, tint = (i * 17) % 60)
            val f = save(b, "ids8-pick-$i.png")
            SwapPickPhoto("pick$i", EditImage(f.path, 120, 160, ImageFormat.PNG), faces.map(::box))
        }
    }

    // ---- synthetic host and saver ----

    private val names = listOf("Ada", "Bo", "Cyril", "Dana", "Eli", "Femke", "Gijs", "Hanna", "Ilse", "Joris",
        "Kim", "Lotte", "Maarten Alexander", "Noor")

    private inner class Host(
        val count: Int = 9,
        val selection: TargetFaceSelection = TargetFaceSelection.Auto(faceA),
        val setup: SwapSetupStatus = SwapSetupStatus.Ready,
        val tooFew: Set<String> = emptySet(),
        val downloadGate: CountDownLatch? = null,
        val downloadOk: Boolean = true
    ) : SwapPersonHost {
        var people: List<SwapPersonChoice> = List(count) { i ->
            val looks = if (i == 4) listOf(SwapLook("cur", "Current"), SwapLook("gl", "Glasses"))
            else listOf(SwapLook("cur", "Current"))
            SwapPersonChoice("%032x".format(i + 1), names[i], 200 - i * 9, if (i == 2) 0 else if (i == 4) 1 else null, looks, avatar(i))
        }
        override fun people() = people
        override fun references(profileId: String, lookId: String?) =
            if (people.firstOrNull { it.profileId == profileId }?.name in tooFew) SwapReferences.TooFew(2)
            else SwapReferences.Ready(IdentityReferenceSet(profileId, lookId,
                List(4) { EditImage("synthetic://ids8/$profileId/$lookId/$it", 64, 64, ImageFormat.PNG) }))
        override fun findFaces() = selection
        override fun usableFaces() = listOf(faceA, faceB)
        override fun setupStatus() = setup
        override fun download(onProgress: (Float) -> Unit): Boolean {
            onProgress(.45f)
            downloadGate?.await(30, TimeUnit.SECONDS)
            return downloadOk
        }
        override fun photosToPick() = pickPhotos
        override fun matchFace(photo: SwapPickPhoto, chosen: List<Pair<SwapPickPhoto, FaceBox>>): Int? = null
        override fun createProfile(faces: List<Pair<SwapPickPhoto, FaceBox>>, name: String?) =
            SwapPersonChoice("%032x".format(99), name, faces.size, 0, listOf(SwapLook("cur", "Current")), avatar(99))
        override fun addReferences(profileId: String, faces: List<Pair<SwapPickPhoto, FaceBox>>) {}
        override fun referencePhotos(profileId: String) = List(5) { SwapReferencePhoto("ref$it", avatar(it + 20)) }
        override fun removeReference(profileId: String, referenceId: String) {}
        override fun forget(profileId: String) {}
        override fun markUsed(profileId: String) {}
    }

    private class Saver(val outcome: SwapSaveOutcome?) : SwapPersonSaver {
        override fun save(candidate: IdentityCandidate, profileId: String, variantId: String?,
                          refinementParent: String?, done: (SwapSaveOutcome) -> Unit) { outcome?.let(done) }
    }

    /** One flow: its machine, its live state and a hand-driven worker queue. */
    private inner class Harness(
        val host: Host = Host(),
        config: FakeIdentityEngineConfig = FakeIdentityEngineConfig(),
        saver: SwapPersonSaver = Saver(SwapSaveOutcome.Saved("content://synthetic/ids8")),
        threaded: Boolean = false
    ) {
        var state by mutableStateOf(SwapPersonUiState())
        val queue = ArrayDeque<Runnable>()
        private val handler = Handler(Looper.getMainLooper())
        private val pool = if (threaded) Executors.newSingleThreadExecutor() else null
        val machine = SwapPersonMachine(
            host = host, engine = FakeIdentityEditEngine(config), source = sourceImage,
            workspaceRoot = File(cache, "ids8-work"), saver = saver,
            keeper = FolderVersionKeeper(File(cache, "ids8-kept/${System.nanoTime()}")),
            worker = { r -> if (pool != null) pool.execute(r) else queue.addLast(r) },
            main = { r -> if (pool != null) handler.post(r) else r.run() },
            onState = { state = it }, sessionSeed = { 7L }
        )
        fun drain(steps: Int = Int.MAX_VALUE, until: (SwapPersonUiState) -> Boolean = { false }) {
            var n = 0
            while (n < steps && queue.isNotEmpty() && !until(state)) { queue.removeFirst().run(); n++ }
        }
        fun release() { machine.release(); pool?.shutdownNow() }
    }

    private var current by mutableStateOf<Harness?>(null)
    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)

    private fun show(h: Harness, drive: Harness.() -> Unit = {}) {
        compose.runOnIdle {
            current?.release()
            current = h
            h.drive()
        }
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }

    private fun shot(name: String) {
        compose.waitForIdle()
        saveUiEvidence("ids8-$name-${if (dark) "dark" else "light"}-${(fontScale * 100).toInt()}.png")
    }

    private fun Harness.studio(person: Int = 4) {
        machine.start(); drain()
        machine.pickPerson(state.listedPeople.first { it.name == names[person] }); drain()
    }

    private fun renderAll() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    val h = current
                    if (h != null) key(h) { SwapPersonFlow(h.state, sourceImage, h.machine, onFinished = {}, onView = {}) }
                }
            }
        }

        // S1 person sheet.
        show(Harness()) { machine.start() }
        compose.onAllNodesWithTag("swap-person-skeleton").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        shot("s1-loading")
        act { current!!.drain() }
        compose.onNodeWithTag("swap-looks-badge", useUnmergedTree = true).assertExists()
        shot("s1-people")
        show(Harness(Host(count = 14))) { machine.start(); drain() }
        compose.onNodeWithTag("swap-person-search").performClick()
        compose.typeText(hasTestTag("swap-person-search"), "a", KeyboardAfter.Shown)
        shot("s1-search-keyboard")
        compose.hideKeyboard()
        show(Harness(Host(count = 0))) { machine.start(); drain() }
        compose.onNodeWithTag("swap-no-people").assertExists()
        shot("s1-no-people")
        show(Harness(Host(tooFew = setOf("Ada")))) {
            machine.start(); drain(); machine.pickPerson(state.listedPeople.first { it.name == "Ada" }); drain()
        }
        compose.onNodeWithTag("swap-too-few").assertExists()
        shot("s1-too-few")

        // S6 one-time setup.
        show(Harness(Host(setup = SwapSetupStatus.Needed(120)))) {
            machine.start(); drain(); machine.pickPerson(state.listedPeople.first()); drain()
        }
        compose.onNodeWithTag("swap-setup-download").assertExists()
        shot("s6-setup-offer")
        val gate = CountDownLatch(1)
        show(Harness(Host(setup = SwapSetupStatus.Needed(120), downloadGate = gate), threaded = true)) {
            machine.start()
        }
        compose.waitUntil(10_000) { current!!.state.people.isNotEmpty() }
        act { current!!.machine.pickPerson(current!!.state.listedPeople.first()) }
        compose.waitUntil(10_000) { current!!.state.setup is SwapSetupUi.Offer }
        act { current!!.machine.download() }
        compose.waitUntil(10_000) { (current!!.state.setup as? SwapSetupUi.Running)?.progress == .45f }
        shot("s6-setup-running")
        gate.countDown()
        show(Harness(Host(setup = SwapSetupStatus.Needed(120), downloadOk = false))) {
            machine.start(); drain(); machine.pickPerson(state.listedPeople.first()); drain(); machine.download(); drain()
        }
        compose.onNodeWithTag("swap-setup-failed").assertExists()
        shot("s6-setup-failed")

        // S2 target.
        show(Harness(Host(selection = TargetFaceSelection.Ask(listOf(faceA, faceB))))) { studio() }
        compose.onNodeWithTag("swap-target-chip").assertExists()
        shot("s2-target")
        show(Harness(Host(selection = TargetFaceSelection.NoFace))) { studio() }
        compose.onNodeWithTag("swap-no-face").assertExists()
        shot("s2-no-face")
        show(Harness(Host(selection = TargetFaceSelection.TooSmall(faceB)))) { studio() }
        compose.onNodeWithTag("swap-face-small").assertExists()
        shot("s2-face-small")

        // S3 generating, non-blocking.
        show(Harness()) {
            machine.start(); drain(); machine.pickPerson(state.listedPeople.first { it.name == "Eli" })
            drain(until = { it.stage == SwapStage.STUDIO })
        }
        assertEquals(0, current!!.state.ready.size)
        compose.onNodeWithTag("swap-making").assertExists()
        shot("s3-generating-0")
        act { current!!.drain(2) }
        assertEquals(2, current!!.state.ready.size)
        shot("s3-generating-2")

        // S4 studio: mixed first batch (face, hair, face, hair), look chip "Glasses".
        show(Harness()) { studio() }
        assertEquals(listOf(false, true, false, true), current!!.state.versions.map { it.hair })
        assertEquals(listOf("Glasses"), current!!.state.otherLooks.map { it.label })
        shot("s4-studio")
        // The look chip sits after the refine chips; scroll the row to it.
        compose.onNodeWithTag("swap-refine-row").performScrollToNode(hasTestTag("swap-chip-Glasses"))
        compose.onNodeWithTag("swap-chip-Glasses").assertExists()
        shot("s4-look-chip")
        compose.onNodeWithTag("swap-original-pill").performTouchInput { down(center) }
        compose.waitForIdle()
        shot("s4-hold-original")
        compose.onNodeWithTag("swap-original-pill").performTouchInput { up() }
        compose.onNodeWithTag("swap-menu").performClick()
        compose.onNodeWithTag("swap-change-person").assertExists()
        compose.onNodeWithTag("swap-change-face").assertExists()
        shot("s4-menu")
        compose.onNodeWithTag("swap-change-person").performClick()
        compose.waitForIdle()
        act { current!!.machine.close() }
        act { current!!.machine.refine(SwapRefine.MORE_NATURAL) }
        compose.onNodeWithTag("swap-refine-running", useUnmergedTree = true).assertExists()
        shot("s4-refine-running")
        act { current!!.machine.openCompareAll() }
        act { current!!.drain() }
        compose.onNodeWithTag("swap-compare-all").assertExists()
        shot("s4-compare-all")
        act { current!!.machine.closeOverlay(); current!!.machine.split(current!!.state.versions[1].key) }
        compose.onNodeWithTag("swap-split").assertExists()
        shot("s4-split")
        act { current!!.machine.closeOverlay(); current!!.machine.close() }
        compose.onNodeWithTag("swap-discard-dialog").assertExists()
        shot("s4-discard")
        act { current!!.machine.keepEditing() }

        show(Harness(config = FakeIdentityEngineConfig(failOnCall = mapOf(2 to IdentityFailureKind.UNKNOWN)))) { studio() }
        assertEquals(1, current!!.state.versions.count { it.status == VersionStatus.FAILED })
        shot("s4-tile-failed")
        show(Harness(config = FakeIdentityEngineConfig(failOnCall = (0..3).associateWith { IdentityFailureKind.UNKNOWN }))) { studio() }
        compose.onNodeWithTag("swap-all-failed").assertExists()
        shot("s4-all-failed")
        show(Harness(config = FakeIdentityEngineConfig(outOfMemoryOnCall = 1))) { studio() }
        compose.onNodeWithTag("swap-out-of-memory").assertExists()
        shot("s4-out-of-memory")
        // Change person with versions present: the new batch is appended after a gap.
        show(Harness()) {
            studio(); machine.changePerson(); machine.pickPerson(state.listedPeople.first { it.name == "Bo" }); drain()
        }
        assertEquals(2, current!!.state.versions.last().batch)
        shot("s4-appended-batch")
        show(Harness(config = FakeIdentityEngineConfig(supportedModes = setOf(IdentityEditMode.FACE)))) { studio(0) }
        assertTrue(current!!.state.versions.none { it.hair })
        shot("s4-face-only")

        // S5 save.
        show(Harness(saver = Saver(null))) { studio(); machine.save() }
        shot("s5-saving")
        show(Harness()) { studio(); machine.save() }
        compose.onNodeWithTag("swap-saved").assertExists()
        shot("s5-saved")
        show(Harness(saver = Saver(SwapSaveOutcome.Failed))) { studio(); machine.save() }
        compose.onNodeWithTag("swap-save-failed").assertExists()
        shot("s5-save-failed")

        // S7 photos used.
        show(Harness()) { studio(); machine.openPhotosUsed(); drain() }
        compose.onNodeWithTag("swap-forget").assertExists()
        shot("s7-photos-used")
        act { current!!.machine.askForget() }
        compose.onNodeWithTag("swap-forget-dialog").assertExists()
        shot("s7-forget")

        // S8 choose photos.
        show(Harness()) {
            machine.start(); drain(); machine.choosePhotos(); drain()
            listOf(0, 2).forEach { machine.togglePick(pickPhotos[it]) }
        }
        shot("s8-choose")
        act { current!!.machine.togglePick(pickPhotos[1]); current!!.drain() }
        compose.onNodeWithTag("swap-which-face").assertExists()
        shot("s8-which-face")
        act { current!!.machine.answerWhichFace(pickPhotos[1].faces[0]); current!!.machine.pickNext() }
        compose.onNodeWithTag("swap-name-field").performClick()
        compose.typeText(hasTestTag("swap-name-field"), "Sam", KeyboardAfter.Shown)
        shot("s8-name-keyboard")
        compose.hideKeyboard()
        compose.runOnIdle { current?.release(); current = null }
    }

    @Test fun light100() { dark = false; fontScale = 1f; renderAll() }
    @Test fun dark100() { dark = true; fontScale = 1f; renderAll() }
    @Test fun light200() { dark = false; fontScale = 2f; renderAll() }
    @Test fun dark200() { dark = true; fontScale = 2f; renderAll() }
}
