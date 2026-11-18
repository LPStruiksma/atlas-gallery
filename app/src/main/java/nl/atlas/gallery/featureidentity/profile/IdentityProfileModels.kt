package nl.atlas.gallery.featureidentity.profile

import java.util.Locale
import java.util.UUID

/*
 * IDS-4 (#472): pure models of the Identity Studio profile store. No Android
 * types, so JVM tests cover every rule. A profile LINKS to an ATLAS People
 * person by its opaque person key; it never copies the person's name, group
 * or any People decision, so nothing here can drift from or change People.
 */

/** 32 lowercase hex characters, minted locally; never a People or media id. */
object IdentityProfileIds {
    private val ID = Regex("[0-9a-f]{32}")
    fun mint(): String = UUID.randomUUID().toString().replace("-", "")
    fun valid(id: String): Boolean = ID.matches(id)
    fun require(id: String): String {
        kotlin.require(valid(id)) { "Invalid identity profile id" }
        return id
    }
}

object IdentityProfileRules {
    const val MAX_LABEL_LENGTH = 60
    const val MAX_VARIANTS = 16
    const val MAX_REFERENCES_PER_VARIANT = 64
    const val MAX_LANDMARKS = 128
    const val MAX_KEY_LENGTH = 1024
    const val MAX_PERSON_KEY_LENGTH = 256
    /** Variant label every new profile starts with. */
    const val DEFAULT_VARIANT_LABEL = "Current"
    /** Example variant labels the studio may offer; any valid label is allowed. */
    val SUGGESTED_VARIANT_LABELS = listOf("Current", "Younger", "Short hair", "Long hair", "Glasses", "Beard")

    /** Trimmed, 1..60 chars, no control characters (same spirit as People names). */
    fun validLabel(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_LABEL_LENGTH && value == value.trim() &&
            value.none { Character.isISOControl(it) }

    fun validPersonKey(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_PERSON_KEY_LENGTH && '\u0000' !in value

    fun validMediaText(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_KEY_LENGTH && '\u0000' !in value

    /** Two labels are the same variant when they match ignoring case. */
    fun sameLabel(a: String, b: String): Boolean =
        a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)
}

/** Where a reference came from. Stored by [code]; the codes never change. */
enum class ReferenceOrigin(val code: String) {
    /** ATLAS proposed one of the linked People person's faces and the user kept it. */
    SUGGESTED_FROM_PEOPLE("people_suggested"),
    /** The user picked a face themselves (from People or any photo with a detected face). */
    ADDED_BY_USER("user_added"),
    /** A hand-picked photo outside People; the box came from the user or a local detector. */
    MANUAL("manual");

    companion object {
        fun fromCode(code: String): ReferenceOrigin =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown reference origin")
    }
}

/**
 * Why a reference no longer matches its source. A stale reference is kept
 * and shown as stale; it is never silently swapped for another face.
 */
enum class StaleReason(val code: String) {
    /** The photo is no longer in the library (deleted or out of the grant for good). */
    MEDIA_GONE("media_gone"),
    /** The photo changed (edited, replaced) since the reference was taken. */
    MEDIA_REVISED("media_revised"),
    /** People no longer has this face (source changed or rescanned). */
    FACE_GONE("face_gone"),
    /** The user took this face out of the linked person, or rejected it, in People. */
    LEFT_PERSON("left_person");

    companion object {
        fun fromCode(code: String): StaleReason =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown stale reason")
    }
}

/** A face box in source-image coordinates normalised to 0..1, like People's boxes. */
data class ReferenceFaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) { "Face box outside the image" }
        require(left < right && top < bottom) { "Empty face box" }
    }
}

/**
 * Facial landmarks in the same normalised image coordinates as the box.
 * A point may sit slightly outside the image (a face cut by the frame).
 * Encoded as text, so no binary face data is ever stored or logged.
 */
class ReferenceLandmarks(points: List<Pair<Float, Float>>) {
    val points: List<Pair<Float, Float>> = points.toList()

    init {
        require(this.points.size in 1..IdentityProfileRules.MAX_LANDMARKS) { "Invalid landmark count" }
        require(this.points.all { (x, y) -> x.isFinite() && y.isFinite() && x in -0.5f..1.5f && y in -0.5f..1.5f }) {
            "Landmark outside the allowed range"
        }
    }

    fun encode(): String = points.joinToString(";") { (x, y) -> "${x}," + y }

    override fun equals(other: Any?): Boolean = other is ReferenceLandmarks && other.points == points
    override fun hashCode(): Int = points.hashCode()
    override fun toString(): String = "ReferenceLandmarks(${points.size} points)"

    companion object {
        fun decode(text: String): ReferenceLandmarks {
            require(text.isNotEmpty() && text.length <= IdentityProfileRules.MAX_LANDMARKS * 32) { "Invalid landmarks" }
            return ReferenceLandmarks(text.split(';').map { pair ->
                val parts = pair.split(',')
                require(parts.size == 2) { "Invalid landmark" }
                (parts[0].toFloatOrNull() ?: error("Invalid landmark")) to
                    (parts[1].toFloatOrNull() ?: error("Invalid landmark"))
            })
        }
    }
}

/**
 * What a caller hands the store to add a reference. [mediaKey] and
 * [revision] are the same canonical key and source revision People uses
 * (`FaceMediaKey`), so staleness checks need no new media identity.
 */
data class IdentityReferenceDraft(
    val mediaKey: String,
    val revision: String,
    val box: ReferenceFaceBox,
    val landmarks: ReferenceLandmarks?,
    val origin: ReferenceOrigin,
    /** People's native face uid when the face came from People; null for a manual photo. */
    val peopleFaceUid: String? = null
) {
    init {
        require(IdentityProfileRules.validMediaText(mediaKey)) { "Invalid media key" }
        require(IdentityProfileRules.validMediaText(revision)) { "Invalid media revision" }
        require(peopleFaceUid == null || IdentityProfileIds.valid(peopleFaceUid)) { "Invalid People face uid" }
        when (origin) {
            ReferenceOrigin.SUGGESTED_FROM_PEOPLE -> require(peopleFaceUid != null) {
                "A People suggestion must name its People face"
            }
            ReferenceOrigin.MANUAL -> require(peopleFaceUid == null) {
                "A manual reference is not a People face"
            }
            ReferenceOrigin.ADDED_BY_USER -> Unit
        }
    }

    /** The same face of the same photo revision; adding it twice to a variant is refused. */
    fun sameFaceAs(other: IdentityReference): Boolean =
        mediaKey == other.mediaKey && revision == other.revision &&
            (if (peopleFaceUid != null && other.peopleFaceUid != null) peopleFaceUid == other.peopleFaceUid
            else box == other.box)
}

data class IdentityReference(
    val id: String,
    val variantId: String,
    val mediaKey: String,
    val revision: String,
    val box: ReferenceFaceBox,
    val landmarks: ReferenceLandmarks?,
    val origin: ReferenceOrigin,
    val peopleFaceUid: String?,
    val addedAtMillis: Long,
    /** Non-null once the source no longer matches; the reference is then kept but not used. */
    val stale: StaleReason?
) {
    init {
        IdentityProfileIds.require(id)
        IdentityProfileIds.require(variantId)
    }
    val usable: Boolean get() = stale == null
}

/** One look of a person ("Current", "Younger", "Glasses"): an identity with ordered references. */
data class AppearanceVariant(
    val id: String,
    val profileId: String,
    val label: String,
    val references: List<IdentityReference>
) {
    init {
        IdentityProfileIds.require(id)
        IdentityProfileIds.require(profileId)
        require(IdentityProfileRules.validLabel(label)) { "Invalid variant label" }
        require(references.size <= IdentityProfileRules.MAX_REFERENCES_PER_VARIANT)
    }
    val usableReferences: List<IdentityReference> get() = references.filter { it.usable }
}

/**
 * A person the studio can put into a photo. [peoplePersonKey] links to an
 * ATLAS People person (the name is read from People when shown, never
 * copied). A manual profile has no link and carries its own [label].
 */
data class IdentityProfile(
    val id: String,
    val peoplePersonKey: String?,
    val label: String?,
    val createdAtMillis: Long,
    val variants: List<AppearanceVariant>
) {
    init {
        IdentityProfileIds.require(id)
        require(peoplePersonKey != null || label != null) { "A profile needs a People link or a label" }
        require(peoplePersonKey == null || IdentityProfileRules.validPersonKey(peoplePersonKey)) { "Invalid person key" }
        require(label == null || IdentityProfileRules.validLabel(label)) { "Invalid profile label" }
        require(variants.size <= IdentityProfileRules.MAX_VARIANTS)
        require(variants.all { it.profileId == id })
    }
    val linkedToPeople: Boolean get() = peoplePersonKey != null
}

/** What deleting a profile removed, and whether anything was left behind. */
data class IdentityProfileDeletion(
    val profileFound: Boolean,
    val rowsDeleted: Int,
    val filesDeleted: Int,
    val rowsLeft: Int,
    val filesLeft: Int
) {
    val complete: Boolean get() = rowsLeft == 0 && filesLeft == 0
}

/** Rule for reordering: the new order must be exactly the variant's current references. */
object ReferenceOrdering {
    fun validReorder(current: List<String>, requested: List<String>): Boolean =
        requested.size == current.size && requested.toSet() == current.toSet() &&
            requested.toSet().size == requested.size
}

/** Derived-file names stay inside their profile folder: no separators, no dot-dot. */
object DerivedFileNames {
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    fun valid(name: String): Boolean = NAME.matches(name) && ".." !in name
}
