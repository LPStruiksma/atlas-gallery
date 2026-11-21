package nl.atlas.gallery.featureeditor.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EDIT-2 (#632) asset strictness on the CPU backend (every API level): exports refuse anything
 * they cannot render, previews leave it out. Synthetic images only.
 */
@RunWith(AndroidJUnit4::class)
class EditorRendererStrictnessTest {
    private val w = 120
    private val h = 80
    private val source = BitmapSource(RenderTestKit.picture(w, h))

    private fun cpu() = PlanRenderer(RenderTestKit.assets(), CpuPixelBackend())

    @Test fun exportRefusesAnUnhashedOverlayItCannotDraw() {
        // Text, stroke and shape layers carry no content hash; with no rasteriser they must not
        // vanish from a "successful" export.
        for (kind in listOf(OverlayKind.TEXT, OverlayKind.STROKE, OverlayKind.SHAPE)) {
            val layer = OverlayLayer("l-${kind.key}", kind, 0.5, 0.5, props = mapOf("text" to "synthetic"))
            val plan = RenderPlan.build(EditRecipe(w, h, listOf(EditOperation.OverlayAdd("o", layer))))
            cpu().use { r ->
                assertThrows(MissingAssetException::class.java) {
                    r.export(plan, source, BitmapTileSink(plan.outputWidth, plan.outputHeight, PixelSpace.SRGB), PixelSpace.SRGB)
                }
                // The preview stays lenient: the picture without the layer.
                val preview = r.renderTile(plan, source, IntRect.size(plan.outputWidth, plan.outputHeight), PixelSpace.SRGB, strict = false)
                assertEquals(plan.outputWidth, preview.width)
            }
        }
        RenderTestKit.log("strict_overlay unhashed=refused preview=lenient")
    }

    @Test fun cpuPreviewLeavesOutAMissingLutAndExportRefusesIt() {
        val adjust = EditOperation.Adjust("a", AdjustParam.CONTRAST, 0.3)
        val missing = RenderPlan.build(EditRecipe(w, h, listOf(adjust, EditOperation.Filter("f", "not-in-store", null, 0.8))))
        val plain = RenderPlan.build(EditRecipe(w, h, listOf(adjust)))
        val whole = IntRect.size(w, h)
        cpu().use { r ->
            // What EditorPreviewSurface's API 29-32 fallback calls.
            val preview = r.renderTile(missing, source, whole, PixelSpace.SRGB, strict = false)
            val expected = r.renderTile(plain, source, whole, PixelSpace.SRGB, strict = false)
            val (max, _) = RenderTestKit.diff(preview, expected)
            RenderTestKit.log("cpu_missing_lut preview_vs_unfiltered max=$max")
            assertEquals(0, max)
            assertThrows(MissingAssetException::class.java) {
                r.export(missing, source, BitmapTileSink(w, h, PixelSpace.SRGB), PixelSpace.SRGB)
            }
        }
    }
}
