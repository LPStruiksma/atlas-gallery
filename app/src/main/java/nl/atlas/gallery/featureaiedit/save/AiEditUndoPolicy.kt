package nl.atlas.gallery.featureaiedit.save

/**
 * Session Undo for a published AI Edit copy (AIE-21 rev 5 E, gesture and data contract item 5).
 * Pure rules, JVM-tested; [AiEditSaver.undoPublished] gathers the facts and acts on the verdict.
 *
 * Undo may delete only the exact Uri this save returned, and only when MediaStore still shows it
 * as this app's own published row in the save's folder, the lineage store still links it to the
 * same original, and it is not the original. Anything else is refused and nothing is deleted.
 */
object AiEditUndoPolicy {
    /** What the saver observed about the copy just before deleting it. */
    data class Facts(
        val savedUri: String,
        val originalUri: String,
        /** The Uri rebuilt from the row's volume collection and id; must equal [savedUri]. */
        val canonicalUri: String?,
        val rowExists: Boolean,
        val ownerPackage: String?,
        val appPackage: String,
        val pending: Boolean,
        val relativePath: String?,
        val expectedRelativePath: String?,
        /** The lineage row for [savedUri], as stored at save time. */
        val lineageOriginalUri: String?
    )

    enum class Verdict { DELETE, ALREADY_GONE, REFUSE }

    fun decide(f: Facts): Verdict = when {
        f.savedUri.isBlank() || f.savedUri == f.originalUri -> Verdict.REFUSE
        f.canonicalUri != f.savedUri -> Verdict.REFUSE
        !f.rowExists -> Verdict.ALREADY_GONE
        f.ownerPackage != f.appPackage -> Verdict.REFUSE
        f.pending -> Verdict.REFUSE
        f.expectedRelativePath != null && f.relativePath != f.expectedRelativePath -> Verdict.REFUSE
        f.lineageOriginalUri == null || f.lineageOriginalUri != f.originalUri -> Verdict.REFUSE
        else -> Verdict.DELETE
    }
}

/** Outcome shown in the Saved slab. */
enum class AiEditUndoOutcome {
    /** The copy is gone; lineage forgotten. */
    REMOVED,
    /** Nothing was deleted: not provably ours, permission denied, or the delete failed. */
    FAILED
}
