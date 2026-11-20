package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * DESIGN-8M #609 baseline: captures *current* viewer, not a proposed redesign.
 * Real renderer and MediaStore records, but ONLY generated graphic blocks; no
 * phone, personal media, Samsung screenshot or imported fixture is consulted.
 */
@RunWith(AndroidJUnit4::class)
class Viewer609PhotoBaselineTest {
    private val rule = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @Test fun measureRenderedPhotoStatesWithInsetsAndTrueRotation() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val inserted = mutableListOf<android.net.Uri>()
        try {
            for (bright in listOf(true, false)) {
                val uri = requireNotNull(resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME,
                            if (bright) "ATLAS_609_BRIGHT.png" else "ATLAS_609_DARK.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-SYNTHETIC-609/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                ))
                inserted += uri
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(if (bright) Color.rgb(240, 233, 120) else Color.rgb(13, 25, 47))
                    val brush = Paint().apply {
                        isAntiAlias = true
                        color = if (bright) Color.rgb(22, 80, 150) else Color.rgb(155, 190, 238)
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
            }
            // 30 indexed tiles exercise the actual LazyRow. Only two are materialized;
            // absent synthetic URIs are intentionally unavailable, never user media.
            val items = (0 until 30).map { index ->
                MediaRecord(60900L + index,
                    if (index < inserted.size) inserted[index].toString()
                    else "content://media/external/images/media/${999000 + index}",
                    when (index) {
                        0 -> "Synthetic bright block"
                        1 -> "Synthetic dark block"
                        else -> "Unmaterialized synthetic ${index}"
                    },
                    "Pictures/ATLAS-SYNTHETIC-609/", 4_096L,
                    1_700_000_000_000L + index, false)
            }
            val dark = mutableStateOf(true)
            val viewerOpen = mutableStateOf(true)
            var exits = 0
            var window: Window? = null
            var insetSample: Viewer609InsetSample? = null
            rule.setContent {
                val view = LocalView.current
                SideEffect { window = view.context.viewer609Activity()?.window }
                AtlasGalleryTheme(dark = dark.value) {
                    Box(Modifier.fillMaxSize()) {
                        Viewer609InsetProbe { insetSample = it }
                        if (viewerOpen.value) GalleryViewer(
                            items, items[0].uri,
                            onEdit = { true }, onTrashed = {},
                            onBack = { exits++; viewerOpen.value = false })
                    }
                }
            }
            val recorder = Viewer609Recorder(rule) { window }
            fun shot(fixture: String, theme: String, orientation: String, state: String) {
                val name = "viewer-609-photo-$fixture-$theme-$orientation-$state.png"
                rule.waitForIdle()
                saveUiEvidence(name)
                recorder.capture("photo/$fixture/$theme/$orientation/$state", name, insetSample)
            }
            for (mode in listOf("dark", "light")) {
                rule.runOnIdle { dark.value = mode == "dark" }
                for (index in 0..1) {
                    rule.onNodeWithTag("atlas-thumbnail-$index").performClick()
                    rule.waitUntil(10_000L) {
                        runCatching {
                            rule.onNodeWithTag("atlas-photo-counter")
                                .assertTextEquals("${index + 1} of 30")
                        }.isSuccess
                    }
                    val fixture = if (index == 0) "bright" else "dark"
                    shot(fixture, mode, "portrait", "shown")
                    val focused = hasTestTag("atlas-photo-surface") and hasClickAction()
                    rule.onNode(focused).performSemanticsAction(SemanticsActions.OnClick)
                    rule.onNodeWithTag("atlas-filmstrip").assertDoesNotExist()
                    shot(fixture, mode, "portrait", "hidden")
                    rule.onNode(focused).performSemanticsAction(SemanticsActions.OnClick)
                    rule.onNodeWithTag("atlas-filmstrip").assertExists()
                }
                try {
                    rotateViewerEvidenceLandscape()
                    rule.waitUntil(10_000L) {
                        rule.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                            .let { it.width > it.height }
                    }
                    for (index in 0..1) {
                        rule.onNodeWithTag("atlas-thumbnail-$index").performClick()
                        rule.waitUntil(10_000L) {
                            runCatching {
                                rule.onNodeWithTag("atlas-photo-counter")
                                    .assertTextEquals("${index + 1} of 30")
                            }.isSuccess
                        }
                        shot(if (index == 0) "bright" else "dark", mode,
                            "landscape", "shown")
                    }
                } finally {
                    restoreViewerEvidencePortrait()
                }
                rule.waitUntil(10_000L) {
                    rule.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                        .let { it.height > it.width }
                }
            }
            // An invalid invented MediaStore ID exercises current unavailable content.
            // The screenshot alone cannot distinguish loading from permanent error.
            rule.onNodeWithTag("atlas-thumbnail-2").performClick()
            rule.waitUntil(10_000L) {
                runCatching {
                    rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("3 of 30")
                }.isSuccess
            }
            shot("unavailable", "light", "portrait", "shown")
            recorder.publish("viewer-609-photo.json", listOf(
                "Media decode error vs loading: renderer exposes no distinct semantics",
                "System TalkBack speech/focus traversal: Compose descriptions only, NOT_CAPTURED",
                "Physical S24+ panel, density, gesture hit testing: emulator only",
                "Emulated cutout support must be read from each frame's cutout_supported value"
            ))
            rule.onNodeWithTag("atlas-back").performClick()
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithTag("atlas-viewer").fetchSemanticsNodes().isEmpty()
            }
            rule.runOnIdle { assertEquals("Back must exit", 1, exits) }
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
            restoreViewerEvidencePortrait()
        }
    }

    @Test fun emptyCatalogueUsesSafeExitWithoutShowingViewer() {
        var exits = 0
        rule.setContent {
            GalleryViewer(emptyList(), "synthetic://not-present", onBack = { exits++ })
        }
        rule.waitUntil(10_000L) { exits == 1 }
        rule.onNodeWithTag("atlas-viewer").assertDoesNotExist()
    }
}
