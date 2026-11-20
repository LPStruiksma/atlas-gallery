package nl.atlas.gallery.featureeditor.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.engine.RenderStage
import nl.atlas.gallery.featureeditor.engine.Point2
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator pixel tests use only synthetic, locally generated buffers; no personal media. */
@RunWith(AndroidJUnit4::class)
class DecorationRasterAndroidTest {
    private val p = DecorationPlacement("x", 0.5, 0.5)

    private fun stage(vararg decorations: DecorationLayer): RenderStage.Overlays {
        val operations = decorations.mapIndexed { i, it ->
            EditOperation.OverlayAdd("op-$i", DecorationCodec.encode(it))
        }
        return RenderPlan.build(EditRecipe(200, 200, operations))
            .stages.filterIsInstance<RenderStage.Overlays>().single()
    }

    private fun render(
        bitmap: Bitmap, stage: RenderStage.Overlays,
        resolver: DecorationBitmapResolver = DecorationBitmapResolver { null },
    ) = DecorationRasterizer.draw(Canvas(bitmap), stage, 200, 200, 200, 200, resolver)

    @Test fun strokeEraserOnlyErasesDecorationsNotPhoto() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val red = StrokeLayer(p.copy(layerId = "red"), StrokeMode.PEN, Color.RED, 0.08,
            listOf(InkPoint(-0.35, 0.0), InkPoint(0.35, 0.0)))
        val clear = StrokeLayer(p.copy(layerId = "erase"), StrokeMode.ERASER, Color.TRANSPARENT, 0.10,
            listOf(InkPoint(0.0, -0.3), InkPoint(0.0, 0.3)))
        render(bitmap, stage(red, clear))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 100))
        assertEquals(Color.RED, bitmap.getPixel(60, 100))
    }

    @Test fun highlighterOpacityIsCompositedAsOneLayer() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val highlighter = StrokeLayer(p.copy(opacity = 0.4), StrokeMode.HIGHLIGHTER,
            Color.YELLOW, 0.12, listOf(InkPoint(-0.2, 0.0), InkPoint(0.2, 0.0)))
        render(bitmap, stage(highlighter))
        val alpha = Color.alpha(bitmap.getPixel(100, 100))
        assertTrue("Expected translucent highlighter, alpha=$alpha", alpha in 70..125)
    }

    @Test fun filledShapesAndTextBackgroundUseCanvasPixels() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val rectangle = ShapeLayer(p.copy(layerId = "shape", centerX = 0.25, centerY = 0.5),
            ShapeType.RECTANGLE, Point2(-0.1, -0.1), Point2(0.1, 0.1),
            Color.RED, 0.02, filled = true)
        val text = TextLayer(p.copy(layerId = "text", centerX = 0.75, centerY = 0.5),
            "hello", TextFace.SANS, 0.1, Color.WHITE, TextAlign.CENTER,
            0.25, TextBackground.SOLID, Color.GREEN)
        render(bitmap, stage(rectangle, text))
        assertEquals(Color.RED, bitmap.getPixel(50, 100))
        // Bottom of the text box is background-only, away from the glyph baseline.
        assertEquals(Color.GREEN, bitmap.getPixel(150, 108))
    }

    @Test fun contentHashedStickerRendersAtSourceAttachedPosition() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val hash = "f".repeat(64)
        val sticker = StickerLayer(p.copy(centerX = 0.4, centerY = 0.6), hash, 0.3, 0.3)
        render(bitmap, stage(sticker), DecorationBitmapResolver { requested ->
            if (requested == hash) source else null
        })
        assertEquals(Color.GREEN, bitmap.getPixel(80, 120))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(10, 10))
    }

    @Test(expected = IllegalStateException::class)
    fun missingStickerFailsExportRatherThanOmittingIt() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        render(bitmap, stage(StickerLayer(p, "c".repeat(64), 0.2, 0.2)))
    }
}
