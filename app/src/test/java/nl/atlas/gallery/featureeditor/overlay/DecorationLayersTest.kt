package nl.atlas.gallery.featureeditor.overlay

import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.FlipAxis
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayKind
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.Point2
import nl.atlas.gallery.featureeditor.engine.RecipeJson
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.engine.RenderStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorationLayersTest {
    private val p = DecorationPlacement("editable", 0.5, 0.5, scale = 1.5,
        rotationDegrees = 22.0, opacity = 0.7)

    @Test fun fiveKindsRoundTripWithoutLosingEditableFields() {
        val layers: List<DecorationLayer> = listOf(
            StrokeLayer(p, StrokeMode.PEN, 0xff123456.toInt(), 0.02,
                listOf(InkPoint(-0.1, -0.03), InkPoint(0.2, 0.04, 1.4))),
            ShapeLayer(p, ShapeType.ARROW, Point2(-0.1, -0.1), Point2(0.3, 0.2),
                0xffaabbcc.toInt(), 0.01, snapped = true),
            TextLayer(p, "line 1\nline 2", TextFace.SERIF, 0.1, 0xffddeeff.toInt(),
                TextAlign.RIGHT, 0.8, TextBackground.ROUNDED, 0xff010203.toInt()),
            EmojiLayer(p, "🌈", 0.3),
            StickerLayer(p, "a".repeat(64), 0.4, 0.3),
        )
        for (layer in layers) {
            val raw = DecorationCodec.encode(layer)
            assertNull(raw.validate())
            assertEquals(layer, DecorationCodec.decode(raw))
            val json = RecipeJson.encode(EditRecipe(800, 600,
                listOf(EditOperation.OverlayAdd("add", raw))))
            val restored = RecipeJson.decode(json).operations.single() as EditOperation.OverlayAdd
            assertEquals(layer, DecorationCodec.decode(restored.layer))
        }
    }

    @Test fun savedRecipeCanUpdateLayerWithoutFlatteningOrChangingZOrder() {
        val old = TextLayer(p, "before", TextFace.SANS, 0.08,
            0xffffffff.toInt(), TextAlign.LEFT, 0.5)
        val next = old.copy(placement = old.placement.moved(0.7, 0.25)
            .transformed(2.0, -35.0), text = "after", align = TextAlign.CENTER)
        val a = DecorationCodec.encode(old)
        val b = DecorationCodec.encode(EmojiLayer(p.copy(layerId = "other"), "⭐", 0.2))
        val recipe = EditRecipe(200, 100, listOf(
            EditOperation.OverlayAdd("a", a),
            EditOperation.OverlayAdd("b", b),
        ))
        val saved = RecipeJson.decode(RecipeJson.encode(recipe))
        val revised = saved.copy(operations = saved.operations +
            EditOperation.OverlayUpdate("c", DecorationCodec.encode(next)))
        val layers = RenderPlan.build(revised).stages.filterIsInstance<RenderStage.Overlays>().single().layers
        assertEquals(listOf("editable", "other"), layers.map { it.layerId })
        assertEquals(next, DecorationCodec.decode(layers.first()))
        assertEquals(0.7, layers.first().centerX, 1e-10)
    }

    @Test fun smoothCurveInterpolatesEndpointsAndPressure() {
        val raw = listOf(
            InkPoint(-0.3, 0.0, 0.5), InkPoint(-0.1, 0.1, 1.0),
            InkPoint(0.1, 0.1, 2.0), InkPoint(0.3, 0.0, 0.7),
        )
        val curve = DecorationGeometry.smooth(raw, subdivisions = 8)
        assertEquals(25, curve.size)
        assertEquals(raw.first(), curve.first())
        assertEquals(raw.last(), curve.last())
        assertTrue(curve.all { it.pressure != null && it.pressure in 0.1..4.0 })
        assertTrue(curve.drop(1).zipWithNext().all {
            (a, b) -> kotlin.math.abs(a.x - b.x) < 0.15
        })
    }

    @Test fun snappingConstrainsDiagonalAndCircle() {
        val a = Point2(0.0, 0.0)
        val line = DecorationGeometry.snappedEnd(a, Point2(0.8, 0.12), ShapeType.LINE)
        assertEquals(0.0, line.y, 1e-9)
        val ellipse = DecorationGeometry.snappedEnd(a, Point2(-0.8, 0.3), ShapeType.ELLIPSE)
        assertEquals(-0.3, ellipse.x, 1e-9)
        assertEquals(0.3, ellipse.y, 1e-9)
    }

    @Test fun strokeHitFollowsGeometryAndOverlayPlacementThroughRotateFlipCrop() {
        val stroke = StrokeLayer(p, StrokeMode.HIGHLIGHTER, 0x77880000,
            0.02, listOf(InkPoint(-0.12, 0.0), InkPoint(0.12, 0.0)))
        val recipe = EditRecipe(400, 300, listOf(
            EditOperation.OverlayAdd("a", DecorationCodec.encode(stroke)),
            EditOperation.Rotate90("r", 1),
            EditOperation.Flip("f", FlipAxis.HORIZONTAL),
            EditOperation.Crop("c", NormRect(0.1, 0.1, 0.9, 0.9)),
        ))
        val plan = RenderPlan.build(recipe)
        val stage = plan.stages.filterIsInstance<RenderStage.Overlays>().single()
        val space = DecorationSpace(400, 300, stage.sourceToOutput)
        val target = space.localToOutput(stroke.placement, Point2(0.0, 0.0))
        assertEquals(0.0, space.outputToLocal(stroke.placement, target).x, 1e-10)
        assertTrue(DecorationGeometry.hitTest(stroke, target, space, 0.0))
        assertFalse(DecorationGeometry.hitTest(stroke, Point2(target.x + 800, target.y + 800), space, 0.0))
        assertEquals("editable", DecorationGeometry.hitTopmost(stage.layers, target, space))
    }

    @Test fun strokeSelectionMatchesHighLowPressureAndEndpointCapPixels() {
        val placement = p.copy(scale = 1.0, rotationDegrees = 0.0, opacity = 1.0)
        val high = StrokeLayer(placement, StrokeMode.PEN, 0xff000000.toInt(), 0.02,
            listOf(InkPoint(-0.3, 0.0, 4.0), InkPoint(0.3, 0.0, 4.0)))
        val low = high.copy(points = listOf(
            InkPoint(-0.3, 0.0, 0.1), InkPoint(0.3, 0.0, 0.1)))
        val stage = RenderPlan.build(EditRecipe(200, 200, listOf(
            EditOperation.OverlayAdd("ink", DecorationCodec.encode(high)))))
            .stages.filterIsInstance<RenderStage.Overlays>().single()
        val space = DecorationSpace(200, 200, stage.sourceToOutput)
        fun hits(layer: StrokeLayer, x: Double, y: Double) = DecorationGeometry.hitTest(
            layer, space.localToOutput(placement, Point2(x, y)), space, 0.0)

        assertTrue(hits(high, 0.0, 0.03))
        assertFalse(hits(high, 0.0, 0.05))
        assertTrue(hits(low, 0.0, 0.0005))
        assertFalse(hits(low, 0.0, 0.003))
        assertTrue(hits(high, 0.33, 0.0))
        assertFalse(hits(low, 0.33, 0.0))
        val dot = high.copy(points = listOf(InkPoint(0.0, 0.0, 4.0)))
        assertTrue(hits(dot, 0.03, 0.0))
        assertFalse(hits(dot, 0.05, 0.0))
    }

    @Test fun zOrderCanMoveAnExistingLayerToTheFrontUsingRecipeOperations() {
        val a = DecorationCodec.encode(EmojiLayer(p.copy(layerId = "a"), "🎨", 0.2))
        val b = DecorationCodec.encode(EmojiLayer(p.copy(layerId = "b"), "🎨", 0.2))
        val operations = listOf(
            EditOperation.OverlayAdd("add-a", a), EditOperation.OverlayAdd("add-b", b),
        ) + DecorationGeometry.bringToFront(a, "remove-a", "readd-a")
        val stage = RenderPlan.build(EditRecipe(100, 100, operations))
            .stages.filterIsInstance<RenderStage.Overlays>().single()
        assertEquals(listOf("b", "a"), stage.layers.map { it.layerId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptPropertiesAreNotSilentlyRendered() {
        DecorationCodec.decode(OverlayLayer("x", OverlayKind.STROKE, 0.5, 0.5,
            props = mapOf("d.version" to "1", "points" to "not,a,point", "mode" to "PEN",
                "color" to "ffffffff", "width" to "0.01")))
    }

    @Test fun stickerBitmapHashesRemainTrackedByTheRecipe() {
        val sticker = DecorationCodec.encode(StickerLayer(p, "b".repeat(64), 0.2, 0.3))
        val recipe = EditRecipe(500, 500, listOf(EditOperation.OverlayAdd("add", sticker)))
        assertEquals(setOf("b".repeat(64)), recipe.referencedContentHashes())
        assertNotNull(DecorationCodec.decode(sticker))
    }
}
