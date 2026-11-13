package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream

/**
 * E2b (#442) end to end in the real Activity: two byte-identical invented PNGs
 * and one different PNG. Albums → Photo DNA → identical group → open a copy in
 * the viewer → Back to Photo DNA → system Back to Albums. Nothing is trashed.
 * Fixtures are inserted by this app, emulator only, and deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaJourneyTest {
    private val fixtures = SyntheticDuplicateFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // Grant + insert BEFORE the Activity launches, delete after it finishes.
    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun albumsPhotoDnaEntryShowsIdenticalCopiesOpensViewerAndBackReturnsToAlbums() {
        // The gallery shell is hidden until the first scan for the grant publishes.
        waitFor(hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor(hasTestTag("atlas-albums-menu-photo-dna"))
        compose.onNodeWithTag("atlas-albums-menu-photo-dna").performClick()

        waitFor(hasTestTag("photo-dna-exact-group"))
        waitFor(hasTestTag("atlas-photo-dna-title") and hasText("Photo DNA"))
        waitFor(hasText(SyntheticDuplicateFixtures.COPY_A))
        waitFor(hasText(SyntheticDuplicateFixtures.COPY_B))
        // #657 audit: Photo DNA had no render in the parity audit.
        compose.waitForIdle()
        saveUiEvidence("audit-657-photo-dna.png")
        // Nothing is preselected for Trash.
        compose.onAllNodes(hasTestTag("photo-dna-final-review")).onFirst().assertIsNotEnabled()

        compose.onAllNodes(hasTestTag("atlas-photo-dna-thumb")).onFirst().performClick()
        waitFor(hasTestTag("atlas-photo-counter"))
        compose.onNodeWithTag("atlas-back").performClick()
        waitFor(hasTestTag("photo-dna-exact-group"))
        compose.onNodeWithTag("atlas-photo-counter").assertDoesNotExist()
        // #663: plain copy, rendered after the round trip through the viewer.
        waitFor(hasText("Duplicates · ", substring = true))
        check(compose.onAllNodes(hasText("None selected")).fetchSemanticsNodes().isEmpty())
        compose.waitForIdle()
        saveUiEvidence("photo-dna-663.png")
        saveUiEvidence("photo-dna-681.png")

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasTestTag("atlas-header-more"))
        compose.onNodeWithTag("atlas-photo-dna").assertDoesNotExist()
        compose.onNodeWithTag("atlas-header-more").assertExists()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/** Invented, locally drawn images only; never real photos. */
private class SyntheticDuplicateFixtures : TestRule {
    companion object {
        const val COPY_A = "ATLAS_DNA_COPY_A.png"
        const val COPY_B = "ATLAS_DNA_COPY_B.png"
        const val OTHER = "ATLAS_DNA_OTHER.png"
    }
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
                    val same = png(Color.rgb(30, 140, 90))
                    insert(target, COPY_A, same)
                    insert(target, COPY_B, same)
                    insert(target, OTHER, png(Color.rgb(200, 60, 20)))
                    base.evaluate()
                } finally {
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun insert(context: android.content.Context, name: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-PhotoDna-Fixture/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create synthetic Photo DNA fixture $name"
        }
        inserted += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }
}
