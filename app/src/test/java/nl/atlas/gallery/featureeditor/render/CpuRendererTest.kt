package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuRendererTest {
    private val w = 96
    private val h = 64
    private val pic = RenderFixtures.picture(w, h)
    private val whole = RgbaRegion(w, h, pic)

    private fun render(recipe: EditRecipe, lut: Lut3D? = null, space: PixelSpace = PixelSpace.SRGB, maxLongEdge: Int? = null): Pair<SegmentProgram, ByteArray> {
        val program = SegmentProgram.of(RenderPlan.build(recipe, maxLongEdge))
        return program to CpuRenderer.render(program, whole, IntRect.size(program.outputWidth, program.outputHeight), lut, space)
    }

    @Test fun emptyRecipeReproducesTheSourceExactly() {
        val (_, out) = render(EditRecipe(w, h))
        assertArrayEquals(pic, out)
    }

    @Test fun quarterTurnMovesPixelsExactly() {
        val (program, out) = render(EditRecipe(w, h, listOf(EditOperation.Rotate90("r", 1))))
        assertEquals(h, program.outputWidth)
        assertEquals(w, program.outputHeight)
        // Clockwise: output (x, y) shows source (y, h - 1 - x).
        for (y in 0 until program.outputHeight) for (x in 0 until program.outputWidth) {
            val o = (y * program.outputWidth + x) * 4
            val s = ((h - 1 - x) * w + y) * 4
            for (c in 0..3) assertEquals(pic[s + c], out[o + c])
        }
    }

    @Test fun displayP3BytesPassThroughUntouchedToP3Output() {
        val p3 = RgbaRegion(w, h, pic, space = PixelSpace.DISPLAY_P3)
        val program = SegmentProgram.of(RenderPlan.build(EditRecipe(w, h)))
        val out = CpuRenderer.render(program, p3, IntRect.size(w, h), null, PixelSpace.DISPLAY_P3)
        assertArrayEquals(pic, out)
        // The same bytes written as sRGB clip only at the final encode: saturated red stays 255.
        val srgb = CpuRenderer.render(program, p3, IntRect.size(w, h), null, PixelSpace.SRGB)
        val bar = ((h - 2) * w + 1) * 4 // a red bar pixel
        assertEquals(255, srgb[bar].toInt() and 0xff)
        assertEquals(0, srgb[bar + 1].toInt() and 0xff)
    }

    @Test fun tilesAreSeamlessAndReadOnlyTheirRegion() {
        val recipe = RenderFixtures.everything(w, h)
        val (program, full) = render(recipe, RenderFixtures.warmLut)
        val stitched = ByteArray(full.size)
        for (tile in TileGrid.tiles(program.outputWidth, program.outputHeight, 13)) {
            val need = program.sourceRegionFor(tile)
            assertTrue(!need.isEmpty)
            val region = RgbaRegion(need.width, need.height, RenderFixtures.crop(pic, w, need), need.left.toFloat(), need.top.toFloat())
            val px = CpuRenderer.render(program, region, tile, RenderFixtures.warmLut, PixelSpace.SRGB)
            for (y in 0 until tile.height) {
                System.arraycopy(px, y * tile.width * 4, stitched, ((tile.top + y) * program.outputWidth + tile.left) * 4, tile.width * 4)
            }
        }
        assertEquals("tiled render must equal the whole-frame render", 0, RenderFixtures.maxDiff(full, stitched))
    }

    @Test fun everyControlAndTheFilterChangeTheRender() {
        val (_, plain) = render(EditRecipe(w, h))
        for (p in AdjustParam.entries) {
            val (_, out) = render(EditRecipe(w, h, listOf(EditOperation.Adjust("a", p, if (p.min < 0) -0.6 else 0.6))))
            assertTrue("${p.key} must change the render", RenderFixtures.maxDiff(plain, out) > 2)
        }
        val (_, filtered) = render(EditRecipe(w, h, listOf(EditOperation.Filter("f", "warm", null, 1.0))), RenderFixtures.warmLut)
        assertTrue(RenderFixtures.maxDiff(plain, filtered) > 2)
    }

    @Test fun previewSizePlanScalesTheSameRecipe() {
        val (program, out) = render(RenderFixtures.everything(w, h), RenderFixtures.warmLut, maxLongEdge = 40)
        assertEquals(40, maxOf(program.outputWidth, program.outputHeight))
        assertEquals(program.outputWidth * program.outputHeight * 4, out.size)
    }
}
