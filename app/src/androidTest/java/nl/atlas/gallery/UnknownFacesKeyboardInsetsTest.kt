package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.testkit.ConfigurationRule
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.assertAboveKeyboard
import nl.atlas.gallery.testkit.awaitKeyboardShown
import nl.atlas.gallery.testkit.hideKeyboard
import nl.atlas.gallery.testkit.keyboardInsetPx
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #448: the Unknown faces group sheet with the keyboard open, in the REAL
 * MainActivity (its scaffold, tab pill and insets) at the S24+ size.
 *
 * On main the list applied the full keyboard inset on top of the scaffold's
 * bottom padding (tab pill, margins, navigation bar), so 350 px of nothing
 * sat between the list and the keyboard and the name form had 440 px. Here
 * the list must end at the keyboard's top edge (±2 px) and "Who is this?",
 * the name field and Create (≥16 dp clear of the keyboard) must show at
 * once, with at least 4 faces of the sticky header.
 *
 * The keyboard is the test kit's fixed panel (918 px in portrait, the same
 * share of the height in landscape), so the inset is the same on every run.
 * Fixture: six invented [SyntheticFace] drawings of one identity in
 * MediaStore (deleted afterwards), scanned in the test as in
 * [PeoplePrototypeJourneyTest]. No device photo is read.
 *
 * Renders, light and dark (scripts/ui-evidence.d/448-unknown-faces-keyboard.txt):
 * (a) closed, (b) opening (animations slowed 10x for that frame), (c) open
 * with an empty field, (d) open with typed text, (e) landscape open,
 * (f) font scale 1.3 open. Logcat tag ATLAS_IME_448 has one line per state
 * with the list bottom, keyboard top and Create/field/header bounds.
 */
@RunWith(AndroidJUnit4::class)
class UnknownFacesKeyboardInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "unknown-ime-448-" + UUID.randomUUID())
    private val inserted = mutableListOf<Uri>()
    private val specs = HashMap<Long, SyntheticFace.Spec>()
    private val stores = mutableListOf<FaceIndexStore>()
    private val optIn = object : PeopleOptInMemory {
        @Volatile var value: PeopleOptIn? = null
        override fun load() = value
        override fun save(value: PeopleOptIn?) { this.value = value }
    }
    private lateinit var store: FaceIndexStore

    private val setup = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
                clearGalleryInventoryCacheForTest(target)
                PeopleScanWorkRuntime.clear()
                try {
                    insertFaces()
                    installHost()
                    base.evaluate()
                } finally {
                    PeopleDiscoveryHost.replaceAppForTest(null)
                    PeopleScanWorkRuntime.clear()
                    stores.forEach { runCatching { it.close() } }
                    dir.deleteRecursively()
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }
    }

    /** Night mode, font scale, rotation and animator scale go back even when a step fails. */
    private val device = ConfigurationRule()

    private val compose = createAndroidComposeRule<MainActivity>()

    /** A failed run must not leave the keyboard up for the next test (#411). */
    private val closeKeyboard = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    runCatching { compose.hideKeyboard() }
                }
            }
        }
    }

    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(device).around(setup).around(compose)
        .around(closeKeyboard)

    @Test fun keyboardOpenListEndsAtKeyboardTopAndNameFormStaysVisible() {
        openPeople()
        startDiscovery()
        drainScan()
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            if (dark) reconfigure { device.night(true) }
            openReview()

            // (a) Keyboard closed: the layout main already had.
            compose.hideKeyboard()
            steady()
            log("$theme a-closed")
            assertTrue("the tab pill shows while the keyboard is closed",
                compose.onAllNodes(hasTestTag("atlas-nav-pill"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty())
            saveUiEvidence("unknown-ime-448-a-closed-$theme.png")

            // (b) Opening: slow the window animations so one frame is mid-way.
            captureOpening(theme)

            // (c) Keyboard open, empty field.
            assertKeyboardContract("$theme c-open-empty", portrait = true)
            saveUiEvidence("unknown-ime-448-c-open-empty-$theme.png")

            // (d) Keyboard open with typed text. With one identity and no named
            // person there is no duplicate-name block; the form keeps Create.
            compose.onNodeWithTag("atlas-group-name").performTextInput(TYPED_NAME)
            compose.waitForIdle()
            assertKeyboardContract("$theme d-open-typed", portrait = true)
            saveUiEvidence("unknown-ime-448-d-open-typed-$theme.png")
            compose.onNodeWithTag("atlas-group-name").performTextClearance()
            compose.hideKeyboard()

            // (e) Landscape, keyboard open: the header shrinks to one row first,
            // and the field and Create stay reachable above the keyboard.
            rotate(landscape = true)
            openReview()
            focusNameField()
            compose.awaitKeyboardShown()
            steady()
            assertKeyboardContract("$theme e-landscape", portrait = false)
            saveUiEvidence("unknown-ime-448-e-landscape-$theme.png")
            compose.hideKeyboard()
            rotate(landscape = false)

            // (f) Font scale 1.3, keyboard open.
            reconfigure { device.fontScale(1.3f) }
            openReview()
            focusNameField()
            compose.awaitKeyboardShown()
            steady()
            assertKeyboardContract("$theme f-font-1.3", portrait = true)
            saveUiEvidence("unknown-ime-448-f-font130-$theme.png")
            compose.hideKeyboard()
            reconfigure { device.fontScale(1.0f) }
        }
    }

    /**
     * The #448 contract with the keyboard up: list bottom = keyboard top ±2 px,
     * the field and Create fully above the keyboard (Create by ≥16 dp, 12 dp
     * in landscape where the compact header takes the rest), and in portrait
     * "Who is this?" and at least four header faces on screen as well.
     */
    private fun assertKeyboardContract(label: String, portrait: Boolean) {
        val keyboard = compose.keyboardInsetPx()
        assertTrue("$label: the keyboard is up", keyboard > 0)
        val windowHeight = compose.runOnUiThread { compose.activity.window.decorView.rootView.height }
        val keyboardTop = windowHeight - keyboard
        val list = unclipped("atlas-unknown-face-review-screen")
        log(label)
        assertTrue("$label: list bottom ${list.bottom} must equal the keyboard top $keyboardTop " +
            "(±2 px); a gap is the #448 dead band", abs(list.bottom - keyboardTop) <= 2f)
        val density = compose.activity.resources.displayMetrics.density
        val clearance = ((if (portrait) 16 else 12) * density).toInt()
        compose.assertAboveKeyboard(hasTestTag("atlas-group-name"))
        compose.assertAboveKeyboard(hasTestTag("atlas-group-create-person"), clearance)
        val header = headerBounds()
        val field = unclipped("atlas-group-name")
        assertTrue("$label: the name field ($field) must not sit under the sticky header ($header)",
            field.top >= header.bottom - 1f)
        if (portrait) {
            val title = compose.onAllNodesWithText("Who is this?").onFirst().fetchSemanticsNode()
            val titleTop = title.positionInWindow.y
            assertTrue("$label: \"Who is this?\" (top $titleTop) must show below the header " +
                "(${header.bottom})", titleTop >= header.bottom - 1f)
        }
        val faces = (1..6).count { index ->
            compose.onAllNodes(hasTestTag("atlas-group-face-$index"))
                .fetchSemanticsNodes().any { it.boundsInWindow.height > 0f }
        }
        assertTrue("$label: at least 4 faces of the group stay visible ($faces)", faces >= 4)
    }

    /** Taps the field; with animations 10x slower, saves a frame while the keyboard is mid-way. */
    private fun captureOpening(theme: String) {
        device.animatorScale(10f)
        try {
            val full = 918
            focusNameField()
            var seen = 0
            // Best effort: if no mid-way frame is sampled the capture is the
            // nearest one, and the log line says which inset it started at.
            runCatching {
                compose.waitUntil(15_000L) {
                    seen = compose.keyboardInsetPx()
                    seen in (full / 4)..(full * 3 / 4)
                }
            }
            Log.i(LOG_TAG, "$theme b-opening inset=$seen of $full px at capture start")
            saveUiEvidence("unknown-ime-448-b-opening-$theme.png")
        } finally {
            device.animatorScale(1f)
        }
        compose.awaitKeyboardShown()
        steady()
    }

    private fun focusNameField() {
        compose.onNodeWithTag("atlas-group-name").performScrollTo()
        compose.tap(hasTestTag("atlas-group-name"))
    }

    /**
     * People → the Unknown group's tile → the group sheet with its name field.
     * The sheet is saved state, so after a rotation or configuration change it
     * is usually still open: then only wait for it.
     */
    private fun openReview() {
        compose.waitForIdle()
        if (compose.exists(hasTestTag("atlas-group-name"), useUnmergedTree = true)) {
            steady()
            return
        }
        openPeople()
        awaitTag("atlas-people-unnamed")
        compose.tap(hasTestTag("atlas-people-unnamed")) {
            compose.exists(hasTestTag("atlas-group-name"), useUnmergedTree = true)
        }
        steady()
    }

    private fun headerBounds(): Rect {
        for (tag in listOf("atlas-unknown-group-header", "atlas-unknown-group-header-compact")) {
            val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) return nodes.first().boundsInWindow
        }
        throw AssertionError("no sticky group header on screen")
    }

    private fun unclipped(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode("no $tag")
        return Rect(node.positionInWindow, node.size.toSize())
    }

    private fun log(label: String) {
        val parts = listOf("atlas-unknown-face-review-screen", "atlas-group-name",
            "atlas-group-create-person", "atlas-group-face-1").joinToString(" | ") { tag ->
            "$tag=${runCatching { unclipped(tag) }.getOrNull()}"
        }
        val header = runCatching { headerBounds() }.getOrNull()
        // keyboardInsetPx() synchronises with Compose, so never call it on the UI thread.
        val keyboard = compose.keyboardInsetPx()
        val height = compose.runOnUiThread { compose.activity.window.decorView.rootView.height }
        Log.i(LOG_TAG, "$label window=$height keyboard=$keyboard keyboardTop=${height - keyboard} " +
            "header=$header | $parts")
    }

    /** Applies a configuration change and waits for the recreated Activity's window. */
    private fun reconfigure(change: () -> Unit) {
        val before = activityIdentity()
        change()
        compose.awaitThat("the Activity to be recreated", 30_000L) {
            compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                activityIdentity() != before
        }
        compose.awaitWindowSettled()
    }

    private fun rotate(landscape: Boolean) {
        reconfigure { device.rotate(landscape) }
        compose.awaitThat("the window to be ${if (landscape) "landscape" else "portrait"}", 30_000L) {
            val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
            root != null && (root.width > root.height) == landscape
        }
    }

    private fun activityIdentity(): Int {
        var identity = 0
        compose.activityRule.scenario.onActivity { identity = System.identityHashCode(it) }
        return identity
    }

    private fun insertFaces() {
        val now = System.currentTimeMillis()
        for (i in 1..PHOTOS) {
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_UNKNOWN_IME_448_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$FOLDER/")
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "Could not create synthetic face fixture $i" }
            inserted += uri
            val bitmap = SyntheticFace.bitmap(spec)
            try {
                requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            target.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
            }, null, null)
            specs[ContentUris.parseId(uri)] = spec
        }
    }

    private fun installHost() {
        val file = File(dir.apply { mkdirs() }, "faces.db")
        val context: Context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(
                file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        store = FaceIndexStore(context).also { stores += it }
        val host = PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { true },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    val id = Uri.parse(directory.authorizedUri(lease.media)).lastPathSegment
                        ?.toLongOrNull()
                    val spec = id?.let { specs[it] } ?: throw FaceSourceSkipped("NOT_A_FIXTURE")
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(spec))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            faceCropDecoder = MediaStoreFaceCropDecoder(target.contentResolver),
            optInMemory = optIn
        )
        PeopleDiscoveryHost.replaceAppForTest(host)
    }

    /** "Discover faces", retried while People recomposes (as in [PeoplePrototypeJourneyTest]). */
    private fun startDiscovery() {
        awaitTag("atlas-people-home")
        repeat(3) { attempt ->
            if (attempt > 0 && scanShown()) return
            clickStart()
            if (runCatching { compose.awaitThat("the scan to start") { scanShown() } }.isSuccess) {
                return
            }
        }
    }

    private fun clickStart() {
        val emptyButton = compose.onAllNodesWithText("Discover faces", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (compose.onAllNodes(hasTestTag("atlas-people-empty"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() && emptyButton.isNotEmpty()
        ) {
            compose.onAllNodesWithText("Discover faces").onFirst().performClick()
            return
        }
        compose.onNodeWithTag("atlas-header-more").performClick()
        compose.waitForIdle()
        if (compose.onAllNodes(hasTestTag("atlas-people-start"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithTag("atlas-people-start").performClick()
        } else {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun scanShown(): Boolean {
        if (compose.onAllNodes(hasTestTag("atlas-people-progress-line"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()) return true
        val empty = compose.onAllNodes(hasTestTag("atlas-people-empty"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        return empty && compose.onAllNodesWithText("Discover faces", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty()
    }

    private fun openPeople() {
        awaitTag("atlas-nav-people")
        compose.onNodeWithTag("atlas-nav-people").performClick()
        awaitTag("atlas-people-home")
    }

    /** Scan batches until the live epoch has nothing pending (see [PeoplePrototypeJourneyTest]). */
    private fun drainScan(): String {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var batches = 0
        var moves = 0
        while (true) {
            val before = liveScanEpoch()
            val result = before?.let { runBlocking { runPeopleScanBatch(target) { false } } }
            if (result == null) {
                // The host is still publishing the gallery to its new epoch.
                check(SystemClock.elapsedRealtime() < deadline) {
                    "no scan session within 60 s (epoch moved $moves times)"
                }
                compose.awaitThat("a scan session", 60_000L) { liveScanEpoch() != null }
                continue
            }
            if (result.stop == FaceBatchStop.NO_PENDING) {
                val after = liveScanEpoch()
                if (after == before) {
                    compose.waitForIdle()
                    return after
                }
                check(++moves <= 5) { "the gallery kept republishing: epoch moved $moves times" }
                continue
            }
            check(++batches <= 600) { "scan did not finish: ${result.stop}" }
        }
    }

    private fun liveScanEpoch(): String? = PeopleScanWorkRuntime.acquire(target)?.use { it.epoch }

    private fun awaitTag(tag: String) =
        compose.awaitThat("tag $tag", 60_000L) { compose.exists(hasTestTag(tag), useUnmergedTree = true) }

    /** Window and Compose idle before a measurement or screenshot. */
    private fun steady() {
        compose.awaitWindowSettled()
        compose.waitForIdle()
    }

    private companion object {
        const val PHOTOS = 6
        const val TYPED_NAME = "Synthetic Morgan"
        const val FOLDER = "ATLAS-Unknown-IME-448"
        const val LOG_TAG = "ATLAS_IME_448"
    }
}
