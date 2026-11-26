package nl.atlas.gallery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.rules.RuleChain
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featuregrid.SQUARE_GRID_ACTION
import nl.atlas.gallery.featurepeople.identity.ReviewTap
import nl.atlas.gallery.featurepeople.identity.TapCount
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.testShell
import nl.atlas.gallery.testkit.typeText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #551 on API35, the one emulator journey of this unit: the Samsung person
 * page over a real app-private FaceIndexStore with the bundled YuNet + SFace
 * models. Photos are [SyntheticFace] drawings written to MediaStore by this
 * test (deleted afterwards), so the grid shows real thumbnails of invented
 * faces. Journey: an unnamed group reads "Add name"; naming it on the page
 * commits and the page follows the new person; ⋮, the merge picker and the
 * merge confirmation open; Hide closes the page; a new process over the same
 * store keeps the name and the hide, and reopening shows the name. Saves the
 * person-unnamed, person-named, person-menu and person-merge-picker renders.
 */
@RunWith(AndroidJUnit4::class)
class PersonPageJourneyTest {
    // #584: rendered like the app (dark theme, app system bars) at S24+ size.
    private val compose = createAndroidComposeRule<ComponentActivity>()
    // Render journeys use no keyboard, but the IME-Done journey needs a real,
    // deterministic test keyboard selected before the Activity is created.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(
        TestKeyboard.None,
        methodKeyboards = mapOf("keyboardDoneInTheNameDialogNamesThePerson" to TestKeyboard.Fixed)
    )).around(S24DisplayRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "person-page-" + UUID.randomUUID())
    private lateinit var store: FaceIndexStore
    private val inserted = mutableListOf<Uri>()
    private val specs = HashMap<String, SyntheticFace.Spec>()
    private lateinit var inventory: List<FaceVisibleMedia>
    private lateinit var records: List<MediaRecord>

    @Before fun insertSyntheticFaces() {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
        val now = System.currentTimeMillis()
        val media = mutableListOf<FaceVisibleMedia>()
        val rows = mutableListOf<MediaRecord>()
        for (i in 1..6) {
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_PERSON_PAGE_FIXTURE_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Person-Fixture/")
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
            val id = ContentUris.parseId(uri)
            // The face catalogue only admits canonical /<volume>/images/media/<id>
            // URIs; insert() answers with the generic "external" volume.
            val canonical = ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri("external_primary"), id).toString()
            specs[canonical] = spec
            media += FaceVisibleMedia(
                uri = canonical, volumeName = "external_primary", isVideo = false, id = id,
                providerVersion = "synthetic-provider-1", generationModified = 10 + id,
                sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
                widthPixels = 480, heightPixels = 480, orientationDegrees = 0)
            rows += MediaRecord(id, canonical, "IMG_PERSON_PAGE_FIXTURE_$i.png",
                "Pictures/ATLAS-Person-Fixture/", 1000 + id, taken, false,
                volumeName = "external_primary")
        }
        inventory = media
        records = rows
    }

    /** Test content is composed only while this holds; see [cleanUp]. */
    private var composing by mutableStateOf(true)

    @After fun cleanUp() {
        // Dispose the composed routes first: their effects still read the
        // store, and closing or deleting it under them fails the test.
        composing = false
        compose.waitForIdle()
        PeopleScanWorkRuntime.clear()
        if (::store.isInitialized) store.close()
        dir.deleteRecursively()
        inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
    }

    private fun host(): PeopleDiscoveryHost {
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
        store = FaceIndexStore(context)
        return PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { true },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    AndroidBitmapFaceFrame(SyntheticFace.bitmap(
                        specs.getValue(directory.authorizedUri(lease.media))))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            faceCropDecoder = MediaStoreFaceCropDecoder(target.contentResolver)
        )
    }

    private fun drain(host: PeopleDiscoveryHost, grant: String) {
        host.start(grant, inventory)
        var guard = 0
        while (runBlocking { runPeopleScanBatch(target) { false } }!!.stop !=
            FaceBatchStop.NO_PENDING && guard++ < 20) Unit
    }

    /** Pinch out on the grid until the real-ratio rows show (the ladder may need more than one). */
    private fun pinchToRatioRows() {
        fun shown(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        for (attempt in 1..3) {
            // #614: a pinch that worked can leave a moment with neither view in
            // the tree; wait for one of them instead of failing the grid lookup.
            compose.awaitThat("grid or ratio rows", WAIT_MS) {
                shown("atlas-ratio-gallery") || shown("atlas-timeline-grid")
            }
            if (shown("atlas-ratio-gallery")) break
            compose.onNodeWithTag("atlas-timeline-grid").performTouchInput {
                val reach = width * 0.4f
                pinch(
                    start0 = center - Offset(40f, 0f), end0 = center - Offset(reach, 0f),
                    start1 = center + Offset(40f, 0f), end1 = center + Offset(reach, 0f),
                    durationMillis = 400L
                )
            }
            compose.waitForIdle()
        }
        compose.waitForNode(hasTestTag("atlas-ratio-gallery"), WAIT_MS, useUnmergedTree = true)
    }

    /**
     * #404: unlike a plain composition/theme toggle, this recreates the real
     * ComponentActivity four times. The test supplies its fixture-backed page
     * content again in the new Activity, as MainActivity does in onCreate;
     * only the ViewModel-owned selection snapshot crosses that boundary.
     * No real people or photos are used.
     */
    @Test fun selectionOnPersonPageSurvivesRealActivityRotation() {
        val grant = "1" // Matches the synthetic catalogueEpoch and the live grant fence.
        val host = host()
        drain(host, grant)
        val epoch = checkNotNull(host.displayEpoch(grant))
        val group = store.peopleReview(epoch)!!.clusters.unknownClusters
            .maxBy { it.members.size }
        val matchingUris = checkNotNull(host.personPageFaces(grant, group.members)).keys
        val selectedRecords = records.filter { it.uri in matchingUris }.take(2)
        assertEquals("two authorized synthetic person photos", 2, selectedRecords.size)
        val ids = selectedRecords.map { it.id }
        val expectedUris = selectedRecords.map { it.uri }.toSet()
        val request = NativePersonAlbumRequest(PersonPageTarget.Unnamed(group.id), "")
        val holder = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
            .pendingPersonSelectionRotationState
        var dark by mutableStateOf(true)
        var increasedFontScale by mutableStateOf(false)
        var routeGrant by mutableStateOf(grant)
        var catalogueEpoch by mutableStateOf(1L)

        // On each Activity instance, re-attach the same synthetic route with
        // the SAME configuration-retained holder, not a Bundle/rememberSaveable.
        val page: @Composable () -> Unit = {
            if (composing) {
                AtlasGalleryTheme(dark = dark) {
                    val density = LocalDensity.current
                    // Synthetic font-scale stress without changing the emulator's
                    // global setting: the selection action must still fit landscape.
                    CompositionLocalProvider(LocalDensity provides Density(
                        density.density, if (increasedFontScale) 1.3f else density.fontScale
                    )) {
                        Surface(Modifier.fillMaxSize(), color = DeepBg,
                            contentColor = MaterialTheme.colorScheme.onBackground) {
                            val discovery = rememberPeopleDiscoveryOf(host, grant, inventory) { it }
                            NativePersonAlbumRoute(host, routeGrant, request,
                                discovery.status?.people, records, rememberLazyGridState(),
                                catalogueEpoch,
                                selectionRotationState = holder, onBack = {},
                                onOpen = { _, _ -> })
                        }
                    }
                }
            }
        }
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent { page() }
        compose.waitForNode(hasTestTag("atlas-person-page"), WAIT_MS, useUnmergedTree = true)
        ids.forEach { compose.waitForNode(hasTestTag("atlas-timeline-media-$it"), WAIT_MS, useUnmergedTree = true) }
        compose.onNodeWithTag("atlas-timeline-media-${ids[0]}")
            .performTouchInput { longClick() }
        compose.waitForNode(hasText("1 selected"), WAIT_MS)
        compose.tap(hasTestTag("atlas-timeline-media-${ids[1]}"))

        fun assertPersonSelection() {
            compose.waitForNode(hasText("2 selected"), WAIT_MS)
            compose.onNodeWithTag("atlas-person-select-count").assertExists()
            compose.onNodeWithTag("atlas-person-remove-confirm").assertExists()
            // Both controls remain inside the current S24+ viewport, apart
            // from each other, in portrait AND constrained landscape.
            val root = compose.onRoot().getUnclippedBoundsInRoot()
            val count = compose.onNodeWithTag("atlas-person-select-count")
                .getUnclippedBoundsInRoot()
            val action = compose.onNodeWithTag("atlas-person-remove-confirm")
                .getUnclippedBoundsInRoot()
            assertTrue("selection header is inside viewport", count.top >= root.top &&
                count.bottom < action.top)
            assertTrue("person action stays inside navigation-safe viewport",
                action.bottom <= root.bottom)
            ids.forEach { id ->
                val tag = "atlas-timeline-media-$id"
                compose.onNodeWithTag("atlas-timeline-grid")
                    .performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsSelected()
            }
            compose.waitForIdle()
            assertEquals("configuration holder keeps exactly the same keys",
                expectedUris, holder.value?.encoded?.drop(3)?.toSet())
            assertEquals(request.opening, holder.value?.opening)
            // #404: visible semantics alone can mean a 5px sliver below the
            // person avatar, with the checked overlay entirely hidden.
            // Require at least half of a selected square above the action bar
            // and below the pinned person header in every landscape capture.
            val selectedTile = compose.onNodeWithTag("atlas-timeline-media-${ids.last()}")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            if (root.right - root.left > root.bottom - root.top) {
                val pinnedHeader = compose.onNodeWithTag("atlas-scrolling-header")
                    .getUnclippedBoundsInRoot()
                val visibleTop = maxOf(selectedTile.top, pinnedHeader.bottom, root.top)
                val visibleBottom = minOf(selectedTile.bottom, action.top, root.bottom)
                assertTrue("checked person tile must be at least half visible in landscape",
                    visibleBottom - visibleTop >=
                        (selectedTile.bottom - selectedTile.top) * 0.5f)
            }
            compose.waitForNode(hasText("2 selected"), WAIT_MS)
        }

        fun rotateAndReattach(landscape: Boolean) {
            lateinit var before: ComponentActivity
            compose.activityRule.scenario.onActivity { before = it }
            instrumentation.uiAutomation.setRotation(
                if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90
                else android.app.UiAutomation.ROTATION_FREEZE_0)
            // UiAutomation rotation can update the window configuration in place
            // on the API35 fixture Activity. Explicitly recreate after requesting
            // orientation so this test actually exercises an Activity boundary,
            // rather than waiting for a system-dependent automatic relaunch.
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity {
                assertTrue("rotation must replace the ComponentActivity", it !== before)
                val retained = ViewModelProvider(it)[GalleryViewModel::class.java]
                    .pendingPersonSelectionRotationState
                assertSame("snapshot lives in the Activity-retained ViewModel", holder, retained)
                applyAtlasSystemBars(it.window, dark = dark)
                it.setContent { page() }
            }
            compose.awaitThat("person page laid out in requested orientation", WAIT_MS) {
                val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                val bounds = roots.maxByOrNull {
                    it.boundsInRoot.width * it.boundsInRoot.height
                }?.boundsInRoot
                bounds != null && (bounds.width > bounds.height) == landscape
            }
            assertPersonSelection()
        }

        try {
            assertPersonSelection()
            compose.awaitScreenStill("selection-404-dark-person-portrait-before")
            saveUiEvidence("selection-404-dark-person-portrait-before.png")
            rotateAndReattach(landscape = true)
            compose.awaitScreenStill("selection-404-dark-person-landscape")
            saveUiEvidence("selection-404-dark-person-landscape.png")
            rotateAndReattach(landscape = false)
            compose.awaitScreenStill("selection-404-dark-person-portrait-after")
            saveUiEvidence("selection-404-dark-person-portrait-after.png")

            dark = false
            compose.activityRule.scenario.onActivity {
                applyAtlasSystemBars(it.window, dark = false)
            }
            assertPersonSelection()
            compose.awaitScreenStill("selection-404-light-person-portrait-before")
            saveUiEvidence("selection-404-light-person-portrait-before.png")
            rotateAndReattach(landscape = true)
            compose.awaitScreenStill("selection-404-light-person-landscape")
            saveUiEvidence("selection-404-light-person-landscape.png")
            increasedFontScale = true
            assertPersonSelection() // 130% text must keep count/action in bounds.
            compose.awaitScreenStill("selection-404-light-person-landscape-font130")
            saveUiEvidence("selection-404-light-person-landscape-font130.png")
            increasedFontScale = false
            rotateAndReattach(landscape = false)
            compose.awaitScreenStill("selection-404-light-person-portrait-after")
            saveUiEvidence("selection-404-light-person-portrait-after.png")

            // Negative journey on the same real rendered person route: a
            // newer publication must immediately disable the old action and
            // permanently discard the snapshot, even if identity later reverts.
            catalogueEpoch = 2L
            compose.awaitThat("publication mismatch drops the person selection", WAIT_MS) {
                compose.onAllNodesWithTag("atlas-person-select-count")
                    .fetchSemanticsNodes().isEmpty() &&
                    holder.value?.encoded?.isEmpty() == true
            }
            catalogueEpoch = 1L
            compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[0]}"), WAIT_MS, useUnmergedTree = true)
            compose.onNodeWithTag("atlas-person-select-count").assertDoesNotExist()

            // A changed grant is an independent invalidation boundary.
            compose.onNodeWithTag("atlas-timeline-media-${ids[0]}")
                .performTouchInput { longClick() }
            compose.waitForNode(hasText("1 selected"), WAIT_MS)
            routeGrant = "2"
            compose.awaitThat("grant mismatch drops the person selection", WAIT_MS) {
                compose.onAllNodesWithTag("atlas-person-select-count")
                    .fetchSemanticsNodes().isEmpty() &&
                    holder.value?.encoded?.isEmpty() == true
            }
            routeGrant = grant
            compose.waitForNode(hasTestTag("atlas-timeline-media-${ids[0]}"), WAIT_MS, useUnmergedTree = true)
            compose.onNodeWithTag("atlas-person-select-count").assertDoesNotExist()
        } finally {
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test fun nameOnThePersonPageThenReopenShowsTheNameAndHideSurvivesRestart() {
        var host by mutableStateOf(host())
        var grant by mutableStateOf<String?>("gallery-1")
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!

        // A second, named person for the merge picker: split two faces off the
        // one group of drawings and name them, through the same review API.
        // People home (#532) no longer loads the full review snapshot; ask for it.
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot after the scan" }
        val group = checkNotNull(start.clusters.unknownClusters.maxByOrNull { it.members.size }) {
            "no unknown face group after the scan: ${start.clusters.faceCount} faces"
        }
        assertTrue("one group of drawn faces", group.members.size >= 4)
        val boFaces = group.members.take(2).toSet()
        assertTrue(host.review(PeopleClusterReviewCommand(start,
            PeopleReviewAction.SplitUnknownCluster(group.id, boFaces))))
        val split = host.liveReview(epoch)!!
        val boGroup = split.clusters.unknownClusters.first { boFaces.first() in it.members }
        assertTrue(host.review(PeopleClusterReviewCommand(split, PeopleReviewAction.NameCluster(
            boGroup.id, nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-bo"),
            "Synthetic Bo"))))
        val unnamed = host.liveReview(epoch)!!.clusters.unknownClusters.maxBy { it.members.size }

        var request by mutableStateOf<NativePersonAlbumRequest?>(
            NativePersonAlbumRequest(PersonPageTarget.Unnamed(unnamed.id), ""))
        // #662: MainActivity follows the phone's setting; dark is the main
        // evidence, and the page is re-rendered once in light below.
        var dark by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            if (!composing) return@setContent
            // The same root as MainActivity: gallery theme over DeepBg.
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    key(host) {
                        val discovery = rememberPeopleDiscoveryOf(host, grant, inventory) { it }
                        val opened = request
                        if (opened != null) NativePersonAlbumRoute(host, grant, opened,
                            discovery.status?.people, records, rememberLazyGridState(), 1L,
                            onBack = { request = null }, onOpen = { _, _ -> },
                            onRetarget = { request = it })
                    }
                }
            }
        }

        // Unnamed: "Add name" and the group's photos, newest first.
        compose.waitForNode(hasText("Add name"), WAIT_MS)
        compose.waitForNode(hasText("${unnamed.members.size} photos"), WAIT_MS)
        compose.waitForIdle()
        // #642: one photo a day over consecutive days in a month shares one
        // header, so the photos fill rows of four instead of one per header.
        val dayHeaders = compose.onAllNodes(SemanticsMatcher("timeline date header") {
            it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
                ?.startsWith("atlas-timeline-header-") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().size
        // The fixtures are one photo a day over consecutive days: one header,
        // or two when the run crosses a month end.
        assertTrue("sparse days share a header ($dayHeaders headers for " +
            "${unnamed.members.size} photos)", dayHeaders in 1..2)
        saveUiEvidence("person-unnamed.png")
        // #662: the same page under the phone's light setting.
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = false) }
        dark = false
        compose.awaitScreenStill("theme-662-person-light")
        saveUiEvidence("theme-662-person-light.png")
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        dark = true
        compose.waitForIdle()

        // Name it on the page: the page follows the new person.
        // The keyboard's Done on this field has its own test below.
        compose.tap(hasTestTag("atlas-person-name"))
        compose.typeText(hasTestTag("atlas-person-name-field"), "Synthetic Ada")
        compose.tap(hasTestTag("atlas-person-name-save"))
        compose.awaitThat("page follows the named person", WAIT_MS) { request?.person != null }
        val ada = request!!.person!!
        compose.waitForNode(hasText("Synthetic Ada"), WAIT_MS)
        assertEquals("Synthetic Ada", store.peopleReview(epoch)!!.names[ada])
        compose.awaitScreenStill("person-named")
        saveUiEvidence("person-named.png")
        // #655: same named synthetic person, actual Activity, both palettes.
        saveUiEvidence("parity-24-people-named-dark.png")
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = false) }
        dark = false
        compose.awaitScreenStill("parity-24-people-named-light")
        saveUiEvidence("parity-24-people-named-light.png")
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        dark = true
        compose.waitForIdle()

        // FIND-0: native Person page exposes the shared scoped search and
        // retains its current face-grant result set. Invented fixture names only.
        compose.tap(hasTestTag("atlas-search-expand"))
        compose.waitForNode(hasTestTag("atlas-scoped-search-field"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithTag("atlas-search-scope-screen").assertExists()
        compose.typeText(hasTestTag("atlas-scoped-search-field"), "FIXTURE_")
        compose.awaitScreenStill("find-0-person-scoped")
        saveUiEvidence("find-0-person-scoped.png")
        compose.tap(hasContentDescription("Close search"))
        compose.awaitThat("scoped person search closes", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-scoped-search-field").fetchSemanticsNodes().isEmpty()
        }

        // COVER-3: an actual synthetic face-crop chooser, checked selection,
        // selected portrait and reversible automatic default.
        compose.tap(hasTestTag("atlas-header-more"))
        compose.waitForNode(hasTestTag("atlas-person-change-picture"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-person-change-picture"))
        compose.waitForNode(hasTestTag("atlas-person-cover-grid"), WAIT_MS, useUnmergedTree = true)
        compose.awaitScreenStill("person-cover-chooser-dark")
        saveUiEvidence("person-cover-chooser-dark.png")
        compose.tap(hasTestTag("atlas-person-cover-choice-1"))
        compose.awaitThat("selected profile cover persisted", WAIT_MS) {
            store.personCoverOverride(epoch, ada) != null
        }
        compose.awaitScreenStill("person-cover-selected-page-dark")
        saveUiEvidence("person-cover-selected-page-dark.png")
        compose.tap(hasTestTag("atlas-header-more"))
        compose.waitForNode(hasTestTag("atlas-person-change-picture"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-person-change-picture"))
        compose.waitForNode(hasTestTag("atlas-person-cover-automatic"), WAIT_MS, useUnmergedTree = true)
        compose.waitForNode(hasTestTag("atlas-person-cover-checked"), WAIT_MS, useUnmergedTree = true)
        compose.awaitScreenStill("person-cover-checked-dark")
        saveUiEvidence("person-cover-checked-dark.png")
        // Design pair: the same checked chooser in the light theme.
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = false) }
        dark = false
        compose.awaitScreenStill("person-cover-checked-light")
        saveUiEvidence("person-cover-checked-light.png")
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        dark = true
        compose.waitForIdle()
        compose.tap(hasTestTag("atlas-person-cover-automatic"))
        compose.awaitThat("profile cover reverted", WAIT_MS) {
            store.personCoverOverride(epoch, ada) == null
        }
        compose.awaitScreenStill("person-cover-automatic-dark")
        saveUiEvidence("person-cover-automatic-dark.png")

        // ⋮ menu, then Merge with… lists Bo; the confirmation opens and is cancelled.
        compose.tap(hasTestTag("atlas-header-more"))
        compose.waitForNode(hasTestTag("atlas-person-merge"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithTag("atlas-person-hide").assertExists()
        compose.onNodeWithTag("atlas-person-remove").assertExists()
        compose.onNodeWithTag("atlas-person-edit-name").assertExists()
        compose.awaitScreenStill("person-menu")
        saveUiEvidence("person-menu.png")
        compose.tap(hasTestTag("atlas-person-merge"))
        compose.waitForNode(hasTestTag("atlas-person-merge-candidate"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithText("Synthetic Bo").assertExists()
        compose.awaitScreenStill("person-merge-picker")
        saveUiEvidence("person-merge-picker.png")
        compose.tap(hasTestTag("atlas-person-merge-candidate"))
        compose.waitForNode(hasTestTag("atlas-person-merge-dialog"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasText("Cancel"))
        compose.awaitThat("merge cancelled", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-person-merge-dialog")
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals("nothing merged", "Synthetic Bo",
            store.peopleReview(epoch)!!.names[nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-bo")])

        // #527 on the person page: pinching out past the largest square stop
        // opens real-ratio rows under the same person header; the Square grid
        // action brings the timeline grid back.
        pinchToRatioRows()
        compose.onNodeWithText("Synthetic Ada").assertExists()
        compose.onNodeWithTag("atlas-header-more").assertExists()
        val squareGrid = compose.onNodeWithTag("atlas-ratio-gallery")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { squareGrid.first { it.label == SQUARE_GRID_ACTION }.action() }
        compose.waitForNode(hasTestTag("atlas-timeline-grid"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithText("Synthetic Ada").assertExists()

        // #584: a new opening (another person, as from People or the viewer's
        // People row) starts at the grid even while this page is in ratio rows;
        // only a rename or merge (a retarget) keeps the stop.
        pinchToRatioRows()
        val bo = nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-bo")
        request = NativePersonAlbumRequest(bo, "Synthetic Bo")
        compose.waitForNode(hasText("Synthetic Bo"), WAIT_MS)
        compose.waitForNode(hasTestTag("atlas-timeline-grid"), WAIT_MS, useUnmergedTree = true)
        assertTrue("a new person page opens at the grid",
            compose.onAllNodesWithTag("atlas-ratio-gallery", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty())
        request = NativePersonAlbumRequest(ada, "Synthetic Ada")
        compose.waitForNode(hasText("Synthetic Ada"), WAIT_MS)
        compose.waitForNode(hasTestTag("atlas-timeline-grid"), WAIT_MS, useUnmergedTree = true)

        // Hide: Samsung returns to People; the store keeps the hide.
        compose.tap(hasTestTag("atlas-header-more"))
        compose.waitForNode(hasTestTag("atlas-person-hide"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-person-hide"))
        compose.awaitThat("page closed after hide", WAIT_MS) { request == null }
        assertEquals(setOf(ada), store.peopleReview(epoch)!!.hidden)

        // A new process over the same private store: name and hide are kept,
        // and reopening the person shows the name.
        grant = null
        compose.waitForIdle()
        // Swap the composed host first, then close the old store: closing it
        // under live routes fails them with a closed SQLiteDatabase.
        val old = store
        val restarted = host()
        host = restarted
        compose.waitForIdle()
        old.close()
        drain(restarted, "gallery-2")
        grant = "gallery-2"
        val home = restarted.status("gallery-2").people
        assertNotNull("People home restored after a restart", home)
        // People home (#532 summary) leaves the hidden person out; Bo stays.
        assertTrue("hidden person not on People home", ada !in home!!.people)
        assertEquals("Synthetic Bo", home.people[
            nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-bo")]?.name)
        val people = restarted.reviewState(restarted.displayEpoch("gallery-2")!!)
        assertNotNull("names restored after a restart", people)
        assertEquals("Synthetic Ada", people!!.names[ada])
        assertEquals(setOf(ada), people.hidden)
        host = restarted
        request = NativePersonAlbumRequest(ada, "")
        compose.waitForNode(hasText("Synthetic Ada"), WAIT_MS)
        // One tap on ⋮ must open the menu (rule 4): tap() waits until ⋮ holds
        // still while the restarted host publishes, and names a lost touch.
        compose.tap(hasTestTag("atlas-header-more"), timeoutMs = WAIT_MS) {
            compose.exists(hasText("Show this person"))
        }
    }

    /**
     * #580 "Same person?": one person left as two unnamed groups (here the
     * stored membership of the drawn faces is split in two, as an older scan
     * can leave it). The page of one group asks about the other, with its
     * face and size; Yes joins them, the page shows all photos and the answer
     * is stored. Saves the person-same-question and person-same-joined renders.
     */
    @Test fun samePersonQuestionOnAnUnnamedPageJoinsTheTwoGroupsOnYes() {
        val host = host()
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!
        val scanned = checkNotNull(store.peopleReview(epoch)) { "no groups after the scan" }
        val group = scanned.clusters.unknownClusters.maxBy { it.members.size }
        val all = group.members.size
        assertTrue("one group of drawn faces, found $all", all >= 5)
        // Members are sorted, so each half's first face is its group's anchor.
        val second = group.members.drop(3)
        for (face in second) store.writableDatabase.execSQL(
            "UPDATE cluster_members SET anchor_uid=? WHERE face_uid=?",
            arrayOf(second.first().localHex, face.localHex))
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot" }
        assertEquals(listOf(3, all - 3).sorted(),
            start.clusters.unknownClusters.map { it.members.size }.sorted())
        val own = start.clusters.unknownClusters.first { group.members.first() in it.members }

        var request by mutableStateOf<NativePersonAlbumRequest?>(
            NativePersonAlbumRequest(PersonPageTarget.Unnamed(own.id), ""))
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            if (!composing) return@setContent
            // The same root as MainActivity: dark gallery theme over DeepBg.
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    val opened = request
                    if (opened != null) NativePersonAlbumRoute(host, "gallery-1", opened,
                        discovery.status?.people, records, rememberLazyGridState(), 1L,
                        onBack = { request = null }, onOpen = { _, _ -> },
                        onRetarget = { request = it })
                }
            }
        }
        compose.waitForNode(hasText("3 photos"), WAIT_MS)
        compose.waitForNode(hasTestTag("atlas-person-same"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithText("Same person?").assertExists()
        compose.onNodeWithText(if (all - 3 == 1) "1 more face" else "${all - 3} more faces").assertExists()
        compose.awaitScreenStill("person-same-question")
        saveUiEvidence("person-same-question.png")

        compose.tap(hasTestTag("atlas-person-same-yes"))
        compose.waitForNode(hasText("$all photos"), WAIT_MS)
        compose.awaitThat("question answered", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-person-same", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        val joined = store.peopleReview(epoch)!!
        assertEquals(all, joined.clusters.unknownClusters.single().members.size)
        assertEquals(1, joined.clusters.reviews.sameUnknown.size)
        assertTrue(joined.clusters.reviews.cannotLink.isEmpty())
        compose.awaitScreenStill("person-same-joined")
        saveUiEvidence("person-same-joined.png")
    }

    /**
     * #580 People ⋮ → Same person?: the same question asked from one list of
     * all open questions, with a few faces of each side. Yes joins the two
     * groups and the list is empty. Saves the people-same-queue render.
     */
    @Test fun theQuestionListAsksTheOpenQuestionAndYesEmptiesIt() {
        val host = host()
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!
        val scanned = checkNotNull(store.peopleReview(epoch)) { "no groups after the scan" }
        val group = scanned.clusters.unknownClusters.maxBy { it.members.size }
        val all = group.members.size
        assertTrue("one group of drawn faces, found $all", all >= 5)
        val second = group.members.drop(3)
        for (face in second) store.writableDatabase.execSQL(
            "UPDATE cluster_members SET anchor_uid=? WHERE face_uid=?",
            arrayOf(second.first().localHex, face.localHex))
        checkNotNull(host.reviewState(epoch)) { "no review snapshot" }

        var open by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    if (open) SamePersonQueueRoute(host, discovery.status?.people) { open = false }
                }
            }
        }
        compose.waitForNode(hasTestTag("atlas-same-queue-yes"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithText("1 open").assertExists()
        compose.onNodeWithText("Unnamed person").assertExists()
        compose.onNodeWithText("Unnamed group").assertExists()
        compose.awaitThat("face crops of both sides", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-person-face", useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 4
        }
        compose.awaitScreenStill("people-same-queue")
        saveUiEvidence("people-same-queue.png")

        compose.tap(hasTestTag("atlas-same-queue-yes"))
        compose.waitForNode(hasText("No open questions."), WAIT_MS)
        val joined = store.peopleReview(epoch)!!
        assertEquals(all, joined.clusters.unknownClusters.single().members.size)
        assertEquals(1, joined.clusters.reviews.sameUnknown.size)
        assertEquals(mapOf(ReviewTap.SAME_YES to TapCount(1, all.toLong())),
            store.peopleReviewTally(epoch))
        compose.tap(hasTestTag("atlas-same-queue-close"))
        compose.awaitThat("list closed", WAIT_MS) { !open }
    }

    /**
     * #580: "No" on the question keeps the two groups apart for good, and
     * People numbers counts the tap. The dialog shows counts only and Copy
     * puts the one-line form on the clipboard. Saves the people-numbers render.
     */
    @Test fun noOnTheSamePersonQuestionIsStoredAndPeopleNumbersCountIt() {
        val host = host()
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!
        val scanned = checkNotNull(store.peopleReview(epoch)) { "no groups after the scan" }
        val group = scanned.clusters.unknownClusters.maxBy { it.members.size }
        val all = group.members.size
        assertTrue("one group of drawn faces, found $all", all >= 5)
        val second = group.members.drop(3)
        for (face in second) store.writableDatabase.execSQL(
            "UPDATE cluster_members SET anchor_uid=? WHERE face_uid=?",
            arrayOf(second.first().localHex, face.localHex))
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot" }
        val own = start.clusters.unknownClusters.first { group.members.first() in it.members }
        assertEquals(3, own.members.size)

        var numbersOpen by mutableStateOf(false)
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    NativePersonAlbumRoute(host, "gallery-1",
                        NativePersonAlbumRequest(PersonPageTarget.Unnamed(own.id), ""),
                        discovery.status?.people, records, rememberLazyGridState(), 1L,
                        onBack = {}, onOpen = { _, _ -> }, onRetarget = {})
                    if (numbersOpen) PeopleNumbersRoute(host, "gallery-1") { numbersOpen = false }
                }
            }
        }
        compose.waitForNode(hasText("3 photos"), WAIT_MS)
        compose.waitForNode(hasTestTag("atlas-person-same"), WAIT_MS, useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-person-same-no"))
        compose.awaitThat("question answered", WAIT_MS) {
            compose.onAllNodesWithTag("atlas-person-same", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() &&
                store.peopleReview(epoch)?.clusters?.reviews?.cannotLink?.size == 1
        }
        val apart = store.peopleReview(epoch)!!
        assertEquals(listOf(3, all - 3).sorted(),
            apart.clusters.unknownClusters.map { it.members.size }.sorted())
        assertTrue(apart.clusters.reviews.sameUnknown.isEmpty())
        assertEquals(mapOf(ReviewTap.SAME_NO to TapCount(1, all.toLong())),
            store.peopleReviewTally(epoch))
        compose.onNodeWithText("3 photos").assertExists()

        compose.runOnUiThread { numbersOpen = true }
        compose.waitForNode(hasTestTag("atlas-people-numbers-copy"), WAIT_MS, useUnmergedTree = true)
        compose.onNodeWithText("Same person? Yes 0 · No 1 · 0 questions open").assertExists()
        compose.onNodeWithText("0 named people with 0 faces").assertExists()
        // Before Copy: Android's own "copied" preview would cover part of the render.
        compose.awaitScreenStill("people-numbers")
        saveUiEvidence("people-numbers.png")
        compose.tap(hasTestTag("atlas-people-numbers-copy"))
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.ClipboardManager::class.java)
        var copied: String? = null
        compose.awaitThat("numbers on the clipboard", WAIT_MS) {
            compose.runOnUiThread {
                copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.text?.toString()
            }
            copied?.startsWith("people_numbers v=1 policy=") == true
        }
        assertTrue(copied, copied!!.contains(",same_no:1/$all,"))
        assertTrue(copied, copied!!.contains(" named_people=0 "))
        // Before another test takes a screenshot, Android's own "copied"
        // preview must be gone. It closes on CLOSE_SYSTEM_DIALOGS.
        testShell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        compose.awaitThat("the system clipboard preview to close", WAIT_MS) {
            !clipboardPreviewShown()
        }
    }

    /**
     * #580: a face grouping is sure about joins the named person on its own
     * (owner's choice, 2026-11-12). Three of the drawn faces are named; the
     * others show on the person's page at once, without a question and
     * without becoming confirmed examples. Taking one out is stored as "not
     * this person", counted on its own in People numbers, and survives a
     * rebuild from the stored rows. Saves the person-auto-added render.
     */
    @Test fun sureFacesJoinANamedPersonOnTheirOwnAndOneCanBeTakenOut() {
        val host = host()
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!
        val scanned = checkNotNull(store.peopleReview(epoch)) { "no groups after the scan" }
        val group = scanned.clusters.unknownClusters.maxBy { it.members.size }
        val all = group.members.size
        assertTrue("one group of drawn faces, found $all", all >= 5)
        val second = group.members.drop(3)
        for (face in second) store.writableDatabase.execSQL(
            "UPDATE cluster_members SET anchor_uid=? WHERE face_uid=?",
            arrayOf(second.first().localHex, face.localHex))
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot" }
        val own = start.clusters.unknownClusters.first { group.members.first() in it.members }
        assertEquals(3, own.members.size)
        val ada = nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-ada")
        assertTrue(host.review(PeopleClusterReviewCommand(start,
            PeopleReviewAction.NameCluster(own.id, ada, "Synthetic Ada"))))
        val named = store.peopleReview(epoch)!!
        assertEquals(3, named.clusters.reviews.confirmed.size)
        assertEquals(second.toSet(), named.clusters.matched.keys)
        assertTrue(named.clusters.matched.values.all { it.person == ada })
        assertTrue(named.clusters.unknownClusters.isEmpty())

        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    NativePersonAlbumRoute(host, "gallery-1",
                        NativePersonAlbumRequest(ada, "Synthetic Ada"),
                        discovery.status?.people, records, rememberLazyGridState(), 1L,
                        onBack = {}, onOpen = { _, _ -> }, onRetarget = {})
                }
            }
        }
        compose.waitForNode(hasText("Synthetic Ada"), WAIT_MS)
        compose.waitForNode(hasText("$all photos"), WAIT_MS)
        // Nothing is left to ask about.
        assertTrue(compose.onAllNodesWithTag("atlas-person-same", useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty())
        assertEquals(all, host.personAlbumUris("gallery-1", ada)!!.size)
        compose.awaitScreenStill("person-auto-added")
        saveUiEvidence("person-auto-added.png")

        // Take one of the added faces out again: it is an unnamed face once more.
        val out = second.first()
        val live = host.liveReview(epoch)!!
        assertTrue(host.review(PeopleClusterReviewCommand(live,
            PeopleReviewAction.SplitPerson(ada, setOf(out)))))
        compose.waitForNode(hasText("${all - 1} photos"), WAIT_MS)
        val after = store.peopleReview(epoch)!!
        assertEquals(second.drop(1).toSet(), after.clusters.matched.keys)
        assertEquals(3, after.clusters.reviews.confirmed.size)
        assertEquals(listOf(listOf(out)), after.clusters.unknownClusters.map { it.members })
        assertTrue(after.clusters.reviews.rejectedPeople.any { it.observation == out && it.person == ada })
        assertEquals(mapOf(ReviewTap.NAMED to TapCount(1, 3),
            ReviewTap.AUTO_TAKEN_OUT to TapCount(1, 1)), store.peopleReviewTally(epoch))
        val numbers = host.peopleNumbers("gallery-1")!!
        assertEquals(all - 4, numbers.autoAdded)
        val line = numbers.line()
        assertTrue(line, line.contains(" named_faces=3 auto_added=${all - 4} "))
        assertTrue(line, line.contains(",auto_taken_out:1/1 "))
    }

    /**
     * The name dialog's text field exposes the keyboard's Done as its submit
     * path (onSave): a person can press Done instead of Save. The journey
     * above taps Save; this is the keyboard path on its own (#442).
     */
    @Test fun keyboardDoneInTheNameDialogNamesThePerson() {
        val host = host()
        drain(host, "gallery-1")
        val epoch = host.displayEpoch("gallery-1")!!
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot after the scan" }
        val group = checkNotNull(start.clusters.unknownClusters.maxByOrNull { it.members.size }) {
            "no unknown face group after the scan: ${start.clusters.faceCount} faces"
        }
        var request by mutableStateOf<NativePersonAlbumRequest?>(
            NativePersonAlbumRequest(PersonPageTarget.Unnamed(group.id), ""))
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            if (!composing) return@setContent
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    val opened = request
                    if (opened != null) NativePersonAlbumRoute(host, "gallery-1", opened,
                        discovery.status?.people, records, rememberLazyGridState(), 1L,
                        onBack = { request = null }, onOpen = { _, _ -> },
                        onRetarget = { request = it })
                }
            }
        }
        compose.waitForNode(hasText("Add name"), WAIT_MS)
        compose.tap(hasTestTag("atlas-person-name"))
        // Keep Done actionable: the default typeText path hides the keyboard, which can
        // race the IME action on a dialog. This journey exercises Done while it is shown.
        compose.typeText(hasTestTag("atlas-person-name-field"), "Synthetic Ada", KeyboardAfter.Shown)
        compose.awaitThat("name entered before IME Done", WAIT_MS) {
            compose.exists(hasText("Synthetic Ada"), useUnmergedTree = true)
        }
        compose.onNodeWithTag("atlas-person-name-field").performImeAction()
        compose.awaitThat("page follows the named person", WAIT_MS) { request?.person != null }
        val ada = request!!.person!!
        compose.waitForNode(hasText("Synthetic Ada"), WAIT_MS)
        assertEquals("Synthetic Ada", store.peopleReview(epoch)!!.names[ada])
    }

    /** Android's own clipboard preview (System UI) is on screen. Window titles and ids only. */
    private fun clipboardPreviewShown(): Boolean = runCatching {
        interactiveUiAutomation().windows.any { window ->
            window.title?.toString()?.contains("clipboard", ignoreCase = true) == true ||
                window.root?.findAccessibilityNodeInfosByViewId(
                    "com.android.systemui:id/clipboard_ui")?.isNotEmpty() == true
        }
    }.getOrDefault(false)

    private companion object {
        /** Scans, decodes and the store run on real time on a slow emulator. */
        const val WAIT_MS = 20_000L
    }
}
