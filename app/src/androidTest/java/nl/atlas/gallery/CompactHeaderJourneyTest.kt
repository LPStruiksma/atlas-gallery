package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #492 compact Samsung-style header in the real Activity, on invented PNGs
 * inserted by this test (emulator only, deleted afterwards). Screenshots go
 * to the emulator-only UI-evidence folder (UI_EVIDENCE_DEVICE_DIR) for the PR.
 */
@RunWith(AndroidJUnit4::class)
class CompactHeaderJourneyTest {
    private val fixtures = SyntheticHeaderFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()

    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    /** Plain landing screenshots; no header-shape assertion, so it also documents the old UI. */
    @Test fun photosAndAlbumsLandingScreenshots() {
        waitFor(hasTestTag("atlas-nav-photos"))
        waitFor(hasText("photos & videos", substring = true))
        compose.waitForIdle()
        saveUiEvidence("header-photos.png")
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(hasTestTag("atlas-header-more"))
        compose.waitForIdle()
        saveUiEvidence("header-albums.png")
    }

    /** Synthetic named folders prove a full three-column row and pin order. */
    @Test fun everyTopLevelHeaderIsSlimAndKeepsEveryAction() {
        waitFor(hasTestTag("atlas-nav-photos"))
        waitFor(hasText("photos & videos", substring = true))
        assertSlimHeader()

        // Sort is one icon; its menu marks the current order and offers Reshuffle for Random.
        openPopup("atlas-photos-sort", "atlas-media-sort-date_created")
        compose.onNodeWithTag("atlas-media-sort-date_created").assertIsSelected()
        compose.tap(hasTestTag("atlas-media-sort-random"))
        awaitClosed("atlas-photos-sort-sheet")
        // A new order is computed off Main and republishes the Photos header
        // when it lands; a menu opened before that is closed with it. Wait for
        // "Ordering library…" to settle, as a user would see it (#530).
        waitForSortSettled()
        openPopup("atlas-photos-sort", "atlas-media-sort-random")
        compose.onNodeWithTag("atlas-media-sort-random").assertIsSelected()
        compose.onNodeWithTag("atlas-photos-reshuffle").assertExists()
        compose.tap(hasTestTag("atlas-media-sort-date_created"))
        awaitClosed("atlas-photos-sort-sheet")
        waitForSortSettled()

        // Former chip row + settings gear now live in the overflow menu.
        openPopup("atlas-header-more", "atlas-timeline-toggle")
        compose.onNodeWithTag("atlas-timeline-toggle").assertIsSelected()
        // #494: no "Original proportions" entry; pinching reaches that view.
        compose.onNodeWithTag("atlas-original-proportions").assertDoesNotExist()
        compose.onNodeWithTag("atlas-library-status").assertExists()
        compose.waitForIdle()
        saveUiEvidence("header-photos-menu.png")
        // Each menu item closes the menu; wait for that before opening it again,
        // so the next lookup cannot find the closing menu's item.
        compose.tap(hasTestTag("atlas-timeline-toggle"))
        awaitClosed("atlas-timeline-toggle")
        openPopup("atlas-header-more", "atlas-timeline-toggle")
        compose.onNodeWithTag("atlas-timeline-toggle").assertIsNotSelected()
        compose.tap(hasTestTag("atlas-timeline-toggle"))
        awaitClosed("atlas-timeline-toggle")
        openPopup("atlas-header-more", "atlas-timeline-toggle")
        compose.onNodeWithTag("atlas-timeline-toggle").assertIsSelected()
        compose.tap(hasTestTag("atlas-library-status"))

        // Settings gets a Back arrow in the same slim bar.
        waitFor(hasTestTag("atlas-header-title") and hasText("Settings"))
        assertSlimHeader()
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText("Pictures"))

        // Albums: slim header, an opened album shows its name with Back to All albums.
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(hasTestTag("atlas-header-more"))
        assertSlimHeader()
        waitFor(hasTestTag("atlas-album-card") and hasText("ATLAS-Header-Fixture", substring = true))
        compose.onNode(hasTestTag("atlas-album-card") and
            hasText("ATLAS-Header-Fixture", substring = true)).performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText("ATLAS-Header-Fixture"))
        assertSlimHeader()
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(hasTestTag("atlas-header-more"))

        // #644: Search is one field with its own ⋮ and no title bar (Samsung).
        compose.onNodeWithTag("atlas-nav-search").performClick()
        waitFor(hasTestTag("atlas-search-field"))
        compose.onNodeWithTag("atlas-header").assertDoesNotExist()
        openPopup("atlas-search-overflow", "atlas-library-status")
        compose.tap(hasTestTag("atlas-library-status"))
        waitFor(hasTestTag("atlas-header-title") and hasText("Settings"))
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(hasTestTag("atlas-search-field"))
    }

    private fun assertSlimHeader() {
        compose.waitForIdle()
        val height = compose.onNodeWithTag("atlas-header").getUnclippedBoundsInRoot().height
        assertTrue("Header is $height tall; #492 wants at most 64dp", height <= 64.dp)
    }

    /** Taps [opener] and waits until [firstItem] of the window it opens exists (rule 5). */
    private fun openPopup(opener: String, firstItem: String) {
        compose.tap(hasTestTag(opener))
        compose.awaitThat("$firstItem in the window opened by $opener") {
            compose.exists(hasTestTag(firstItem))
        }
    }

    private fun awaitClosed(tag: String) =
        compose.awaitThat("$tag gone (its window closed)") { !compose.exists(hasTestTag(tag)) }

    private fun waitForSortSettled() =
        waitFor(hasText("photos & videos", substring = true))

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}

/**
 * Saves a synthetic-UI screenshot where the CI script pulls it. Gradle
 * uninstalls the test APK afterwards, so app-private files would vanish.
 */
/**
 * #577: the MediaStore name of a UI-evidence screenshot. The camera-style
 * prefix dates it 2001-01-01 through the app's filename hint
 * ([resolveMediaDate]), because MediaStore keeps no DATE_TAKEN for a PNG
 * without EXIF. Without it every screenshot dated "now" (DATE_MODIFIED) and
 * sorted ahead of the test fixtures, pushing them out of the first rows.
 * scripts/ci_ui_evidence.sh pulls the prefixed name. Since #703 the file
 * is no longer in MediaStore, so the date hint only keeps the name stable.
 */
internal const val UI_EVIDENCE_PREFIX = "IMG_20010101_000000_"

internal fun uiEvidenceDisplayName(filename: String): String = UI_EVIDENCE_PREFIX + filename

/**
 * [beforeLook], when given, runs before every look at the screen. A Compose
 * test passes a wait for the state it means to capture: Compose only
 * recomposes when the test clock advances, which this loop's real-time
 * polling never does on its own. #693 (inferred from run 44677974548): the
 * Month/Year grid passed its level check, then showed only the page ground
 * for the whole 10 s retry while the app kept drawing frames; a replan that
 * could not reach the screen without a Compose sync fits that.
 */
internal fun saveUiEvidence(filename: String, beforeLook: (() -> Unit)? = null) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext
    // Never publish obscured evidence. A slow CI emulator sometimes shows a
    // system "isn't responding" dialog for another process (Quickstep): press
    // its Wait button and look again; fail only if it keeps coming back.
    // #645: never publish a blank or wrong-size frame either. A capture taken
    // before the first draw is one flat colour; look again until it has
    // content, and fail rather than publish one that never does.
    val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
    var bitmap: Bitmap
    var looks = 0
    while (true) {
        if (beforeLook != null) {
            if (looks > 0) android.util.Log.i("ATLAS_EVIDENCE",
                "$filename look ${looks + 1}: waiting for the captured state again")
            beforeLook()
        }
        looks++
        // UiAutomation's Wait click may be asynchronous on a loaded emulator.
        // Never capture a frame through an ANR window, but allow the bounded
        // window to disappear before deciding the evidence is obstructed.
        val cleared = clearSystemAnrDialogs()
        if (visibleSystemAnrRoot() != null) {
            check(android.os.SystemClock.uptimeMillis() < deadline) {
                "System ANR dialog obstructs UI evidence $filename after $cleared " +
                    "recovery actions; ${describeFocus()}"
            }
            Thread.sleep(250L)
            continue
        }
        bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Screenshot acquisition failed"
        }
        val problem = uiEvidenceProblem(bitmap) ?: break
        bitmap.recycle()
        check(android.os.SystemClock.uptimeMillis() < deadline) {
            "UI evidence $filename $problem; ${describeFocus()}"
        }
        // #570: an idle app posts no new frame, so a buffer the loaded
        // emulator's GPU lost ("Failed to find ColorBuffer") stays on screen
        // as a flat window ground however long we wait, while the composition
        // behind it is complete. Ask the resumed window to draw again.
        redrawResumedWindows()
        Thread.sleep(250L)
    }
    val png = try {
        java.io.ByteArrayOutputStream().also {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Screenshot encoding failed" }
        }.toByteArray()
    } finally {
        bitmap.recycle()
    }
    writeUiEvidenceFile(uiEvidenceDisplayName(filename), png)
}

/** Invalidates every resumed activity's window so the next screenshot sees a fresh frame. */
private fun redrawResumedWindows() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .forEach { activity -> invalidateTree(activity.window.decorView) }
    }
}

private fun invalidateTree(view: android.view.View) {
    view.invalidate()
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) invalidateTree(view.getChildAt(i))
    }
}

/**
 * #703: where the CI script pulls evidence from. It is shell-owned scratch
 * space outside shared storage, so MediaStore never indexes a screenshot and
 * no later test finds one in its gallery. Gradle's uninstall leaves it alone.
 */
internal const val UI_EVIDENCE_DEVICE_DIR = "/data/local/tmp/atlas-ui-evidence"

/** Writes [bytes] through the shell user, which owns [UI_EVIDENCE_DEVICE_DIR]. */
internal fun writeUiEvidenceFile(name: String, bytes: ByteArray) {
    require(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$").matches(name)) { "Bad UI-evidence name $name" }
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
        throw IllegalStateException("UI evidence needs API 31+ (CI runs API 35)")
    }
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun drain(fd: android.os.ParcelFileDescriptor) =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    drain(automation.executeShellCommand("mkdir -p $UI_EVIDENCE_DEVICE_DIR"))
    val path = "$UI_EVIDENCE_DEVICE_DIR/$name"
    val (stdout, stdin) = automation.executeShellCommandRw("dd of=$path")
    android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
    drain(stdout) // dd has finished once its output closes
    val size = String(drain(automation.executeShellCommand("stat -c %s $path"))).trim()
    check(size == bytes.size.toString()) {
        "UI evidence $name: wrote ${bytes.size} bytes, device has '$size'"
    }
}

/** Six invented, locally drawn images in one folder; never real photos. */
internal class SyntheticHeaderFixtures(
    /** Pixel sizes per image; mixed shapes let the real-ratio view show its rows (#494). */
    private val sizes: List<Pair<Int, Int>> = List(6) { 120 to 90 },
    /** Folder per 1-based image step; more folders give more album cards (#506). */
    private val folderOf: (Int) -> String = { "ATLAS-Header-Fixture" },
    /**
     * File name per 1-based step and its taken time. The emulator's media
     * scanner can drop DATE_TAKEN on a PNG without EXIF; a camera-style name
     * carries the same date as ATLAS's filename hint (BRAND-L4 #657).
     */
    private val nameOf: (Int, Long) -> String = { step, _ -> "IMG_HEADER_FIXTURE_$step.png" },
    private val beforeLaunch: (android.content.Context) -> Unit = {}
) : TestRule {
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
                    beforeLaunch(target)
                    insertAll(target)
                    base.evaluate()
                } finally {
                    inserted.forEach { runCatching { target.contentResolver.delete(it, null, null) } }
                }
            }
        }

    private fun insertAll(context: android.content.Context) {
        val now = System.currentTimeMillis()
        sizes.forEachIndexed { i, (width, height) ->
            val step = i + 1
            val taken = now - step * 86_400_000L
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, nameOf(step, taken))
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${folderOf(step)}/")
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Could not create synthetic header fixture $step"
            }
            inserted += uri
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Distinct, fully opaque colour per step for any number of fixtures.
            bitmap.eraseColor(Color.HSVToColor(floatArrayOf((step * 47f) % 360f, 0.6f, 0.85f)))
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, taken)
            }, null, null)
        }
    }
}
