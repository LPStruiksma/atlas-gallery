package nl.atlas.gallery.featureintents

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.saveUiEvidence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * BENCH-2 (#573) Phase A1: another app opens one item in ATLAS.
 *
 * Every journey starts MainActivity with an explicit synthetic intent, the way
 * Android delivers one once the Phase B manifest filters exist. Media is one
 * generated PNG (MediaStore row owned by the test) or a generated PNG behind
 * a non-MediaStore `content://` provider; no real photos.
 */
@RunWith(AndroidJUnit4::class)
class ExternalIntentJourneyTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var mediaUri: Uri? = null
    private var providerFile: File? = null
    private var nightBefore: String? = null

    @Before fun fixtures() {
        nightBefore = shell("cmd uimode night").substringAfter(":").trim()
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_573_FICTIONAL.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-573-Test/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        resolver.openOutputStream(uri)!!.use { syntheticPng(AndroidColor.rgb(38, 97, 156)).let { b ->
            check(b.compress(Bitmap.CompressFormat.PNG, 100, it)); b.recycle() } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        mediaUri = uri
    }

    @After fun cleanUp() {
        mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        providerFile?.delete()
        nightBefore?.takeIf { it in setOf("yes", "no", "auto") }?.let { shell("cmd uimode night $it") }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test fun openWithShowsOneReadOnlyItemAndBackFinishes() {
        for (dark in listOf(false, true)) {
            night(dark)
            val scenario = launch(ExternalIntentParser.ACTION_VIEW, mediaUri.toString(), "image/png")
            awaitViewer()
            assertReadOnlyChrome(secure = false)
            capture("external-573-view-${if (dark) "dark" else "light"}")
            compose.onNodeWithTag("atlas-back").performClick()
            awaitDestroyed(scenario)
        }
    }

    @Test fun nonMediaStoreProviderItemOpensWithoutAType() {
        night(false)
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "atlas-573-chat-share.png").also { providerFile = it }
        file.outputStream().use { out -> syntheticPng(AndroidColor.rgb(196, 120, 52)).let { b ->
            check(b.compress(Bitmap.CompressFormat.PNG, 100, out)); b.recycle() } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val scenario = launch(ExternalIntentParser.ACTION_VIEW, uri.toString(), null)
        awaitViewer()
        assertReadOnlyChrome(secure = false)
        capture("external-573-provider-light")
        scenario.close()
    }

    @Test fun cameraReviewIsSingleItemAndNotShownWhenLocked() {
        night(false)
        val scenario = launch(ExternalIntentParser.ACTION_REVIEW, mediaUri.toString(), "image/png")
        awaitViewer()
        assertReadOnlyChrome(secure = false)
        // Back returns to the camera: the Activity finishes, nothing else opens.
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitDestroyed(scenario)

        // Ordinary review must not inherit the lock-screen privilege.
        lockScreen()
        val started = startUnwaited(ExternalIntentParser.ACTION_REVIEW, mediaUri.toString(), "image/png")
        SystemClock.sleep(3_000)
        assertFalse("ACTION_REVIEW must stay behind the keyguard", resumedMainActivity())
        started.finishAll()
    }

    @Test fun secureReviewShowsOnlyTheCaptureOverTheLockScreen() {
        for (dark in listOf(false, true)) {
            night(dark)
            lockScreen()
            val scenario = launch(ExternalIntentParser.ACTION_REVIEW_SECURE, mediaUri.toString(), "image/png")
            awaitViewer()
            assertTrue("keyguard must still be up", keyguard().isKeyguardLocked)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            compose.onNodeWithTag(ExternalViewerTags.ROOT).assertIsDisplayed()
            assertReadOnlyChrome(secure = true)
            capture("external-573-secure-locked-${if (dark) "dark" else "light"}")
            // Rotation / recreation keeps the same restricted single item.
            scenario.recreate()
            awaitViewer()
            assertReadOnlyChrome(secure = true)
            compose.onNodeWithTag("atlas-back").performClick()
            awaitDestroyed(scenario)
            shell("wm dismiss-keyguard")
        }
    }

    @Test fun unreadableItemsShowCantOpenAndNeverCrash() {
        val resolver = context.contentResolver
        val gone = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_573_GONE.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-573-Test/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        resolver.delete(gone, null, null)
        for (dark in listOf(false, true)) {
            night(dark)
            // A row whose access is gone (deleted after the caller's grant).
            val scenario = launch(ExternalIntentParser.ACTION_VIEW, gone.toString(), "image/png")
            awaitTag(ExternalViewerTags.ERROR)
            compose.onNodeWithText(EXTERNAL_CANT_OPEN).assertIsDisplayed()
            capture("external-573-error-${if (dark) "dark" else "light"}")
            compose.onNodeWithTag(ExternalViewerTags.ERROR_BACK).performClick()
            awaitDestroyed(scenario)
        }
        night(false)
        for ((action, uri, type) in listOf(
            Triple(ExternalIntentParser.ACTION_VIEW, "content://nl.atlas.gallery.test.no-such-provider/1", "image/png"),
            Triple(ExternalIntentParser.ACTION_REVIEW_SECURE, "file:///sdcard/DCIM/fictional.jpg", "image/jpeg"),
            Triple(ExternalIntentParser.ACTION_VIEW, mediaUri.toString(), "application/pdf"),
            Triple(ExternalIntentParser.ACTION_REVIEW, null, "image/png")
        )) {
            val scenario = launch(action, uri, type)
            awaitTag(ExternalViewerTags.ERROR)
            compose.onNodeWithTag(ExternalViewerTags.ERROR_BACK).performClick()
            awaitDestroyed(scenario)
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun intent(action: String, uri: String?, type: String?) = Intent(action).apply {
        component = ComponentName(context, MainActivity::class.java)
        when {
            uri != null && type != null -> setDataAndType(Uri.parse(uri), type)
            uri != null -> data = Uri.parse(uri)
            type != null -> this.type = type
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun launch(action: String, uri: String?, type: String?): ActivityScenario<MainActivity> =
        ActivityScenario.launch(intent(action, uri, type))

    private class Unwaited(val run: () -> Unit) { fun finishAll() = run() }

    /** Starts without waiting for RESUMED (an Activity behind the keyguard never gets there). */
    private fun startUnwaited(action: String, uri: String?, type: String?): Unwaited {
        context.startActivity(intent(action, uri, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Unwaited {
            instrumentation.runOnMainSync {
                Stage.values().forEach { stage ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                        .filterIsInstance<MainActivity>().forEach { it.finish() }
                }
            }
        }
    }

    private fun resumedMainActivity(): Boolean {
        var resumed = false
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
        }
        return resumed
    }

    private fun awaitViewer() {
        awaitTag(ExternalViewerTags.ROOT)
        awaitTag("atlas-viewer-top-chrome")
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertReadOnlyChrome(secure: Boolean) {
        for (absent in listOf("atlas-viewer-favourite", "atlas-share-media", "atlas-viewer-trash",
                "atlas-edit-photo", "atlas-viewer-more", "atlas-viewer-find-similar",
                "atlas-viewer-find-person", "atlas-filmstrip", "atlas-nav-pill")) {
            assertNoTag(absent)
        }
        if (secure) assertNoTag("atlas-viewer-details-open")
        else compose.onNodeWithTag("atlas-viewer-details-open").assertIsDisplayed()
        compose.onNodeWithTag("atlas-back").assertIsDisplayed()
    }

    private fun assertNoTag(tag: String) {
        assertTrue("$tag must not be shown",
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag))
                .fetchSemanticsNodes().isEmpty())
    }

    private fun awaitDestroyed(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (scenario.state != Lifecycle.State.DESTROYED) {
            check(SystemClock.uptimeMillis() < deadline) { "Back must finish to the caller; state=${scenario.state}" }
            SystemClock.sleep(100)
        }
    }

    private fun keyguard() = context.getSystemService(KeyguardManager::class.java)

    private fun lockScreen() {
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(1_500)
        shell("input keyevent KEYCODE_WAKEUP")
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!keyguard().isKeyguardLocked) {
            check(SystemClock.uptimeMillis() < deadline) { "Emulator keyguard did not engage" }
            SystemClock.sleep(200)
        }
    }

    private fun night(dark: Boolean) {
        shell("cmd uimode night " + if (dark) "yes" else "no")
        SystemClock.sleep(500)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(700)
        compose.waitForIdle()
        saveUiEvidence("$name.png")
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    /** A plain invented picture: two colour bands and a white disc, 960x720. */
    private fun syntheticPng(base: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(base)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = AndroidColor.rgb(232, 226, 210)
        canvas.drawRect(0f, 480f, 960f, 720f, paint)
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(640f, 260f, 110f, paint)
        return bitmap
    }
}
