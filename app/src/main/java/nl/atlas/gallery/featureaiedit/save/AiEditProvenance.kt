package nl.atlas.gallery.featureaiedit.save

/**
 * AIE-10: the explicit persisted edit relationship, NOT a visual similarity match.
 * Only URIs in the currently authorized inventory may be shown.
 */
internal data class AiEditProvenanceGroup(
    val originalUri: String,
    val editedUris: List<String>
)

internal fun aiEditProvenanceGroups(
    rows: List<AiEditLineage>,
    visibleUris: Set<String>
): List<AiEditProvenanceGroup> {
    // The source must still be present. Stale rows from deleted edits do not
    // resurrect them; an edit never authorizes deleting the original.
    val unique = rows.groupBy { it.editedUri }
        .filterValues { it.size == 1 }
        .values.map { it.single() }
    val edits = unique.filter {
        it.editedUri != it.originalUri &&
            it.editedUri in visibleUris && it.originalUri in visibleUris
    }
    // An original may itself be edited, or part of a duplicate/cyclic record.
    // Neither should be inferred as a root of a safe provenance family.
    val editedKeys = rows.mapTo(HashSet()) { it.editedUri }
    return edits.groupBy { it.originalUri }
        .filterKeys { it !in editedKeys }
        .map { (original, children) ->
            AiEditProvenanceGroup(original, children.map { it.editedUri }.distinct().sorted())
        }.filter { it.editedUris.isNotEmpty() }
        .sortedBy { it.originalUri }
}

internal fun aiEditProvenanceFor(
    uri: String,
    groups: List<AiEditProvenanceGroup>
): AiEditProvenanceGroup? = groups.firstOrNull {
    it.originalUri == uri || uri in it.editedUris
}
