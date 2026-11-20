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
        val recipe = EngineFixtures.everyOperation()
        val plan = RenderPlan.build(recipe)
        // Split at the Face Swap output patch (op-15); its base splits again at the AI Edit (op-14).
        val base = (plan.stages[0] as RenderStage.Base).plan
        assertEquals("op-15", (plan.stages[1] as RenderStage.OutputPatch).patch.id)
        assertEquals("op-14", (base.stages[1] as RenderStage.OutputPatch).patch.id)
        val innerBase = (base.stages[0] as RenderStage.Base).plan
        // The innermost base holds the erase patch, the old adjustments, filter and the text layer.
        assertEquals(1, innerBase.stages.filterIsInstance<RenderStage.RasterPatches>().single().patches.size)
        assertEquals(listOf(AdjustParam.EXPOSURE, AdjustParam.CONTRAST),
            innerBase.stages.filterIsInstance<RenderStage.ColourAdjust>().single().values.map { it.first })
        assertEquals(listOf("text-1"), innerBase.stages.filterIsInstance<RenderStage.Overlays>().single().layers.map { it.layerId })
        // After the last AI step: reset adjustments + sharpness, reset geometry, one turn left.
        assertEquals(base.outputHeight, plan.outputWidth); assertEquals(base.outputWidth, plan.outputHeight)
        assertEquals(listOf(AdjustParam.SHARPNESS to 0.3), plan.stages.filterIsInstance<RenderStage.ColourAdjust>().single().values)
        assertTrue(plan.stages.none { it is RenderStage.Lut || it is RenderStage.Overlays || it is RenderStage.RasterPatches })
    }

    /**
     * Golden for an AI result: the AI tool saw the cropped, exposure-adjusted picture, so its
     * full-frame output-space patch replaces that render and only the later contrast applies.
     */
    @Test fun outputPatchIsNotEditedTwiceGolden() {
        val ops = listOf(
            EditOperation.Adjust("1", AdjustParam.EXPOSURE, 0.4),
            EditOperation.Crop("2", NormRect(0.0, 0.0, 0.5, 1.0)),
            EditOperation.Filter("3", "atlas-film", null, 1.0),
            EditOperation.RasterPatch("4", PatchTool.AI_EDIT, hash('a'), NormRect.FULL, 2000, 3000, space = PatchSpace.OUTPUT),
            EditOperation.Adjust("5", AdjustParam.CONTRAST, 0.2),
        )
        val expected = """
            source 4000x3000 -> output 2000x3000
            base {
              source 4000x3000 -> output 2000x3000
              decode 4000x3000
              geometry 2000x3000 [1,0,0,0,1,0,0,0,1]
              adjust exposure=0.4
              lut atlas-film strength=1
            }
            output-patch ai-edit aaaaaaaaaaaa [0,0,1,1]
            adjust contrast=0.2

        """.trimIndent()
        assertEquals(expected, RenderPlan.build(EditRecipe(4000, 3000, ops)).describe())
        // Undo the AI step: the earlier steps come back exactly as they were.
        val undone = RenderPlan.build(EditRecipe(4000, 3000, ops.take(3)))
        assertEquals(undone, (RenderPlan.build(EditRecipe(4000, 3000, ops)).stages[0] as RenderStage.Base).plan)
    }

    @Test fun maskedOutputPatchKeepsEarlierStepsUnderneath() {
        val ops = listOf(
            EditOperation.Rotate90("1", 1),
            EditOperation.RasterPatch("2", PatchTool.FACE_SWAP, hash('a'), NormRect(0.3, 0.1, 0.6, 0.4), 512, 512, hash('b'), PatchSpace.OUTPUT),
            EditOperation.Crop("3", NormRect(0.0, 0.0, 0.5, 1.0)),
        )
        val plan = RenderPlan.build(EditRecipe(4000, 3000, ops))
        assertTrue((plan.stages[0] as RenderStage.Base).plan.stages.any { it is RenderStage.Geometry })
        assertEquals(1500, plan.outputWidth); assertEquals(4000, plan.outputHeight)
        // Taps and gainmaps compose the turn before the patch with the crop after it:
        // source (u, v) -> turned (1 - v, u) -> left-half crop ((1 - v) * 2, u).
        val p = plan.normalizedSourceToOutput.map(0.9, 0.2)
        assertEquals(1.6, p.x, 1e-9); assertEquals(0.9, p.y, 1e-9)
        val back = plan.outputToSource(Point2(1.6, 0.9))
        assertEquals(0.9, back.x, 1e-9); assertEquals(0.2, back.y, 1e-9)
        val gain = plan.gainmap(1000, 750)
        assertEquals(375, gain.width); assertEquals(1000, gain.height)
        val g = gain.sourceToOutput.map(0.9 * 1000, 0.2 * 750)
        assertEquals(1.6 * 375, g.x, 1e-6); assertEquals(0.9 * 1000, g.y, 1e-6)
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
