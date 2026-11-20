package nl.atlas.gallery.featureeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

class GeometryTest {
    private val w = 4000
    private val h = 3000

    private fun geometry(vararg ops: EditOperation) = GeometryState.of(EditRecipe(w, h, ops.toList()))

    @Test fun orientationFormsTheRectangleSymmetryGroup() {
        val o = Orientation.NONE
        assertEquals(o, o.rotatedClockwise(4))
        assertEquals(o, o.rotatedClockwise(1).rotatedClockwise(-1))
        assertEquals(o, o.flipped(FlipAxis.HORIZONTAL).flipped(FlipAxis.HORIZONTAL))
        assertEquals(o.rotatedClockwise(2), o.flipped(FlipAxis.HORIZONTAL).flipped(FlipAxis.VERTICAL))
        // Turn then mirror equals the matrix product of the two display operations.
        for (q in 0..3) for (m in listOf(false, true)) {
            val start = Orientation(q, m)
            val turned = start.rotatedClockwise(1)
            val expected = Orientation(1, false).matrix(if (start.swapsAxes) 3.0 else 4.0, if (start.swapsAxes) 4.0 else 3.0) * start.matrix(4.0, 3.0)
            assertMatrixEquals(expected, turned.matrix(4.0, 3.0))
            val mirroredH = start.flipped(FlipAxis.HORIZONTAL)
            val cw = if (start.swapsAxes) 3.0 else 4.0
            val flip = Matrix3(doubleArrayOf(-1.0, 0.0, cw, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
            assertMatrixEquals(flip * start.matrix(4.0, 3.0), mirroredH.matrix(4.0, 3.0))
        }
    }

    @Test fun quarterTurnSwapsCanvasAndMapsCornersExactly() {
        val g = geometry(EditOperation.Rotate90("r", 1))
        assertEquals(3000, g.canvasWidth); assertEquals(4000, g.canvasHeight)
        assertEquals(3000, g.outputWidth); assertEquals(4000, g.outputHeight)
        // Source top-left goes to output top-right after a clockwise turn.
        assertPoint(Point2(3000.0, 0.0), g.sourceToOutput().map(0.0, 0.0))
        assertPoint(Point2(3000.0, 4000.0), g.sourceToOutput().map(4000.0, 0.0))
    }

    @Test fun cropFollowsTheDisplayWhenRotatingOrFlipping() {
        val crop = NormRect(0.1, 0.2, 0.5, 0.6)
        val base = geometry(EditOperation.Crop("c", crop))
        val turned = geometry(EditOperation.Crop("c", crop), EditOperation.Rotate90("r", 1))
        // The same source pixels are inside the crop before and after the turn.
        val inside = base.outputToSource(Point2(0.3, 0.3))
        val mapped = turned.sourceToOutputNormalized(inside)
        assertTrue(mapped.x in 0.0..1.0 && mapped.y in 0.0..1.0)
        assertEquals(base.outputWidth, turned.outputHeight)
        assertEquals(base.outputHeight, turned.outputWidth)
        val flipped = geometry(EditOperation.Crop("c", crop), EditOperation.Flip("f", FlipAxis.HORIZONTAL))
        assertEquals(NormRect(0.5, 0.2, 0.9, 0.6), flipped.requestedCrop)
        assertEquals(base.outputWidth, flipped.outputWidth)
    }

    @Test fun flippingKeepsTheVisibleStraightenSenseConsistent() {
        val g = geometry(EditOperation.Straighten("s", 5.0), EditOperation.Flip("f", FlipAxis.HORIZONTAL))
        assertEquals(-5.0, g.straightenDegrees, 0.0)
        // Same source-space rotation as before the flip: the photo content did not re-rotate.
        assertEquals(5.0, g.sourceStraightenDegrees, 0.0)
    }

    @Test fun straightenShrinksTheCropAndZeroRestoresIt() {
        val crop = NormRect(0.0, 0.0, 1.0, 1.0)
        val tilted = geometry(EditOperation.Crop("c", crop), EditOperation.Straighten("s", 10.0))
        assertTrue(tilted.effectiveCrop.width < 1.0)
        assertTrue(tilted.contains(tilted.effectiveCrop))
        assertEquals(tilted.effectiveCrop.width * w / (tilted.effectiveCrop.height * h), 4.0 / 3.0, 1e-6)
        val back = geometry(EditOperation.Crop("c", crop), EditOperation.Straighten("s", 10.0), EditOperation.Straighten("t", 0.0))
        assertEquals(NormRect.FULL, back.effectiveCrop)
        assertTrue(back.isIdentity)
    }

    @Test fun keystonePresetsAreValidAndKeepTheFullFrame() {
        for (v in listOf(-1.0, -0.3, 0.0, 0.5, 1.0)) for (hz in listOf(-1.0, 0.0, 0.7)) {
            val q = Quad.fromKeystone(v, hz)
            assertTrue("$v $hz", q.isValid())
            val g = geometry(EditOperation.Perspective("p", q))
            // Inward corners only: the warped picture covers the whole canvas.
            assertEquals(NormRect.FULL, g.effectiveCrop)
        }
        assertFalse(Quad(Point2(0.0, 0.0), Point2(0.0, 1.0), Point2(1.0, 1.0), Point2(1.0, 0.0)).isValid())
        // Convex but wrapping through the vanishing line: refused.
        assertFalse(Quad(Point2(0.0, 0.0), Point2(1.0, 0.25), Point2(0.75, 0.75), Point2(0.25, 1.0)).isValid())
        // Convex and non-wrapping but entirely off the picture: nothing to render, refused.
        val offCanvas = Quad(Point2(1.2, 0.0), Point2(1.8, 0.0), Point2(1.8, 1.0), Point2(1.2, 1.0))
        assertFalse(offCanvas.isValid())
        assertTrue(EditOperation.Perspective("p", offCanvas).validate() != null)
        try {
            RenderPlan.build(EditRecipe(w, h, listOf(EditOperation.Perspective("p", offCanvas))))
            throw AssertionError("off-canvas quad must be refused")
        } catch (_: IllegalArgumentException) {
        }
        // A GeometryState built around validation still never reports an empty crop as fine.
        val unchecked = GeometryState(w, h, perspective = offCanvas)
        assertFalse(unchecked.contains(NormRect.FULL))
        try {
            unchecked.effectiveCrop
            throw AssertionError("empty crop region must not yield a crop")
        } catch (_: IllegalStateException) {
        }
        // Full strength on one axis is kept exactly.
        assertEquals(Point2(0.25, 0.0), Quad.fromKeystone(1.0, 0.0).topLeft)
    }

    @Test fun perspectiveSendsTheQuadToTheFrameCorners() {
        val q = Quad(Point2(0.1, 0.05), Point2(0.95, 0.0), Point2(0.9, 0.9), Point2(0.0, 1.0))
        val g = geometry(EditOperation.Perspective("p", q))
        val m = g.sourceToCanvas
        val expected = listOf(Point2(0.0, 0.0), Point2(4000.0, 0.0), Point2(4000.0, 3000.0), Point2(0.0, 3000.0))
        q.points().zip(expected).forEach { (p, e) -> assertPoint(e, m.map(p.x * w, p.y * h)) }
    }

    /** Property: whatever the geometry, the effective crop never shows outside the picture. */
    @Test fun cropAlwaysStaysInsideTheWarpedImage() {
        val rnd = Random(631)
        repeat(3000) { case ->
            val g = randomGeometry(rnd)
            assertTrue("case $case: ${g.effectiveCrop}", g.contains(g.effectiveCrop, 1e-9))
            assertTrue("case $case valid", g.effectiveCrop.isValid())
            val aspect = listOf(null, 1.0, 4.0 / 3.0, 9.0 / 16.0, 21.0 / 9.0)[rnd.nextInt(5)]
            val largest = g.largestCrop(aspect)
            assertTrue("case $case largest", g.contains(largest, 1e-9))
            if (aspect != null) {
                val pixelAspect = largest.width * g.canvasWidth / (largest.height * g.canvasHeight)
                assertEquals("case $case aspect", aspect, pixelAspect, 1e-6 * aspect)
            }
        }
    }

    /** Property: output -> source inverse of source -> output is identity within 0.5 px. */
    @Test fun inverseOfForwardIsIdentity() {
        val rnd = Random(629)
        var worst = 0.0
        repeat(3000) {
            val g = randomGeometry(rnd)
            val forward = g.sourceToOutput()
            val inverse = forward.inverse()
            repeat(20) {
                val p = Point2(rnd.nextDouble() * w, rnd.nextDouble() * h)
                val back = inverse.map(forward.map(p))
                worst = maxOf(worst, hypot(back.x - p.x, back.y - p.y))
                // And the other way: a tap on the output maps back onto the same output pixel.
                val tap = Point2(rnd.nextDouble(), rnd.nextDouble())
                val again = g.sourceToOutputNormalized(g.outputToSource(tap))
                worst = maxOf(worst, hypot((again.x - tap.x) * g.outputWidth, (again.y - tap.y) * g.outputHeight))
            }
        }
        assertTrue("worst round trip $worst px", worst < 0.5)
    }

    /** Ultra HDR: a gainmap at any resolution is warped with the same normalized transform. */
    @Test fun gainmapGeometryMatchesTheBaseImage() {
        val rnd = Random(4242)
        repeat(500) {
            val g = randomGeometry(rnd)
            val recipe = EditRecipe(w, h, listOf(
                EditOperation.Perspective("p", g.perspective),
                EditOperation.Straighten("s", if (g.orientation.mirrored) -g.straightenDegrees else g.straightenDegrees),
            ) + orientationOps(g.orientation) + EditOperation.Crop("c", g.requestedCrop))
            val plan = RenderPlan.build(recipe)
            val gain = plan.gainmap(1000, 750)
            assertEquals(plan.outputWidth / 4.0, gain.width.toDouble(), 1.0)
            assertEquals(plan.outputHeight / 4.0, gain.height.toDouble(), 1.0)
            repeat(10) {
                val u = rnd.nextDouble(); val v = rnd.nextDouble()
                val base = plan.geometry.sourceToOutput().map(u * w, v * h)
                val gp = gain.sourceToOutput.map(u * 1000, v * 750)
                assertEquals(base.x / plan.outputWidth, gp.x / gain.width, 2e-3)
                assertEquals(base.y / plan.outputHeight, gp.y / gain.height, 2e-3)
            }
        }
    }

    @Test fun recipeGeometryFoldMatchesRandomStateRebuild() {
        val g = geometry(EditOperation.Rotate90("a", 3), EditOperation.Flip("b", FlipAxis.VERTICAL), EditOperation.Straighten("c", -7.0))
        // V * R(3) = R(2) * H * R(3) = R(-1) * H
        assertEquals(Orientation(3, true), g.orientation)
        assertEquals(-7.0, g.straightenDegrees, 0.0)
        assertTrue(geometry(EditOperation.Rotate90("a", 1), EditOperation.ResetGeometry("b")).isIdentity)
    }

    private fun orientationOps(o: Orientation): List<EditOperation> = buildList {
        if (o.mirrored) add(EditOperation.Flip("m", FlipAxis.HORIZONTAL))
        if (o.quarterTurns != 0) add(EditOperation.Rotate90("q", o.quarterTurns))
    }

    private fun randomGeometry(rnd: Random): GeometryState {
        val orientation = Orientation(rnd.nextInt(4), rnd.nextBoolean())
        val straighten = if (rnd.nextInt(4) == 0) 0.0 else (rnd.nextDouble() * 90 - 45)
        val quad = when (rnd.nextInt(3)) {
            0 -> Quad.IDENTITY
            1 -> Quad.fromKeystone(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1)
            else -> randomConvexQuad(rnd)
        }
        val l = rnd.nextDouble() * 0.6; val t = rnd.nextDouble() * 0.6
        val crop = if (rnd.nextInt(3) == 0) NormRect.FULL
        else NormRect(l, t, l + 0.05 + rnd.nextDouble() * (0.95 - l), t + 0.05 + rnd.nextDouble() * (0.95 - t))
        return GeometryState(w, h, orientation, straighten, quad, crop)
    }

    private fun randomConvexQuad(rnd: Random): Quad {
        while (true) {
            fun j() = rnd.nextDouble() * 0.5 - 0.2
            val q = Quad(Point2(j(), j()), Point2(1 - j(), j()), Point2(1 - j(), 1 - j()), Point2(j(), 1 - j()))
            if (q.isValid()) return q
        }
    }

    private fun assertPoint(expected: Point2, actual: Point2, tol: Double = 1e-6) {
        assertTrue("$expected vs $actual", abs(expected.x - actual.x) <= tol && abs(expected.y - actual.y) <= tol)
    }

    private fun assertMatrixEquals(a: Matrix3, b: Matrix3) {
        val x = a.normalized().values(); val y = b.normalized().values()
        for (i in 0..8) assertEquals("entry $i", x[i], y[i], 1e-9)
    }
}
