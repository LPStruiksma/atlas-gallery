package nl.atlas.gallery.featurepersistence

/**
 * HIDE-1 (#585): People home counts and covers must ignore hidden media while
 * the face index keeps its unfiltered inventory (it prunes media missing from
 * its inventory, so it must never be fed a filtered list).
 *
 * Pure half (no SQLite), shared by [FaceIndexStore.peopleHome] and JVM tests.
 * Keys are the face index's canonical media keys of the hidden items; the
 * hidden set never reaches a face or person row on disk.
 */
object PeopleHomeHidden {
    /** One face of one named person, in the shape of the People home aggregate. */
    data class PersonFace(
        val faceUid: String,
        val mediaKey: String,
        val takenMs: Long,
        val isVideo: Boolean,
        /** Confirmed faces can be a cover; faces grouping added on its own cannot. */
        val confirmed: Boolean
    )

    /**
     * [stored] re-counted without the faces in [hidden] media, or null when the
     * person has no visible media or no visible confirmed face left (a person
     * needs a confirmed face for a cover, so they leave People home).
     * With nothing hidden the stored summary is returned unchanged.
     */
    fun summarize(
        stored: StoredPersonSummary, faces: List<PersonFace>, hidden: Set<String>
    ): StoredPersonSummary? {
        if (hidden.isEmpty()) return stored
        val visible = faces.filter { it.mediaKey !in hidden }
        if (visible.isEmpty()) return null
        val cover = visible.firstOrNull { it.faceUid == stored.coverFaceUid && it.confirmed }
            ?.faceUid ?: visible.filter { it.confirmed }.minOfOrNull { it.faceUid }
            ?: return null
        // One count per photo/video, as the stored aggregate does (a video wins
        // when any face of the media is a video frame).
        val media = visible.groupBy { it.mediaKey }
        val videos = media.values.count { rows -> rows.any { it.isVideo } }
        val times = media.values.mapNotNull { rows ->
            rows.maxOf { it.takenMs }.takeIf { it != 0L }
        }
        return stored.copy(
            faceCount = visible.size,
            coverFaceUid = cover,
            mediaCount = media.size,
            firstAppearanceMs = times.minOrNull(),
            mostRecentAppearanceMs = times.maxOrNull(),
            photoCount = media.size - videos,
            videoCount = videos
        )
    }

    /**
     * Unnamed groups without their hidden faces. A group whose anchor face (the
     * crop People home shows) is hidden leaves home; a group that drops below
     * [minGroup] faces leaves home as well. Order is kept.
     */
    fun unnamed(
        groups: List<StoredUnnamedGroup>,
        hiddenMembers: Map<String, Int>,
        hiddenAnchors: Set<String>,
        minGroup: Int = FaceIndexStore.PEOPLE_HOME_MIN_GROUP
    ): List<StoredUnnamedGroup> = groups.mapNotNull { group ->
        if (group.anchorUid in hiddenAnchors) return@mapNotNull null
        val left = group.faceCount - (hiddenMembers[group.anchorUid] ?: 0)
        if (left < minGroup) null else StoredUnnamedGroup(group.anchorUid, left)
    }
}
