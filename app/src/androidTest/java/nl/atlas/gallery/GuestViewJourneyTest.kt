package nl.atlas.gallery

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.PixelCopy
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import nl.atlas.gallery.testkit.DeviceCredentialRule
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.featureguest.GuestAndroid
import nl.atlas.gallery.featureguest.GuestBoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * GUEST-1 (#589): the guest view journey on generated media only.
 *
 * Light: selection More entry, tap order, bounded paging, Back, a new intent,
 * recreation, landscape, the OS pinning prompt ACCEPTED, credential cancel and
 * success. Dark: the same entry with the pinning prompt DECLINED. Viewer: entry
 * from viewer More at 200% text, exactly one photo, return to that photo.
 *
 * Guest frames are FLAG_SECURE. FLAG_SECURE is asserted; what the OS capture
 * paths a test can reach (UiAutomation, screencap) return for such a frame is
 * logged and the screencap published, because test captures may include
 * secure layers. The guest UI itself is captured from the app's own window
 * with PixelCopy. Pinning and credential behaviour is logged under
 * ATLAS_GUEST_PROBE for the PR. If the OS pinning prompt cannot be pressed,
 * the light journey pins the task through the shell so the credential screen
 * is still probed while pinned (review note A).
 */
@RunWith(AndroidJUnit4::class)
class GuestViewLightJourneyTest : GuestViewSelectionJourney(dark = false, acceptPin = true)

@RunWith(AndroidJUnit4::class)
class GuestViewDarkJourneyTest : GuestViewSelectionJourney(dark = true, acceptPin = false)

private const val PROBE = "ATLAS_GUEST_PROBE"
private const val PIN = "1589"
private const val FOLDER = "ATLAS-Guest-589"

abstract class GuestViewSelectionJourney(private val dark: Boolean, private val acceptPin: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark))
        .around(DeviceCredentialRule(PIN))
        .around(GuestStoreRule())
        .around(SyntheticHeaderFixtures(sizes = List(4) { 1200 to 900 }, folderOf = { FOLDER }))
        .around(compose)
        .around(UnpinRule())

    private val theme get() = if (dark) "dark" else "light"

    @Test fun selectionEntryConfinesToChosenPhotosUntilDeviceCredential() {
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        val tile = SemanticsMatcher("visible timeline media") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-timeline-media-") == true
        }
        compose.awaitThat("four synthetic tiles", 30_000L) { compose.onAllNodes(tile).fetchSemanticsNodes().size >= 4 }
        val tags = compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
        // Tap order 0, 2, 1: guest view must page in exactly this order.
        compose.onNodeWithTag(tags[0]).performTouchInput { longClick() }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.tap(hasTestTag(tags[2]))
        compose.tap(hasTestTag(tags[1]))
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("3 selected"))
        compose.tap(hasTestTag("atlas-selection-more"))
        compose.waitForNode(hasTestTag("atlas-selection-guest-view"))
        compose.waitForIdle()
        saveUiEvidence("guest-589-$theme-a-entry.png")
        compose.tap(hasTestTag("atlas-selection-guest-view"))

        compose.waitForNode(hasTestTag("atlas-guest-root"))
        val expectedIds = tags.map { it.removePrefix("atlas-timeline-media-").toLong() }
            .let { listOf(it[0], it[2], it[1]) }
        val record = requireNotNull(store().activeRecord()) { "no live guest record" }
        assertEquals("frozen set in tap order", expectedIds, record.items.map { it.id })
        assertOwnerChromeGone()
        assertTrue("FLAG_SECURE on the guest window", secureFlag())
        awaitPage(0, 3)

        // The OS pinning prompt, if any: accept (light) or decline (dark).
        val pin = handlePinPrompt(acceptPin)
        Log.i(PROBE, "API ${android.os.Build.VERSION.SDK_INT} $theme pin: ${pin.description}")
        if (acceptPin) assertTrue("pinned for the credential probe: ${pin.description}",
            lockState() != ActivityManager.LOCK_TASK_MODE_NONE)
        observeOsCapture("guest-589-$theme-os-screencap.png")
        captureGuest("guest-589-$theme-c-first.png")

        // Swipes stay inside the frozen three; no wrap, no neighbours.
        repeat(4) {
            compose.onNodeWithTag("atlas-guest-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        awaitPage(2, 3)
        compose.onNodeWithTag("atlas-guest-page-3").assertDoesNotExist()
        captureGuest("guest-589-$theme-c-last.png")
        compose.tap(hasTestTag("atlas-guest-thumb-1"))
        awaitPage(1, 3)

        // Back is consumed silently; a new intent is not routed.
        shell("input keyevent 4")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.waitForNode(hasTestTag("atlas-guest-root"))
        compose.activityRule.scenario.onActivity {
            it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.awaitWindowSettled()
        compose.waitForNode(hasTestTag("atlas-guest-root"))
        assertOwnerChromeGone()

        // All externally declared routes delivered to the existing guest
        // Activity must leave its frozen set and owner confinement intact.
        if (dark) {
            for (action in listOf(Intent.ACTION_VIEW, "android.provider.action.REVIEW",
                    "android.provider.action.REVIEW_SECURE", Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK)) {
                compose.activityRule.scenario.onActivity { activity ->
                    activity.startActivity(Intent(activity, MainActivity::class.java)
                        .setAction(action).setDataAndType(android.net.Uri.parse(record.items.first().uri), "image/jpeg")
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                compose.awaitWindowSettled()
                compose.waitForNode(hasTestTag("atlas-guest-root"))
                assertOwnerChromeGone()
                assertTrue("secure guest window after $action", secureFlag())
                assertEquals(expectedIds, requireNotNull(store().activeRecord()).items.map { it.id })
            }
        }

        // Recreation (as on rotation) restores the same set at the same page.
        compose.activityRule.scenario.recreate()
        compose.awaitWindowSettled()
        compose.waitForNode(hasTestTag("atlas-guest-root"))
        awaitPage(1, 3)
        assertTrue("FLAG_SECURE after recreation", secureFlag())
        if (!dark) {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.awaitThat("landscape") { displayIsLandscape() }
            compose.awaitWindowSettled()
            compose.waitForNode(hasTestTag("atlas-guest-root"))
            awaitPage(1, 3)
            captureGuest("guest-589-light-c-landscape.png")
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            compose.awaitThat("portrait") { !displayIsLandscape() }
            compose.awaitWindowSettled()
            compose.waitForNode(hasTestTag("atlas-guest-root"))
        }

        // Exit → system confirmation → cancel keeps guest.
        compose.tap(hasTestTag("atlas-guest-exit"))
        val prompt = credentialScreen()
        Log.i(PROBE, "API ${android.os.Build.VERSION.SDK_INT} $theme credential screen while " +
            "lockTaskModeState=${lockState()}: $prompt")
        assertTrue("system credential screen opened: ${describeFocus()}", prompt.opened)
        // Back first hides the PIN keyboard, then cancels the confirmation.
        var backs = 0
        while (credentialOnTop() && backs < 4) {
            shell("input keyevent 4")
            backs++
            compose.observe(4_000L) { !credentialOnTop() }
        }
        Log.i(PROBE, "credential cancelled with $backs Back press(es)")
        compose.awaitCredentialClosed()
        compose.awaitWindowSettled()
        compose.waitForNode(hasTestTag("atlas-guest-root"))
        assertOwnerChromeGone()
        Log.i(PROBE, "after cancel lockTaskModeState=${lockState()}")
        assertTrue(GuestGuardCheck.guest(store().boot()))

        // Exit again → correct device PIN → the owner's gallery returns.
        compose.tap(hasTestTag("atlas-guest-exit"))
        assertTrue("credential screen again: ${describeFocus()}", credentialScreen().opened)
        shell("input text $PIN")
        shell("input keyevent 66")
        compose.awaitCredentialClosed()
        compose.awaitThat("guest view ended in the store") { store().boot() == GuestBoot.Owner }
        // Ending screen pinning on a device with a screen lock locks the device
        // (the system's "lock device when unpinning" default); unlock it again.
        val lockedByUnpin = compose.unlockIfLocked()
        Log.i(PROBE, "API ${android.os.Build.VERSION.SDK_INT} $theme exit: unpinning locked the device=" +
            "$lockedByUnpin, lockTaskModeState=${lockState()}")
        compose.awaitWindowSettled()
        compose.awaitThat("the owner's gallery after the PIN", 30_000L) {
            compose.exists(hasTestTag("atlas-nav-photos")) || compose.exists(hasTestTag("atlas-selection-count"))
        }
        compose.onNodeWithTag("atlas-guest-root").assertDoesNotExist()
        assertFalse("FLAG_SECURE cleared after exit", secureFlag())
        if (pin.byShell) {
            // A shell pin belongs to the shell, so the app may not end it; the
            // OS prompt's own pin (pin.byShell false) is ended by the app.
            Log.i(PROBE, "after exit (shell pin) lockTaskModeState=${lockState()}")
            shell("am task lock stop")
        }
        compose.awaitThat("unpinned after exit") { lockState() == ActivityManager.LOCK_TASK_MODE_NONE }
        Log.i(PROBE, "API ${android.os.Build.VERSION.SDK_INT} $theme exit ok; selection restored=" +
            compose.onAllNodes(hasTestTag("atlas-selection-count")).fetchSemanticsNodes().isNotEmpty())
    }

    private fun assertOwnerChromeGone() {
        for (tag in listOf("atlas-nav-photos", "atlas-selection-count", "atlas-selection-more",
                "atlas-viewer-more", "atlas-viewer-details-open")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    private fun awaitPage(page: Int, count: Int) {
        compose.waitForNode(hasTestTag("atlas-guest-page-$page") and
            hasContentDescription("Photo ${page + 1} of $count"))
        compose.awaitThat("guest page $page saved") { store().activeRecord()?.index == page }
    }

    private fun displayIsLandscape(): Boolean {
        var landscape = false
        compose.activityRule.scenario.onActivity {
            landscape = it.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        return landscape
    }

    private fun store() = GuestAndroid.store(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun secureFlag(): Boolean {
        var flags = 0
        compose.activityRule.scenario.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private class PinOutcome(val description: String, val byShell: Boolean)

    /**
     * Presses the OS pinning prompt's own button (accept or decline). When it
     * cannot be found or pressed and [accept] is set, pins the task through the
     * shell (the state the OS enters after "Got it") so probe A still runs.
     */
    private fun handlePinPrompt(accept: Boolean): PinOutcome {
        val automation = interactiveUiAutomation()
        var found: Pair<AccessibilityNodeInfo, String>? = null
        // The prompt is optional OS behaviour: observe, never fail, if it does not come.
        compose.observe(8_000L) {
            if (lockState() != ActivityManager.LOCK_TASK_MODE_NONE) return@observe true
            found = findPinButton(automation, accept)
            found != null
        }
        val windows = pinWindows()
        val silently = lockState() != ActivityManager.LOCK_TASK_MODE_NONE
        val pressed = found?.takeIf { !silently }?.first?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (pressed == true) {
            compose.observe(5_000L) { (lockState() != ActivityManager.LOCK_TASK_MODE_NONE) == accept }
        }
        var byShell = false
        if (accept && lockState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            // The prompt is listed by the window manager but not exposed to
            // accessibility on this image: close it, then pin as "Got it" would.
            if (pinWindows().isNotEmpty()) {
                shell("input keyevent 4")
                compose.observe(3_000L) { pinWindows().isEmpty() }
            }
            var task = -1
            compose.activityRule.scenario.onActivity { task = it.taskId }
            shell("am task lock $task")
            byShell = compose.observe(5_000L) { lockState() != ActivityManager.LOCK_TASK_MODE_NONE }
        }
        if (!accept && pinWindows().isNotEmpty()) shell("input keyevent 4")
        compose.awaitWindowSettled()
        return PinOutcome("prompt=${found?.second ?: "none"} windows=[$windows] " +
            "${if (accept) "accept" else "decline"} pressed=$pressed silently=$silently byShell=$byShell " +
            "→ lockTaskModeState=${lockState()}", byShell)
    }

    private data class Prompt(val opened: Boolean, val focus: String)

    private fun credentialScreen(): Prompt {
        val opened = compose.observe(10_000L) { credentialOnTop() }
        if (opened) compose.observe(5_000L) { credentialFocused() }
        return Prompt(opened, resumedActivities())
    }

    /**
     * Review note B: what the OS capture paths reachable from a test return for
     * a FLAG_SECURE guest frame. Logged, not asserted (test captures may include
     * secure layers); the screencap is published as evidence.
     */
    private fun observeOsCapture(name: String) {
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val automationShare = try { brightShare(automation) } finally { automation.recycle() }
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p")
        val png = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        val screencap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) {
            "screencap returned ${png.size} bytes that are not a PNG"
        }
        Log.i(PROBE, "API ${android.os.Build.VERSION.SDK_INT} $theme OS capture of a FLAG_SECURE guest " +
            "frame (share of content samples not black): UiAutomation.takeScreenshot $automationShare, " +
            "screencap ${brightShare(screencap)}")
        writeGuestPng(name, screencap)
    }

    private fun brightShare(shot: Bitmap): String {
        val margin = shot.height / 6
        var bright = 0
        var samples = 0
        for (y in margin until shot.height - margin step 24) for (x in 0 until shot.width step 24) {
            val c = shot.getPixel(x, y)
            if ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF) > 60) bright++
            samples++
        }
        return "$bright/$samples"
    }

    /** The guest window's own pixels (PixelCopy works under FLAG_SECURE; the OS screenshot does not). */
    private fun captureGuest(name: String) {
        compose.awaitWindowSettled()
        lateinit var bitmap: Bitmap
        var result = -1
        val thread = HandlerThread("guest-capture").apply { start() }
        try {
            val latch = CountDownLatch(1)
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bitmap, { code -> result = code; latch.countDown() },
                    Handler(thread.looper))
            }
            check(latch.await(10, TimeUnit.SECONDS)) { "PixelCopy timed out for $name" }
        } finally {
            thread.quitSafely()
        }
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed ($result) for $name" }
        Log.i(PROBE, "$name captured with PixelCopy ${bitmap.width}x${bitmap.height}")
        val problem = uiEvidenceProblem(bitmap)
        check(problem == null || problem.startsWith("is ") && !problem.contains("blank")) {
            "guest evidence $name $problem"
        }
        writeGuestPng(name, bitmap)
    }

    private fun writeGuestPng(name: String, bitmap: Bitmap) {
        val png = try {
            java.io.ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        writeUiEvidenceFile(uiEvidenceDisplayName(name), png)
    }
}

/** Viewer More entry: one photo only, at 200% text, back to that photo after the PIN. */
@RunWith(AndroidJUnit4::class)
class GuestViewViewerEntryTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = false, fontScale = 2f))
        .around(DeviceCredentialRule(PIN))
        .around(GuestStoreRule())
        .around(SyntheticHeaderFixtures(sizes = List(3) { 900 to 1200 }, folderOf = { FOLDER }))
        .around(compose)
        .around(UnpinRule())

    @Test fun viewerEntryShowsExactlyThePhotoOnScreen() {
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        val tile = SemanticsMatcher("visible timeline media") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atlas-timeline-media-") == true
        }
        compose.awaitThat("three synthetic tiles", 30_000L) { compose.onAllNodes(tile).fetchSemanticsNodes().size >= 3 }
        val tag = compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()[1]
        val id = tag.removePrefix("atlas-timeline-media-").toLong()
        compose.tap(hasTestTag(tag))
        compose.waitForNode(hasTestTag("atlas-viewer-more"))
        compose.tap(hasTestTag("atlas-viewer-more"))
        compose.waitForNode(hasTestTag("atlas-viewer-guest-view"))
        compose.waitForIdle()
        saveUiEvidence("guest-589-light-b-viewer-entry-font200.png")
        compose.tap(hasTestTag("atlas-viewer-guest-view"))

        compose.waitForNode(hasTestTag("atlas-guest-root"))
        val store = GuestAndroid.store(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(listOf(id), requireNotNull(store.activeRecord()).items.map { it.id })
        compose.onNodeWithTag("atlas-guest-filmstrip").assertDoesNotExist()
        compose.onNodeWithTag("atlas-viewer-more").assertDoesNotExist()
        compose.onNodeWithTag("atlas-guest-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.waitForNode(hasTestTag("atlas-guest-page-0") and hasContentDescription("Photo 1 of 1"))
        // Decline any pinning prompt so the exit runs unpinned here.
        val automation = interactiveUiAutomation()
        compose.observe(6_000L) {
            lockState() != ActivityManager.LOCK_TASK_MODE_NONE || findPinButton(automation, false) != null
        }
        val declined = findPinButton(automation, false)?.first?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (lockState() != ActivityManager.LOCK_TASK_MODE_NONE) shell("am task lock stop")
        compose.awaitThat("unpinned for the viewer exit") { lockState() == ActivityManager.LOCK_TASK_MODE_NONE }
        Log.i(PROBE, "viewer: pin prompt declined=$declined windows=[${pinWindows()}]")
        compose.awaitWindowSettled()
        // 200% text: the Exit slab keeps at least 48 dp and stays inside the screen.
        val exit = compose.onNodeWithTag("atlas-guest-exit").getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("atlas-guest-root").getUnclippedBoundsInRoot()
        assertTrue("Exit at least 48 dp tall at 200%: $exit", exit.bottom - exit.top >= 48.dp)
        assertTrue("Exit inside the screen at 200%: $exit in $root", exit.bottom <= root.bottom)

        compose.tap(hasTestTag("atlas-guest-exit"))
        compose.awaitThat("the system credential screen") { credentialOnTop() }
        compose.awaitThat("the PIN field focused") { credentialFocused() }
        shell("input text $PIN")
        shell("input keyevent 66")
        // Read no Compose tree while the system screen covers the activity.
        compose.awaitCredentialClosed()
        compose.awaitThat("guest view ended in the store") { store.boot() == GuestBoot.Owner }
        compose.awaitWindowSettled()
        compose.awaitThat("the viewer back on the same photo", 30_000L) {
            compose.exists(hasTestTag("atlas-viewer-more"))
        }
        compose.onNodeWithTag("atlas-guest-root").assertDoesNotExist()
    }
}

private object GuestGuardCheck {
    fun guest(boot: GuestBoot) = boot !is GuestBoot.Owner
}

private fun shell(command: String): String {
    val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
}

/** A clean guest store before and after each test, also on failure. */
private class GuestStoreRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            clearGuestStore(context)
            try {
                base.evaluate()
            } finally {
                clearGuestStore(context)
            }
        }
    }

    private fun clearGuestStore(context: Context) {
        context.getSharedPreferences("atlas_guest_guard", Context.MODE_PRIVATE).edit().clear().commit()
        for (suffix in listOf("", ".bak", ".new")) {
            File(context.noBackupFilesDir, "guest-session.v1$suffix").delete()
        }
    }
}

/**
 * Waits up to [timeoutMs] for optional system behaviour (an OS prompt that may
 * or may not appear) and reports whether it happened; never fails the test.
 */
private fun ComposeTestRule.observe(timeoutMs: Long, condition: () -> Boolean): Boolean = try {
    waitUntil(timeoutMs) { condition() }
    true
} catch (_: ComposeTimeoutException) {
    false
}

private fun resumedActivities(): String = shell("dumpsys activity activities").lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("topResumedActivity") || it.startsWith("ResumedActivity") }
    .joinToString("; ").take(300)

private fun credentialOnTop(): Boolean = resumedActivities().let {
    it.contains("ConfirmLock") || it.contains("ConfirmDeviceCredential")
}

/**
 * The credential screen's PIN entry has focus, so typed keys reach it. Older
 * images focus the Settings ConfirmLock/ConfirmDeviceCredential window itself;
 * API 35 draws the PIN inside SystemUI's BiometricPrompt ("Verify it's you")
 * window while ConfirmDeviceCredentialActivity stays the focused app.
 */
private fun credentialFocused(): Boolean {
    val lines = shell("dumpsys window").lineSequence().map { it.trim() }.toList()
    val focus = lines.filter { it.startsWith("mCurrentFocus=") }
    if (focus.any { it.contains("ConfirmLock") || it.contains("ConfirmDeviceCredential") }) return true
    val focusedApp = lines.filter { it.startsWith("mFocusedApp=") }
    return focus.any { it.contains("BiometricPrompt") } &&
        focusedApp.any { it.contains("ConfirmDeviceCredential") || it.contains("ConfirmLock") }
}

private fun lockState(): Int = InstrumentationRegistry.getInstrumentation().targetContext
    .getSystemService(ActivityManager::class.java).lockTaskModeState

private fun keyguardLocked(): Boolean = InstrumentationRegistry.getInstrumentation().targetContext
    .getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

/** Unlocks the lock screen with the test PIN if it is showing; returns whether it was. */
private fun ComposeTestRule.unlockIfLocked(): Boolean {
    if (!observe(3_000L) { keyguardLocked() }) return false
    shell("wm dismiss-keyguard")
    observe(3_000L) { credentialFocusIsKeyguardPin() }
    shell("input text $PIN")
    shell("input keyevent 66")
    awaitThat("the lock screen to unlock with the test PIN", 15_000L) { !keyguardLocked() }
    return true
}

/** The lock screen's PIN entry (SystemUI's bouncer) has focus. */
private fun credentialFocusIsKeyguardPin(): Boolean = shell("dumpsys window").lineSequence()
    .map { it.trim() }.filter { it.startsWith("mCurrentFocus=") }
    .any { it.contains("NotificationShade") || it.contains("Bouncer") }

/** The credential screen has left the top, so the activity's Compose tree is readable again. */
private fun ComposeTestRule.awaitCredentialClosed() =
    awaitThat("the credential screen to close", 30_000L) { !credentialOnTop() }

/** The OS pinning prompt's windows as the window manager lists them ("" when none). */
private fun pinWindows(): String = shell("dumpsys window windows").lineSequence()
    .map { it.trim() }.filter { it.startsWith("Window #") && it.contains("ScreenPinning") }
    .joinToString("; ").take(200)

/**
 * The OS pinning prompt's accept or decline button (SystemUI's own view id
 * first, then its label), in any window but ATLAS's; null when not shown.
 */
private fun findPinButton(automation: UiAutomation, accept: Boolean): Pair<AccessibilityNodeInfo, String>? {
    val own = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val roots = buildList<AccessibilityNodeInfo> {
        runCatching { automation.windows.mapNotNullTo(this) { it.root } }
        runCatching { automation.rootInActiveWindow }.getOrNull()?.let { add(it) }
    }.filter { it.packageName?.toString() != own }
    val id = if (accept) "screen_pinning_ok_button" else "screen_pinning_cancel_button"
    val labels = if (accept) listOf("Got it", "Pin", "Start", "Yes", "OK") else listOf("No thanks", "Cancel", "No")
    for (root in roots) {
        val byId = runCatching { root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id") }
            .getOrDefault(emptyList()).firstOrNull()
        val node = byId ?: labels.firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.text?.toString()?.trim().equals(label, ignoreCase = true) ||
                    it.contentDescription?.toString()?.trim().equals(label, ignoreCase = true)
            }
        } ?: continue
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: continue
        return clickable to "${root.packageName}:${if (byId != null) id else node.text}"
    }
    return null
}

/** Ends any screen pinning or open credential screen before the Activity rule closes the activity. */
private class UnpinRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                if (lockState() != ActivityManager.LOCK_TASK_MODE_NONE) shell("am task lock stop")
                if (credentialOnTop()) shell("am force-stop com.android.settings")
            }
        }
    }
}

/** A separate picker caller gets a cancelled result, not a guest or owner gallery. */
@RunWith(AndroidJUnit4::class)
class GuestExternalPickCancellationTest {
    @get:Rule val guestStore: TestRule = GuestStoreRule()

    @Test fun activeGuestCancelsBothPickerActionsWithoutData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GuestAndroid.store(context)
        val item = nl.atlas.gallery.featureguest.GuestItem(Long.MAX_VALUE,
            "content://media/external/images/media/${Long.MAX_VALUE}")
        val started = store.start(listOf(item), "")
        assertTrue(started is nl.atlas.gallery.featureguest.GuestStartResult.Started)
        for (action in listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK)) {
            val request = Intent(context, MainActivity::class.java).setAction(action).setType("image/*")
            androidx.test.core.app.ActivityScenario.launchActivityForResult<MainActivity>(request).use { scenario ->
                assertEquals(android.app.Activity.RESULT_CANCELED, scenario.result.resultCode)
                assertEquals(null, scenario.result.resultData)
            }
            assertEquals(listOf(item), requireNotNull(store.activeRecord()).items)
            assertTrue(store.boot() is GuestBoot.Guest)
        }
    }
}


/** Two real existing activity instances on the synthetic emulator, not a recreation. */
@RunWith(AndroidJUnit4::class)
class GuestExistingActivityTest {
    @get:Rule val guestStore: TestRule = GuestStoreRule()

    @Test fun earlierOwnerInstanceReconcilesWhenItReturnsToForeground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ).use { scenario ->
            activateGuestInSecondActivity(scenario)
            scenario.onActivity { earlier ->
                assertTrue("earlier owner now guarded", earlier.guestActive)
                assertTrue("earlier owner window secure",
                    earlier.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test fun earlierPickerReturnsCancelledWithoutDataWhenGuestStartsElsewhere() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.core.app.ActivityScenario.launchActivityForResult<MainActivity>(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_GET_CONTENT).setType("image/*")
        ).use { scenario ->
            val picker = activateGuestInSecondActivity(scenario)
            assertEquals(android.app.Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertEquals(null, scenario.result.resultData)
            // The picker finishes from onStart, so it sits at STARTED until the
            // system stops and destroys it. ActivityScenario.close() refuses a
            // transient stage ("Current state was null unexpectedly. Last stage
            // = STARTED"); wait for the destroy so close() sees DESTROYED.
            awaitDestroyed(picker)
        }
    }

    private fun awaitDestroyed(activity: MainActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
        var destroyed = false
        while (!destroyed) {
            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
            if (destroyed) break
            assertTrue("earlier picker destroyed after cancelling (finishing=${activity.isFinishing})",
                android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(50L) // bounded polling of a system lifecycle predicate
        }
        instrumentation.waitForIdleSync()
    }

    private fun activateGuestInSecondActivity(scenario: androidx.test.core.app.ActivityScenario<MainActivity>): MainActivity {
        lateinit var earlier: MainActivity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var second: MainActivity? = null
        try {
            scenario.onActivity {
                earlier = it
                assertFalse("first activity starts in owner mode", it.guestActive)
                it.startActivity(Intent(it, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            }
            val other = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as MainActivity
            second = other
            assertTrue("standard launch creates a distinct activity", earlier !== other)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("second activity starts in owner mode", other.guestActive)
                val store = GuestAndroid.store(other)
                val item = nl.atlas.gallery.featureguest.GuestItem(Long.MAX_VALUE,
                    "content://media/external/images/media/${Long.MAX_VALUE}")
                assertTrue(store.start(listOf(item), "") is nl.atlas.gallery.featureguest.GuestStartResult.Started)
                // Suppress OS pin prompting for this lifecycle-only synthetic probe.
                assertTrue(store.update { it.copy(pinOffered = true) } != null)
                other.startActivity(Intent(other, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("second activity reconciles on new intent", other.guestActive)
                other.finish()
            }
            instrumentation.waitForIdleSync()
            assertTrue(GuestAndroid.store(instrumentation.targetContext).boot() is GuestBoot.Guest)
        } finally {
            instrumentation.removeMonitor(monitor)
            second?.let { activity -> instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() } }
        }
        return earlier
    }
}
