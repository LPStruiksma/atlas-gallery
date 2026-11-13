package nl.atlas.gallery.featuregrid

/**
 * A fresh immutable catalogue publication is a new epoch even when its values
 * look identical. Compose's structural equality over 150k MediaRecords both
 * blocks the main thread and risks retaining stale layout across a grant swap.
 * Pure JVM key, independent of Android/Compose and of media identity semantics.
 */
internal class GridCatalogIdentity(
    private val source: Any,
    /** Monotonic permission/publication epoch; change it on grant/reselection. */
    private val accessEpoch: Long = 0L
) {
    override fun equals(other: Any?): Boolean =
        other is GridCatalogIdentity &&
            source === other.source && accessEpoch == other.accessEpoch

    override fun hashCode(): Int =
        31 * System.identityHashCode(source) + accessEpoch.hashCode()
}
