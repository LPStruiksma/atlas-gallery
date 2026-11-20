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

/**
 * #458: what viewer Details shows for one photo. [editedWithAi] is true only
 * when this exact, still-readable URI has its own completed lineage row; never
 * from a filename, EXIF text, similarity, or merely having edited children.
 * The original may be gone: the label stays, the "Edited from…" family does not.
 */
internal data class AiEditDetails(
    val editedWithAi: Boolean,
    val group: AiEditProvenanceGroup?
)

internal fun aiEditDetailsFor(
    uri: String,
    own: AiEditLineage?,
    allRows: () -> List<AiEditLineage>,
    visible: (String) -> Boolean
): AiEditDetails? {
    if (!visible(uri)) return null
    val edited = own != null && own.editedUri == uri && own.originalUri != uri
    val group = if (edited && visible(own!!.originalUri)) {
        AiEditProvenanceGroup(own.originalUri, listOf(uri))
    } else if (edited) {
        null
    } else {
        val children = allRows().asSequence()
            .filter { it.originalUri == uri && it.editedUri != uri }
            .map { it.editedUri }.distinct().filter(visible).sorted().toList()
        if (children.isEmpty()) null else AiEditProvenanceGroup(uri, children)
    }
    return if (!edited && group == null) null else AiEditDetails(edited, group)
}
