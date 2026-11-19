package nl.atlas.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
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
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featuresearch.EphemeralSearchHistory
import nl.atlas.gallery.featuresearch.LocalSearchControls
import nl.atlas.gallery.featuresearch.LocalSearchFacets
import nl.atlas.gallery.featuresearch.SearchPerson
import nl.atlas.gallery.featuresearch.searchWithFacets
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #24: the last three parity surfaces as controlled light/dark pairs at the
 * owner's S24+ size: a named person page, the viewer's Details People row and
 * a person-name search. Each test renders one real app route once and flips
 * only the ATLAS theme (and the window's system-bar icons, as MainActivity
 * does for the phone setting), so the two captures differ in theme alone.
 *
 * Synthetic only: the person page runs the bundled face models over
 * [SyntheticFace] drawings written to MediaStore by this test and deleted
 * afterwards; Details and search use locally drawn faces and invented names.
 * No real people, photos or Samsung assets.
 */
@RunWith(AndroidJUnit4::class)
class ParityPeopleThemePairsTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule())
        .around(S24DisplayRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "parity-people-" + UUID.randomUUID())
    private var store: FaceIndexStore? = null
    private val inserted = mutableListOf<Uri>()
    private val specs = HashMap<String, SyntheticFace.Spec>()

    /** Test content is composed only while this holds; see [cleanUp]. */
    private var composing by mutableStateOf(true)
    private var dark by mutableStateOf(true)

    @After fun cleanUp() {
        // Dispose the routes first: their effects still read the store.
        composing = false
        compose.waitForIdle()
        PeopleScanWorkRuntime.clear()
        store?.close()
        dir.deleteRecursively()
        inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
    }

    // ---- people-named -------------------------------------------------------

    @Test fun namedPersonPagePair() {
        val (inventory, records) = insertSyntheticFaces()
        val host = host()
        val grant = "gallery-1"
        host.start(grant, inventory)
        var guard = 0
        while (runBlocking { runPeopleScanBatch(target) { false } }!!.stop !=
            FaceBatchStop.NO_PENDING && guard++ < 20) Unit
        val epoch = checkNotNull(host.displayEpoch(grant)) { "no display epoch after the scan" }
        val start = checkNotNull(host.reviewState(epoch)) { "no review snapshot after the scan" }
        val group = checkNotNull(start.clusters.unknownClusters.maxByOrNull { it.members.size }) {
            "no unknown face group after the scan: ${start.clusters.faceCount} faces"
        }
        assertTrue("one group of drawn faces, found ${group.members.size}",
            group.members.size >= 4)
        val ada = PersonKey("synthetic-ada")
        assertTrue(host.review(PeopleClusterReviewCommand(start,
            PeopleReviewAction.NameCluster(group.id, ada, "Synthetic Ada"))))
        assertEquals("Synthetic Ada", store!!.peopleReview(epoch)!!.names[ada])
        val photos = host.personAlbumUris(grant, ada)!!.size

        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = true) }
        compose.setContent {
            if (!composing) return@setContent
            // The same root as MainActivity: gallery theme over DeepBg.
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, grant, inventory) { it }
                    NativePersonAlbumRoute(host, grant,
                        NativePersonAlbumRequest(ada, "Synthetic Ada"),
                        discovery.status?.people, records, rememberLazyGridState(), 1L,
                        onBack = {}, onOpen = { _, _ -> }, onRetarget = {})
                }
            }
        }
        compose.awaitThat("text Synthetic Ada") { compose.exists(hasText("Synthetic Ada")) }
        compose.awaitThat("text $photos photos") { compose.exists(hasText("$photos photos")) }
        compose.waitForNode(hasTestTag("atlas-person-page"), useUnmergedTree = true)
        capturePair("parity-24-people-named") {
            // The page still shows the named person, not "Add name".
            compose.onAllNodesWithText("Synthetic Ada").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Add name").fetchSemanticsNodes().isEmpty()
        }
    }

    // ---- viewer-details-people ---------------------------------------------

    @Test fun viewerDetailsPeoplePair() {
        val photo = MediaRecord(
            id = 7, uri = "content://synthetic-atlas-test/images/7",
            name = "Fictional-party.jpg", relativePath = "DCIM/Camera/",
            sizeBytes = 3_200_000L, takenAtMillis = 1_600_000_000_000L, isVideo = false)
        fun uid(n: Int) = n.toString(16).padStart(32, '0')
        val people: List<PhotoPerson> = listOf(
            PhotoPerson.Named("synthetic-epoch", uid(1), PersonKey("native-ada"), "Ada"),
            PhotoPerson.Named("synthetic-epoch", uid(2), PersonKey("native-bram"), "Bram"),
            PhotoPerson.Unnamed("synthetic-epoch", uid(3), uid(3)))
        val crops = mapOf(
            uid(1) to drawnFaceBitmap(0xFFE8C4A0.toInt(), 0xFF3B2A20.toInt()),
            uid(2) to drawnFaceBitmap(0xFFC69C72.toInt(), 0xFF111111.toInt()),
            uid(3) to drawnFaceBitmap(0xFFF1D3B5.toInt(), 0xFFB8862F.toInt()))
        val port = ViewerPhotoPeople(
            changes = MutableStateFlow(0L),
            load = { people },
            crop = { crops[it.face] },
            onOpen = {},
            onName = { _, _ -> false })
        val reader = ViewerDimensionsReader { _, _ -> ViewerDimensions(4000, 3000) }
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = true) }
        compose.setContent {
            if (!composing) return@setContent
            CompositionLocalProvider(LocalViewerDimensionsReader provides reader) {
                AtlasGalleryTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = DeepBg,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        GalleryViewer(listOf(photo), photo.uri, photoPeople = port, onBack = {})
                    }
                }
            }
        }
        compose.tap(hasTestTag("atlas-viewer-details-open"))
        compose.waitForNode(hasTestTag("atlas-details-people"), useUnmergedTree = true)
        compose.onNodeWithTag("atlas-details-people").performScrollTo()
        compose.onNodeWithText("Ada").assertExists()
        compose.onNodeWithText("Bram").assertExists()
        capturePair("parity-24-viewer-details-people") {
            compose.onAllNodesWithTag("atlas-details-people").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Ada").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- search-person ------------------------------------------------------

    @Test fun personSearchPair() {
        val named = listOf(
            SearchPerson("p1", "Susanne de Vries"), SearchPerson("p2", "Sam"),
            SearchPerson("p3", "Sophie"), SearchPerson("p4", "Daan"))
        val photos = (1L..12L).map { id ->
            MediaRecord(id, "content://synthetic/$id", "IMG_$id.jpg", "DCIM/Camera/", 100,
                millis(2018 + (id % 3).toInt(), 1 + (id % 12).toInt()), false)
        }
        val uris = mapOf(
            "p1" to photos.filter { it.id % 2 == 0L }.map { it.uri }.toSet(),
            "p2" to setOf(photos[0].uri))
        var shown = -1
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window, dark = true) }
        compose.setContent {
            if (!composing) return@setContent
            var filters by remember { mutableStateOf(LocalSearchFacets()) }
            val results = remember(filters) {
                if (filters == LocalSearchFacets()) photos
                else searchWithFacets(photos, emptyList(), filters,
                    mediaVolumeOf = { it.volumeName }, nativePersonUris = uris)
            }
            shown = results.size
            AtlasGalleryTheme(dark = dark) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {}) }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)) {
                        LocalSearchControls(
                            filters = filters, people = emptyList(),
                            history = EphemeralSearchHistory(emptyList(), "synthetic-grant"),
                            onlyTheseFacesAvailable = false,
                            onFiltersChange = { filters = it },
                            onSubmittedQuery = {}, onClearHistory = {},
                            currentPermissionEpoch = "synthetic-grant",
                            onSearchByFace = {}, onFindSimilar = {},
                            namedPeople = named,
                            personAvatar = { DrawnFaceTile(it.key.hashCode().toLong()) },
                            hasResults = filters != LocalSearchFacets() && results.isNotEmpty()
                        )
                        LazyVerticalGrid(GridCells.Fixed(4),
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.testTag("parity-search-results")) {
                            items(results, key = { it.id }) { DrawnYearTile(it) }
                        }
                    }
                }
            }
        }
        compose.typeText(hasTestTag("atlas-search-field"), "sus", KeyboardAfter.Hidden)
        compose.waitForNode(hasTestTag("atlas-search-person-suggestion"), useUnmergedTree = true)
        compose.tap(hasTestTag("atlas-search-person-suggestion"))
        compose.waitForNode(hasTestTag("atlas-search-person-chip"), useUnmergedTree = true)
        // Susanne is in the six even-numbered invented photos.
        compose.awaitThat("six photos of Susanne") { shown == 6 }
        capturePair("parity-24-search-person") {
            shown == 6 && compose.onAllNodes(hasTestTag("atlas-search-person-chip"),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- shared -------------------------------------------------------------

    /**
     * Dark first, then the same composition in light: flips the ATLAS theme
     * and the window's bar icons together, as MainActivity does when the phone
     * setting changes. [stillShown] must hold in both themes before saving.
     */
    private fun capturePair(prefix: String, stillShown: () -> Boolean) {
        for (isDark in listOf(true, false)) {
            compose.activityRule.scenario.onActivity {
                applyAtlasSystemBars(it.window, dark = isDark)
            }
            dark = isDark
            // Rule 6: a theme flip changes the window; wait for it, not a time.
            compose.waitForIdle()
            compose.awaitWindowSettled()
            assertTrue("$prefix: subject not shown in ${mode(isDark)}", stillShown())
            saveUiEvidence("$prefix-${mode(isDark)}.png")
        }
    }

    private fun mode(isDark: Boolean) = if (isDark) "dark" else "light"

    private fun millis(year: Int, month: Int) = Calendar.getInstance(TimeZone.getDefault()).apply {
        clear(); set(year, month - 1, 15, 12, 0, 0)
    }.timeInMillis

    /** Six invented face drawings in MediaStore, one a day; deleted in [cleanUp]. */
    private fun insertSyntheticFaces(): Pair<List<FaceVisibleMedia>, List<MediaRecord>> {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
        val now = System.currentTimeMillis()
        val media = mutableListOf<FaceVisibleMedia>()
        val rows = mutableListOf<MediaRecord>()
        for (i in 1..6) {
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val name = "IMG_PARITY_PEOPLE_FIXTURE_$i.png"
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-Parity-People/")
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
            // The face catalogue only admits canonical /<volume>/images/media/<id> URIs.
            val canonical = ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri("external_primary"), id).toString()
            specs[canonical] = spec
            media += FaceVisibleMedia(
                uri = canonical, volumeName = "external_primary", isVideo = false, id = id,
                providerVersion = "synthetic-provider-1", generationModified = 10 + id,
                sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
                widthPixels = 480, heightPixels = 480, orientationDegrees = 0)
            rows += MediaRecord(id, canonical, name, "Pictures/ATLAS-Parity-People/",
                1000 + id, taken, false, volumeName = "external_primary")
        }
        return media to rows
    }

    /** An app-private, throwaway FaceIndexStore with the bundled YuNet + SFace models. */
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
        val opened = FaceIndexStore(context).also { store = it }
        return PeopleDiscoveryHost(
            store = opened,
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
}

/** A flat, invented face crop for the Details People row. Never a real face. */
private fun drawnFaceBitmap(skin: Int, hair: Int) = Bitmap.createBitmap(
    160, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(0xFF5C6B7A.toInt())
    paint.color = hair; canvas.drawCircle(80f, 70f, 58f, paint)
    paint.color = skin; canvas.drawOval(34f, 40f, 126f, 150f, paint)
    paint.color = 0xFF222222.toInt()
    canvas.drawCircle(62f, 88f, 6f, paint); canvas.drawCircle(98f, 88f, 6f, paint)
    paint.color = 0xFFB0605A.toInt(); canvas.drawOval(66f, 118f, 94f, 128f, paint)
}.asImageBitmap()

/** An invented photo tile: a coloured scene with its year. Never a real photo. */
@Composable
private fun DrawnYearTile(record: MediaRecord) {
    val year = Calendar.getInstance().apply { timeInMillis = record.takenAtMillis }.get(Calendar.YEAR)
    val tones = listOf(0xFF4E7D6B, 0xFF3F6E8C, 0xFF8A6D3B, 0xFF6D5C8A, 0xFF7A4E5A)
    Box(Modifier.aspectRatio(1f).background(Color(tones[(record.id % tones.size).toInt()])),
        contentAlignment = Alignment.BottomStart) {
        Text("$year", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(4.dp))
    }
}

/** A flat, invented face for the search avatar: backdrop, skin oval, two eyes. */
@Composable
private fun DrawnFaceTile(seed: Long) {
    val backdrops = listOf(0xFF5C6B73, 0xFF8D6E63, 0xFF4E7D6B, 0xFF6D5C8A)
    val skins = listOf(0xFFF1C9A5, 0xFFD9A47E, 0xFFB57B55, 0xFF8D5B3C)
    val i = Math.floorMod(seed, 4L).toInt()
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(backdrops[i]))
        val w = size.width
        val h = size.height
        drawOval(Color(skins[i]), androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.2f),
            androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.56f))
        drawOval(Color(skins[i]), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.78f),
            androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.5f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.44f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.44f))
    }
}
