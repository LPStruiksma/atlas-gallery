package nl.atlas.gallery.featureidentity.profile

import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceLocation

/*
 * IDS-4 (#472): read-only bridge from ATLAS People to identity profiles.
 * Every call goes through FaceIndexStore's existing read APIs
 * (activeEpoch, peopleReview, faceLocation, reviewObservationState) under
 * the active permission epoch. Nothing here writes People state, copies a
 * People name, or re-derives an identity: People stays the only authority.
 */

/** People's human and engine facts, keyed by native face uid and opaque person key. */
data class PeopleFaceFacts(
    /** Faces a person confirmed: face uid -> person key. */
    val confirmed: Map<String, String>,
    /** Faces the engine added to a named person on its own (#580): face uid -> person key. */
    val autoAdded: Map<String, String>,
    /** "Not this person" decisions: (face uid, person key). */
    val rejected: Set<Pair<String, String>>
)

/** The narrow People read surface this unit needs; production is [FaceIndexStorePeopleReads]. */
interface PeopleFaceReads {
    fun activeEpoch(): String?
    /** Null while People is revoked, superseded or not yet published. */
    fun facts(epoch: String): PeopleFaceFacts?
    /** Null for an unknown face, a stale epoch, or a source not processed at its current revision. */
    fun location(epoch: String, faceUid: String): IndexedFaceLocation?
    /** True when the user said "not a face" for this observation. */
    fun notAFace(epoch: String, faceUid: String): Boolean
}

/** Adapter over the existing People store; read calls only. Call from an IO worker. */
class FaceIndexStorePeopleReads(private val store: FaceIndexStore) : PeopleFaceReads {
    override fun activeEpoch(): String? = store.activeEpoch()

    override fun facts(epoch: String): PeopleFaceFacts? {
        val review = store.peopleReview(epoch) ?: return null
        val clusters = review.clusters
        return PeopleFaceFacts(
            confirmed = clusters.reviews.confirmed.entries.associate { it.key.localHex to it.value.value },
            autoAdded = clusters.matched.entries.associate { it.key.localHex to it.value.person.value },
            rejected = clusters.reviews.rejectedPeople.mapTo(HashSet()) { it.observation.localHex to it.person.value }
        )
    }

    override fun location(epoch: String, faceUid: String): IndexedFaceLocation? =
        store.faceLocation(epoch, faceUid)

    override fun notAFace(epoch: String, faceUid: String): Boolean =
        store.reviewObservationState(epoch, faceUid) == "NOT_A_FACE"
}

/** How People holds a face of the person. */
enum class PeopleFaceBasis { CONFIRMED, AUTO_ADDED }

/** One of a person's faces as People holds it now; becomes a reference only when the user keeps it. */
data class PeopleFaceCandidate(
    val faceUid: String,
    val mediaKey: String,
    val revision: String,
    val box: ReferenceFaceBox,
    val basis: PeopleFaceBasis
) {
    fun toDraft(origin: ReferenceOrigin = ReferenceOrigin.SUGGESTED_FROM_PEOPLE): IdentityReferenceDraft {
        require(origin != ReferenceOrigin.MANUAL) { "A People face is not a manual reference" }
        return IdentityReferenceDraft(mediaKey, revision, box, landmarks = null, origin = origin, peopleFaceUid = faceUid)
    }
}

sealed interface PeopleFacesRead {
    /** People is not readable now (no grant, superseded, not yet published). Nothing is inferred. */
    data object Unavailable : PeopleFacesRead
    data class Faces(val faces: List<PeopleFaceCandidate>) : PeopleFacesRead
}

/** Result of checking one stored reference against its source. */
sealed interface ReferenceCheck {
    data object Fresh : ReferenceCheck
    /** The source cannot be checked now (People revoked, media out of scope): change nothing. */
    data object Unknown : ReferenceCheck
    data class Stale(val reason: StaleReason) : ReferenceCheck
}

/** What the media layer says about a manual reference's photo. */
enum class MediaPresence { PRESENT, REVISED, GONE, UNKNOWN }

/**
 * Checks a manual reference's photo by its canonical key and revision.
 * The host wires this to its media grant; UNKNOWN never marks anything stale.
 */
fun interface ReferenceMediaProbe {
    fun presence(mediaKey: String, revision: String): MediaPresence
}

/** Pure selection rules, shared by [PeopleReferenceSource] and JVM tests. */
object PeopleReferenceRules {
    /**
     * Face uids People holds for [person]: confirmed first, then auto-added,
     * each in face-uid order. A face with a "not this person" decision for
     * [person] is left out even if a stale row still names it.
     */
    fun eligibleFaces(facts: PeopleFaceFacts, person: String): List<Pair<String, PeopleFaceBasis>> {
        fun rejected(uid: String) = (uid to person) in facts.rejected
        val confirmed = facts.confirmed.filter { it.value == person && !rejected(it.key) }.keys.sorted()
        val auto = facts.autoAdded.filter { it.value == person && it.key !in facts.confirmed && !rejected(it.key) }
            .keys.sorted()
        return confirmed.map { it to PeopleFaceBasis.CONFIRMED } + auto.map { it to PeopleFaceBasis.AUTO_ADDED }
    }

    /** Is [faceUid] still the person's face in People? */
    fun stillHeld(facts: PeopleFaceFacts, person: String, faceUid: String): Boolean =
        (faceUid to person) !in facts.rejected &&
            (facts.confirmed[faceUid] == person || (faceUid !in facts.confirmed && facts.autoAdded[faceUid] == person))

    /** Staleness of a People face from its current location; null location means People lost the face. */
    fun peopleFaceCheck(
        reference: IdentityReference, location: IndexedFaceLocation?, heldByPerson: Boolean?
    ): ReferenceCheck = when {
        location == null -> ReferenceCheck.Stale(StaleReason.FACE_GONE)
        location.media.key != reference.mediaKey -> ReferenceCheck.Stale(StaleReason.FACE_GONE)
        location.media.revision != reference.revision -> ReferenceCheck.Stale(StaleReason.MEDIA_REVISED)
        heldByPerson == false -> ReferenceCheck.Stale(StaleReason.LEFT_PERSON)
        else -> ReferenceCheck.Fresh
    }

    fun mediaCheck(presence: MediaPresence): ReferenceCheck = when (presence) {
        MediaPresence.PRESENT -> ReferenceCheck.Fresh
        MediaPresence.REVISED -> ReferenceCheck.Stale(StaleReason.MEDIA_REVISED)
        MediaPresence.GONE -> ReferenceCheck.Stale(StaleReason.MEDIA_GONE)
        MediaPresence.UNKNOWN -> ReferenceCheck.Unknown
    }
}

/**
 * Reads a People person's faces for a profile and checks stored references.
 * Read-only by construction: it only holds a [PeopleFaceReads].
 */
class PeopleReferenceSource(private val reads: PeopleFaceReads) {

    /**
     * Up to [limit] of [person]'s photo faces: confirmed ones first, then
     * ones the engine added on its own. Rejected, taken-out and "not a face"
     * observations are excluded; video keyframes are left out (a reference
     * is a still photo). Blocking; IO only.
     */
    fun faces(person: String, limit: Int = 200): PeopleFacesRead {
        require(IdentityProfileRules.validPersonKey(person)) { "Invalid person key" }
        require(limit in 1..2_000) { "Limit must be 1..2000" }
        val epoch = reads.activeEpoch() ?: return PeopleFacesRead.Unavailable
        val facts = reads.facts(epoch) ?: return PeopleFacesRead.Unavailable
        val out = ArrayList<PeopleFaceCandidate>()
        for ((uid, basis) in PeopleReferenceRules.eligibleFaces(facts, person)) {
            if (out.size >= limit) break
            val location = reads.location(epoch, uid) ?: continue
            if (location.frameMs != null) continue
            if (reads.notAFace(epoch, uid)) continue
            val b = location.box
            out += PeopleFaceCandidate(uid, location.media.key, location.media.revision,
                ReferenceFaceBox(b.left, b.top, b.right, b.bottom), basis)
        }
        // A re-read: if the grant moved while we read, report nothing rather than a mix.
        if (reads.activeEpoch() != epoch) return PeopleFacesRead.Unavailable
        return PeopleFacesRead.Faces(out)
    }

    /**
     * Checks every reference of [profile]. People-face references are checked
     * against People (face still there, same revision and, for a People
     * suggestion, still the linked person's); others go to [media]. Unknown
     * results change nothing.
     */
    fun check(profile: IdentityProfile, media: ReferenceMediaProbe): Map<String, ReferenceCheck> {
        val epoch = reads.activeEpoch()
        val facts = epoch?.let { reads.facts(it) }
        val out = LinkedHashMap<String, ReferenceCheck>()
        for (variant in profile.variants) for (reference in variant.references) {
            val uid = reference.peopleFaceUid
            out[reference.id] = if (uid != null) {
                if (epoch == null || facts == null) ReferenceCheck.Unknown
                else {
                    // Only a People suggestion depends on People's membership; a face
                    // the user picked themselves only has to still exist.
                    val person = profile.peoplePersonKey
                    val held = if (reference.origin == ReferenceOrigin.SUGGESTED_FROM_PEOPLE && person != null)
                        PeopleReferenceRules.stillHeld(facts, person, uid) else null
                    PeopleReferenceRules.peopleFaceCheck(reference, reads.location(epoch, uid), held)
                }
            } else PeopleReferenceRules.mediaCheck(media.presence(reference.mediaKey, reference.revision))
        }
        if (epoch != null && reads.activeEpoch() != epoch) {
            return out.mapValues { (_, v) -> if (v is ReferenceCheck.Stale) ReferenceCheck.Unknown else v }
        }
        return out
    }
}
