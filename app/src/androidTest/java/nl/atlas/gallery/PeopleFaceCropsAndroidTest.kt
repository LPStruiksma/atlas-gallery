package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #438 on API35: review face crops and the self-closing person album, with
 * the bundled YuNet + SFace models, a real app-private FaceIndexStore and the
 * real review screen. Photos are [SyntheticFace] drawings keyed by invented
 * MediaStore rows; the crop decoder draws the same picture for an authorized
 * URI and crops it with [FaceCropGeometry], so no real photo is read.
 */
@RunWith(AndroidJUnit4::class)
class PeopleFaceCropsAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "people-face-crops-" + UUID.randomUUID())
    private lateinit var store: FaceIndexStore

    @Volatile private var granted = true
    private val cropDecodes = AtomicInteger()

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0
    )

    private val inventory = (1L..4L).map(::photo)
    private val records = inventory.map {
        MediaRecord(it.id, it.uri, "p${it.id}.jpg", "DCIM/Camera/", 64L, it.id * 1000L,
            false, volumeName = "external_primary")
    }

    private fun drawn(uri: String): Bitmap {
        val id = uri.substringAfterLast('/').toInt()
        return SyntheticFace.bitmap(SyntheticFace.Spec(480, 480, 0.4f + 0.02f * id, 0.5f,
            0.8f + 0.01f * id))
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
            photoGrantCurrent = { granted },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            readerFor = { directory ->
                FaceFrameReader { lease, _ ->
                    AndroidBitmapFaceFrame(drawn(directory.authorizedUri(lease.media)))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() = Unit
                override fun later() = Unit
                override fun cancel() = Unit
            },
            faceCropDecoder = { uri, box ->
                // Only ever called with a URI the live directory authorized.
                cropDecodes.incrementAndGet()
                val full = drawn(uri)
                val plan = FaceCropGeometry.plan(full.width, full.height, box)!!
                val scaled = Bitmap.createScaledBitmap(full, plan.decodeWidth,
                    plan.decodeHeight, true)
                Bitmap.createBitmap(scaled, plan.left, plan.top,
                    plan.right - plan.left, plan.bottom - plan.top)
            }
        )
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
    }

    private fun drain(host: PeopleDiscoveryHost) {
        host.start("gallery-7", inventory)
        var guard = 0
        while (runBlocking { runPeopleScanBatch(target) { false } }!!.stop !=
            FaceBatchStop.NO_PENDING && guard++ < 20) Unit
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(20_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $label. Screen:\n$tree", e)
        }
    }

    private fun crops() = compose.onAllNodesWithTag("atlas-face-crop", useUnmergedTree = true)
        .fetchSemanticsNodes().size

    @Test fun reviewShowsFaceCropsAndRevokeRemovesThem() {
        val host = host()
        drain(host)
        val epoch = host.displayEpoch("gallery-7")!!
        val page = host.reviewPage("gallery-7")!!
        val face = page.clusters!!.clusters.unknownClusters.first().members.first()

        // Host level: a bounded crop for a live face, cached on a second read.
        val crop = host.faceCrop(epoch, face.localHex)
        assertNotNull("a crop for a live face", crop)
        // Pixel rounding of the decode size and crop edges adds at most 2 px.
        assertTrue("crop ${crop!!.width}x${crop.height} within the target edge",
            maxOf(crop.width, crop.height) <= FaceCropGeometry.TARGET_EDGE + 2)
        val decodes = cropDecodes.get()
        assertNotNull("cached crop", host.faceCrop(epoch, face.localHex))
        assertEquals("second read is a cache hit", decodes, cropDecodes.get())
        assertNull("stale epoch", host.faceCrop("atlas-face-stale", face.localHex))

        var grant by mutableStateOf<String?>("gallery-7")
        compose.setContent { if (composing) PeopleReviewRoute(host, grant, onClose = {}) }
        waitFor("a face crop in review") { crops() > 0 }

        // Revoke: the host hands out no crop and the screen drops every one.
        granted = false
        host.onGalleryPublication(null) { inventory }
        grant = null
        waitFor("crops gone after revoke") { crops() == 0 }
        assertNull("no crop after revoke", host.faceCrop(epoch, face.localHex))
    }

    @Test fun undoOfTheOnlyNamingClosesTheOpenPersonAlbum() {
        val host = host()
        drain(host)
        val epoch = host.displayEpoch("gallery-7")!!
        val state = host.reviewPage("gallery-7")!!.clusters!!
        val group = state.clusters.unknownClusters.first()
        val person = PersonKey("native-" + group.id.anchor.localHex)
        assertTrue("naming committed", host.review(PeopleClusterReviewCommand(state,
            PeopleReviewAction.NameCluster(group.id, person, "Synthetic Ada"))))

        var album by mutableStateOf<NativePersonAlbumRequest?>(
            NativePersonAlbumRequest(person, "Synthetic Ada"))
        compose.setContent {
            if (!composing) return@setContent
            val discovery = rememberPeopleDiscoveryOf(host, "gallery-7", inventory) { it }
            val opened = album
            if (opened != null) {
                NativePersonAlbumRoute(host, "gallery-7", opened, discovery.status?.people,
                    records, rememberLazyGridState(), 1L,
                    onBack = { album = null }, onOpen = { _, _ -> })
            }
        }
        waitFor("album shows the person") {
            compose.onAllNodesWithTag("atlas-person-page")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertNotNull("album stays open while the person is named", album)

        // Undo the naming: Ada has no confirmed face left, the album closes itself.
        val live = host.liveReview(epoch)!!
        assertTrue("Undo committed", host.review(
            PeopleClusterReviewCommand(live, PeopleReviewAction.Undo)))
        waitFor("album closed after Undo") { album == null }
    }
}
