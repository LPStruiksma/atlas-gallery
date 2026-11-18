package nl.atlas.gallery.featurepeople.splitinput

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic observations only: no media, names, source paths or real embeddings. */
class SplitInputAdapterTest {
    private val named = PersonKey("synthetic-person-a")
    private val other = PersonKey("synthetic-person-b")
    private val epoch = "synthetic-grant-1"
    private val generation = "synthetic-source-1"
    private val model = "synthetic-metric"

    private fun id(n: Int) = NativeFaceObservationId("%032x".format(n))
    private fun row(n: Int, vector: FloatArray = floatArrayOf(1f, 0f), modelVersion: String = model,
                    capturedAt: Long = 0L, media: Long = 0L) =
        SplitSourceObservation(FaceEmbedding(id(n), modelVersion, vector), capturedAt, media)

    private fun snapshot(
        subject: SplitSubject = SplitSubject.Named(named),
        observations: List<SplitSourceObservation> = listOf(row(1), row(2)),
        facts: ClusterReviewFacts = ClusterReviewFacts.NONE,
        permissionEpoch: String = epoch,
        sourceGeneration: String = generation,
        modelVersion: String = model,
        crowdWeight: Float = 1f
    ) = SplitSourceSnapshot(subject, permissionEpoch, sourceGeneration, modelVersion,
        observations, facts, crowdWeight)

    private fun ready(snapshot: SplitSourceSnapshot): SplitInputResult.Ready =
        SplitInputAdapter.adapt(snapshot, epoch, generation) as SplitInputResult.Ready

    private fun unavailable(snapshot: SplitSourceSnapshot?, reason: SplitInputUnavailableReason,
                            currentEpoch: String? = epoch, currentGeneration: String? = generation) {
        assertEquals(SplitInputResult.Unavailable(reason),
            SplitInputAdapter.adapt(snapshot, currentEpoch, currentGeneration))
    }

    @Test fun namedSelectionCarriesActualConfirmedOwnersAndSourceMetadata() {
        val chosen = snapshot(
            observations = listOf(row(3, capturedAt = 123L, media = 90L), row(1, media = 90L), row(2)),
            facts = ClusterReviewFacts(confirmed = mapOf(id(1) to named, id(2) to other))
        )
        val built = ready(chosen)
        assertEquals(SplitSubject.Named(named), built.subject)
        assertEquals(listOf(id(1), id(2), id(3)),
            built.request.faces.map { it.embedding.observation })
        assertEquals(named, built.request.faces[0].confirmedAs)
        assertEquals(other, built.request.faces[1].confirmedAs)
        assertEquals(null, built.request.faces[2].confirmedAs)
        assertEquals(123L, built.request.faces[2].capturedAtMillis)
        assertEquals(90L, built.request.faces[0].media)
        assertEquals(90L, built.request.faces[2].media)
        assertEquals(1f, built.request.crowdWeight)
    }

    @Test fun unnamedSelectionAndInsideOnlyReviewFacts() {
        val selection = SplitSubject.Unnamed(FaceClusterId(id(1)))
        val inside = FacePair.of(id(1), id(2))
        val crossing = FacePair.of(id(2), id(99))
        val same = FacePair.of(id(2), id(1))
        val facts = ClusterReviewFacts(
            confirmed = mapOf(id(1) to other, id(99) to named),
            cannotLink = setOf(inside, crossing),
            rejectedPeople = setOf(RejectedPerson(id(1), named), RejectedPerson(id(99), other)),
            sameUnknown = setOf(same, crossing)
        )
        val built = ready(snapshot(subject = selection, facts = facts))
        assertEquals(selection, built.subject)
        assertEquals(mapOf(id(1) to other), built.request.facts.confirmed)
        assertEquals(setOf(inside), built.request.facts.cannotLink)
        assertEquals(setOf(RejectedPerson(id(1), named)), built.request.facts.rejectedPeople)
        assertEquals(setOf(same), built.request.facts.sameUnknown)
        assertFalse(built.request.facts.confirmed.containsKey(id(99)))
    }

    @Test fun safetyFactsRemainEvenWhenBothEndsHaveTheSameConfirmedPerson() {
        // A split can be needed precisely because an old confirmed person contains two people.
        val veto = FacePair.of(id(1), id(2))
        val built = ready(snapshot(
            facts = ClusterReviewFacts(confirmed = mapOf(id(1) to named, id(2) to named),
                cannotLink = setOf(veto), rejectedPeople = setOf(RejectedPerson(id(1), other)))
        ))
        assertEquals(setOf(veto), built.request.facts.cannotLink)
        assertEquals(2, built.request.faces.count { it.confirmedAs == named })
        assertEquals(setOf(RejectedPerson(id(1), other)), built.request.facts.rejectedPeople)
    }

    @Test fun inputOrderDoesNotChangeRequestOrScopedFacts() {
        val a = row(1); val b = row(2); val c = row(3)
        val facts = ClusterReviewFacts(
            confirmed = linkedMapOf(id(3) to named, id(1) to other),
            cannotLink = linkedSetOf(FacePair.of(id(3), id(2)), FacePair.of(id(1), id(2))),
            rejectedPeople = linkedSetOf(RejectedPerson(id(3), other), RejectedPerson(id(1), named))
        )
        val x = ready(snapshot(observations = listOf(c, b, a), facts = facts))
        val y = ready(snapshot(observations = listOf(a, b, c), facts = facts))
        assertEquals(x.request.faces.map { it.embedding.observation },
            y.request.faces.map { it.embedding.observation })
        assertEquals(x.request.facts, y.request.facts)
    }

    @Test fun staleGrantOrSourceMustNotProduceEvenAnEmptyRequest() {
        val valid = snapshot()
        unavailable(valid, SplitInputUnavailableReason.STALE_SCOPE, currentEpoch = "revoked")
        unavailable(valid, SplitInputUnavailableReason.STALE_SCOPE, currentGeneration = "rescanned")
        unavailable(valid, SplitInputUnavailableReason.STALE_SCOPE, currentEpoch = null)
        unavailable(valid, SplitInputUnavailableReason.STALE_SCOPE, currentGeneration = "")
        unavailable(snapshot(permissionEpoch = ""), SplitInputUnavailableReason.STALE_SCOPE,
            currentEpoch = "")
        unavailable(null, SplitInputUnavailableReason.NO_SNAPSHOT)
    }

    @Test fun emptyDuplicateAndOversizeSelectionsAreRefused() {
        unavailable(snapshot(observations = emptyList()), SplitInputUnavailableReason.EMPTY_SELECTION)
        unavailable(snapshot(observations = listOf(row(1), row(1))),
            SplitInputUnavailableReason.DUPLICATE_OBSERVATION)
        unavailable(snapshot(observations = List(50_001) { row(it + 1) }),
            SplitInputUnavailableReason.TOO_MANY_OBSERVATIONS)
    }

    @Test fun embeddingModelDimensionAndCrowdWeightMustBeConsistent() {
        unavailable(snapshot(modelVersion = "wrong-model"),
            SplitInputUnavailableReason.EMBEDDING_MODEL_MISMATCH)
        unavailable(snapshot(observations = listOf(row(1), row(2, modelVersion = "other-model"))),
            SplitInputUnavailableReason.EMBEDDING_MODEL_MISMATCH)
        unavailable(snapshot(observations = listOf(row(1), row(2, floatArrayOf(1f, 0f, 0f)))),
            SplitInputUnavailableReason.EMBEDDING_DIMENSION_MISMATCH)
        unavailable(snapshot(crowdWeight = Float.NaN), SplitInputUnavailableReason.INVALID_CROWD_WEIGHT)
        unavailable(snapshot(crowdWeight = 4.1f), SplitInputUnavailableReason.INVALID_CROWD_WEIGHT)
    }

    @Test fun impossibleConfirmedAndRejectedFactIsNotSilentlyDiscarded() {
        unavailable(snapshot(facts = ClusterReviewFacts(
            confirmed = mapOf(id(1) to named),
            rejectedPeople = setOf(RejectedPerson(id(1), named))
        )), SplitInputUnavailableReason.CONFLICTING_REVIEW_FACTS)
        // An external contradiction is filtered away with all external facts.
        val external = snapshot(facts = ClusterReviewFacts(
            confirmed = mapOf(id(99) to named),
            rejectedPeople = setOf(RejectedPerson(id(99), named))
        ))
        assertTrue(ready(external).request.facts.rejectedPeople.isEmpty())
    }

    @Test fun fiveThousandObservationsRetainTheirMembershipAndNoExternalReviewData() {
        val rows = (5_000 downTo 1).map { row(it, capturedAt = it.toLong(), media = it.toLong()) }
        val outside = FacePair.of(id(5_000), id(6_000))
        val inside = FacePair.of(id(1), id(5_000))
        val built = ready(snapshot(
            subject = SplitSubject.Unnamed(FaceClusterId(id(1))),
            observations = rows,
            facts = ClusterReviewFacts(
                cannotLink = setOf(outside, inside),
                rejectedPeople = setOf(RejectedPerson(id(5_000), named), RejectedPerson(id(6_000), other))
            )
        ))
        assertEquals(5_000, built.request.faces.size)
        assertEquals(id(1), built.request.faces.first().embedding.observation)
        assertEquals(id(5_000), built.request.faces.last().embedding.observation)
        assertEquals(5_000L, built.request.faces.last().capturedAtMillis)
        assertEquals(setOf(inside), built.request.facts.cannotLink)
        assertEquals(setOf(RejectedPerson(id(5_000), named)), built.request.facts.rejectedPeople)
    }
}
