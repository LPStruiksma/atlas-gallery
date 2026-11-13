package nl.atlas.gallery

/**
 * MediaStore item URIs require a positive numeric row ID. Cursor.getLong()
 * turns a SQL NULL into 0, and malformed vendor rows can report negatives;
 * neither is an addressable photo/video. Never invent an item URI or key for
 * them. Reject the entire scan so a previously published People/album catalog
 * remains available until the provider returns a coherent inventory.
 *
 * MediaStore IDs are Long: no arbitrary Int-sized upper bound is imposed.
 */
internal fun requireMediaStoreItemId(rawId: Long): Long {
    check(rawId > 0L) { "Media provider returned an invalid item identifier" }
    return rawId
}
