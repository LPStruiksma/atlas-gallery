package nl.atlas.gallery.featureeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Schema v2 contracts: synthetic points and colours only, no media or renderer needed. */
class RecipeV2Test {
    private val identity = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))
    private val curve = listOf(CurvePoint(0.0, 0.0), CurvePoint(0.4, 0.25), CurvePoint(1.0, 1.0))
    private fun hash(c: Char) = c.toString().repeat(64)

    private fun ops() = listOf(
        EditOperation.Curves("curves", rgb = curve, red = identity, green = curve, blue = identity),
        EditOperation.ColorMix("mix", presets = listOf(HueAdjustment(PresetHue.RED, 0.2, -0.1, 0.4)),
            custom = listOf(SampledHue("#ab12cd", -0.3, 0.2, -0.2))),
        EditOperation.SpotColor("spot", keep = listOf("#0033aa"), remove = listOf("#ffeedd"),
            tolerance = 0.17, brushMaskHash = hash('a')),
        EditOperation.ShapeCrop("shape", polygon = listOf(Point2(0.1, 0.1), Point2(0.9, 0.1),
            Point2(0.8, 0.9), Point2(0.2, 0.7)), outside = CropOutside.SOLID, fillColor = "#123456"),
        EditOperation.Resize("size", longEdge = 1200),
    )

    @Test fun allV2OperationsEncodeDecodeByteIdentically() {
        val r = EditRecipe(4000, 3000, ops())
        val encoded = RecipeJson.encode(r)
        assertEquals(2, RecipeJson.decode(encoded).schemaVersion)
        assertEquals(r, RecipeJson.decode(encoded))
        assertEquals(encoded, RecipeJson.encode(RecipeJson.decode(encoded)))
        assertTrue(encoded.contains(""""type":"curves""""))
        assertTrue(encoded.contains(""""type":"shapeCrop""""))
        assertTrue(encoded.contains(""""type":"spotColor""""))
    }

    @Test fun randomizedMonotoneCurvesRoundTrip() {
        val rng = Random(726)
        repeat(150) { i ->
            val x = List(6) { it.toDouble() / 5 }
            val increments = List(5) { rng.nextDouble(0.001, 1.0) }
            val sum = increments.sum()
            var y = 0.0
            val points = listOf(CurvePoint(0.0, 0.0)) + increments.mapIndexed { j, d ->
                y += d / sum
                CurvePoint(x[j + 1], if (j == 4) 1.0 else y)
            }
            val r = EditRecipe(100, 120, listOf(EditOperation.Curves("c$i", points)))
            val bytes = RecipeJson.encode(r)
            assertEquals(bytes, RecipeJson.encode(RecipeJson.decode(bytes)))
        }
    }

    @Test fun legacyV1DecodesAndPlansUnchanged() {
        val fixture = """{"schema":"atlas.edit-recipe","version":1,"source":{"width":400,"height":300},"operations":[{"id":"a","type":"adjust","param":"exposure","value":0.25}]}"""
        val migrated = RecipeJson.decode(fixture)
        assertEquals(2, migrated.schemaVersion)
        assertEquals(listOf(EditOperation.Adjust("a", AdjustParam.EXPOSURE, 0.25)), migrated.operations)
        assertEquals("source 400x300 -> output 400x300\ndecode 400x300\nadjust exposure=0.25\n",
            RenderPlan.build(migrated).describe())
        assertEquals(RecipeJson.encode(migrated), RecipeJson.encode(RecipeJson.decode(RecipeJson.encode(migrated))))
        assertEquals(listOf(1), RecipeJson.MIGRATIONS.map { it.fromVersion })
    }

    @Test fun stageOrderIsFixedIndependentlyOfOperationOrder() {
        val first = ops()
        val other = listOf(first[4], first[2], first[1], first[3], first[0])
        val a = RenderPlan.build(EditRecipe(4000, 3000, first))
        val b = RenderPlan.build(EditRecipe(4000, 3000, other))
        assertEquals(a.stages.map { it::class }, b.stages.map { it::class })
        assertEquals(listOf(RenderStage.Decode::class, RenderStage.Geometry::class,
            RenderStage.ShapeCrop::class, RenderStage.Resize::class, RenderStage.Curves::class,
            RenderStage.ColorMix::class, RenderStage.SpotColor::class), a.stages.map { it::class })
        assertEquals(1200, a.outputWidth)
        assertEquals(900, a.outputHeight)
        assertEquals(600, RenderPlan.build(EditRecipe(4000, 3000, first), 600).outputWidth)
    }

    @Test fun maskReferenceAndResetSemanticsAreStable() {
        val r = EditRecipe(200, 100, ops())
        assertTrue(hash('a') in r.referencedContentHashes())
        val cleared = r.copy(operations = r.operations + EditOperation.ResetAdjustments("reset") +
            EditOperation.ResetGeometry("reset-geo"))
        val stages = RenderPlan.build(cleared).stages
        assertFalse(stages.any { it is RenderStage.Curves || it is RenderStage.ColorMix ||
            it is RenderStage.SpotColor || it is RenderStage.ShapeCrop })
    }

    @Test fun invalidBoundsAndNewerSchemaAreRefused() {
        val bad = listOf(
            EditOperation.Curves("bad", listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.8), CurvePoint(1.0, 0.4))),
            EditOperation.Curves("bad", listOf(CurvePoint(0.1, 0.0), CurvePoint(1.0, 1.0))),
            EditOperation.ColorMix("bad", custom = (1..4).map { SampledHue("#112233", it.toDouble(), 0.0, 0.0) }),
            EditOperation.ColorMix("bad", presets = listOf(HueAdjustment(PresetHue.RED, Double.NaN, 0.0, 0.0))),
            EditOperation.SpotColor("bad", keep = listOf("#xyzxyz")),
            EditOperation.SpotColor("bad", keep = listOf("#abcdef"), remove = listOf("#abcdef")),
            EditOperation.ShapeCrop("bad", polygon = listOf(Point2(0.0, 0.0), Point2(2.0, 0.0), Point2(0.0, 1.0))),
            EditOperation.ShapeCrop("bad", preset = CropShape.CIRCLE, outside = CropOutside.SOLID),
            EditOperation.Resize("bad", longEdge = 100, percentage = 50.0),
            EditOperation.Resize("bad", percentage = -1.0),
        )
        for (op in bad) assertTrue("$op unexpectedly valid", EditRecipe(100, 100, listOf(op)).validate().isNotEmpty())
        val v3 = """{"schema":"atlas.edit-recipe","version":3,"source":{"width":1,"height":1},"operations":[]}"""
        try { RecipeJson.decode(v3); fail("v3 accepted") }
        catch (e: RecipeFormatException) { assertTrue(e.message.orEmpty().contains("newer")) }
    }

    @Test fun undoRedoAndPreviewStillUseOneOperation() {
        val initial = EditHistory.start(EditRecipe(100, 100))
        val previewed = initial.preview(ops().first())
        val committed = previewed.commit()
        assertEquals(1, committed.current.operations.size)
        assertTrue(committed.undo().current.operations.isEmpty())
        assertEquals(committed.current, committed.undo().redo().current)
    }

    @Test fun percentResizePreservesAspectAndCanUpscale() {
        val plan = RenderPlan.build(EditRecipe(400, 300, listOf(EditOperation.Resize("pct", percentage = 150.0))))
        assertEquals(600, plan.outputWidth)
        assertEquals(450, plan.outputHeight)
    }
}
