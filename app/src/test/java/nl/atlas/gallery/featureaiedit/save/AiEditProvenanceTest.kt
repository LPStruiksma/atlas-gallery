package nl.atlas.gallery.featureaiedit.save

import org.junit.Assert.*
import org.junit.Test

class AiEditProvenanceTest {
    private fun edit(id: Int, original: Int, parent: Int? = null) = AiEditLineage(
        editedUri = "content://media/external/images/media/$id",
        originalUri = "content://media/external/images/media/$original",
        originalRevision = "r1",
        provider = "synthetic", model = "test", modelVersion = "1",
        prompt = "", settingsJson = "{}", createdAtMillis = id.toLong(),
        candidateParentUri = parent?.let { "content://media/external/images/media/$it" }
    )
    private fun uri(id: Int) = "content://media/external/images/media/$id"

    @Test fun groupsOnlyLiveOriginalsAndEditsAndKeepsProvenanceSeparate() {
        val groups = aiEditProvenanceGroups(
            listOf(edit(3, 1), edit(4, 1), edit(6, 5), edit(7, 1)),
            setOf(uri(1), uri(3), uri(4), uri(6))
        )
        assertEquals(1, groups.size)
        assertEquals(uri(1), groups.single().originalUri)
        assertEquals(listOf(uri(3), uri(4)), groups.single().editedUris)
    }

    @Test fun deletedEditCannotTakeTheOriginalWithIt() {
        val groups = aiEditProvenanceGroups(listOf(edit(3, 1)), setOf(uri(1)))
        assertTrue(groups.isEmpty())
        assertNull(aiEditProvenanceFor(uri(3), groups))
    }

    @Test fun editingAnEditRetainsOriginalLineageAndIgnoresCandidateParentForGrouping() {
        val groups = aiEditProvenanceGroups(
            listOf(edit(3, 1), edit(4, 1, 3)),
            setOf(uri(1), uri(3), uri(4))
        )
        assertEquals(2, groups.single().editedUris.size)
        assertEquals(uri(1), aiEditProvenanceFor(uri(4), groups)?.originalUri)
        assertEquals(2, aiEditProvenanceFor(uri(1), groups)?.editedUris?.size)
    }

    @Test fun duplicateRowsAndCyclesDoNotCreateFalseFamilies() {
        val groups = aiEditProvenanceGroups(
            listOf(edit(2, 1), edit(2, 1), edit(1, 2), edit(5, 5)),
            setOf(uri(1), uri(2), uri(5))
        )
        assertTrue(groups.isEmpty())
    }
}
