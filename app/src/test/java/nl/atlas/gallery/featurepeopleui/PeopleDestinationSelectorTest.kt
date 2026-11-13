package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic identities, embeddings and grants only; no photos or device storage. */
class PeopleDestinationSelectorTest {
    private fun face(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private val first = PersonKey("synthetic-person-first")
    private val second = PersonKey("synthetic-person-second")
    private val source = PersonKey("synthetic-person-source")
    private val epoch = "synthetic-grant"
    private val generation = "synthetic-generation"

    private fun state(): PeopleClusterReviewState {
        val embeddings = listOf(
            FaceEmbedding(face(1), "synthetic-v1", floatArrayOf(1f, 0f)),
            FaceEmbedding(face(2), "synthetic-v1", floatArrayOf(0f, 1f)),
            FaceEmbedding(face(3), "synthetic-v1", floatArrayOf(-1f, 0f)),
            FaceEmbedding(face(4), "synthetic-v1", floatArrayOf(0f, -1f))
        )
        var state = PeopleClusterReviewState(
            epoch, generation,
            FaceClusterer.update(
                FaceClusterState.empty("synthetic-v1", 2, ClusterPolicy.SFACE_CONSERVATIVE),
                add = embeddings
            )
        )
        for ((number, person, name) in listOf(
            Triple(1, first, "Same Name"),
            Triple(2, second, "Same Name"),
            Triple(3, source, "Source")
        )) {
            val unknown = state.clusters.unknownClusters.single { face(number) in it.members }
            state = reviewPeopleCluster(
                state, PeopleReviewAction.NameCluster(unknown.id, person, name)
            )!!
        }
        return state
    }

    private fun choose(
        state: PeopleClusterReviewState, options: List<NativePersonAlbum>,
        position: Int?, excluded: PersonKey? = null,
        live: PeopleClusterReviewState? = state,
        grant: String? = epoch, scan: String? = generation
    ) = currentSelectedReviewedPersonOrNull(
        state, options, position, excluded, grant, scan, live
    )

    @Test fun duplicateNamesRetainTwoDifferentExplicitPersonKeys() {
        val state = state()
        val options = peopleAlbumsForGrant(state, epoch, generation)
        val homonyms = options.filter { it.name == "Same Name" }
        assertEquals(2, homonyms.size)
        assertNotEquals(homonyms[0].person, homonyms[1].person)
        val positions = options.withIndex().filter { it.value.name == "Same Name" }
        assertEquals(first, choose(state, options, positions[0].index))
        assertEquals(second, choose(state, options, positions[1].index))
        assertNull(choose(state, options, null)) // not even a single option is auto-picked
    }

    @Test fun correctExplicitTargetIsUsedForAssignMergeAndFaceReassignment() {
        val initial = state()
        val all = peopleAlbumsForGrant(initial, epoch, generation)
        val target = choose(initial, all, all.indexOfFirst { it.person == second })!!
        val unknown = initial.clusters.unknownClusters.single { face(4) in it.members }
        val assigned = applyCurrentPeopleReviewCommand(
            initial, PeopleClusterReviewCommand(
                initial, PeopleReviewAction.AssignCluster(unknown.id, target)
            )
        )!!
        assertEquals(second, assigned.clusters.reviews.confirmed[face(4)])
        assertNull(initial.clusters.reviews.confirmed[face(4)])

        val excludingSource = all.filter { it.person != source }
        val intoFirst = choose(
            initial, excludingSource, excludingSource.indexOfFirst { it.person == first }, source
        )!!
        val merged = applyCurrentPeopleReviewCommand(
            initial, PeopleClusterReviewCommand(
                initial, PeopleReviewAction.MergePeople(source, intoFirst)
            )
        )!!
        assertEquals(first, merged.clusters.reviews.confirmed[face(3)])
        assertTrue(source !in merged.names)

        val intoSecond = choose(
            initial, excludingSource, excludingSource.indexOfFirst { it.person == second }, source
        )!!
        val reassigned = applyCurrentPeopleReviewCommand(
            initial, PeopleClusterReviewCommand(
                initial, PeopleReviewAction.ReassignFace(face(3), intoSecond)
            )
        )!!
        assertEquals(second, reassigned.clusters.reviews.confirmed[face(3)])
        assertEquals(source, initial.clusters.reviews.confirmed[face(3)])
    }

    @Test fun revokedRegrantedReorderedAndReplacedCataloguesCannotDispatch() {
        val rendered = state()
        val options = peopleAlbumsForGrant(rendered, epoch, generation)
        val secondIndex = options.indexOfFirst { it.person == second }
        assertNull(choose(rendered, options, secondIndex, live = null))
        assertNull(choose(rendered, options, secondIndex, grant = null))
        assertNull(choose(rendered, options, secondIndex, grant = "revoked"))
        assertNull(choose(rendered, options, secondIndex, scan = "new-generation"))
        assertNull(choose(rendered, options, secondIndex, live = rendered.copy()))
        assertNull(choose(rendered, options.reversed(), secondIndex))
        assertNull(choose(rendered, options.drop(1), secondIndex))
        val regranted = rendered.copy(permissionEpoch = "new-grant")
        assertNull(choose(rendered, options, secondIndex, live = regranted))
        assertNull(applyCurrentPeopleReviewCommand(regranted,
            PeopleClusterReviewCommand(rendered,
                PeopleReviewAction.ReassignFace(face(3), second))))
    }

    @Test fun emptySingleAndExcludedCandidateStatesRequireExplicitValidChoice() {
        val rendered = state()
        val options = peopleAlbumsForGrant(rendered, epoch, generation)
        assertNull(choose(rendered, emptyList(), null))
        assertNull(choose(rendered, emptyList(), 0))
        assertNull(choose(rendered, options, -1))
        assertNull(choose(rendered, options, options.size))
        val onlyOne = rendered.copy(names = mapOf(first to "Same Name"))
        // It is not the same committed catalog: stale options may not dispatch.
        assertNull(choose(onlyOne, options, 0, live = onlyOne))
        val onlySourceOption = options.filter { it.person != first && it.person != second }
        assertNull(choose(rendered, onlySourceOption, null, first))
        assertNull(choose(rendered, options, options.indexOfFirst { it.person == source },
            excluded = source))
    }
}
