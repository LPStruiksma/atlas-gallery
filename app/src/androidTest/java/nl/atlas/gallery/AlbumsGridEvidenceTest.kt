package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Nine fictional folders; gradient images are drawn locally and removed after the journey. */
@RunWith(AndroidJUnit4::class)
class AlbumsGridEvidenceTest {
    private val fixture = AlbumGridFixture()
    private val compose = createAndroidComposeRule<MainActivity>()

    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixture).around(compose)

    @Test fun threeColumnsAndPinnedAlbumsScreenshot() {
        // The CI runner invokes this same fixture against the baseline and
        // candidate APKs under both system themes. Ordinary suite execution
        // still validates three-column geometry and the menu/sort journeys.
        val args = InstrumentationRegistry.getInstrumentation().arguments
        val revision = args.getString("atlasAlbumsRevision")
        val captureTheme = args.getString("atlasAlbumsTheme")
        assertTrue("Unrecognized Albums capture revision",
            revision == null || revision in setOf("before", "after"))
        assertTrue("Capture revision and theme must be paired",
            (revision == null && captureTheme == null) ||
                (revision != null && captureTheme in setOf("light", "dark")))
        waitForAlbumsStage("Albums navigation") {
            compose.onAllNodes(hasTestTag("atlas-nav-albums")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        // LazyVerticalGrid exposes only composed cards. The old two-column
        // baseline may expose three or four, while the candidate must expose
        // six; that viewport assertion remains strict for the proposed UI.
        waitForAlbumsStage("first three pinned Albums cards") {
            compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes().size >= 3
        }
        if (revision != "before") {
            waitForAlbumsStage("visible Albums grid cards (at least six)") {
                compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes().size >= 6
            }
        }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes()
        if (revision != "before") {
            assertTrue("Expected six visible synthetic albums", nodes.size >= 6)
        }
        val pinned = nodes.take(3).map { it.config.toString() }
        assertTrue(pinned.any { it.contains("Camera") })
        assertTrue(pinned.any { it.contains("Screenshots") })
        assertTrue(pinned.any { it.contains("Black and White") })
        // The previous mainline grid is proof of the BEFORE state and may
        // genuinely be two columns. Only the candidate must pass the geometry
        // check; never weaken the ordinary acceptance test.
        if (revision != "before") {
            val firstRow = nodes.take(3).map { it.boundsInRoot }
            assertTrue("Three pinned covers must share top row at S24 size",
                firstRow.maxOf { it.top } - firstRow.minOf { it.top } < 4f)
            assertTrue("Pinned covers must occupy three distinct left-to-right columns",
                firstRow[0].left < firstRow[1].left && firstRow[1].left < firstRow[2].left)
        }
        if (revision != null) {
            compose.activityRule.scenario.onActivity { activity ->
                val isDark = (activity.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                assertTrue("Albums screenshot must reflect requested $captureTheme system mode",
                    isDark == (captureTheme == "dark"))
            }
            compose.waitForIdle()
            saveUiEvidence("albums-$revision-$captureTheme.png")
            return
        }
        saveUiEvidence("albums-after-populated.png")
        // Stories and Photo DNA live only in the Albums overflow (spec §3).
        compose.onNodeWithTag("atlas-header-more").performClick()
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-albums-menu-stories")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("atlas-albums-menu-photo-dna")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("albums-menu.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-album-sort").performClick()
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-album-sort-custom")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("albums-sort.png")
    }
    private fun waitForAlbumsStage(stage: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(120_000L, condition)
        } catch (error: ComposeTimeoutException) {
            val nav = compose.onAllNodes(hasTestTag("atlas-nav-albums")).fetchSemanticsNodes().size
            val cards = compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes()
            val labels = cards.map { it.config.toString().take(160) }
            val inventory = fixture.inventoryDiagnostic()
            val detail = "Albums evidence stage=$stage; nav=$nav; visibleCards=${cards.size}; " +
                "cardSemantics=$labels; MediaStore=$inventory"
            Log.e("ATLAS_ALBUM_EVIDENCE", detail, error)
            throw AssertionError(detail, error)
        }
    }
}

private class AlbumGridFixture : TestRule {
    private val inserted = mutableListOf<Uri>()
    private var targetContext: android.content.Context? = null

    fun inventoryDiagnostic(): String {
        val target = targetContext ?: return "fixture not started"
        val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING)
        return runCatching {
            val found = mutableListOf<String>()
            target.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns, "display_name LIKE ?", arrayOf("ATLAS_FAKE_%"), null)?.use { cursor ->
                val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val folder = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val pending = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                while (cursor.moveToNext()) {
                    found += "${cursor.getString(name)}@${cursor.getString(folder)}" +
                        "(pending=${cursor.getInt(pending)})"
                }
            }
            "inserted=${inserted.size}; queried=${found.size}; entries=$found"
        }.getOrElse { "query failed: ${it.javaClass.simpleName}: ${it.message}" }
    }
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val target = instrumentation.targetContext
                targetContext = target
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it)
                }
                clearGalleryInventoryCacheForTest(target)
                try {
                    val paths = listOf("DCIM/Camera/", "Pictures/Screenshots/",
                        "Pictures/Black and White/", "Pictures/IMG 9/",
                        "Pictures/IMG 10/", "Pictures/Weekend/", "Pictures/Beach/",
                        "Pictures/Garden/", "DCIM/Holiday/")
                    // Two images in some folders so the counts under covers differ.
                    (paths + paths.take(4)).forEachIndexed { index, path ->
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_FAKE_${index}.png")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, path)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = requireNotNull(target.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                        inserted += uri
                        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
                        val hue = (index * 47f) % 360f
                        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
                        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.75f, 0.45f))
                        android.graphics.Canvas(bitmap).drawPaint(android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(0f, 0f, 480f, 480f,
                                top, bottom, android.graphics.Shader.TileMode.CLAMP)
                        })
                        try {
                            requireNotNull(target.contentResolver.openOutputStream(uri)).use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        target.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                    }
                    base.evaluate()
                } finally {
                    inserted.forEach {
                        runCatching { target.contentResolver.delete(it, null, null) }
                    }
                }
            }
        }
}
