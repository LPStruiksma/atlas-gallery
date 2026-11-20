package nl.atlas.gallery.featureeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanTest {
    private fun hash(c: Char) = EngineFixtures.hash(c)

    @Test fun emptyRecipeIsJustDecode() {
        val plan = RenderPlan.build(EditRecipe(4032, 3024))
        assertEquals(listOf(RenderStage.Decode(4032, 3024)), plan.stages)
        assertTrue(plan.isIdentity)
        assertEquals(4032, plan.outputWidth)
    }

    /**
     * Golden: hand-checked. Rotating a 4000x3000 photo clockwise gives a 3000x4000 canvas; the
     * crop [0.1,0.2,0.9,0.8] is 2400x2400 px starting at (300, 800); so source (x, y) lands at
     * (3000 - y - 300, x - 800) = (2700 - y, x - 800).
     */
    @Test fun recipeToPlanGolden() {
        val recipe = EditRecipe(4000, 3000, listOf(
            EditOperation.Adjust("1", AdjustParam.CONTRAST, -0.1),
            EditOperation.RasterPatch("2", PatchTool.ERASE, hash('a'), NormRect(0.1, 0.1, 0.3, 0.25), 800, 450, hash('b')),
            EditOperation.Rotate90("3", 1),
            EditOperation.Adjust("4", AdjustParam.EXPOSURE, 0.25),
            EditOperation.Crop("5", NormRect(0.1, 0.2, 0.9, 0.8), "1:1"),
            EditOperation.Filter("6", "atlas-warm", null, 0.6),
            EditOperation.OverlayAdd("7", OverlayLayer("t1", OverlayKind.TEXT, 0.5, 0.5, props = mapOf("text" to "hi"))),
            EditOperation.RasterPatch("8", PatchTool.AI_EDIT, hash('c'), NormRect.FULL, 4000, 3000),
            EditOperation.Adjust("9", AdjustParam.EXPOSURE, 0.3),
        ))
        val expected = """
            source 4000x3000 -> output 2400x2400
            decode 4000x3000
            patches erase aaaaaaaaaaaa [0.1,0.1,0.3,0.25] masked; ai-edit cccccccccccc [0,0,1,1]
            geometry 2400x2400 [0,-1,2700,1,0,-800,0,0,1]
            adjust exposure=0.3 contrast=-0.1
            lut atlas-warm strength=0.6
            overlays text:t1

        """.trimIndent()
        assertEquals(expected, RenderPlan.build(recipe).describe())
    }

    @Test fun previewScalesDownButNeverUp() {
        val recipe = EditRecipe(4000, 3000, listOf(EditOperation.Rotate90("r", 1)))
        val preview = RenderPlan.build(recipe, maxLongEdge = 1080)
        assertEquals(810, preview.outputWidth); assertEquals(1080, preview.outputHeight)
        val geometry = preview.stages.filterIsInstance<RenderStage.Geometry>().single()
        // Source bottom-left lands at the output's top-left corner after a clockwise turn.
        val p = geometry.sourceToOutput.map(0.0, 3000.0)
        assertEquals(0.0, p.x, 1e-9); assertEquals(0.0, p.y, 1e-9)
        val small = RenderPlan.build(EditRecipe(400, 300), maxLongEdge = 1080)
        assertEquals(400, small.outputWidth)
        assertTrue(small.isIdentity)
        // Scaling alone needs a warp stage.
        assertEquals(2, RenderPlan.build(EditRecipe(4000, 3000), maxLongEdge = 1000).stages.size)
    }

    @Test fun identityControlsAreLeftOut() {
        val recipe = EditRecipe(100, 100, listOf(
            EditOperation.Adjust("a", AdjustParam.EXPOSURE, 0.5),
            EditOperation.Adjust("b", AdjustParam.EXPOSURE, 0.0),
            EditOperation.Filter("c", "atlas-mono", null, 0.0),
            EditOperation.Rotate90("d", 2),
            EditOperation.Rotate90("e", 2),
            EditOperation.OverlayAdd("f", OverlayLayer("x", OverlayKind.EMOJI, 0.5, 0.5)),
            EditOperation.OverlayRemove("g", "x"),
        ))
        assertEquals(listOf(RenderStage.Decode(100, 100)), RenderPlan.build(recipe).stages)
    }

    @Test fun overlaysKeepZOrderAndLatestState() {
        val recipe = EditRecipe(100, 100, listOf(
            EditOperation.OverlayAdd("1", OverlayLayer("a", OverlayKind.STROKE, 0.1, 0.1)),
            EditOperation.OverlayAdd("2", OverlayLayer("b", OverlayKind.SHAPE, 0.2, 0.2)),
            EditOperation.OverlayUpdate("3", OverlayLayer("a", OverlayKind.STROKE, 0.9, 0.9)),
            EditOperation.OverlayUpdate("4", OverlayLayer("ghost", OverlayKind.TEXT, 0.5, 0.5)),
        ))
        val layers = RenderPlan.build(recipe).stages.filterIsInstance<RenderStage.Overlays>().single().layers
        assertEquals(listOf("a", "b"), layers.map { it.layerId })
        assertEquals(0.9, layers[0].centerX, 0.0)
    }

    @Test fun everyOperationFixturePlansAndKeepsOnlyCurrentState() {
        val plan = RenderPlan.build(EngineFixtures.everyOperation())
        // ResetGeometry then Rotate90(-1): canvas 3000x4000, no crop.
        assertEquals(3000, plan.outputWidth); assertEquals(4000, plan.outputHeight)
        val adjust = plan.stages.filterIsInstance<RenderStage.ColourAdjust>().single()
        assertEquals(listOf(AdjustParam.SHARPNESS to 0.3), adjust.values)
        assertEquals(3, plan.stages.filterIsInstance<RenderStage.RasterPatches>().single().patches.size)
        assertEquals(listOf("text-1"), plan.stages.filterIsInstance<RenderStage.Overlays>().single().layers.map { it.layerId })
    }

    @Test fun invalidRecipeIsRefused() {
        val bad = EditRecipe(10, 10, listOf(EditOperation.Crop("c", NormRect(0.5, 0.5, 0.4, 0.9))))
        try {
            RenderPlan.build(bad)
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected refusal")
    }

    @Test fun twelveMegapixelPlanIsCheap() {
        // Planning must never be the bottleneck of the 2 s save budget: 500 ops, 12 MP.
        val ops = (1..500).map { i ->
            when (i % 4) {
                0 -> EditOperation.Adjust("$i", AdjustParam.entries[i % AdjustParam.entries.size], 0.0)
                1 -> EditOperation.Straighten("$i", (i % 40).toDouble())
                2 -> EditOperation.Crop("$i", NormRect(0.05, 0.05, 0.95, 0.95))
                else -> EditOperation.Rotate90("$i", 1)
            }
        }
        val recipe = EditRecipe(4000, 3000, ops)
        RenderPlan.build(recipe) // warm-up
        val start = System.nanoTime()
        repeat(20) { RenderPlan.build(recipe) }
        val perPlanMs = (System.nanoTime() - start) / 20 / 1e6
        assertTrue("plan took $perPlanMs ms", perPlanMs < 50)
    }
}
