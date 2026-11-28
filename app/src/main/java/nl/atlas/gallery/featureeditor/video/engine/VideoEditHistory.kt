package nl.atlas.gallery.featureeditor.video.engine

/**
 * Immutable snapshot history with the same preview/commit/undo/redo/compare semantics as
 * the photo editor. One drag is one step; a new commit invalidates the redo branch.
 * Snapshots are recipes, not encoded media or mutable collections.
 */
data class VideoEditHistory(
    val base: VideoEditRecipe,
    val applied: VideoEditRecipe = base,
    val undoStack: List<VideoEditRecipe> = emptyList(),
    val redoStack: List<VideoEditRecipe> = emptyList(),
    val pending: VideoEditRecipe? = null,
    val comparing: Boolean = false,
) {
    val current: VideoEditRecipe get() = pending ?: applied
    val committed: VideoEditRecipe get() = applied
    val displayed: VideoEditRecipe get() = if (comparing) base else current
    val canUndo: Boolean get() = pending != null || undoStack.isNotEmpty()
    val canRedo: Boolean get() = pending == null && redoStack.isNotEmpty()
    val isDirty: Boolean get() = applied != base

    fun preview(recipe: VideoEditRecipe): VideoEditHistory {
        recipe.requireValid()
        return copy(pending = recipe, comparing = false)
    }

    fun apply(recipe: VideoEditRecipe): VideoEditHistory = preview(recipe).commit()

    fun commit(): VideoEditHistory {
        val next = pending ?: return this
        if (next == applied) return copy(pending = null)
        return copy(
            applied = next, undoStack = undoStack + applied,
            redoStack = emptyList(), pending = null, comparing = false,
        )
    }

    fun cancelPending(): VideoEditHistory = copy(pending = null)

    fun undo(): VideoEditHistory = when {
        pending != null -> copy(pending = null, comparing = false)
        undoStack.isEmpty() -> this
        else -> copy(
            applied = undoStack.last(), undoStack = undoStack.dropLast(1),
            redoStack = redoStack + applied, comparing = false,
        )
    }

    fun redo(): VideoEditHistory {
        if (!canRedo) return this
        return copy(
            applied = redoStack.last(), redoStack = redoStack.dropLast(1),
            undoStack = undoStack + applied, comparing = false,
        )
    }

    fun revertAll(): VideoEditHistory =
        if (applied == base && pending == null) copy(comparing = false)
        else copy(
            applied = base, undoStack = undoStack + applied,
            pending = null, redoStack = emptyList(), comparing = false,
        )

    fun setComparing(on: Boolean): VideoEditHistory =
        if (on == comparing) this else copy(comparing = on)

    companion object {
        fun start(recipe: VideoEditRecipe): VideoEditHistory =
            VideoEditHistory(recipe.requireValid())
    }
}
