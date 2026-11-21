package nl.atlas.gallery.featureeditor.save

import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.NormRect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** EDIT-3 (#633): the app-private original/recipe journal, on a temp folder only. */
class EditorSaveStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val uri = "content://media/external/images/media/42"
    private val recipe1 = EditRecipe(400, 300, listOf(EditOperation.Rotate90("r", 1)))
    private val recipe2 = EditRecipe(400, 300, listOf(EditOperation.Rotate90("r", 1),
        EditOperation.Adjust("a", AdjustParam.entries.first(), 0.25)))
    private val original = "ORIGINAL".toByteArray()
    private val edit1 = "EDIT-ONE".toByteArray()
    private val edit2 = "EDIT-TWO".toByteArray()

    private fun store() = EditorSaveStore(tmp.root)

    @Test fun firstSaveKeepsOriginalAndCommitsRecipe() {
        val s = store()
        val writing = s.prepareInPlace(uri, original, edit1, recipe1)
        assertEquals(EditorSaveStore.State.WRITING, writing.state)
        assertNull("nothing committed yet", s.recipe(uri))
        assertArrayEquals(edit1, s.pendingFile(uri).readBytes())
        s.commitInPlace(uri)
        val e = s.entry(uri)!!
        assertEquals(EditorSaveStore.State.COMMITTED, e.state)
        assertEquals(EditorSaveStore.sha256(edit1), e.savedSha)
        assertEquals(EditorSaveStore.sha256(original), e.originalSha)
        assertArrayEquals(original, s.originalFile(uri).readBytes())
        assertEquals(recipe1, s.recipe(uri))
        assertFalse(s.pendingFile(uri).exists())
    }

    @Test fun reEditingAnUnchangedAtlasEditKeepsTheTrueOriginal() {
        val s = store()
        s.prepareInPlace(uri, original, edit1, recipe1); s.commitInPlace(uri)
        s.prepareInPlace(uri, edit1, edit2, recipe2); s.commitInPlace(uri)
        assertArrayEquals("Revert must reach the first original", original, s.originalFile(uri).readBytes())
        assertEquals(recipe2, s.recipe(uri))
        assertEquals(EditorSaveStore.sha256(edit2), s.entry(uri)!!.savedSha)
    }

    @Test fun aPhotoChangedOutsideAtlasStartsANewRecord() {
        val s = store()
        s.prepareInPlace(uri, original, edit1, recipe1); s.commitInPlace(uri)
        val outside = "CHANGED-BY-ANOTHER-APP".toByteArray()
        s.prepareInPlace(uri, outside, edit2, recipe2); s.commitInPlace(uri)
        assertArrayEquals(outside, s.originalFile(uri).readBytes())
    }

    @Test fun abortReturnsToThePreviousStateOrToNothing() {
        val s = store()
        s.prepareInPlace(uri, original, edit1, recipe1)
        s.abortPending(uri)
        assertNull("a first save that never reached MediaStore leaves no record", s.entry(uri))
        s.prepareInPlace(uri, original, edit1, recipe1); s.commitInPlace(uri)
        s.prepareInPlace(uri, edit1, edit2, recipe2)
        s.abortPending(uri)
        val e = s.entry(uri)!!
        assertEquals(EditorSaveStore.State.COMMITTED, e.state)
        assertEquals(EditorSaveStore.sha256(edit1), e.savedSha)
        assertEquals(recipe1, s.recipe(uri))
        assertArrayEquals(original, s.originalFile(uri).readBytes())
    }

    @Test fun journalSurvivesANewStoreInstanceLikeAProcessRestart() {
        store().prepareInPlace(uri, original, edit1, recipe1)
        val reopened = store()
        val pending = reopened.entries().single()
        assertEquals(EditorSaveStore.State.WRITING, pending.state)
        assertEquals(EditorSaveStore.sha256(edit1), pending.pendingSha)
        assertArrayEquals(edit1, reopened.pendingFile(uri).readBytes())
    }

    @Test fun copyRecordsRecipeAndSourceWithoutOriginalBytes() {
        val s = store()
        val copy = "content://media/external/images/media/43"
        s.recordCopy(copy, uri, EditorSaveStore.sha256(original), EditorSaveStore.sha256(edit1), recipe1)
        val e = s.entry(copy)!!
        assertEquals(EditorSaveStore.Kind.COPY, e.kind)
        assertEquals(uri, e.sourceUri)
        assertEquals(recipe1, s.recipe(copy))
        assertFalse(s.originalFile(copy).exists())
    }

    @Test fun policyNamesCopiesAndNeverChangesFormatInPlace() {
        assertEquals("IMG_1_edited.jpg", SavePolicy.copyName("IMG_1.jpg", "jpg", emptySet()))
        assertEquals("IMG_1_edited_3.jpg",
            SavePolicy.copyName("IMG_1.HEIC", "jpg", setOf("img_1_edited.jpg", "IMG_1_edited_2.jpg")))
        assertEquals(OutputFormat(AiEditImageFormat.JPEG, true), OutputFormat.forSource("image/jpeg", 35))
        assertEquals(OutputFormat(AiEditImageFormat.PNG, true), OutputFormat.forSource("image/png", 35))
        assertEquals(OutputFormat(AiEditImageFormat.WEBP, true), OutputFormat.forSource("image/webp", 30))
        assertEquals(OutputFormat(AiEditImageFormat.JPEG, false), OutputFormat.forSource("image/webp", 29))
        assertEquals(OutputFormat(AiEditImageFormat.JPEG, false), OutputFormat.forSource("image/heic", 35))
        assertEquals(OutputFormat(AiEditImageFormat.JPEG, false), OutputFormat.forSource("image/heif", 35))
        assertTrue(SavePolicy.isGeometryOnly(EditRecipe(4, 3, listOf(EditOperation.Rotate90("r", 1),
            EditOperation.Crop("c", NormRect(0.0, 0.0, 0.5, 0.5))))))
        assertFalse(SavePolicy.isGeometryOnly(recipe2))
        assertTrue(SavePolicy.isGeometryOnly(EditRecipe(4, 3)))
    }
}
