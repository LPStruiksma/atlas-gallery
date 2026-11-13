package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson

/**
 * D2-5c part 1 (#434): durable identity review, stored by [FaceIndexStore]
 * schema v3. A human-authored snapshot read under one permission epoch:
 * [clusters] carries the confirmed, cannot-link and rejected-person facts in
 * [FaceClusterState.reviews]; [names] are the reviewed person names.
 *
 * [generation] changes on every review commit and every cluster publication.
 * Use it as `PeopleClusterReviewState.sourceGeneration` and pass it back to
 * [FaceIndexStore.commitPeopleReview]: a commit computed from an older
 * snapshot is refused instead of overwriting newer clusters or facts.
 */
data class StoredPeopleReview(
    val generation: String,
    val clusters: FaceClusterState,
    val names: Map<PersonKey, String>,
    /** Named people hidden from People (#551); always a subset of [names]. */
    val hidden: Set<PersonKey> = emptySet()
)

/** Lightweight, persisted-table-backed People home entry; no vectors or cluster state. */
data class StoredPersonSummary(
    val name: String,
    val faceCount: Int,
    val coverFaceUid: String
)

/**
 * #579: an unnamed group of at least [FaceIndexStore.PEOPLE_HOME_MIN_GROUP]
 * faces, named by its anchor (smallest member) face, which is also its cover.
 */
data class StoredUnnamedGroup(val anchorUid: String, val faceCount: Int)

/** One permission-scoped, review-generation-scoped People home snapshot. */
data class StoredPeopleHome(
    val generation: String,
    val people: Map<PersonKey, StoredPersonSummary>,
    /** #579: largest first, then by anchor; at most [FaceIndexStore.PEOPLE_HOME_MAX_GROUPS]. */
    val unnamed: List<StoredUnnamedGroup> = emptyList()
)

/**
 * Pure (no SQLite) half of the v3 identity tables, shared by [FaceIndexStore]
 * and JVM tests: row shapes, validation and the row delta a commit writes.
 * Row keys are native observation hex IDs and opaque person keys only.
 */
object PeopleReviewRecords {
    const val MAX_NAME_LENGTH = 80

    /** Same rule as the review reducer: trimmed, 1..80 chars, no control chars. */
    fun validName(value: String): Boolean {
        val cleaned = value.trim()
        return cleaned.isNotEmpty() && cleaned.length <= MAX_NAME_LENGTH &&
            cleaned == value && value.none { Character.isISOControl(it) }
    }

    fun generation(revision: Long): String {
        require(revision >= 0)
        return "people-review:$revision"
    }

    /** Table rows for one set of facts; cannot-link pairs are stored ordered (a < b). */
    data class Rows(
        val confirmed: Map<String, String>,
        val cannotLink: Set<Pair<String, String>>,
        val rejected: Set<Pair<String, String>>,
        /** #580 "same person" answers for two unnamed groups, stored ordered (a < b). */
        val sameUnknown: Set<Pair<String, String>> = emptySet()
    )

    fun rows(facts: ClusterReviewFacts): Rows = Rows(
        confirmed = facts.confirmed.entries.associate { (f, p) -> f.localHex to p.value },
        cannotLink = facts.cannotLink.mapTo(HashSet()) { it.first.localHex to it.second.localHex },
        rejected = facts.rejectedPeople.mapTo(HashSet()) { it.observation.localHex to it.person.value },
        sameUnknown = facts.sameUnknown.mapTo(HashSet()) { it.first.localHex to it.second.localHex }
    )

    fun facts(rows: Rows): ClusterReviewFacts = ClusterReviewFacts(
        confirmed = rows.confirmed.entries.associate { (f, p) ->
            NativeFaceObservationId(f) to PersonKey(p)
        },
        cannotLink = rows.cannotLink.mapTo(HashSet()) { (a, b) ->
            FacePair.of(NativeFaceObservationId(a), NativeFaceObservationId(b))
        },
        rejectedPeople = rows.rejected.mapTo(HashSet()) { (f, p) ->
            RejectedPerson(NativeFaceObservationId(f), PersonKey(p))
        },
        sameUnknown = rows.sameUnknown.mapTo(HashSet()) { (a, b) ->
            FacePair.of(NativeFaceObservationId(a), NativeFaceObservationId(b))
        }
    )

    /**
     * #580: rows of `people_matched` (face uid to person key) that [clusters]
     * implies: every face grouping showed under a named person on its own
     * ([FaceClusterState.matched]). The store keeps the table equal to this
     * after every publication and review commit.
     */
    fun matchedRows(clusters: FaceClusterState): Map<String, String> =
        clusters.matched.entries.associate { it.key.localHex to it.value.person.value }

    /** Rows to delete and to insert so that [old] becomes [new]; unchanged rows are kept. */
    class SetDelta<T>(val delete: List<T>, val insert: List<T>)
    fun <T> setDelta(old: Set<T>, new: Set<T>): SetDelta<T> =
        SetDelta(old.filter { it !in new }, new.filter { it !in old })

    /** Map rows: a changed value is a delete plus an insert of that key. */
    fun <K, V> mapDelta(old: Map<K, V>, new: Map<K, V>): SetDelta<Pair<K, V>> =
        setDelta(old.entries.mapTo(HashSet()) { it.key to it.value },
            new.entries.mapTo(HashSet()) { it.key to it.value })

    /**
     * Commit-time invariants the store checks before writing: valid names,
     * every confirmed person is named, and the unknown clusters plus the
     * confirmed faces account for every face in [clusters] exactly once.
     */
    fun requireConsistent(
        clusters: FaceClusterState, names: Map<PersonKey, String>,
        hidden: Set<PersonKey> = emptySet()
    ) {
        require(names.size <= ClusterReviewFacts.MAX_FACTS) { "Too many person names" }
        require(names.keys.containsAll(hidden)) { "A hidden person must be named" }
        require(names.values.all(::validName)) { "Invalid reviewed person name" }
        val facts = clusters.reviews
        require(facts.confirmed.values.all { it in names }) {
            "A confirmed face belongs to an unnamed person"
        }
        val confirmedPresent = facts.confirmed.keys.count { it in clusters }
        val membership = ClusterPublication.membership(clusters)
        require(membership.keys.none { NativeFaceObservationId(it) in facts.confirmed }) {
            "A confirmed face is still in an unknown cluster"
        }
        require(membership.size + confirmedPresent == clusters.faceCount) {
            "Unknown clusters and confirmed faces do not cover every face"
        }
    }
}
