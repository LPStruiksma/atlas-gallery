package nl.atlas.gallery

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.reviewPeopleCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #434 part 2 host rules; synthetic embeddings, names and media rows only. */
class NativePersonAlbumHostTest {
    private fun face(n: Int) = NativeFaceObservationId(n.toString(16).padStart(32, '0'))
    private val ada = PersonKey("synthetic-person-ada")
    private val bo = PersonKey("synthetic-person-bo")

    private fun named(): PeopleClusterReviewState {
        var state = PeopleClusterReviewState("synthetic-grant", "synthetic-generation",
            FaceClusterer.update(
                FaceClusterState.empty("synthetic-v1", 2, ClusterPolicy.SFACE_CONSERVATIVE),
                add = listOf(
                    FaceEmbedding(face(1), "synthetic-v1", floatArrayOf(1f, 0f)),
                    FaceEmbedding(face(2), "synthetic-v1", floatArrayOf(0f, 1f)))))
        for ((n, person, name) in listOf(Triple(1, ada, "Ada"), Triple(2, bo, "Bo"))) {
            val group = state.clusters.unknownClusters.single { face(n) in it.members }
            state = reviewPeopleCluster(state,
                PeopleReviewAction.NameCluster(group.id, person, name))!!
        }
        return state
    }

    @Test fun everyNamedPersonWithAPresentFaceIsKeptAsTheSameSnapshot() {
        val state = named()
        assertSame(state, peopleWithPresentFaces(state))
    }

    @Test fun aNameWhoseConfirmedFacesAreAllGoneIsHiddenFromHome() {
        val state = named()
        // Face 2's photo was edited: its face left the published clusters while
        // the durable confirmation row is still there.
        val gone = state.copy(clusters = FaceClusterer.update(state.clusters,
            remove = listOf(face(2))))
        assertTrue(face(2) in gone.clusters.reviews.confirmed)
        val home = peopleWithPresentFaces(gone)
        assertEquals(mapOf(ada to "Ada"), home.names)
        assertEquals(gone.permissionEpoch, home.permissionEpoch)
        assertEquals(gone.sourceGeneration, home.sourceGeneration)
    }

    private fun record(id: Long, path: String = "DCIM/Camera/", video: Boolean = false) =
        MediaRecord(id, "content://media/external_primary/images/media/$id", "p$id.jpg",
            path, 64L, id * 1000L, video, volumeName = "external_primary")

    @Test fun albumKeepsGalleryOrderAndOnlyAuthorizedPhotos() {
        val sorted = listOf(record(5), record(3), record(9), record(4, video = true))
        val uris = setOf(sorted[2].uri, sorted[0].uri, sorted[3].uri,
            "content://media/external_primary/images/media/77")
        assertEquals(listOf(sorted[0], sorted[2]), selectNativePersonAlbum(sorted, uris))
        assertEquals(emptyList<MediaRecord>(), selectNativePersonAlbum(sorted, emptySet()))
    }

    @Test fun blackAndWhiteFilterNarrowsToThatFolder() {
        val sorted = listOf(record(1), record(2, "DCIM/Black and White/"), record(3))
        val all = sorted.map { it.uri }.toSet()
        assertEquals(listOf(sorted[1]), selectNativePersonAlbum(sorted, all, true))
    }
}
