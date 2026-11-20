package nl.atlas.gallery.featureintents

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * BENCH-2 (#573) Phase A1: another app opens one item in ATLAS.
 *
 * Every journey starts MainActivity with an explicit synthetic intent, the way
 * Android delivers one once the Phase B manifest filters exist. Media is one
 * generated PNG (MediaStore row owned by the test) or a generated PNG behind a
 * non-MediaStore `content://` provider; no real photos.
 */
@RunWith(AndroidJUnit4::class)
class ExternalIntentJourneyTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = false)).around(compose)
    private val fixture = ExternalIntentFixture()

    @Before fun setUp() = fixture.insertMedia()
    @After fun tearDown() = fixture.cleanUp()

    @Test fun nonMediaStoreProviderItemOpensWithoutAType() {
        val uri = fixture.providerItem()
        val scenario = fixture.launch(ExternalIntentParser.ACTION_VIEW, uri.toString(), null)
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
        compose.capture("external-573-provider-light")
        scenario.close()
    }

    @Test fun cameraReviewIsSingleItemNeverShowsWhenLockedAndBackReturns() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_REVIEW, fixture.media, "image/png")
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
        // Ordinary review must not inherit the lock-screen privilege.
        scenario.onActivity { assertFalse(it.showWhenLockedApplied) }
        // Back returns to the camera: the Activity finishes, nothing else opens.
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.awaitDestroyed(scenario)
    }

    @Test fun showWhenLockedIsScopedToTheSecureEntry() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_REVIEW_SECURE, fixture.media, "image/png")
        compose.awaitViewer()
        scenario.onActivity { assertTrue(it.showWhenLockedApplied) }
        // A later ordinary intent delivered to the same Activity clears the privilege.
        fixture.deliverNewIntent(ExternalIntentParser.ACTION_VIEW, fixture.media, "image/png")
        compose.awaitThat("the secure flag to clear on the new intent") {
            var applied = true
            scenario.onActivity { applied = it.showWhenLockedApplied }
            !applied
        }
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
        // ActivityScenario does not track the onNewIntent pause/resume (it waits on
        // "PAUSED" forever in close()), so finish through the lifecycle monitor.
        fixture.finishMainActivities()
        compose.awaitThat("every MainActivity to be destroyed") { !fixture.anyMainActivityAlive() }
    }

    @Test fun unreadableIntentsShowCantOpenAndNeverCrash() {
        for ((action, uri, type) in listOf(
            Triple(ExternalIntentParser.ACTION_VIEW, "content://nl.atlas.gallery.test.no-such-provider/1", "image/png"),
            Triple(ExternalIntentParser.ACTION_REVIEW_SECURE, "file:///sdcard/DCIM/fictional.jpg", "image/jpeg"),
            Triple(ExternalIntentParser.ACTION_VIEW, fixture.media, "application/pdf"),
            Triple(ExternalIntentParser.ACTION_REVIEW, null, "image/png")
        )) {
            val scenario = fixture.launch(action, uri, type)
            compose.awaitTag(ExternalViewerTags.ERROR)
            compose.onNodeWithText(EXTERNAL_CANT_OPEN).assertIsDisplayed()
            compose.tap(hasTestTag(ExternalViewerTags.ERROR_BACK))
            compose.awaitDestroyed(scenario)
        }
    }
}

/** The three themed states, rendered once with the phone light and once dark. */
@RunWith(Parameterized::class)
class ExternalIntentEvidenceTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = dark)).around(compose)
    private val fixture = ExternalIntentFixture()
    private val theme get() = if (dark) "dark" else "light"

    @Before fun setUp() = fixture.insertMedia()
    @After fun tearDown() = fixture.cleanUp()

    @Test fun openWithShowsOneReadOnlyItemAndBackFinishes() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_VIEW, fixture.media, "image/png")
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = false)
        compose.capture("external-573-view-$theme")
        compose.tap(hasTestTag("atlas-back"))
        compose.awaitDestroyed(scenario)
    }

    @Test fun secureReviewShowsOnlyTheCapture() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_REVIEW_SECURE, fixture.media, "image/png")
        compose.awaitViewer()
        scenario.onActivity { assertTrue(it.showWhenLockedApplied) }
        compose.onNodeWithTag(ExternalViewerTags.ROOT).assertIsDisplayed()
        compose.assertReadOnlyChrome(secure = true)
        compose.capture("external-573-secure-$theme")
        // Rotation / recreation keeps the same restricted single item.
        scenario.recreate()
        compose.awaitWindowSettled()
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = true)
        scenario.onActivity { assertTrue(it.showWhenLockedApplied) }
        compose.tap(hasTestTag("atlas-back"))
        compose.awaitDestroyed(scenario)
    }

    @Test fun aGrantThatIsGoneShowsCantOpen() {
        val gone = fixture.deletedRow()
        val scenario = fixture.launch(ExternalIntentParser.ACTION_VIEW, gone, "image/png")
        compose.awaitTag(ExternalViewerTags.ERROR)
        compose.onNodeWithText(EXTERNAL_CANT_OPEN).assertIsDisplayed()
        compose.capture("external-573-error-$theme")
        compose.tap(hasTestTag(ExternalViewerTags.ERROR_BACK))
        compose.awaitDestroyed(scenario)
    }
}

// ---- shared helpers ----------------------------------------------------------------------

internal class ExternalIntentFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var mediaUri: Uri? = null
    private var providerFile: File? = null
    val media: String get() = requireNotNull(mediaUri).toString()

    fun insertMedia() {
        mediaUri = insertPng("ATLAS_573_FICTIONAL.png", AndroidColor.rgb(38, 97, 156))
    }

    /** A MediaStore row that existed and is gone, like a revoked or stale capture grant. */
    fun deletedRow(): String {
        val uri = insertPng("ATLAS_573_GONE.png", AndroidColor.rgb(90, 90, 90))
        context.contentResolver.delete(uri, null, null)
        return uri.toString()
    }

    /** A generated PNG behind a non-MediaStore FileProvider, like a chat app's share. */
    fun providerItem(): Uri {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "atlas-573-chat-share.png").also { providerFile = it }
        file.outputStream().use { out -> writePng(out, AndroidColor.rgb(196, 120, 52)) }
        return FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    }

    fun cleanUp() {
        finishMainActivities()
        mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        providerFile?.delete()
    }

    fun intent(action: String, uri: String?, type: String?) = Intent(action).apply {
        component = ComponentName(context, MainActivity::class.java)
        when {
            uri != null && type != null -> setDataAndType(Uri.parse(uri), type)
            uri != null -> data = Uri.parse(uri)
            type != null -> this.type = type
        }
        // No FLAG_GRANT_READ_URI_PERMISSION: the test and ATLAS share one uid, and
        // granting a deleted row would throw in startActivity before ATLAS runs.
    }

    fun launch(action: String, uri: String?, type: String?): ActivityScenario<MainActivity> =
        ActivityScenario.launch(intent(action, uri, type))

    /** Delivers [action] to the running MainActivity through onNewIntent (single top). */
    fun deliverNewIntent(action: String, uri: String?, type: String?) {
        context.startActivity(intent(action, uri, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun anyMainActivityAlive(): Boolean {
        var alive = false
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            alive = Stage.values().filter { it != Stage.DESTROYED }
                .any { stage -> monitor.getActivitiesInStage(stage).any { it is MainActivity } }
        }
        return alive
    }

    fun finishMainActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.forEach { stage ->
                monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
                    .forEach { if (!it.isFinishing) it.finish() }
            }
        }
    }

    private fun insertPng(name: String, colour: Int): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-573-Test/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        resolver.openOutputStream(uri)!!.use { writePng(it, colour) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A plain invented picture: a colour field, a pale band and a white disc, 960x720. */
    private fun writePng(out: java.io.OutputStream, base: Int) {
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(base)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = AndroidColor.rgb(232, 226, 210)
            canvas.drawRect(0f, 480f, 960f, 720f, paint)
            paint.color = AndroidColor.WHITE
            canvas.drawCircle(640f, 260f, 110f, paint)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        } finally {
            bitmap.recycle()
        }
    }
}

internal fun ComposeTestRule.awaitTag(tag: String) =
    awaitThat("node $tag") { exists(hasTestTag(tag)) }

internal fun ComposeTestRule.awaitViewer() {
    awaitTag(ExternalViewerTags.ROOT)
    awaitTag("atlas-viewer-top-chrome")
}

internal fun ComposeTestRule.assertReadOnlyChrome(secure: Boolean) {
    for (absent in listOf("atlas-viewer-favourite", "atlas-share-media", "atlas-viewer-trash",
            "atlas-edit-photo", "atlas-viewer-more", "atlas-viewer-find-similar",
            "atlas-viewer-find-person", "atlas-filmstrip", "atlas-nav-pill")) {
        assertFalse("$absent must not be shown", exists(hasTestTag(absent)))
    }
    if (secure) assertFalse("Details must not be offered", exists(hasTestTag("atlas-viewer-details-open")))
    else onNodeWithTag("atlas-viewer-details-open").assertIsDisplayed()
    onNodeWithTag("atlas-back").assertIsDisplayed()
}

internal fun ComposeTestRule.awaitDestroyed(scenario: ActivityScenario<MainActivity>) =
    awaitThat("Back to finish straight to the caller") { scenario.state == Lifecycle.State.DESTROYED }

internal fun ComposeTestRule.capture(name: String) {
    awaitScreenStill(name)
    saveUiEvidence("$name.png")
}
