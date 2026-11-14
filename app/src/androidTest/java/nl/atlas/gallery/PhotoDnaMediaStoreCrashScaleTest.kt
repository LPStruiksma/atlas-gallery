package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream

/**
 * #33: unlike PhotoDnaScaleAndroidTest (in-memory records), run against
 * 2,000 genuinely published MediaStore PNGs and the production Activity.
 * Covers Albums -> Photo DNA planning and People -> visual-index toggle.
 * The images are generated on an isolated emulator and deleted by URI.
 * This is a regression at 2k, NOT proof of behavior at the owner's 150k scale.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDnaMediaStoreCrashScaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtures = CrashScaleMediaStoreFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun twoThousandMediaStoreCopiesSurvivePhotoDnaOpenAndVisualIndexOn() {
        val crashBefore = LastCrashRecorder.read(context)
        val started = SystemClock.elapsedRealtime()
        waitFor("Albums nav") { exists("atlas-nav-albums") }
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor("Albums menu") { exists("atlas-header-more") }
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Photo DNA menu") { exists("atlas-albums-menu-photo-dna") }
        compose.onNodeWithTag("atlas-albums-menu-photo-dna").performClick()
        waitFor("Photo DNA route") { exists("atlas-photo-dna") }
        // The two byte-identical sets of 1,000 invented files must be hashed
        // from MediaStore, not merely supplied as in-memory CurationAssets.
        waitFor("1,000-copy Photo DNA group", 180_000L) {
            compose.onAllNodesWithText("1000 copies", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("photo-dna-list").assertExists()
        compose.onNodeWithTag("atlas-photo-dna-failed").assertDoesNotExist()
        scaleLog("photo-dna", started)

        compose.onNodeWithTag("atlas-photo-dna-back").performClick()
        waitFor("People nav") { exists("atlas-nav-people") }
        compose.onNodeWithTag("atlas-nav-people").performClick()
        waitFor("People home") { exists("atlas-people-home") }
        // Start the real opted-in face scope if it is off. Scan is not drained
        // here: this test checks the visual switch's initialization, not 2k
        // expensive ONNX embeddings or a charging-only background scan.
        if (compose.onAllNodesWithText("Discover faces")
                .fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("Discover faces")[0].performClick()
        } else {
            compose.onNodeWithTag("atlas-header-more").performClick()
            if (exists("atlas-people-start")) {
                compose.onNodeWithTag("atlas-people-start").performClick()
            } else {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
        }
        waitFor("People menu after opt-in") { exists("atlas-header-more") }
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("Library status") { exists("atlas-library-status") }
        compose.onNodeWithTag("atlas-library-status").performClick()
        waitFor("Visual index entry") { exists("atlas-people-visual-index") }
        compose.onNodeWithTag("atlas-people-visual-index").performClick()
        waitFor("Visual index switch") { exists("atlas-visual-index-switch") }
        waitFor("Visual index can enable", 120_000L) {
            runCatching {
                compose.onNodeWithTag("atlas-visual-index-switch").assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithTag("atlas-visual-index-switch").performClick()
        waitFor("Visual index enabled", 120_000L) {
            runCatching { compose.onNodeWithTag("atlas-visual-index-switch").assertIsOn() }.isSuccess
        }
        scaleLog("visual-index-on", started)
        // Leave the background stage off for subsequent tests on this shard.
        compose.onNodeWithTag("atlas-visual-index-switch").performClick()
        waitFor("Visual index disabled") {
            runCatching { compose.onNodeWithTag("atlas-visual-index-switch").assertIsOff() }.isSuccess
        }
        assertEquals("no new handled/fatal exception during the routes",
            crashBefore, LastCrashRecorder.read(context))
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(step: String, timeout: Long = 120_000L, done: () -> Boolean) {
        try {
            compose.waitUntil(timeout) { done() }
        } catch (e: Throwable) {
            throw AssertionError("Crash-scale step failed: $step; " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - fixtures.startedAt}", e)
        }
    }

    private fun scaleLog(phase: String, started: Long) {
        val heap = Runtime.getRuntime()
        val usedMiB = (heap.totalMemory() - heap.freeMemory()) / (1024 * 1024)
        // Count/heap/time ONLY. No URI, display name, image data, or raw trace.
        Log.i("ATLAS_SCALE", "crash33 phase=$phase synthetic_images=2000 " +
            "elapsed_ms=${SystemClock.elapsedRealtime() - started} heap_used_mib=$usedMiB")
    }
}

/** One disposable, emulator-only MediaStore collection; never a private gallery. */
private class CrashScaleMediaStoreFixtures : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inserted = ArrayList<Uri>(2_000)
    private val folder = "Pictures/ATLAS-CrashScale-33-${System.nanoTime()}/"
    var startedAt: Long = 0L
        private set

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val emulator = Build.HARDWARE.contains("ranchu") ||
                    Build.HARDWARE.contains("goldfish") ||
                    Build.FINGERPRINT.contains("generic")
                check(emulator) { "Crash-scale fixtures are emulator-only" }
                val choices = PeopleOptInMemory.preferences(context)
                val previousPeople = choices.load()
                val previousVisual = choices.loadVisual()
                listOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO).forEach {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
                }
                clearGalleryInventoryCacheForTest(context)
                startedAt = SystemClock.elapsedRealtime()
                try {
                    val a = png(Color.rgb(25, 85, 180))
                    val b = png(Color.rgb(180, 65, 40))
                    repeat(2_000) { index -> insert(index, if (index < 1_000) a else b) }
                    val count = context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                        arrayOf(folder), null)?.use { it.count }
                    assertEquals("exactly the synthetic MediaStore library was inserted", 2_000, count)
                    Log.i("ATLAS_SCALE", "crash33 fixture_ready synthetic_images=2000 " +
                        "insert_ms=${SystemClock.elapsedRealtime() - startedAt}")
                    base.evaluate()
                } finally {
                    // Restore original opt-in after the Activity rule finishes;
                    // the fixture never opts a personal library into scanning.
                    choices.saveVisual(previousVisual)
                    choices.save(previousPeople)
                    inserted.asReversed().forEach { uri ->
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                    clearGalleryInventoryCacheForTest(context)
                }
            }
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun insert(index: Int, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "ATLAS_CRASH_SCALE_${index.toString().padStart(4, '0')}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not insert synthetic fixture #$index"
        }
        inserted += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        assertTrue("fixture #$index must become visible", context.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null) > 0)
    }
}
