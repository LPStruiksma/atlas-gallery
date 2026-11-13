package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
        compose.waitUntil(60_000L) {
            compose.onAllNodes(hasTestTag("atlas-nav-albums")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.waitUntil(60_000L) {
            compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes().size >= 9
        }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(hasTestTag("atlas-album-card")).fetchSemanticsNodes()
        assertTrue("Expected six synthetic albums", nodes.size >= 6)
        val pinned = nodes.take(3).map { it.config.toString() }
        assertTrue(pinned.any { it.contains("Camera") })
        assertTrue(pinned.any { it.contains("Screenshots") })
        assertTrue(pinned.any { it.contains("Black and White") })
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
}

private class AlbumGridFixture : TestRule {
    private val inserted = mutableListOf<Uri>()
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val target = instrumentation.targetContext
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
