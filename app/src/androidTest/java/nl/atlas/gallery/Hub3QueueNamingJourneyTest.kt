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

import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextClearance
import nl.atlas.gallery.featurepeople.cluster.FaceMetric
import nl.atlas.gallery.featurepersistence.FaceEmbeddingVectorCodec
import nl.atlas.gallery.testkit.keyboardInsetPx
import nl.atlas.gallery.testkit.hideKeyboard
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.random.Random

/**
 * #171: actual scoped queue route over an app-private store of invented faces.
 * The store is kept alive until after the Activity rule has disposed the route.
 */
@RunWith(AndroidJUnit4::class)
class Hub3QueueNamingJourneyTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixtureLifetime = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    try { base.evaluate() } finally { finishFixture() }
                }
            }
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtureLifetime)
        .around(KeyboardRule(TestKeyboard.Fixed)).around(S24DisplayRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val dir = File(target.cacheDir, "hub3-queue-" + UUID.randomUUID())
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
        for (i in 1..8) {
            val spec = SyntheticFace.Spec(480, 480, 0.4f + 0.02f * i, 0.5f, 0.8f + 0.01f * i)
            val taken = now - i * 86_400_000L
            val uri = requireNotNull(target.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_HUB3_FIXTURE_$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-HUB3-Fixture/")
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
            rows += MediaRecord(id, canonical, "IMG_HUB3_FIXTURE_$i.png",
                "Pictures/ATLAS-HUB3-Fixture/", 1000 + id, taken, false,
                volumeName = "external_primary")
        }
        inventory = media
        records = rows
    }


    private var composing by mutableStateOf(true)

    @After fun detachRoutes() {
        composing = false
        compose.waitForIdle()
    }

    private fun finishFixture() {
        PeopleScanWorkRuntime.clear()
        try {
            if (::store.isInitialized) store.close()
        } finally {
            dir.deleteRecursively()
            inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
        }
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


    /** Find two invented directions inside the ask band, below auto-match
     * and unknown-group merging thresholds of the actual shipped policy.
     * The faces are synthetic drawings; their stored vectors are synthetic too.
     */
    private fun askingVectors(): Pair<ByteArray, ByteArray> {
        val random = Random(171)
        repeat(4000) {
            val rawA = FloatArray(128) { random.nextFloat() * 2f - 1f }
            val rawB = FloatArray(128) { rawA[it] * 0.45f +
                (random.nextFloat() * 2f - 1f) * 0.9f }
            val a = FaceEmbeddingVectorCodec.encode(rawA)
            val b = FaceEmbeddingVectorCodec.encode(rawB)
            val fa = FaceMetric.SFACE.apply(FaceEmbeddingVectorCodec.decode(a))
            val fb = FaceMetric.SFACE.apply(FaceEmbeddingVectorCodec.decode(b))
            val score = fa.vector.indices.sumOf { i ->
                fa.vector[i].toDouble() * fb.vector[i]
            } - (fa.crowd + fb.crowd) / 2.0
            if (score in 0.315..0.335 && fa.crowd < 0.2f && fb.crowd < 0.2f)
                return a to b
        }
        error("No deterministic synthetic pair in the calibrated ask band")
    }

    private fun askingHost(): PeopleDiscoveryHost {
        val initial = host()
        drain(initial, "gallery-1")
        val epoch = checkNotNull(initial.displayEpoch("gallery-1"))
        val scanned = checkNotNull(store.peopleReview(epoch))
        val members = scanned.clusters.unknownClusters.flatMap { it.members }
            .sortedBy { it.localHex }
        assertTrue("at least six synthetic faces", members.size >= 6)
        val (a, b) = askingVectors()
        val groups = listOf(members.take(3), members.drop(3))
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            groups.forEachIndexed { index, group ->
                for (face in group) {
                    db.execSQL("UPDATE embeddings SET vector=? WHERE face_uid=?",
                        arrayOf(if (index == 0) a else b, face.localHex))
                    db.execSQL("UPDATE cluster_members SET anchor_uid=? WHERE face_uid=?",
                        arrayOf(group.first().localHex, face.localHex))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // Reload from disk, not the previous store's immutable vector cache.
        PeopleScanWorkRuntime.clear()
        store.close()
        val restarted = host()
        drain(restarted, "gallery-1")
        val live = checkNotNull(restarted.reviewState(checkNotNull(restarted.displayEpoch("gallery-1"))))
        assertEquals("two groups, exactly one question", 1,
            nl.atlas.gallery.featurepeopleui.samePersonQueue(live).open)
        return restarted
    }

    private fun enabled(tag: String) {
        compose.awaitThat("$tag enabled", WAIT_MS) {
            compose.exists(hasTestTag(tag) and isEnabled(), useUnmergedTree = true)
        }
    }

    private fun typeName(value: String, replace: Boolean = false) {
        compose.waitForNode(hasTestTag("atlas-person-name-field"), WAIT_MS, useUnmergedTree = true)
        if (replace) compose.onNodeWithTag("atlas-person-name-field").performTextClearance()
        compose.typeText(hasTestTag("atlas-person-name-field"), value, KeyboardAfter.Shown)
        enabled("atlas-person-name-save")
        assertTrue("keyboard inset is real", compose.keyboardInsetPx() > 0)
        compose.onNodeWithTag("atlas-person-name-field").assertIsDisplayed()
        compose.onNodeWithTag("atlas-person-name-save").assertIsDisplayed()
        compose.onAllNodesWithTag("atlas-same-queue-name-source", useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                assertTrue("source portrait has visible bounds", node.boundsInRoot.height > 0f)
            }
    }

    @Test fun nameRenameConfirmCancelMergeAndUndoOnBothSides() {
        val host = askingHost()
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        var dark by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            if (composing) AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    SamePersonQueueRoute(host, discovery.status?.people) {}
                }
            }
        }
        fun readyQueue() {
            enabled("atlas-same-queue-subject-name")
            enabled("atlas-same-queue-other-name")
            compose.waitForNode(hasText("1 open"), WAIT_MS)
        }
        fun saveAndWait(name: String) {
            compose.tap(hasTestTag("atlas-person-name-save"))
            compose.awaitThat("saved name", WAIT_MS) {
                host.liveReview(epoch)?.names?.values?.contains(name) == true &&
                    !compose.exists(hasTestTag("atlas-person-name-dialog"), useUnmergedTree = true)
            }
            readyQueue()
        }
        fun undo() {
            enabled("atlas-same-queue-undo")
            compose.tap(hasTestTag("atlas-same-queue-undo"))
            readyQueue()
        }
        readyQueue()
        compose.awaitScreenStill("hub3-queue-dark")
        saveUiEvidence("hub3-queue-dark.png")
        compose.tap(hasTestTag("atlas-same-queue-subject-name"))
        typeName("Synthetic first side")
        compose.awaitScreenStill("hub3-name-keyboard-dark")
        saveUiEvidence("hub3-name-keyboard-dark.png")
        saveAndWait("Synthetic first side")
        undo()
        assertTrue("Undo removed the name", host.liveReview(epoch)!!.names.isEmpty())

        compose.runOnIdle { dark = false }
        readyQueue()
        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        val longName = "Synthetic Alexandra Van der Example Long Family Name"
        typeName(longName)
        compose.awaitScreenStill("hub3-name-keyboard-light")
        saveUiEvidence("hub3-name-keyboard-light.png")
        saveAndWait(longName)
        compose.awaitScreenStill("hub3-queue-named-light")
        saveUiEvidence("hub3-queue-named-light.png")
        compose.tap(hasTestTag("atlas-same-queue-subject-name"))
        typeName("Synthetic Renamed", replace = true)
        saveAndWait("Synthetic Renamed")
        assertEquals(listOf("Synthetic Renamed"), host.liveReview(epoch)!!.names.values.toList())

        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic Renamed")
        compose.hideKeyboard(showExpected = true)
        compose.tap(hasTestTag("atlas-person-name-save"))
        enabled("atlas-person-merge-confirm")
        compose.onNodeWithTag("atlas-same-queue-merge-face-0", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-merge-face-1", useUnmergedTree = true)
            .assertIsDisplayed()
        val before = host.liveReview(epoch)!!.clusters.reviews.confirmed.size
        compose.awaitScreenStill("hub3-confirm-light")
        saveUiEvidence("hub3-confirm-light.png")
        compose.tap(hasText("Cancel"))
        compose.waitForNode(hasTestTag("atlas-person-name-field"), WAIT_MS, useUnmergedTree = true)
        assertEquals("cancel did not merge", before, host.liveReview(epoch)!!.clusters.reviews.confirmed.size)
        enabled("atlas-person-name-save")
        compose.hideKeyboard(showExpected = true)
        compose.tap(hasTestTag("atlas-person-name-save"))
        enabled("atlas-person-merge-confirm")
        compose.runOnIdle { dark = true }
        compose.awaitScreenStill("hub3-confirm-dark")
        saveUiEvidence("hub3-confirm-dark.png")
        compose.tap(hasTestTag("atlas-person-merge-confirm"))
        compose.waitForNode(hasText("No open questions."), WAIT_MS)
        assertTrue("explicit confirmation assigned the remaining group",
            host.liveReview(epoch)!!.clusters.reviews.confirmed.size > before)
        compose.awaitScreenStill("hub3-merged-dark")
        saveUiEvidence("hub3-merged-dark.png")
        undo()
        assertEquals(before, host.liveReview(epoch)!!.clusters.reviews.confirmed.size)
        assertEquals(listOf("Synthetic Renamed"), host.liveReview(epoch)!!.names.values.toList())
        compose.hideKeyboard()
    }

    private companion object { const val WAIT_MS = 30_000L }
}
