package nl.atlas.gallery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsSelected
import nl.atlas.gallery.testkit.captureWhole
import nl.atlas.gallery.testkit.advanceVirtualTime
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.platform.testTag
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

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextClearance
import nl.atlas.gallery.featurepeople.cluster.FaceMetric
import nl.atlas.gallery.featurepersistence.FaceEmbeddingVectorCodec
import nl.atlas.gallery.testkit.keyboardInsetPx
import nl.atlas.gallery.testkit.assertAboveKeyboard
import nl.atlas.gallery.testkit.hideKeyboard
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.random.Random
import org.junit.rules.TestName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import nl.atlas.gallery.featurepeopleui.samePersonQueue
import nl.atlas.gallery.featurepeopleui.samePersonSkipFingerprint
import nl.atlas.gallery.featurepeopleui.SamePersonSkipPreferences
import nl.atlas.gallery.featurepeopleui.applyCurrentPeopleReviewCommand
import nl.atlas.gallery.featurepeopleui.samePersonQueueMixedSides
import nl.atlas.gallery.featurepeopleui.samePersonQueueAnswer
import nl.atlas.gallery.featurepeopleui.PersonNaming
import nl.atlas.gallery.featurepeopleui.personNaming
import nl.atlas.gallery.testkit.withAnimatorScale

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

    @get:Rule val testName = TestName()
    @Volatile private var blockedCropUris: Set<String> = emptySet()
    private val cropEntered = CountDownLatch(1)
    private val releaseCrop = CountDownLatch(1)

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
        val count = if (testName.methodName in setOf(
            "mixedSurvivesNamingUndo", "skipSurvivesNamingUndo",
            "staleStoreGenerationRefusesInFlightNaming", "hostConfirmationLoadingWithReducedMotion"
        )) 12 else 8
        for (i in 1..count) {
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
        releaseCrop.countDown()
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
            faceCropDecoder = FaceCropDecoder { uri, box ->
                if (uri in blockedCropUris) {
                    cropEntered.countDown()
                    check(releaseCrop.await(60, TimeUnit.SECONDS)) { "Synthetic crop gate timed out" }
                }
                MediaStoreFaceCropDecoder(target.contentResolver).decode(uri, box)
            }
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
    private fun correctedScore(a: ByteArray, b: ByteArray): Double {
        val fa = FaceMetric.SFACE.apply(FaceEmbeddingVectorCodec.decode(a))
        val fb = FaceMetric.SFACE.apply(FaceEmbeddingVectorCodec.decode(b))
        return fa.vector.indices.sumOf { i -> fa.vector[i].toDouble() * fb.vector[i] } -
            (fa.crowd + fb.crowd) / 2.0
    }

    private fun askingVectors(seed: Int = 171, excluded: List<ByteArray> = emptyList()):
        Pair<ByteArray, ByteArray> {
        val random = Random(seed)
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
            if (score in 0.315..0.335 && fa.crowd < 0.2f && fb.crowd < 0.2f &&
                excluded.all { correctedScore(a, it) < 0.10 && correctedScore(b, it) < 0.10 })
                return a to b
        }
        error("No deterministic synthetic pair in the calibrated ask band")
    }

    private fun askingHost(twoPairs: Boolean = false): PeopleDiscoveryHost {
        val initial = host()
        drain(initial, "gallery-1")
        val epoch = checkNotNull(initial.displayEpoch("gallery-1"))
        val scanned = checkNotNull(store.peopleReview(epoch))
        val members = scanned.clusters.unknownClusters.flatMap { it.members }
            .sortedBy { it.localHex }
        assertTrue("at least six synthetic faces", members.size >= 6)
        val (a, b) = askingVectors()
        val vectors = if (twoPairs) {
            assertTrue("at least twelve synthetic faces for two disjoint pairs", members.size >= 12)
            val (c, d) = askingVectors(172, listOf(a, b))
            listOf(a, b, c, d)
        } else listOf(a, b)
        val groups = if (twoPairs) listOf(
            members.take(3), members.drop(3).take(3), members.drop(6).take(3), members.drop(9)
        ) else listOf(members.take(3), members.drop(3))
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            groups.forEachIndexed { index, group ->
                for (face in group) {
                    db.execSQL("UPDATE embeddings SET vector=? WHERE face_uid=?",
                        arrayOf(vectors[index], face.localHex))
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
        val queue = samePersonQueue(live)
        assertEquals("exact ask topology", if (twoPairs) 2 else 1, queue.open)
        if (twoPairs) {
            assertEquals("four independent unknown groups", 4, live.clusters.unknownClusters.size)
            val sides = queue.items.flatMap { listOf(
                (it.subject as PersonPageTarget.Unnamed).cluster, it.other) }
            assertEquals("no group shared between the two questions", 4, sides.toSet().size)
        }
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
        val sources = compose.onAllNodesWithTag("atlas-same-queue-name-source", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("at least one source portrait", sources.isNotEmpty())
        val field = compose.onNodeWithTag("atlas-person-name-field", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("atlas-person-name-footer", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        sources.forEach { node ->
            assertTrue("source portrait not clipped", node.boundsInRoot.height >=
                node.boundsInRoot.width - 1f && node.boundsInRoot.height > 0f)
            assertTrue("source above field", node.boundsInRoot.bottom <= field.top)
        }
        assertTrue("field above footer", field.bottom <= footer.top)
        assertTrue("footer has visible bounds", footer.height > 0f)
        compose.assertAboveKeyboard(hasTestTag("atlas-same-queue-name-sources"))
        compose.assertAboveKeyboard(hasTestTag("atlas-person-name-field"))
        compose.assertAboveKeyboard(hasTestTag("atlas-person-name-footer"))
    }

    @Test fun nameRenameConfirmCancelMergeAndUndoOnBothSides() {
        val host = askingHost()
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        var dark by mutableStateOf(true)
        var enlarged by mutableStateOf(false)
        compose.activityRule.scenario.onActivity { applyAtlasSystemBars(it.window) }
        compose.setContent {
            if (composing) AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = DeepBg,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides
                        Density(density.density, if (enlarged) 1.3f else density.fontScale)) {
                        SamePersonQueueRoute(host, discovery.status?.people) {}
                    }
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
        // Saving an unchanged name is refused by the real naming reducer.
        enabled("atlas-person-name-save")
        val generationBeforeRefusal = host.liveReview(epoch)!!.sourceGeneration
        compose.tap(hasTestTag("atlas-person-name-save"))
        compose.waitForNode(hasText("Couldn’t save name. Try again."), WAIT_MS)
        assertEquals(generationBeforeRefusal, host.liveReview(epoch)!!.sourceGeneration)
        assertEquals(listOf(longName), host.liveReview(epoch)!!.names.values.toList())
        compose.awaitScreenStill("hub3-host-refused")
        saveUiEvidence("hub3-host-refused.png")
        compose.onNodeWithTag("atlas-person-name-field").performTextClearance()
        compose.typeText(hasTestTag("atlas-person-name-field"), "Synthetic Renamed", KeyboardAfter.Shown)
        compose.tap(hasText("Retry"))
        compose.awaitThat("retry committed renamed group", WAIT_MS) {
            host.liveReview(epoch)?.names?.values?.contains("Synthetic Renamed") == true &&
                !compose.exists(hasTestTag("atlas-person-name-dialog"), useUnmergedTree = true)
        }
        readyQueue()
        assertEquals(listOf("Synthetic Renamed"), host.liveReview(epoch)!!.names.values.toList())

        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic Ren")
        compose.tap(hasTestTag("atlas-person-name-suggestion"))
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
        compose.onNodeWithTag("atlas-person-name-field", useUnmergedTree = true)
            .assert(androidx.compose.ui.test.hasText("Synthetic Ren"))
        enabled("atlas-person-name-save")
        compose.tap(hasTestTag("atlas-person-name-suggestion"))
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
        // Measure the actual host editor against the fixed IME in both themes
        // and at enlarged font, not just a component with simulated results.
        compose.runOnIdle { enlarged = true; dark = false }
        readyQueue()
        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic enlarged light")
        compose.awaitScreenStill("hub3-host-enlarged-light")
        saveUiEvidence("hub3-host-enlarged-light.png")
        compose.tap(hasText("Cancel"))
        readyQueue()
        compose.runOnIdle { dark = true }
        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic enlarged dark")
        compose.awaitScreenStill("hub3-host-enlarged-dark")
        saveUiEvidence("hub3-host-enlarged-dark.png")
        compose.tap(hasText("Cancel"))
        readyQueue()
        compose.runOnIdle { enlarged = false }
        // Actual host revocation must hide the old portrait and typed editor.
        readyQueue()
        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic revoked")
        host.revoke()
        compose.waitForNode(hasText("Group changed"), WAIT_MS)
        assertTrue("revoked portrait removed",
            !compose.exists(hasTestTag("atlas-same-queue-name-source"), useUnmergedTree = true))
        assertTrue("revoked input removed",
            !compose.exists(hasTestTag("atlas-person-name-field"), useUnmergedTree = true))
        compose.hideKeyboard()
        compose.awaitScreenStill("hub3-revoked")
        saveUiEvidence("hub3-revoked.png")
        compose.tap(hasText("Return"))
    }

    @Test fun persistenceRefusalRollsBackAndChangedGenerationClosesEditor() {
        val host = askingHost()
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        compose.setContent {
            if (composing) AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    SamePersonQueueRoute(host, discovery.status?.people) {}
                }
            }
        }
        enabled("atlas-same-queue-subject-name")
        val before = checkNotNull(host.liveReview(epoch))
        val storedBefore = checkNotNull(store.peopleReview(epoch))
        compose.tap(hasTestTag("atlas-same-queue-subject-name"))
        typeName("Synthetic refusal")
        // Only the test-owned SQLite fixture has this trigger. Abort inside the
        // actual persistence transaction, after the confirmed-face delta.
        store.writableDatabase.execSQL(
            "CREATE TRIGGER hub3_refuse_name BEFORE INSERT ON people_names " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic name refusal'); END")
        try {
            compose.tap(hasTestTag("atlas-person-name-save"))
            compose.waitForNode(hasText("Couldn’t save name. Try again."), WAIT_MS)
            val refused = checkNotNull(store.peopleReview(epoch))
            assertEquals(storedBefore.generation, refused.generation)
            assertEquals(storedBefore.names, refused.names)
            assertEquals(storedBefore.clusters.reviews.confirmed, refused.clusters.reviews.confirmed)
            compose.awaitScreenStill("hub3-persistence-refused")
            saveUiEvidence("hub3-persistence-refused.png")
        } finally {
            store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS hub3_refuse_name")
        }
        // Hold the test database write lock so the real host's retry remains
        // in flight long enough to exercise the disabled controls.
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            compose.tap(hasText("Retry"))
            compose.waitForNode(hasText("Saving name…"), WAIT_MS)
            val save = compose.onNodeWithTag("atlas-person-name-save")
            save.assertIsNotEnabled()
            compose.onNodeWithText("Cancel").assertIsNotEnabled()
            save.performTouchInput { click() }
            compose.onNodeWithTag("atlas-person-name-save").assertIsNotEnabled()
            compose.awaitScreenStill("hub3-host-busy")
            saveUiEvidence("hub3-host-busy.png")
        } finally {
            db.endTransaction()
        }
        compose.awaitThat("persisted retry", WAIT_MS) {
            host.liveReview(epoch)?.names?.values?.contains("Synthetic refusal") == true &&
                !compose.exists(hasTestTag("atlas-person-name-dialog"), useUnmergedTree = true)
        }
        val committed = checkNotNull(host.liveReview(epoch))
        assertEquals("one acknowledged write after retry", 1, committed.undoHistory.size)
        assertEquals(listOf("Synthetic refusal"), committed.names.values.toList())
        enabled("atlas-same-queue-other-name")
        compose.tap(hasTestTag("atlas-same-queue-other-name"))
        typeName("Synthetic stale draft")
        val current = checkNotNull(host.liveReview(epoch))
        // Independent real host writer changes the generation while the
        // editor is open. The captured request must not mutate that new pair.
        val question = nl.atlas.gallery.featurepeopleui.samePersonQueue(current).items.single()
        val action = nl.atlas.gallery.featurepeopleui.samePersonQueueAnswer(question, same = false)
        assertTrue(host.review(PeopleClusterReviewCommand(current, action)))
        val after = checkNotNull(host.liveReview(epoch))
        assertTrue(after.sourceGeneration != before.sourceGeneration)
        compose.waitForNode(hasText("Group changed"), WAIT_MS)
        assertTrue(!compose.exists(hasTestTag("atlas-person-name-save"), useUnmergedTree = true))
        assertTrue(!compose.exists(hasTestTag("atlas-same-queue-name-source"), useUnmergedTree = true))
        compose.hideKeyboard()
        compose.awaitScreenStill("hub3-generation-stale")
        saveUiEvidence("hub3-generation-stale.png")
        compose.tap(hasText("Return"))
        compose.waitForNode(hasText("No open questions."), WAIT_MS)
        val final = checkNotNull(store.peopleReview(epoch))
        assertEquals(after.names, final.names)
        assertEquals(after.clusters.reviews.confirmed, final.clusters.reviews.confirmed)
        assertTrue("stale draft never persisted", "Synthetic stale draft" !in final.names.values)
    }


    private fun showHost(host: PeopleDiscoveryHost) {
        compose.setContent {
            if (composing) AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    val discovery = rememberPeopleDiscoveryOf(host, "gallery-1", inventory) { it }
                    SamePersonQueueRoute(host, discovery.status?.people) {}
                }
            }
        }
    }

    @Test fun mixedSurvivesNamingUndo() = priorDecisionSurvivesNamingUndo(mixed = true)
    @Test fun skipSurvivesNamingUndo() = priorDecisionSurvivesNamingUndo(mixed = false)

    private fun priorDecisionSurvivesNamingUndo(mixed: Boolean) {
        val host = askingHost(twoPairs = true)
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        val scope = checkNotNull(host.stableSamePersonSkipScope(epoch))
        val prefs = SamePersonSkipPreferences.forApp(target)
        val oldSkips = prefs.read(scope)
        try {
            val initial = checkNotNull(host.liveReview(epoch))
            val (first, second) = samePersonQueue(initial).items
            val firstToken = checkNotNull(samePersonSkipFingerprint(initial, first, scope))
            val secondToken = checkNotNull(samePersonSkipFingerprint(initial, second, scope))
            assertTrue(firstToken != secondToken)
            assertTrue(firstToken !in oldSkips && secondToken !in oldSkips)
            val expectedFaces = initial.clusters.unknownClusters.single {
                it.id == (second.subject as PersonPageTarget.Unnamed).cluster
            }.members.toSet()
            showHost(host)
            compose.waitForNode(hasText("2 open"), WAIT_MS)
            enabled("atlas-same-queue-subject-name")
            if (mixed) {
                compose.tap(hasTestTag("atlas-same-queue-subject-mixed"))
                compose.tap(hasTestTag("atlas-same-queue-confirm-mixed"))
                compose.awaitThat("first pair marked mixed", WAIT_MS) {
                    val live = host.liveReview(epoch)
                    live != null && live.sourceGeneration != initial.sourceGeneration &&
                        samePersonQueue(live).items.map { it.key } == listOf(second.key)
                }
                compose.waitForNode(hasText("1 open"), WAIT_MS)
            } else {
                compose.tap(hasTestTag("atlas-same-queue-skip"))
                compose.awaitThat("first fingerprint saved", WAIT_MS) {
                    prefs.read(scope) == oldSkips + firstToken
                }
            }
            enabled("atlas-same-queue-subject-name")
            val prior = checkNotNull(store.peopleReview(epoch))
            val priorLive = checkNotNull(host.liveReview(epoch))
            val priorSkips = prefs.read(scope)
            if (mixed) assertTrue(prior.clusters.reviews.mixedGroups.isNotEmpty())
            else assertEquals(initial.clusters.reviews, prior.clusters.reviews)
            compose.tap(hasTestTag("atlas-same-queue-subject-name"))
            typeName("Synthetic other pair")
            compose.tap(hasTestTag("atlas-person-name-save"))
            compose.awaitThat("only second pair named", WAIT_MS) {
                host.liveReview(epoch)?.names?.values?.contains("Synthetic other pair") == true &&
                    !compose.exists(hasTestTag("atlas-person-name-dialog"), useUnmergedTree = true)
            }
            val named = checkNotNull(store.peopleReview(epoch))
            val namedLive = checkNotNull(host.liveReview(epoch))
            assertTrue(named.generation != prior.generation)
            assertEquals(expectedFaces, named.clusters.reviews.confirmed.keys -
                prior.clusters.reviews.confirmed.keys)
            assertEquals(prior.clusters.reviews.mixedGroups, named.clusters.reviews.mixedGroups)
            assertEquals(priorSkips, prefs.read(scope))
            assertEquals(priorLive.undoHistory.size + 1, namedLive.undoHistory.size)
            enabled("atlas-same-queue-undo")
            compose.tap(hasTestTag("atlas-same-queue-undo"))
            compose.awaitThat("scoped naming undone", WAIT_MS) {
                val live = host.liveReview(epoch)
                live != null && live.names == prior.names &&
                    live.sourceGeneration != namedLive.sourceGeneration
            }
            enabled("atlas-same-queue-subject-name")
            val undone = checkNotNull(store.peopleReview(epoch))
            val undoneLive = checkNotNull(host.liveReview(epoch))
            assertTrue(undone.generation != named.generation)
            assertEquals(prior.names, undone.names)
            assertEquals(prior.clusters.reviews, undone.clusters.reviews)
            assertEquals(priorLive.undoHistory.size, undoneLive.undoHistory.size)
            assertEquals(priorSkips, prefs.read(scope))
            assertEquals(secondToken, samePersonSkipFingerprint(undoneLive, second, scope))
            assertEquals(firstToken, samePersonSkipFingerprint(undoneLive, first, scope))
            compose.hideKeyboard()
            val evidence = if (mixed) "hub3-prior-mixed-undo" else "hub3-prior-skip-undo"
            compose.awaitScreenStill(evidence)
            saveUiEvidence("$evidence.png")
        } finally {
            prefs.clear(scope)
            oldSkips.forEach { prefs.add(scope, it) }
        }
    }

    @Test fun staleStoreGenerationRefusesInFlightNaming() {
        val host = askingHost(twoPairs = true)
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        val initial = checkNotNull(host.liveReview(epoch))
        val (_, second) = samePersonQueue(initial).items
        val persisted = checkNotNull(store.peopleReview(epoch))
        val external = checkNotNull(applyCurrentPeopleReviewCommand(initial,
            PeopleClusterReviewCommand(initial, samePersonQueueAnswer(second, same = false))))
        showHost(host)
        enabled("atlas-same-queue-subject-name")
        compose.tap(hasTestTag("atlas-same-queue-subject-name"))
        typeName("Synthetic stale transaction")
        val db = store.writableDatabase
        db.beginTransaction()
        val externalGeneration: String
        try {
            compose.tap(hasTestTag("atlas-person-name-save"))
            // Hold the actual store boundary before its generation check.
            compose.awaitThat("naming worker reached persistence", WAIT_MS) {
                Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.name == "atlas-same-person" &&
                        stack.any { it.methodName == "commitPeopleReview" &&
                            it.className.endsWith("FaceIndexStore") }
                }
            }
            externalGeneration = checkNotNull(store.commitPeopleReview(epoch, persisted.generation,
                external.clusters, external.names, external.hidden))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        compose.waitForNode(hasText("Group changed"), WAIT_MS)
        val final = checkNotNull(store.peopleReview(epoch))
        assertEquals(externalGeneration, final.generation)
        assertEquals(external.names, final.names)
        assertEquals(external.clusters.reviews, final.clusters.reviews)
        assertTrue("stale write never adds a name", "Synthetic stale transaction" !in final.names.values)
        compose.tap(hasText("Return"))
        enabled("atlas-same-queue-subject-name")
        val refreshed = checkNotNull(host.liveReview(epoch))
        assertTrue("external store generation invalidates old Undo journal", refreshed.undoHistory.isEmpty())
        assertTrue(!compose.exists(hasTestTag("atlas-same-queue-undo"), useUnmergedTree = true))
        compose.hideKeyboard()
        compose.awaitScreenStill("hub3-commit-stale")
        saveUiEvidence("hub3-commit-stale.png")
    }

    @Test fun hostConfirmationLoadingWithReducedMotion() = withAnimatorScale("0") {
        hostConfirmationLoading(reduced = true)
    }

    @Test fun hostConfirmationLoadingWithMotion() = withAnimatorScale("1") {
        hostConfirmationLoading(reduced = false)
    }

    private fun hostConfirmationLoading(reduced: Boolean) {
        val mode = if (reduced) "reduced" else "motion"
        val host = askingHost(twoPairs = true)
        val epoch = checkNotNull(host.displayEpoch("gallery-1"))
        val initial = checkNotNull(host.liveReview(epoch))
        val (first, second) = samePersonQueue(initial).items
        // Keep a named candidate outside the remaining pair and its prefetch.
        assertTrue(host.review(PeopleClusterReviewCommand(initial,
            checkNotNull(samePersonQueueMixedSides(first, subject = false, other = true)))))
        val marked = checkNotNull(host.liveReview(epoch))
        val name = "Synthetic separate candidate"
        val action = (personNaming(marked, first.subject, name) as PersonNaming.Apply).action
        assertTrue(host.review(PeopleClusterReviewCommand(marked, action)))
        val named = checkNotNull(host.liveReview(epoch))
        assertEquals(listOf(second.key), samePersonQueue(named).items.map { it.key })
        val person = named.names.entries.single { it.value == name }.key
        blockedCropUris = named.clusters.reviews.confirmed.filterValues { it == person }.keys
            .map { checkNotNull(host.identityFaceSource(epoch, it.localHex)).first }.toSet()
        assertTrue(blockedCropUris.isNotEmpty())
        try {
            showHost(host)
            enabled("atlas-same-queue-subject-name")
            compose.tap(hasTestTag("atlas-same-queue-subject-name"))
            typeName("Synthetic separate")
            compose.tap(hasTestTag("atlas-person-name-suggestion"))
            assertTrue("real candidate crop requested", cropEntered.await(20, TimeUnit.SECONDS))
            compose.waitForNode(hasTestTag("atlas-same-queue-merge-image-0"), WAIT_MS,
                useUnmergedTree = true)
            compose.onNodeWithTag("atlas-same-queue-merge-image-0", useUnmergedTree = true).assertIsDisplayed()
            assertTrue("candidate must still be a fallback",
                !compose.exists(hasTestTag("atlas-same-queue-merge-image-1"), useUnmergedTree = true))
            compose.onNodeWithTag("atlas-person-merge-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("atlas-person-name-progress").assertIsDisplayed()
            assertEquals(if (reduced) 0f else 1f,
                testShell("settings get global animator_duration_scale").trim().toFloat(), 0f)
            val previousAutoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            try {
                fun pixels(): List<androidx.compose.ui.graphics.Color> {
                    val map = compose.onNodeWithTag("atlas-person-name-progress")
                        .captureWhole().toPixelMap()
                    return (0 until map.height).flatMap { y ->
                        (0 until map.width).map { x -> map[x, y] }
                    }
                }
                compose.advanceVirtualTime(160)
                val before = pixels()
                saveUiEvidence("hub3-host-confirm-loading-$mode.png")
                compose.advanceVirtualTime(480)
                val after = pixels()
                assertEquals("only the normal-motion sphere moves", reduced, before == after)
                if (!reduced) saveUiEvidence("hub3-host-confirm-loading-motion-later.png")
            } finally {
                compose.mainClock.autoAdvance = previousAutoAdvance
            }
            assertEquals(named.names, checkNotNull(store.peopleReview(epoch)).names)
        } finally {
            releaseCrop.countDown()
        }
        enabled("atlas-person-merge-confirm")
        compose.onNodeWithTag("atlas-same-queue-merge-image-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("atlas-same-queue-merge-image-1", useUnmergedTree = true).assertIsDisplayed()
        compose.awaitScreenStill("hub3-host-confirm-ready-$mode")
        saveUiEvidence("hub3-host-confirm-ready-$mode.png")
        compose.tap(hasText("Cancel"))
        enabled("atlas-person-name-save")
        assertEquals(named.clusters.reviews, checkNotNull(store.peopleReview(epoch)).clusters.reviews)
        compose.hideKeyboard()
    }

    /** Component contract with invented metadata, independent of detector throughput.
     * The other journey verifies real host-backed commits and revocation.
     */
    @Test fun enlargedSearchKeepsEditorAndExpandedListAcrossCancel() {
        val people = (1..68).map { i ->
            nl.atlas.gallery.featurepeopleui.NativePersonAlbum(
                nl.atlas.gallery.featurepeople.cluster.PersonKey("synthetic-$i"),
                "Synthetic Person " + i.toString().padStart(2, '0'),
                emptyList(), emptyList())
        }
        var query by mutableStateOf("")
        var expanded by mutableStateOf(false)
        var candidate by mutableStateOf<nl.atlas.gallery.featurepeopleui.NativePersonAlbum?>(null)
        var busy by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var failure by mutableStateOf<String?>(null)
        var retries = 0
        var writes = 0
        compose.setContent {
            if (composing) AtlasGalleryTheme(dark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    val picked = candidate
                    if (picked == null) {
                        PersonNameDialog(initial = query,
                            suggestions = { typed -> people.filter {
                                typed.isNotBlank() && it.name.contains(typed, ignoreCase = true)
                            } },
                            suggestionPortrait = { PersonPortraitFallback() },
                            onDismiss = {}, onSave = { writes++ },
                            sourcePreview = { compact ->
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(if (compact) 64.dp else 72.dp)
                                        .testTag("atlas-same-queue-name-sources")) {
                                    androidx.compose.foundation.layout.Box(
                                        Modifier.fillMaxSize().testTag("atlas-same-queue-name-source")) {
                                        PersonPortraitFallback()
                                    }
                                }
                            },
                            expandedMatches = expanded,
                            onExpandedChange = { expanded = it },
                            onQueryChange = { query = it },
                            onPick = { candidate = it },
                            busy = busy, loading = loading, error = failure,
                            onRetry = { retries++; failure = null })
                    } else PersonMergeConfirm(null, picked,
                        onDismiss = { candidate = null }, onMerge = { writes++ },
                        portraits = {
                            androidx.compose.foundation.layout.Row {
                                repeat(2) {
                                    androidx.compose.foundation.layout.Box(Modifier.size(112.dp)) {
                                        PersonPortraitFallback()
                                    }
                                }
                            }
                        }, loading = loading)
                }
            }
        }
        typeName("Synthetic")
        compose.tap(hasTestTag("atlas-person-name-more"))
        compose.onNodeWithTag("atlas-person-name-results", useUnmergedTree = true)
            .performScrollToIndex(67)
        compose.onNodeWithText("Synthetic Person 68").assertIsDisplayed()
        compose.awaitScreenStill("hub3-more-enlarged")
        saveUiEvidence("hub3-more-enlarged.png")
        compose.tap(hasText("Synthetic Person 68"))
        // Loading the candidate is a separate confirmation state, not a write.
        compose.runOnIdle { loading = true }
        compose.onNodeWithTag("atlas-person-merge-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-person-name-progress").assertIsDisplayed()
        compose.awaitScreenStill("hub3-confirm-loading-component")
        saveUiEvidence("hub3-confirm-loading-component.png")
        compose.runOnIdle { loading = false }
        compose.tap(hasText("Cancel"))
        enabled("atlas-person-name-save")
        assertEquals("Synthetic", query)
        assertTrue("expanded remains selected", expanded)
        assertEquals(0, writes)
        compose.onNodeWithTag("atlas-person-name-results", useUnmergedTree = true)
            .performScrollToIndex(67)
        compose.onNodeWithText("Synthetic Person 68").assertIsDisplayed()

        compose.runOnIdle { loading = true }
        compose.onNodeWithTag("atlas-person-name-save").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-person-name-progress").assertIsDisplayed()
        compose.awaitScreenStill("hub3-loading-enlarged")
        saveUiEvidence("hub3-loading-enlarged.png")
        compose.runOnIdle { loading = false; busy = true }
        compose.onNodeWithTag("atlas-person-name-save").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-person-name-progress").assertIsDisplayed()
        compose.awaitScreenStill("hub3-busy-enlarged")
        saveUiEvidence("hub3-busy-enlarged.png")
        compose.runOnIdle { busy = false; failure = "Couldn’t save name. Try again." }
        compose.onNodeWithTag("atlas-person-name-save").assertIsNotEnabled()
        compose.awaitScreenStill("hub3-error-enlarged")
        saveUiEvidence("hub3-error-enlarged.png")
        compose.tap(hasText("Retry"))
        enabled("atlas-person-name-save")
        assertEquals(1, retries)
        assertEquals(0, writes)
        compose.hideKeyboard()
    }

    private companion object { const val WAIT_MS = 30_000L }
}
