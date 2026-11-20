package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.PersonSuggestion
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only synthetic faces; no private data or inferred confidence percentages. */
class UnknownGroupSuggestionsTest {
    private val face = NativeFaceObservationId("00000000000000000000000000000001")
    private val person = PersonKey("synthetic-reviewed-person")
    private val other = PersonKey("synthetic-unscored-person")
    private val album = NativePersonAlbum(person, "Alex", listOf(face), emptyList())
    private val unscored = NativePersonAlbum(other, "Robin", listOf(face), emptyList())
    private fun group(score: Float?) = UnknownFaceCluster(
        FaceClusterId(face), listOf(face),
        score?.let { PersonSuggestion(person, it) }
    )

    @Test fun onlyAProvenAboveThresholdSuggestionWithFaceIsShown() {
        val catalog = listOf(unscored, album)
        assertEquals(listOf(album),
            eligibleScoredGroupSuggestions(group(0.81f), catalog, 0.75f, emptySet()))
        assertTrue(eligibleScoredGroupSuggestions(group(0.74f), catalog, 0.75f,
            emptySet()).isEmpty())
        assertTrue(eligibleScoredGroupSuggestions(group(null), catalog, 0.75f,
            emptySet()).isEmpty())
        assertTrue(eligibleScoredGroupSuggestions(group(Float.NaN), catalog, 0.75f,
            emptySet()).isEmpty())
        assertTrue(eligibleScoredGroupSuggestions(group(0.81f), listOf(unscored),
            0.75f, emptySet()).isEmpty())
        assertTrue(eligibleScoredGroupSuggestions(group(0.81f),
            listOf(album.copy(observations = emptyList())), 0.75f,
            emptySet()).isEmpty())
    }

    @Test fun aPersistentNegativeSuppressesThePair() {
        assertTrue(eligibleScoredGroupSuggestions(
            group(0.99f), listOf(album), 0.75f,
            setOf(RejectedPerson(face, person))
        ).isEmpty())
    }
}
