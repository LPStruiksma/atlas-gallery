package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster
import org.junit.Assert.assertEquals
import org.junit.Test

/** #579: small groups wait behind "Show all", like Samsung's People. */
class PeopleGroupsToShowTest {
    private var next = 1
    private fun group(size: Int): UnknownFaceCluster {
        val members = List(size) { NativeFaceObservationId("%032x".format(next++)) }
        return UnknownFaceCluster(FaceClusterId(members.first()), members, null)
    }

    @Test fun hidesGroupsUnderThreeFacesUntilShowAll() {
        val groups = listOf(group(40), group(3), group(2), group(1), group(1))
        assertEquals(groups.take(2), peopleGroupsToShow(groups, showAll = false))
        assertEquals(groups, peopleGroupsToShow(groups, showAll = true))
    }

    @Test fun showsEverythingWhenNoGroupIsLargeYet() {
        val groups = listOf(group(2), group(1))
        assertEquals(groups, peopleGroupsToShow(groups, showAll = false))
    }
}
