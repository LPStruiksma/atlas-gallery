package nl.atlas.gallery.featurepeople.splitinput

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import nl.atlas.gallery.featurepeople.split.SplitFace
import nl.atlas.gallery.featurepeople.split.SplitProposer
import nl.atlas.gallery.featurepeople.split.SplitRequest

/**
 * #226 / SPLIT-1A: one selected person or one selected unnamed cluster.
 * The caller resolves membership using its current, grant-scoped People read
 * model. An inferred match is not a confirmation; only [ClusterReviewFacts]
 * holds human-confirmed owners.
 */
sealed interface SplitSubject {
    data class Named(val person: PersonKey) : SplitSubject
    data class Unnamed(val cluster: FaceClusterId) : SplitSubject
}

/**
 * A face *already selected* from the materialised, permission/source-scoped
 * People snapshot. [embedding] must be in the same metric space that
 * SplitProposer expects (ClusterPolicy.embedding), never raw SFace bytes.
 *
 * [capturedAtMillis] and [media] are opaque, 0 if unknown. Media represents a
 * photo or video frame: two different observations with the same non-zero
 * value must never be proposed as one person. This object owns no media URI,
 * pixel, display name or database connection.
 */
class SplitSourceObservation(
    val embedding: FaceEmbedding,
    val capturedAtMillis: Long = 0L,
    val media: Long = 0L
)

/**
 * Immutable-by-contract read-model boundary supplied by the future #172
 * integration. The caller materialises observations for *one* subject and
 * the matching generation, without exposing other People memberships. Facts
 * may come from the larger grant-scoped People state: the adapter narrows
 * every observation-keyed fact to the selected observation IDs.
 *
 * Do not reconstruct a snapshot after the grant or source has changed. The
 * adapter checks both fences again at conversion; accept/commit must recheck
 * them independently in its storage transaction (#233).
 */
class SplitSourceSnapshot(
    val subject: SplitSubject,
    val permissionEpoch: String,
    val sourceGeneration: String,
    val modelVersion: String,
    val observations: List<SplitSourceObservation>,
    val reviewFacts: ClusterReviewFacts,
    val crowdWeight: Float = 1f
)

enum class SplitInputUnavailableReason {
    NO_SNAPSHOT,
    STALE_SCOPE,
    EMPTY_SELECTION,
    TOO_MANY_OBSERVATIONS,
    DUPLICATE_OBSERVATION,
    EMBEDDING_MODEL_MISMATCH,
    EMBEDDING_DIMENSION_MISMATCH,
    INVALID_CROWD_WEIGHT,
    CONFLICTING_REVIEW_FACTS
}

sealed interface SplitInputResult {
    /** The caller may invoke SplitProposer.propose(request) off the UI thread. */
    class Ready(val subject: SplitSubject, val request: SplitRequest) : SplitInputResult

    /** Does not contain faces, names, vectors, source keys or review details. */
    data class Unavailable(val reason: SplitInputUnavailableReason) : SplitInputResult
}

/**
 * Pure read-only converter. Neither the adapter nor SplitProposer persists or
 * infers any confirmed identity. An invalid or stale input always fails closed
 * with a typed result, never an incomplete SplitRequest with missing vetoes.
 *
 * Selection membership is the materialiser's responsibility (e.g. named
 * confirmed + matched faces, or an unnamed cluster's exact members). The
 * adapter cannot prove membership from these selected rows alone; it only
 * validates scope fences and the consistency of their observations/facts.
 */
object SplitInputAdapter {
    fun adapt(
        snapshot: SplitSourceSnapshot?,
        currentPermissionEpoch: String?,
        currentSourceGeneration: String?
    ): SplitInputResult {
        if (snapshot == null) return unavailable(SplitInputUnavailableReason.NO_SNAPSHOT)
        if (snapshot.permissionEpoch.isBlank() || snapshot.sourceGeneration.isBlank() ||
            currentPermissionEpoch.isNullOrBlank() || currentSourceGeneration.isNullOrBlank() ||
            snapshot.permissionEpoch != currentPermissionEpoch ||
            snapshot.sourceGeneration != currentSourceGeneration
        ) return unavailable(SplitInputUnavailableReason.STALE_SCOPE)

        val input = snapshot.observations
        if (input.isEmpty()) return unavailable(SplitInputUnavailableReason.EMPTY_SELECTION)
        if (input.size > SplitProposer.MAX_FACES) {
            return unavailable(SplitInputUnavailableReason.TOO_MANY_OBSERVATIONS)
        }
        if (!snapshot.crowdWeight.isFinite() || snapshot.crowdWeight !in 0f..4f) {
            return unavailable(SplitInputUnavailableReason.INVALID_CROWD_WEIGHT)
        }
        if (snapshot.modelVersion.isBlank() || snapshot.modelVersion.length > 256 ||
            '\u0000' in snapshot.modelVersion
        ) return unavailable(SplitInputUnavailableReason.EMBEDDING_MODEL_MISMATCH)

        val selected = HashSet<NativeFaceObservationId>(input.size * 2)
        val dimension = input[0].embedding.dimension
        for (row in input) {
            val face = row.embedding
            if (!selected.add(face.observation)) {
                return unavailable(SplitInputUnavailableReason.DUPLICATE_OBSERVATION)
            }
            if (face.modelVersion != snapshot.modelVersion) {
                return unavailable(SplitInputUnavailableReason.EMBEDDING_MODEL_MISMATCH)
            }
            if (face.dimension != dimension) {
                return unavailable(SplitInputUnavailableReason.EMBEDDING_DIMENSION_MISMATCH)
            }
        }

        // Filter *both* endpoints: a cross-scope cannot-link is not an in-scope
        // constraint, and passing it to another group would leak outside IDs.
        // Inside-only vetoes, rejections and confirmed owners are preserved.
        val all = snapshot.reviewFacts
        val confirmed = all.confirmed.entries.asSequence()
            .filter { it.key in selected }
            .sortedBy { it.key.localHex }
            .associateTo(LinkedHashMap()) { it.key to it.value }
        val rejected = all.rejectedPeople.asSequence()
            .filter { it.observation in selected }
            .sortedWith(compareBy<RejectedPerson> { it.observation.localHex }
                .thenBy { it.person.value })
            .toCollection(LinkedHashSet())
        // A stale contradictory single-face decision is unsafe to translate:
        // silently letting one override the other would lose a human decision.
        if (rejected.any { confirmed[it.observation] == it.person }) {
            return unavailable(SplitInputUnavailableReason.CONFLICTING_REVIEW_FACTS)
        }
        val cannotLink = insidePairs(all.cannotLink, selected)
        val sameUnknown = insidePairs(all.sameUnknown, selected)
        val facts = ClusterReviewFacts(
            confirmed = confirmed,
            cannotLink = cannotLink,
            rejectedPeople = rejected,
            sameUnknown = sameUnknown
        )
        val faces = input.sortedBy { it.embedding.observation.localHex }.map {
            SplitFace(
                embedding = it.embedding,
                confirmedAs = confirmed[it.embedding.observation],
                capturedAtMillis = it.capturedAtMillis,
                media = it.media
            )
        }
        return SplitInputResult.Ready(snapshot.subject,
            SplitRequest(faces, facts, snapshot.crowdWeight))
    }

    private fun insidePairs(
        pairs: Set<FacePair>,
        selected: Set<NativeFaceObservationId>
    ): Set<FacePair> = pairs.asSequence()
        .filter { it.first in selected && it.second in selected }
        .sortedWith(compareBy<FacePair> { it.first.localHex }.thenBy { it.second.localHex })
        .toCollection(LinkedHashSet())

    private fun unavailable(reason: SplitInputUnavailableReason) =
        SplitInputResult.Unavailable(reason)
}
