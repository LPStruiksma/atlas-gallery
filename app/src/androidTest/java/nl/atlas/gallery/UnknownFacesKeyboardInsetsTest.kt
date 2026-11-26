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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import nl.atlas.gallery.featureface.FaceMediaSourceKey
import nl.atlas.gallery.featureface.FaceSourceSkipped
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
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
    private lateinit var host: PeopleDiscoveryHost

    private val setup = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
                clearGalleryInventoryCacheForTest(target)
                PeopleScanWorkRuntime.clear()
                try {
                    insertFaces(if (description.methodName == "eligibleSuggestionAndDuplicateNameStayAboveKeyboard") PHOTOS + 2 else PHOTOS)
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
     * Same real Activity, two confirmed invented identities and six unknown
     * faces. Name each singleton BEFORE publishing the other faces: splitting
     * a group would add a cannot-link veto and hide the suggestion.
     * One confirmed example stays below the automatic-match minimum.
     */
    @Test fun eligibleSuggestionAndDuplicateNameStayAboveKeyboard() {
        openPeople()
        startDiscovery()
        compose.awaitThat("fixture scan session", 60_000L) { liveScanEpoch() != null }
        val epoch = requireNotNull(liveScanEpoch())
        val person = seedSuggestedPerson(epoch)
        check(drainScan() == epoch) { "fixture epoch changed" }
        val state = requireNotNull(host.reviewState(epoch))
        val group = state.clusters.unknownClusters.single()
        check(group.members.size == PHOTOS && group.suggestion?.person == person) {
            "fixture must expose six unknown faces and an eligible named suggestion"
        }
        check(state.names[person] == SUGGESTED_NAME)
        check(state.names.values.count { it == SUGGESTED_NAME } == 2) { "fixture must contain two named matches" }

        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            if (dark) reconfigure { device.night(true) }
            openReview()
            focusNameField()
            compose.awaitKeyboardShown()
            steady()
            compose.onNodeWithTag("atlas-group-name").assertIsFocused()
            assertKeyboardContract("$theme g-open-suggestion", portrait = true)
            assertClearOfHeaderAndKeyboard(hasText("Looks like someone you know?"))
            assertClearOfHeaderAndKeyboard(hasText("Add") and hasClickAction())
            assertClearOfHeaderAndKeyboard(hasText("Not $SUGGESTED_NAME") and hasClickAction())
            assertMinimumTarget(hasText("Add") and hasClickAction())
            assertMinimumTarget(hasText("Not $SUGGESTED_NAME") and hasClickAction())
            saveUiEvidence("unknown-ime-448-g-open-suggestion-$theme.png")

            compose.onNodeWithTag("atlas-group-name").performTextInput(SUGGESTED_NAME)
            val createAnother = hasText("Create another $SUGGESTED_NAME") and hasClickAction()
            compose.awaitThat("duplicate name actions") { compose.exists(createAnother) }
            compose.awaitKeyboardShown()
            steady()
            compose.onNodeWithTag("atlas-group-name").assertIsFocused()
            assertKeyboardContract("$theme h-open-duplicate", portrait = true, createMatcher = createAnother)
            check(compose.onAllNodes(hasText("Add") and hasClickAction()).fetchSemanticsNodes().size == 2)
            for (index in 1..2) {
                val add = hasTestTag("atlas-group-duplicate-add-$index") and hasClickAction()
                assertClearOfHeaderAndKeyboard(add)
                assertMinimumTarget(add)
            }
            assertClearOfHeaderAndKeyboard(createAnother)
            saveUiEvidence("unknown-ime-448-h-open-duplicate-$theme.png")

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.awaitThat("Back dismisses keyboard, not group") {
                compose.keyboardInsetPx() == 0 &&
                    compose.exists(hasTestTag("atlas-unknown-face-review-screen"))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.awaitThat("second Back closes group") {
                !compose.exists(hasTestTag("atlas-unknown-face-review-screen"))
            }
        }
    }

    private fun assertClearOfHeaderAndKeyboard(matcher: SemanticsMatcher) {
        compose.assertAboveKeyboard(matcher)
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val rect = Rect(node.positionInWindow, node.size.toSize())
        val header = headerBounds()
        assertTrue("${matcher.description} overlaps sticky header: $rect vs $header",
            rect.top >= header.bottom - 1f)
        Log.i(LOG_TAG, "${matcher.description} bounds=$rect header=$header")
    }

    private fun assertMinimumTarget(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val minPx = 48 * compose.activity.resources.displayMetrics.density
        assertTrue("${matcher.description}: target ${node.size} is smaller than 48dp ($minPx px)",
            node.size.width >= minPx - 1f && node.size.height >= minPx - 1f)
        Log.i(LOG_TAG, "${matcher.description} target=${node.size}, minimum=$minPx px")
    }

    /** Seed only this test's generated media, via normal lease and review APIs. */
    private fun seedSuggestedPerson(epoch: String): PersonKey {
        val keys = inserted.map { uri ->
            val volume = target.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME),
                null, null, null)!!.use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val id = ContentUris.parseId(uri)
            val canonical = ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(volume), id).toString()
            // Only the canonical key is used; the lease already holds the
            // actual revision published by MainActivity's catalogue.
            FaceMediaSourceKey.fromVisiblePhoto(canonical, volume, false, id,
                null, 0L, 0L, 0L, 0L, 480, 480, 0).canonicalMediaKey
        }.toSet()
        check(keys.size == PHOTOS + 2)
        val box = IndexedFaceBox(0.1f, 0.1f, 0.9f, 0.9f)
        val person = PersonKey("synthetic-ime-448")
        var count = 0
        while (true) {
            val lease = store.claim(epoch) ?: break
            if (lease.media.key !in keys) {
                // This fixture's private index never reads any other media.
                check(store.skip(lease, "NOT_A_FIXTURE"))
                continue
            }
            // The second named singleton is orthogonal to the suggested identity.
            val axis = if (count == 1) 1 else 0
            val vector = FloatArray(SFaceModel.DIMENSION) { if (it == axis) 1f else 0f }
            check(store.completeEmbeddings(lease, listOf(box), listOf(vector), SFaceModel.EMBEDDER_VERSION))
            count++
            if (count <= 2) {
                check(store.materializeClusters(epoch))
                val state = requireNotNull(host.reviewState(epoch))
                val singleton = state.clusters.unknownClusters.single()
                check(singleton.members.size == 1)
                check(host.review(PeopleClusterReviewCommand(state,
                    PeopleReviewAction.NameCluster(singleton.id, if (count == 1) person else PersonKey("synthetic-ime-448-other"), SUGGESTED_NAME))))
            }
        }
        check(count == keys.size) { "Expected ${keys.size} fixture leases, got $count" }
        check(store.materializeClusters(epoch))
        return person
    }

    /**
     * The #448 contract with the keyboard up: list bottom = keyboard top ±2 px,
     * the field and Create fully above the keyboard (Create by ≥16 dp, 12 dp
     * in landscape where the compact header takes the rest), and in portrait
     * "Who is this?" and at least four header faces on screen as well.
     */
    private fun assertKeyboardContract(
        label: String, portrait: Boolean,
        createMatcher: SemanticsMatcher = hasTestTag("atlas-group-create-person")
    ) {
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
        compose.assertAboveKeyboard(createMatcher, clearance)
        assertMinimumTarget(hasTestTag("atlas-group-name"))
        assertMinimumTarget(createMatcher)
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
        check(android.os.Build.VERSION.SDK_INT >= 30) { "IME animation requires API 30" }
        val full = 918
        val intermediate = (full / 4)..(full * 3 / 4)
        val ime = android.view.WindowInsets.Type.ime()
        val current = java.util.concurrent.atomic.AtomicInteger(-1)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val ready = java.util.concurrent.CountDownLatch(1)
        val callback = object : android.view.WindowInsetsAnimation.Callback(
            android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
            override fun onProgress(
                insets: android.view.WindowInsets,
                runningAnimations: MutableList<android.view.WindowInsetsAnimation>
            ): android.view.WindowInsets {
                if (runningAnimations.any { it.typeMask and ime != 0 }) {
                    val bottom = insets.getInsets(ime).bottom
                    current.set(bottom)
                    peak.set(maxOf(peak.get(), bottom))
                    if (bottom in intermediate) ready.countDown()
                }
                return insets
            }

            override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                if (animation.typeMask and ime != 0) current.set(-1)
                super.onEnd(animation)
            }
        }
        device.animatorScale(10f)
        val decor = compose.activity.window.decorView
        try {
            // Prepare the scroll and resolve the focus action while the IME is
            // still closed. Do not enter Compose's idle barrier after focus.
            compose.onNodeWithTag("atlas-unknown-face-review-screen")
                .performScrollToNode(hasTestTag("atlas-group-name"))
            compose.waitForIdle()
            check(compose.keyboardInsetPx() == 0) { "$theme opening did not start closed" }
            val requestFocus = requireNotNull(
                compose.onNodeWithTag("atlas-group-name").fetchSemanticsNode()
                    .config[SemanticsActions.RequestFocus].action
            )
            check(visibleSystemAnrRoot() == null) { "$theme opening obscured by system ANR" }
            instrumentation.runOnMainSync {
                decor.setWindowInsetsAnimationCallback(callback)
                requestFocus()
                decor.windowInsetsController?.show(ime)
            }
            check(ready.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                "$theme intermediate opening inset not delivered; peak=${peak.get()}"
            }
            // Only publish an acquisition bracketed by actual animated insets.
            // No synthetic frame, final-state substitution or animation retry.
            val before = current.get()
            check(before in intermediate) { "$theme opening missed: before=$before" }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val after = current.get()
                check(after in intermediate && after >= before) {
                    "$theme opening missed: before=$before after=$after"
                }
                check(visibleSystemAnrRoot() == null) { "$theme opening obscured by system ANR" }
                check(uiEvidenceProblem(bitmap) == null) { "$theme opening frame invalid" }
                val png = java.io.ByteArrayOutputStream().also {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }.toByteArray()
                writeUiEvidenceFile(uiEvidenceDisplayName("unknown-ime-448-b-opening-$theme.png"), png)
                Log.i(LOG_TAG, "$theme b-opening before capture=$before after capture=$after of $full")
            } finally {
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { decor.setWindowInsetsAnimationCallback(null) }
            device.animatorScale(1f)
        }
        compose.awaitKeyboardShown()
        steady()
    }

    private fun focusNameField() {
        compose.onNodeWithTag("atlas-unknown-face-review-screen")
            .performScrollToNode(hasTestTag("atlas-group-name"))
        compose.waitForIdle()
        // Focus by semantics: after the scroll the sticky header can sit over
        // part of the field in landscape, and a pointer tap would land on it.
        // Focus opens the keyboard the same way a tap does.
        compose.onNodeWithTag("atlas-group-name")
            .performSemanticsAction(SemanticsActions.RequestFocus)
    }

    /**
     * People → the Unknown group's tile → the group sheet with its name field.
     * The sheet is saved state, so after a rotation or configuration change it
     * is usually still open: then only wait for it.
     */
    private fun openReview() {
        compose.waitForIdle()
        // The list is lazy: in landscape the name field can sit below the
        // fold and is not composed, so look for the list itself.
        if (compose.exists(hasTestTag("atlas-unknown-face-review-screen"), useUnmergedTree = true)) {
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

    private fun insertFaces(count: Int) {
        val now = System.currentTimeMillis()
        for (i in 1..count) {
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
        host = PeopleDiscoveryHost(
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
        const val SUGGESTED_NAME = "Synthetic Morgan Avery Northbridge"
        const val FOLDER = "ATLAS-Unknown-IME-448"
        const val LOG_TAG = "ATLAS_IME_448"
    }
}
