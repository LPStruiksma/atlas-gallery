package nl.atlas.gallery.featureeditor.engine

/**
 * The single undo/redo history of an editing session. Immutable: every call returns a new
 * value, so it drops straight into Compose state and is trivially thread-safe.
 *
 * - [applied] are the committed operations, oldest first; the recipe is exactly these.
 * - [redoStack] holds undone operations, most recently undone last.
 * - [pending] is a live, uncommitted operation (a slider or crop handle being dragged). It
 *   shows in [current] but is not a history entry until [commit], so a 10 s drag is one undo
 *   step, not hundreds.
 * - [comparing] is the press-and-hold "compare to original" toggle; it changes only
 *   [displayed], never the history.
 *
 * Every tool (adjust, crop, filters, decorations, AI Edit, Face Swap, Erase) goes through
 * [apply]/[preview]/[commit], so there is one history and one save path.
 */
data class EditHistory(
    val base: EditRecipe,
    val applied: List<EditOperation> = base.operations,
    val redoStack: List<EditOperation> = emptyList(),
    val pending: EditOperation? = null,
    val comparing: Boolean = false,
) {
    /** The recipe as edited, including a live [pending] change. */
    val current: EditRecipe get() = base.copy(operations = if (pending == null) applied else applied + pending)

    /** The committed recipe: what Save writes. */
    val committed: EditRecipe get() = base.copy(operations = applied)

    /** What the canvas shows: the original while comparing, else [current]. */
    val displayed: EditRecipe get() = if (comparing) base.original() else current

    val canUndo: Boolean get() = pending != null || applied.isNotEmpty()
    val canRedo: Boolean get() = pending == null && redoStack.isNotEmpty()

    /** True when Save would change the file compared with the session's starting recipe. */
    val isDirty: Boolean get() = applied != base.operations

    /** Commits [op] at once (button taps such as rotate, flip, apply AI result). */
    fun apply(op: EditOperation): EditHistory = preview(op).commit()

    /** Shows [op] live without making it a history entry; replaces any earlier pending op. */
    fun preview(op: EditOperation): EditHistory {
        requireValid(op)
        return copy(pending = op, comparing = false)
    }

    /** Turns the pending op into one history entry and clears redo. No pending op: no change. */
    fun commit(): EditHistory {
        val op = pending ?: return this
        return copy(applied = applied + op, redoStack = emptyList(), pending = null)
    }

    /** Drops a pending op (drag cancelled). */
    fun cancelPending(): EditHistory = copy(pending = null)

    fun undo(): EditHistory = when {
        pending != null -> copy(pending = null, comparing = false)
        applied.isEmpty() -> this
        else -> copy(applied = applied.dropLast(1), redoStack = redoStack + applied.last(), comparing = false)
    }

    fun redo(): EditHistory {
        if (!canRedo) return this
        return copy(applied = applied + redoStack.last(), redoStack = redoStack.dropLast(1), comparing = false)
    }

    fun setComparing(on: Boolean): EditHistory = if (on == comparing) this else copy(comparing = on)

    /** Clears every edit back to the original; redo re-applies them one at a time, oldest first. */
    fun revertAll(): EditHistory = if (applied.isEmpty() && pending == null) copy(comparing = false) else copy(
        applied = emptyList(),
        redoStack = redoStack + applied.reversed(),
        pending = null,
        comparing = false,
    )

    /** A fresh id unique within this history (applied, redo and pending). */
    fun nextId(prefix: String = "op"): String {
        val used = HashSet<String>()
        applied.forEach { used += it.id }
        redoStack.forEach { used += it.id }
        pending?.let { used += it.id }
        var n = used.size + 1
        while ("$prefix-$n" in used) n++
        return "$prefix-$n"
    }

    /** Content hashes any state of this history may still need (keep these patches stored). */
    fun referencedContentHashes(): Set<String> =
        base.copy(operations = applied + redoStack + listOfNotNull(pending)).referencedContentHashes()

    private fun requireValid(op: EditOperation) {
        op.validate()?.let { throw IllegalArgumentException("Invalid operation: $it") }
        val clash = applied.any { it.id == op.id } || redoStack.any { it.id == op.id }
        require(!clash) { "Operation id already used" }
    }

    companion object {
        fun start(recipe: EditRecipe): EditHistory {
            val problems = recipe.validate()
            require(problems.isEmpty()) { "Recipe is invalid: ${problems.joinToString()}" }
            return EditHistory(recipe)
        }
    }
}
