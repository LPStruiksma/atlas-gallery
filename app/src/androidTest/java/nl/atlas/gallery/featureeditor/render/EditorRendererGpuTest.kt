package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.Matrix3
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.PatchSpace
import nl.atlas.gallery.featureeditor.engine.PatchTool
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * EDIT-2 (#632) correctness on the device GPU, synthetic images only: the AGSL pass against the
 * CPU reference, tiles against the whole frame, the live preview surface against tiled export,
 * wide gamut, gainmaps, patches and overlays.
 */
@RunWith(AndroidJUnit4::class)
class EditorRendererGpuTest {
    private val w = 240
    private val h = 160
    private val picture = RenderTestKit.picture(w, h)

    @Before fun needsAgsl() = assumeTrue("AGSL needs API 33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    private fun gpu(assets: EditorAssets = RenderTestKit.assets()) = PlanRenderer(assets, AgslPixelBackend())
    private fun cpu(assets: EditorAssets = RenderTestKit.assets()) = PlanRenderer(assets, CpuPixelBackend())

    private fun whole(r: PlanRenderer, plan: RenderPlan, source: SourceProvider, space: PixelSpace = PixelSpace.SRGB) =
        r.renderTile(plan, source, IntRect.size(plan.outputWidth, plan.outputHeight), space)

    @Test fun gpuMatchesTheCpuReference() {
        val single = AdjustParam.entries.map { p ->
            p.key to EditRecipe(w, h, listOf(EditOperation.Adjust("a", p, if (p.min < 0) -0.55 else 0.55)))
        }
        val cases = listOf(
            "identity" to EditRecipe(w, h),
            "geometry" to EditRecipe(w, h, RenderTestKit.geometryOps()),
            "lut" to EditRecipe(w, h, listOf(EditOperation.Filter("f", "synthetic-warm", null, 0.8))),
            "everything" to RenderTestKit.everything(w, h),
        ) + single
        val source = BitmapSource(picture)
        gpu().use { g -> cpu().use { c ->
            for ((name, recipe) in cases) {
                val plan = RenderPlan.build(recipe)
                val (max, mean) = RenderTestKit.diff(whole(g, plan, source), whole(c, plan, source))
                RenderTestKit.log("gpu_vs_cpu case=$name max=$max mean=${"%.4f".format(mean)}")
                assertTrue("$name: GPU differs from the CPU reference by $max/255", max <= 3)
            }
        } }
    }

    @Test fun tilesAreSeamlessOnTheGpu() {
        val plan = RenderPlan.build(RenderTestKit.everything(w, h))
        val source = BitmapSource(picture)
        gpu().use { g ->
            val full = whole(g, plan, source)
            val tiled = RenderTestKit.stitch(plan.outputWidth, plan.outputHeight, PixelSpace.SRGB, { t ->
                g.renderTile(plan, source, t, PixelSpace.SRGB)
            }, tileSize = 61)
            val (max, mean) = RenderTestKit.diff(full, tiled)
            RenderTestKit.log("tiling_seams tile=61 max=$max mean=${"%.4f".format(mean)}")
            assertTrue("tiled export differs from the whole frame by $max/255", max <= 1)
        }
    }

    /** The goal's agreement check: the preview surface and tiled export of one recipe, within 1/255. */
    @Test fun previewSurfaceAgreesWithTiledExport() {
        val png = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoder = BitmapRegionDecoder.newInstance(png, 0, png.size)
        val recipes = listOf("everything" to RenderTestKit.everything(w, h), "geometry" to EditRecipe(w, h, RenderTestKit.geometryOps()))
        for ((name, recipe) in recipes) {
            val plan = RenderPlan.build(recipe)
            val reader = RenderTestKit.reader(plan.outputWidth, plan.outputHeight)
            val preview = EditorPreviewSurface(reader.surface, plan.outputWidth, plan.outputHeight, RenderTestKit.assets())
            try {
                preview.render(plan, BitmapSource(picture))
                val shown = RenderTestKit.readLatest(reader, PixelSpace.SRGB)
                val sink = BitmapTileSink(plan.outputWidth, plan.outputHeight, PixelSpace.SRGB)
                gpu().use { it.export(plan, RegionDecoderSource(decoder), sink, PixelSpace.SRGB, tileSize = 64) }
                val (max, mean) = RenderTestKit.diff(shown, sink.bitmap)
                RenderTestKit.log("preview_vs_export case=$name max=$max mean=${"%.4f".format(mean)}")
                assertTrue("$name: preview and export differ by $max/255", max <= 1)
            } finally {
                preview.close(); reader.close()
            }
        }
    }

    @Test fun displayP3IsNotClippedToSrgb() {
        val p3 = RenderTestKit.picture(w, h, PixelSpace.DISPLAY_P3)
        val source = BitmapSource(p3)
        // An identity LUT at full strength: wide-gamut colour must survive the LUT stage too.
        val identityAssets = object : EditorAssets by RenderTestKit.assets() {
            override fun lut(lutId: String, lutHash: String?) = Lut3D.identity(17)
        }
        val plan = RenderPlan.build(EditRecipe(w, h, listOf(EditOperation.Filter("f", "identity", null, 1.0))))
        gpu(identityAssets).use { g ->
            val out = whole(g, plan, source, PixelSpace.DISPLAY_P3)
            assertEquals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3), out.colorSpace)
            val (max, _) = RenderTestKit.diff(out, p3)
            RenderTestKit.log("wide_gamut p3_roundtrip max=$max")
            assertTrue("P3 bytes changed by $max/255", max <= 1)
        }
    }

    @Test fun gainmapFollowsGeometry() {
        val gm = Bitmap.createBitmap(60, 40, Bitmap.Config.ALPHA_8)
        // A bright marker in the gainmap's top-left corner.
        android.graphics.Canvas(gm).drawRect(0f, 0f, 6f, 4f, android.graphics.Paint().apply { color = Color.WHITE })
        val plan = RenderPlan.build(EditRecipe(w, h, listOf(EditOperation.Rotate90("r", 1))))
        gpu().use { g ->
            val out = g.renderGainmap(plan, gm)
            assertEquals(40, out.width); assertEquals(60, out.height)
            // Clockwise quarter turn: top-left moves to top-right.
            assertTrue(Color.red(out.getPixel(out.width - 2, 1)) > 200)
            assertTrue(Color.red(out.getPixel(1, 1)) < 50)
        }
    }

    @Test fun patchesAndOverlaysAreComposited() {
        val red = "a".repeat(64); val blue = "b".repeat(64); val sticker = "c".repeat(64)
        val assets = RenderTestKit.assets(
            mapOf(red to RenderTestKit.solid(8, 8, 255, 0, 0), blue to RenderTestKit.solid(8, 8, 0, 0, 255), sticker to RenderTestKit.solid(10, 10, 0, 255, 0)),
        ) { layer, sw, sh ->
            // Synthetic placement: a 10 px square centred on the layer's centre, in source pixels.
            val art = RenderTestKit.solid(10, 10, 0, 255, 0)
            PlacedRaster(art, Matrix3.translate(layer.centerX * sw - 5, layer.centerY * sh - 5))
        }
        val recipe = EditRecipe(w, h, listOf(
            EditOperation.RasterPatch("p1", PatchTool.ERASE, red, NormRect(0.0, 0.0, 0.25, 0.25), 8, 8),
            EditOperation.OverlayAdd("o1", OverlayLayer("s1", OverlayKind.STICKER, 0.75, 0.75, contentHash = sticker)),
            EditOperation.RasterPatch("p2", PatchTool.AI_EDIT, blue, NormRect(0.5, 0.0, 1.0, 0.25), 8, 8, space = PatchSpace.OUTPUT),
            EditOperation.Adjust("x", AdjustParam.EXPOSURE, 0.0001),
        ))
        val plan = RenderPlan.build(recipe)
        gpu(assets).use { g ->
            val out = whole(g, plan, BitmapSource(picture))
            val p1 = out.getPixel(10, 10)
            assertTrue("source patch is red", Color.red(p1) > 240 && Color.green(p1) < 15)
            val o1 = out.getPixel((0.75 * w).toInt(), (0.75 * h).toInt())
            assertTrue("sticker is green", Color.green(o1) > 240 && Color.red(o1) < 15)
            val p2 = out.getPixel((0.75 * w).toInt(), 10)
            assertTrue("output patch is blue", Color.blue(p2) > 240 && Color.red(p2) < 15)
            // Tiled export of the same composite recipe agrees with the single frame.
            val tiled = RenderTestKit.stitch(plan.outputWidth, plan.outputHeight, PixelSpace.SRGB, { t ->
                g.renderTile(plan, BitmapSource(picture), t, PixelSpace.SRGB)
            }, tileSize = 50)
            val (max, _) = RenderTestKit.diff(out, tiled)
            RenderTestKit.log("composite_tiles max=$max")
            assertTrue("composite tiles differ by $max/255", max <= 1)
        }
    }
}
