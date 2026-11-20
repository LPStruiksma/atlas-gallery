package nl.atlas.gallery.featureeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private val photo = EditRecipe(4000, 3000)

    @Test fun undoAndRedoWalkTheHistory() {
        var h = EditHistory.start(photo)
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        val ops = listOf(
            EditOperation.Adjust("a", AdjustParam.EXPOSURE, 0.2),
            EditOperation.Rotate90("b", 1),
            EditOperation.Filter("c", "atlas-mono", null, 0.8),
        )
        ops.forEach { h = h.apply(it) }
        assertEquals(ops, h.current.operations)
        h = h.undo()
        assertEquals(ops.take(2), h.current.operations)
        assertTrue(h.canRedo)
        h = h.undo().undo()
        assertTrue(h.current.isEmpty); assertFalse(h.canUndo)
        assertEquals(h, h.undo())
        h = h.redo().redo().redo()
        assertEquals(ops, h.current.operations)
        assertFalse(h.canRedo)
        assertEquals(h, h.redo())
    }

    @Test fun newCommitClearsRedo() {
        var h = EditHistory.start(photo)
            .apply(EditOperation.Adjust("a", AdjustParam.EXPOSURE, 0.2))
            .apply(EditOperation.Adjust("b", AdjustParam.EXPOSURE, 0.4))
            .undo()
        assertTrue(h.canRedo)
        h = h.apply(EditOperation.Adjust("c", AdjustParam.CONTRAST, 0.1))
        assertFalse(h.canRedo)
        assertEquals(listOf("a", "c"), h.current.operations.map { it.id })
    }

    @Test fun aSliderDragIsOneUndoStep() {
        var h = EditHistory.start(photo)
        for (i in 1..300) h = h.preview(EditOperation.Adjust("drag", AdjustParam.BRIGHTNESS, i / 1000.0))
        assertEquals(0.3, (h.current.operations.single() as EditOperation.Adjust).value, 0.0)
        assertTrue(h.committed.isEmpty)
        h = h.commit()
        assertEquals(1, h.applied.size)
        assertTrue(h.undo().current.isEmpty)
    }

    @Test fun undoDuringADragCancelsOnlyTheDrag() {
        val h = EditHistory.start(photo)
            .apply(EditOperation.Rotate90("a", 1))
            .preview(EditOperation.Straighten("b", 4.0))
        assertEquals(listOf("a"), h.undo().current.operations.map { it.id })
        assertFalse(h.canRedo)
        assertEquals(listOf("a"), h.cancelPending().current.operations.map { it.id })
    }

    @Test fun compareShowsTheOriginalWithoutTouchingHistory() {
        val saved = photo.copy(operations = listOf(EditOperation.Adjust("s", AdjustParam.SATURATION, 0.5)))
        var h = EditHistory.start(saved).apply(EditOperation.Rotate90("a", 2))
        h = h.setComparing(true)
        assertTrue(h.displayed.isEmpty)
        assertEquals(2, h.current.operations.size)
        h = h.setComparing(false)
        assertEquals(h.current, h.displayed)
        // Any edit ends a compare.
        assertFalse(h.setComparing(true).apply(EditOperation.Flip("b", FlipAxis.VERTICAL)).comparing)
    }

    @Test fun reopenedEditCanBeUndoneBackToTheOriginal() {
        val saved = photo.copy(operations = listOf(EditOperation.Crop("s", NormRect(0.1, 0.1, 0.9, 0.9))))
        val h = EditHistory.start(saved)
        assertFalse(h.isDirty)
        assertTrue(h.canUndo)
        assertTrue(h.undo().isDirty)
        assertTrue(h.undo().committed.isEmpty)
    }

    @Test fun revertAllThenRedoRestoresInOrder() {
        var h = EditHistory.start(photo)
        val ops = (1..4).map { EditOperation.Rotate90("r$it", 1) }
        ops.forEach { h = h.apply(it) }
        h = h.revertAll()
        assertTrue(h.current.isEmpty)
        repeat(4) { h = h.redo() }
        assertEquals(ops, h.current.operations)
    }

    @Test fun aiPatchesShareTheOneHistory() {
        val patch = EditOperation.RasterPatch("p", PatchTool.FACE_SWAP, EngineFixtures.hash('a'), NormRect(0.4, 0.2, 0.6, 0.5), 512, 512)
        var h = EditHistory.start(photo)
            .apply(EditOperation.Adjust("a", AdjustParam.EXPOSURE, 0.3))
            .apply(patch)
            .apply(EditOperation.Crop("c", NormRect(0.0, 0.0, 0.5, 0.5)))
        assertEquals(setOf(EngineFixtures.hash('a')), h.referencedContentHashes())
        h = h.undo().undo()
        assertEquals(listOf("a"), h.current.operations.map { it.id })
        // Undone patches stay referenced while redo can bring them back.
        assertEquals(setOf(EngineFixtures.hash('a')), h.referencedContentHashes())
        h = h.apply(EditOperation.Adjust("b", AdjustParam.CONTRAST, 0.1))
        assertTrue(h.referencedContentHashes().isEmpty())
    }

    @Test fun invalidOrDuplicateOperationsAreRejected() {
        val h = EditHistory.start(photo).apply(EditOperation.Rotate90("a", 1))
        assertThrows { h.apply(EditOperation.Rotate90("a", 1)) }
        assertThrows { h.apply(EditOperation.Adjust("b", AdjustParam.SHARPNESS, -0.5)) }
        assertThrows { h.apply(EditOperation.Straighten("c", Double.NaN)) }
        assertThrows { EditHistory.start(photo.copy(operations = listOf(EditOperation.ResetGeometry("x"), EditOperation.ResetGeometry("x")))) }
    }

    @Test fun nextIdNeverCollides() {
        var h = EditHistory.start(photo)
        repeat(20) { h = h.apply(EditOperation.Rotate90(h.nextId(), 1)) }
        h = h.undo().undo()
        val id = h.nextId()
        assertFalse((h.applied + h.redoStack).any { it.id == id })
        assertNotEquals(h.nextId("ai"), h.nextId())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
