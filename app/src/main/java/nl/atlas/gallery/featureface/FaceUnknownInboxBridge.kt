package nl.atlas.gallery.featureface

import nl.atlas.gallery.featurepersistence.IndexedFacePageCursor
import nl.atlas.gallery.featurepersistence.IndexedPendingFaceReview
import java.net.URI

/**
 * Single page of UNKNOWN observations suitable for an opt-in, permission-scoped
 * review session. sourceUri is a short-lived MediaStore rendering hint, never
 * durable authorization; I/U must independently gate every image load against
 * the current Android permission + source publication epoch.
 */
data class AuthorizedUnknownFace(
    val nativeFaceUid: String,
    val box: UnknownFaceBox,
    val sourceUri: String,
    val media: FaceMediaKey,
    /** X's authoritative detector version from the SAME processed catalog row. */
    val detectorVersion: String,
    val cursor: IndexedFacePageCursor
) {
    /** U's review key uses this in-photo ordinal, never as the write target. */
    val ordinal: Int get() = cursor.ordinal
}

data class AuthorizedUnknownFacePage(
    val permissionEpoch: String,
    val items: List<AuthorizedUnknownFace>,
    val nextCursor: IndexedFacePageCursor?
)

/**
 * P -> X -> U bridge: the caller injects X's pendingFaceReviewPage and P's
 * FaceAuthorizedMediaDirectory.authorizedUri. Every candidate MUST pass P's
 * exact media-key, source-revision and live grant checks; never fall back to an
 * unverified URI, filename, previous authorization or photo path.
 *
 * Blocking and ephemeral: call on I's exclusive IO worker, discard pages on
 * permission/provider changes, do not store content URIs in SQLite/Compose
 * savedState, and never display a stale image while paging/reviewing.
 */
class FaceUnknownInboxBridge(
    private val loadPage: (String, IndexedFacePageCursor?, Int) -> List<IndexedPendingFaceReview>,
    private val authorizedUri: (FaceMediaKey) -> String,
    private val scopeCurrent: (String) -> Boolean
) {
    fun page(
        expectedEpoch: String,
        after: IndexedFacePageCursor? = null,
        limit: Int = 32
    ): AuthorizedUnknownFacePage {
        require(expectedEpoch.isNotBlank() && limit in 1..128)
        require(after == null || after.permissionEpoch == expectedEpoch) {
            "Unknown inbox cursor belongs to a different grant"
        }
        fun checkScope() {
            if (!scopeCurrent(expectedEpoch)) {
                throw SecurityException("Unknown inbox media grant is no longer current")
            }
        }
        checkScope()
        val rows = loadPage(expectedEpoch, after, limit)
        require(rows.size <= limit) { "Unbounded native face page" }
        val accepted = ArrayList<AuthorizedUnknownFace>(rows.size)
        val seenUids = HashSet<String>(rows.size)
        var previous = after
        for (row in rows) {
            checkScope()
            require(row.cursor.permissionEpoch == expectedEpoch &&
                row.cursor.mediaKey == row.media.key && row.cursor.ordinal >= 0) {
                "Invalid native face page cursor"
            }
            require(previous == null || row.cursor.mediaKey > previous.mediaKey ||
                (row.cursor.mediaKey == previous.mediaKey && row.cursor.ordinal > previous.ordinal)) {
                "Nonmonotone native face review page"
            }
            require(row.cursor.ordinal < FaceDiscoveryContract.MAX_FACES_PER_IMAGE) {
                "Invalid in-photo face ordinal"
            }
            require(row.detectorVersion.isNotBlank() && row.detectorVersion.length <= 256 &&
                '\u0000' !in row.detectorVersion) { "Unverified detector version" }
            require(row.observation.nativeFaceUid.matches(Regex("[0-9a-f]{32}")) &&
                seenUids.add(row.observation.nativeFaceUid)) {
                "Invalid or duplicate native face observation UID"
            }
            previous = row.cursor
            val media = FaceMediaKey(row.media.key, row.media.revision)
            // This callback MUST be P's current permission-checked resolver.
            // Failure aborts the whole page: skipping a stale candidate would
            // silently change review pagination and risk exposing stale bytes.
            val uri = authorizedUri(media)
            // Defense in depth against an accidentally miswired I resolver:
            // an opaque hash must never turn into a raw private path, a
            // foreign ContentProvider or an arbitrary URI in U's previews.
            // This validates the URI SHAPE only, not media authorization.
            requireSpecificMediaStoreMediaUri(uri)
            checkScope()
            accepted.add(AuthorizedUnknownFace(
                nativeFaceUid = row.observation.nativeFaceUid,
                box = row.observation.box.let {
                    UnknownFaceBox(it.left, it.top, it.right, it.bottom)
                },
                sourceUri = uri,
                media = media,
                detectorVersion = row.detectorVersion,
                cursor = row.cursor
            ))
        }
        checkScope()
        // A Kotlin List is read-only by convention only: without a defensive
        // wrapper, a downstream MutableList cast can silently alter validated
        // media URI/UID entries after our live-grant checks.
        val frozen = java.util.Collections.unmodifiableList(ArrayList(accepted))
        return AuthorizedUnknownFacePage(expectedEpoch, frozen,
            accepted.lastOrNull()?.cursor?.takeIf { rows.size == limit })
    }
}

/** Fail closed even if the integrator accidentally injects a permissive resolver.
 * A well-formed MediaStore URI still needs live scope + exact source revision
 * checks by FaceAuthorizedMediaDirectory and again by the image loader.
 * VS-V1c (#468): a face found in a video (VS-V1b) has a specific-volume
 * MediaStore *video* URI; before this, one such face made the whole unknown
 * review page fail. Any other collection or shape is still refused.
 */
private fun requireSpecificMediaStoreMediaUri(value: String) {
    if (value.length !in 1..512 || '\u0000' in value) {
        throw SecurityException("Invalid face review preview URI")
    }
    val uri = try { URI(value) } catch (_: IllegalArgumentException) { null }
    val parts = uri?.rawPath?.split('/')
    val volume = parts?.getOrNull(1)
    val id = parts?.getOrNull(4)?.toLongOrNull()
    if (uri?.scheme != "content" || uri.rawAuthority != "media" ||
        uri.rawQuery != null || uri.rawFragment != null ||
        parts?.size != 5 || parts[0].isNotEmpty() ||
        volume == null || !volume.matches(Regex("[A-Za-z0-9_-]{1,120}")) ||
        volume == "external" || volume == "unknown" ||
        (parts[2] != "images" && parts[2] != "video") || parts[3] != "media" ||
        id == null || id <= 0L || parts[4] != id.toString()
    ) throw SecurityException("Face review preview requires a specific-volume MediaStore image or video URI")
}
