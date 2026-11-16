package nl.atlas.gallery.featureaiedit.mask

import org.junit.Assert.*
import org.junit.Test

class MaskStrokeModelTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-9) =
        assertEquals(expected, actual, tolerance)

    @Test fun everyExifOrientationHasExactCornersAndInverse() {
        val expected = mapOf(
            1 to Pair(0.0, 0.0),
            2 to Pair(1.0, 0.0),
            3 to Pair(1.0, 1.0),
            4 to Pair(0.0, 1.0),
            5 to Pair(0.0, 0.0),
            6 to Pair(1.0, 0.0),
            7 to Pair(1.0, 1.0),
            8 to Pair(0.0, 1.0)
        )
        for (orientation in 1..8) {
            val t = ImageOrientation(400, 200, orientation)
            val mapped = t.originalToOriented(PixelPoint(0.0, 0.0))
            near(expected.getValue(orientation).first, mapped.x / t.orientedWidth)
            near(expected.getValue(orientation).second, mapped.y / t.orientedHeight)
            assertEquals(if (orientation in 5..8) 200 else 400, t.orientedWidth)
            assertEquals(if (orientation in 5..8) 400 else 200, t.orientedHeight)
            for (x in listOf(0.0, 40.0, 400.0)) for (y in listOf(0.0, 70.0, 200.0)) {
                val point = PixelPoint(x, y)
                val roundTrip = t.orientedToOriginal(t.originalToOriented(point))
                near(x, roundTrip.x)
                near(y, roundTrip.y)
            }
        }
        assertEquals(PixelPoint(200.0, 0.0), ImageOrientation(400, 200, 6)
            .originalToOriented(PixelPoint(0.0, 0.0)))
    }

    @Test fun fourQuarterTurnsReturnToStart() {
        val source = ImageOrientation(640, 360, 1)
        val point = PixelPoint(125.0, 72.0)
        val rotated = ImageOrientation(640, 360, 6).originalToOriented(point)
        near(288.0, rotated.x)
        near(125.0, rotated.y)
        near(point.x, ImageOrientation(640, 360, 6).orientedToOriginal(rotated).x)
        for (orientation in 1..8) {
            val t = ImageOrientation(640, 360, orientation)
            for (x in 0..10) for (y in 0..10) {
                val original = PixelPoint(x * 64.0, y * 36.0)
                val back = t.orientedToOriginal(t.originalToOriented(original))
                near(original.x, back.x)
                near(original.y, back.y)
            }
        }
        assertEquals(source.originalToOriented(point), point)
    }

    @Test fun fitCropAndOutsideViewportAreInvertibleWithoutSilentClamping() {
        val t = ImageOrientation(400, 200, 1)
        val fit = t.viewport(300.0, 300.0, ScaleMode.FIT)
        near(0.0, fit.orientedToViewport(PixelPoint(0.0, 0.0)).x)
        near(75.0, fit.orientedToViewport(PixelPoint(0.0, 0.0)).y)
        near(300.0, fit.orientedToViewport(PixelPoint(400.0, 200.0)).x)
        val crop = t.viewport(300.0, 300.0, ScaleMode.CROP)
        near(-150.0, crop.orientedToViewport(PixelPoint(0.0, 0.0)).x)
        val outside = PixelPoint(-20.0, 330.0)
        val restored = crop.viewportToOriented(crop.orientedToViewport(outside))
        near(outside.x, restored.x)
        near(outside.y, restored.y)
    }

    @Test fun clippedBrushesPreserveBoundedNormalizedGeometry() {
        val builder = MaskHistory().add(MaskStroke.clipped(
            BrushMode.ADD,
            0.25,
            listOf(NormalizedPoint(-4.0, 0.2), NormalizedPoint(0.5, 1.4), NormalizedPoint(4.0, 0.8))
        ))
        val stroke = builder.strokes.single()
        assertEquals(listOf(NormalizedPoint(0.0, 0.2), NormalizedPoint(0.5, 1.0),
            NormalizedPoint(1.0, 0.8)), stroke.points)
        assertEquals(0.25, stroke.radius, 0.0)
        assertEquals(NormalizedBounds(0.0, 0.0, 1.0, 1.0), stroke.bounds)
        assertEquals(BrushMode.ADD, stroke.mode)
        assertTrue(builder.strokes.all { s -> s.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } })
    }

    @Test fun polygonBoundsAndUndoRedoHaveStableSerialization() {
        val a = MaskStroke(BrushMode.ADD, 0.04, listOf(
            NormalizedPoint(0.1, 0.2), NormalizedPoint(0.4, 0.5)))
        val b = MaskStroke(BrushMode.REMOVE, 0.1, listOf(NormalizedPoint(0.8, 0.9)))
        val first = MaskHistory().add(a).add(b)
        near(0.06, a.bounds.left)
        near(0.16, a.bounds.top)
        near(0.44, a.bounds.right)
        near(0.54, a.bounds.bottom)
        assertEquals(listOf(a, b), first.strokes)
        val undone = first.undo()
        assertEquals(listOf(a), undone.strokes)
        assertEquals(first.strokes, undone.redo().strokes)
        assertEquals(first.serialize(), undone.redo().serialize())
        assertEquals(first, MaskHistory.parse(first.serialize()))
        assertEquals(a, MaskStroke.parse(a.serialize()))
        assertEquals(listOf(a), undone.add(a).strokes.dropLast(1))
        assertEquals(listOf(a, a), undone.add(a).strokes)
        assertEquals(undone.add(a), undone.add(a).redo())
        assertTrue(MaskHistory().undo().strokes.isEmpty())
    }

    @Test fun hugeAndZeroDimensionsAndMalformedDataFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { ImageOrientation(0, 100, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImageOrientation(100, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImageOrientation(10, 10, 9) }
        val huge = ImageOrientation(Int.MAX_VALUE, Int.MAX_VALUE, 8)
        val p = PixelPoint(1_000_000_000.0, 123456789.0)
        val q = huge.orientedToOriginal(huge.originalToOriented(p))
        near(p.x, q.x)
        near(p.y, q.y)
        assertThrows(IllegalArgumentException::class.java) { huge.viewport(0.0, 200.0, ScaleMode.FIT) }
        assertThrows(IllegalArgumentException::class.java) { MaskStroke(BrushMode.ADD, -1.0, listOf(NormalizedPoint(0.5, 0.5))) }
        assertThrows(IllegalArgumentException::class.java) { MaskStroke(BrushMode.ADD, Double.NaN, listOf(NormalizedPoint(0.5, 0.5))) }
        assertThrows(IllegalArgumentException::class.java) { MaskHistory.parse("MASK1|a|invalid") }
    }
}
