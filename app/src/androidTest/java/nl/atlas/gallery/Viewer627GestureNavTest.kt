package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * DESIGN-8T #627: the *current* viewer measured under gesture navigation
 * (`navigation_mode=2`), which #609 never captured (all its frames were
 * three-button). Evidence only; no production code or proposed target is
 * rendered. The emulator's own gestural overlay is switched on for this test
 * and three-button navigation is restored in `finally`, so later tests see the
 * mode they always had. Only an invented PNG and absent synthetic URIs are used.
 */
@RunWith(AndroidJUnit4::class)
class Viewer627GestureNavTest {
    private val rule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().decodeToString().trim()
        }
    }

    private fun navigationMode(): String = shell("settings get secure navigation_mode")

    private fun waitForMode(expected: String) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (navigationMode() != expected) {
            check(System.currentTimeMillis() < deadline) {
                "navigation_mode did not become $expected (now ${navigationMode()})"
            }
            Thread.sleep(250)
        }
    }

    @Test fun measureCurrentViewerUnderGestureNavigation() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        var inserted: android.net.Uri? = null
        try {
            shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            waitForMode("2")
            val uri = requireNotNull(resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ATLAS_627_BRIGHT.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-SYNTHETIC-627/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ))
            inserted = uri
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(240, 233, 120))
                val brush = Paint().apply {
                    isAntiAlias = true
                    color = Color.rgb(22, 80, 150)
                    strokeWidth = 12f
                }
                for (x in 0..320 step 32) canvas.drawLine(x.toFloat(), 0f,
                    (320 - x).toFloat(), 240f, brush)
                resolver.openOutputStream(uri)!!.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            val items = (0 until 30).map { index ->
                MediaRecord(62700L + index,
                    if (index == 0) uri.toString()
                    else "content://media/external/images/media/${998000 + index}",
                    if (index == 0) "Synthetic bright block" else "Unmaterialized synthetic $index",
                    "Pictures/ATLAS-SYNTHETIC-627/", 4_096L,
                    1_700_000_000_000L + index, false)
            }
            var exits = 0
            var window: Window? = null
            var insetSample: Viewer609InsetSample? = null
            rule.setContent {
                val view = LocalView.current
                SideEffect { window = view.context.viewer609Activity()?.window }
                AtlasGalleryTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) {
                        Viewer609InsetProbe { insetSample = it }
                        GalleryViewer(items, items[0].uri,
                            onEdit = { true }, onTrashed = {}, onBack = { exits++ })
                    }
                }
            }
            // The gestural overlay reports side back-gesture zones; three-button does not.
            rule.waitUntil(20_000L) {
                val decor = window?.decorView ?: return@waitUntil false
                val zones = ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.systemGestures())
                zones != null && zones.left > 0 && zones.right > 0
            }
            val recorder = Viewer609Recorder(rule) { window }
            fun shot(orientation: String, state: String) {
                val name = "viewer-627-gesture-photo-dark-$orientation-$state.png"
                rule.waitForIdle()
                saveUiEvidence(name)
                recorder.capture("gesture/photo/bright/dark/$orientation/$state", name, insetSample)
            }
            rule.waitUntil(10_000L) {
                runCatching {
                    rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 30")
                }.isSuccess
            }
            shot("portrait", "shown")
            val surface = hasTestTag("atlas-photo-surface") and hasClickAction()
            rule.onNode(surface).performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithTag("atlas-filmstrip").assertDoesNotExist()
            shot("portrait", "hidden")
            rule.onNode(surface).performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithTag("atlas-filmstrip").assertExists()
            try {
                rotateViewerEvidenceLandscape()
                rule.waitUntil(10_000L) {
                    rule.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                        .let { it.width > it.height }
                }
                shot("landscape", "shown")
            } finally {
                restoreViewerEvidencePortrait()
            }
            recorder.publish("viewer-627-gesture.json", listOf(
                "Gesture navigation on the API 35 emulator overlay, NOT Lukas's physical S24+ / One UI gesture settings",
                "Video under gesture navigation: not rendered here; video rows stack above the filmstrip, so derive from these insets",
                "Back-gesture sensitivity other than the emulator default: NOT_CAPTURED",
                "System TalkBack speech/focus traversal: Compose descriptions only, NOT_CAPTURED"
            ), issue = 627)
            rule.runOnIdle { assertEquals("No exit during measurement", 0, exits) }
        } finally {
            inserted?.let { resolver.delete(it, null, null) }
            restoreViewerEvidencePortrait()
            shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
            waitForMode("0")
        }
    }
}
