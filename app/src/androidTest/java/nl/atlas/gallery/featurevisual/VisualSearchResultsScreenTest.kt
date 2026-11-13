package nl.atlas.gallery.featurevisual

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featurevisual.search.StoreVisualVectorSource
import nl.atlas.gallery.featurevisual.search.VisualHit
import nl.atlas.gallery.featurevisual.search.VisualMatchBand
import nl.atlas.gallery.featurevisual.search.VisualPoseState
import nl.atlas.gallery.featurevisual.search.VisualQuery
import nl.atlas.gallery.featurevisual.search.VisualQueryEngine
import nl.atlas.gallery.featurevisual.search.VisualQueryResult
import nl.atlas.gallery.featurevisual.search.VisualQueryStatus
import nl.atlas.gallery.featurevisual.search.VisualReference
import nl.atlas.gallery.featurevisual.search.VisualSearchResultsScreen
import nl.atlas.gallery.featurevisual.search.VisualSearchUiState
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API35, VS-S2a (#468): the results screen's typed states with thumbnails
 * DRAWN IN CODE, and the store-backed vector source on a throwaway SQLite
 * file with synthetic keys and vectors. No real photos, no MediaStore.
 */
@RunWith(AndroidJUnit4::class)
class VisualSearchResultsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun media(n: Int, frame: Long? = null) =
        VisualMedia("content://synthetic/visual/$n", "generation:1", frame)

    /** A generated tile: a coloured square with a disc, different per index. */
    private fun generated(n: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((n * 47f) % 360f, 0.6f, 0.9f)))
        canvas.drawCircle(48f, 48f, 20f + n % 20, Paint().apply { color = Color.WHITE })
        return bitmap
    }

    private fun result(vararg hits: VisualHit, pose: VisualPoseState = VisualPoseState.OFF,
                       duplicates: Int = 0) =
        VisualQueryResult(VisualQueryStatus.OK, hits.toList(), duplicates, 100, false, false, pose)

    /** A band badge sits inside its clickable tile, whose semantics merge it: look in the unmerged tree. */
    private fun band(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test fun typedStatesAndResults() {
        var state by mutableStateOf<VisualSearchUiState>(VisualSearchUiState.Loading)
        var poseOn by mutableStateOf(false)
        val opened = ArrayList<VisualMedia>()
        val loaded = ArrayList<VisualMedia>()
        var back = 0
        compose.setContent {
            VisualSearchResultsScreen(
                state = state, poseEnabled = poseOn, onPoseToggle = { poseOn = it },
                onOpen = { opened += it }, onBack = { back++ },
                thumbnail = { m -> loaded += m; generated(loaded.size).asImageBitmap() })
        }
        compose.onNodeWithTag("atlas-visual-search-loading").assertExists()

        state = VisualSearchUiState.NotIndexed
        compose.onNodeWithTag("atlas-visual-search-not-indexed").assertExists()
        compose.onNodeWithTag("atlas-visual-search-loading").assertDoesNotExist()
        state = VisualSearchUiState.VersionMismatch
        compose.onNodeWithTag("atlas-visual-search-version-mismatch").assertExists()
        state = VisualSearchUiState.Revoked
        compose.onNodeWithTag("atlas-visual-search-revoked").assertExists()
        compose.onNodeWithTag("atlas-visual-search-grid").assertDoesNotExist()

        state = VisualSearchUiState.Results(result(duplicates = 2))
        compose.onNodeWithTag("atlas-visual-search-empty").assertExists()
        compose.onNodeWithTag("atlas-visual-search-duplicates").assertExists()
        compose.onNodeWithTag("atlas-visual-search-revoked").assertDoesNotExist()

        val strong = VisualHit(media(1), 0.82f, VisualMatchBand.STRONG)
        val related = VisualHit(media(2, 1500L), 0.61f, VisualMatchBand.RELATED)
        state = VisualSearchUiState.Results(result(strong, related))
        compose.onNodeWithTag("atlas-visual-search-grid").assertExists()
        band("atlas-visual-hit-0-strong").assertExists()
        band("atlas-visual-hit-1-related").assertExists()
        compose.waitUntil(5_000) { loaded.size >= 2 }
        compose.onNodeWithTag("atlas-visual-hit-1").performClick()
        compose.runOnIdle { assertEquals(listOf(related.media), opened) }

        compose.onNodeWithTag("atlas-visual-search-pose-toggle").assertIsOff().performClick()
        compose.runOnIdle { assertTrue(poseOn) }
        compose.onNodeWithTag("atlas-visual-search-pose-toggle").assertIsOn()
        compose.onNodeWithTag("atlas-visual-search-pose-running").assertExists()

        state = VisualSearchUiState.Results(result(strong, related, pose = VisualPoseState.NO_BODY))
        compose.onNodeWithTag("atlas-visual-search-pose-no-body").assertExists()
        compose.onNodeWithTag("atlas-visual-search-pose-running").assertDoesNotExist()
        state = VisualSearchUiState.Results(result(strong, related, pose = VisualPoseState.UNAVAILABLE))
        compose.onNodeWithTag("atlas-visual-search-pose-unavailable").assertExists()

        state = VisualSearchUiState.Results(result(related.copy(score = 0.74f, pose = 0.9f),
            strong.copy(score = 0.70f, pose = 0.1f), pose = VisualPoseState.APPLIED))
        compose.onNodeWithTag("atlas-visual-search-pose-no-body").assertDoesNotExist()
        band("atlas-visual-hit-0-related").assertExists()
        band("atlas-visual-hit-1-strong").assertExists()

        compose.onNodeWithTag("atlas-visual-search-back").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    // --- StoreVisualVectorSource on real SQLite ---

    private fun dbContext(file: File): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
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
    }

    @Test fun storeSourceReusesIndexedReferenceAndRanks() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "atlas-visual-query-" + UUID.randomUUID())
        check(dir.mkdirs())
        try {
            VisualIndexStore(dbContext(File(dir, "visual_query.db"))).use { store ->
                val model = "siglip-test:1"
                val epoch = "epoch-" + UUID.randomUUID()
                val image = media(1)
                val frame0 = media(1, 0L)
                val frame9 = media(1, 9000L)
                val other = media(2)
                val all = listOf(image, frame0, frame9, other)
                store.beginSnapshot(epoch)
                store.stageBatch(epoch, all)
                store.commitSnapshot(epoch, model, 4)
                val vectors = mapOf(
                    image to floatArrayOf(1f, 0f, 0f, 0f),
                    frame0 to floatArrayOf(0f, 1f, 0f, 0f),
                    frame9 to floatArrayOf(0.8f, 0.6f, 0f, 0f),
                    // Cosine 0.78 to the image: "related" under the VS-S2c bands.
                    other to floatArrayOf(0.78f, 0f, 0.6258f, 0f))
                val leases = store.claimAuthorized(epoch, model, all, all.size)
                assertTrue(store.completeBatch(leases.map { VisualVectorWrite(it, vectors.getValue(it.media)) }))

                val source = StoreVisualVectorSource(store, epoch, model)
                for ((m, v) in vectors) assertArrayEquals(m.toString(), v, source.lookup(m), 0.01f)
                assertNull(source.lookup(image.copy(revision = "generation:2")))
                assertNull(source.lookup(media(1, 1L)))
                assertNull(source.lookup(media(3)))

                var embeds = 0
                val result = VisualQueryEngine(source).search(
                    VisualQuery(model, VisualReference(image) { embeds++; null }))
                assertEquals(VisualQueryStatus.OK, result.status)
                assertEquals(0, embeds)
                assertTrue(result.reusedStoredVector)
                assertEquals(listOf(frame9, other), result.hits.map { it.media })
                assertEquals(4, result.scanned)

                val stale = VisualQueryEngine(StoreVisualVectorSource(store, epoch, "siglip-other:2"))
                    .search(VisualQuery("siglip-other:2", VisualReference(image) { floatArrayOf(1f, 0f, 0f, 0f) }))
                assertEquals(VisualQueryStatus.NOT_INDEXED, stale.status)

                assertTrue(source.isCurrent())
                store.invalidatePermissionScope()
                assertNull(source.lookup(image))
                // VS-S2c: a superseded or revoked epoch is no longer current.
                assertFalse(source.isCurrent())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
