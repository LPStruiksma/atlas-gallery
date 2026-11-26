package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.CropShape
import nl.atlas.gallery.featureeditor.engine.CurvePoint
import nl.atlas.gallery.featureeditor.engine.HueAdjustment
import nl.atlas.gallery.featureeditor.engine.PresetHue
import nl.atlas.gallery.featureeditor.engine.RenderStage
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.PatchSpace
import nl.atlas.gallery.featureeditor.engine.PatchTool
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentProgramTest {
    @Test fun tileGridCoversTheOutputExactlyOnce() {
        val tiles = TileGrid.tiles(2500, 1100, 1024)
        assertEquals(3 * 2, tiles.size)
        assertEquals(2500L * 1100, tiles.sumOf { it.pixels })
        assertEquals(IntRect(2048, 1024, 2500, 1100), tiles.last())
    }

    /** A 50 MP photo: every tile reads a bounded source region, never the whole photo. */
    @Test fun fiftyMegapixelExportReadsBoundedRegions() {
        val w = 8192; val h = 6144
        val recipe = EditRecipe(w, h, listOf(
            EditOperation.Straighten("s", 7.0),
            EditOperation.Adjust("d", AdjustParam.DEFINITION, 0.5),
            EditOperation.Adjust("e", AdjustParam.SHARPNESS, 0.5),
        ))
        val plan = RenderPlan.build(recipe)
        val program = SegmentProgram.of(plan)
        val tiles = TileGrid.tiles(plan.outputWidth, plan.outputHeight)
        val peak = tiles.maxOf { program.sourceRegionFor(it).pixels }
        assertTrue("output is still ~50 MP", plan.outputWidth.toLong() * plan.outputHeight > 30_000_000)
        // A rotated 1024 tile plus detail pads: well under 2 MP, against 50 MP for the photo.
        assertTrue("peak region $peak", peak < 2_000_000)
    }

    @Test fun regionCoversEveryTapTheTileReads() {
        val recipe = RenderFixtures.everything(400, 300)
        val program = SegmentProgram.of(RenderPlan.build(recipe))
        val tile = IntRect(37, 21, 101, 77)
        val region = program.sourceRegionFor(tile)
        val m = program.outputToSource
        val pad = program.outputPad.toDouble()
        for (y in listOf(tile.top - pad, tile.bottom + pad)) for (x in listOf(tile.left - pad, tile.right + pad)) {
            val p = m.map(x, y)
            val inside = p.x in 0.0..400.0 && p.y in 0.0..300.0
            if (inside) assertTrue(p.x >= region.left && p.x <= region.right && p.y >= region.top && p.y <= region.bottom)
        }
    }

    @Test fun outputPatchSplitsIntoCompositeInputOverTheBase() {
        val hash = "a".repeat(64)
        val recipe = EditRecipe(400, 300, listOf(
            EditOperation.Rotate90("r", 1),
            EditOperation.RasterPatch("p", PatchTool.AI_EDIT, hash, NormRect.FULL, 300, 400, space = PatchSpace.OUTPUT),
            EditOperation.Adjust("x", AdjustParam.EXPOSURE, 0.2),
        ))
        val program = SegmentProgram.of(RenderPlan.build(recipe))
        val input = program.input as SegmentInput.Composite
        assertEquals(300, input.base.outputWidth)
        assertEquals(300, program.sourceWidth)
        assertEquals(400, program.sourceHeight)
        assertEquals(0.2f, program.adjust[AdjustParam.EXPOSURE], 0f)
    }

    @Test fun gainmapProgramsCarryNoColourWork() {
        val recipe = RenderFixtures.everything(400, 300)
        val plan = RenderPlan.build(recipe)
        val program = SegmentProgram.of(plan, rawValues = true)
        assertTrue(program.rawValues && program.adjust.isIdentity && program.lut == null && program.overlays == null)
    }

    @Test fun v2MetadataStagesDoNotBreakPixelLoweringBeforeGpuSupport() {
        val recipe = EditRecipe(400, 300, listOf(
            EditOperation.Adjust("adjust", AdjustParam.EXPOSURE, 0.25),
            EditOperation.Curves("curves", rgb = listOf(
                CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.4), CurvePoint(1.0, 1.0),
            )),
            EditOperation.ColorMix("mix", presets = listOf(HueAdjustment(PresetHue.RED, hueShift = 0.2))),
            EditOperation.SpotColor("spot", keep = listOf("#336699")),
            EditOperation.ShapeCrop("shape", preset = CropShape.CIRCLE),
            EditOperation.Resize("resize", longEdge = 800),
        ))
        val plan = RenderPlan.build(recipe)
        assertTrue(plan.stages.any { it is RenderStage.Curves })
        assertTrue(plan.stages.any { it is RenderStage.ColorMix })
        assertTrue(plan.stages.any { it is RenderStage.SpotColor })
        assertTrue(plan.stages.any { it is RenderStage.ShapeCrop })
        assertTrue(plan.stages.any { it is RenderStage.Resize })
        val program = SegmentProgram.of(plan)
        assertEquals(800, program.outputWidth)
        assertEquals(600, program.outputHeight)
        assertEquals(0.25f, program.adjust[AdjustParam.EXPOSURE], 0f)
    }
}
