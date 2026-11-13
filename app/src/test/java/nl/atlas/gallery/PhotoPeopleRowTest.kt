package nl.atlas.gallery

import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedPhotoFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #553: the viewer People row's pure lookup rules; invented faces and names only. */
class PhotoPeopleRowTest {
    private fun uid(n: Int) = n.toString(16).padStart(32, '0')
    private fun face(n: Int, left: Float, person: String? = null, name: String? = null,
                     anchor: String? = null) =
        IndexedPhotoFace(uid(n), IndexedFaceBox(left, 0.2f, left + 0.1f, 0.4f), anchor, person, name)

    @Test fun namedPeopleComeFirstByNameThenUnnamedGroupsLeftToRight() {
        val people = photoPeopleOf("epoch-A", listOf(
            face(1, 0.7f, anchor = uid(1)),
            face(2, 0.1f, person = "native-b", name = "Bram"),
            face(3, 0.4f, anchor = uid(30)),
            face(4, 0.5f, person = "native-a", name = "anna")))
        assertEquals(listOf(
            PhotoPerson.Named("epoch-A", uid(4), PersonKey("native-a"), "anna"),
            PhotoPerson.Named("epoch-A", uid(2), PersonKey("native-b"), "Bram"),
            PhotoPerson.Unnamed("epoch-A", uid(3), uid(30)),
            PhotoPerson.Unnamed("epoch-A", uid(1), uid(1))), people)
    }

    @Test fun onePersonOrGroupIsOneCircleWithItsLeftMostFace() {
        val people = photoPeopleOf("e", listOf(
            face(1, 0.6f, person = "p", name = "Ada"),
            face(2, 0.2f, person = "p", name = "Ada"),
            face(3, 0.9f, anchor = uid(9)),
            face(4, 0.3f, anchor = uid(9))))
        assertEquals(listOf(uid(2), uid(4)), people.map { it.face })
    }

    @Test fun unnamedConfirmedFacesAndUnpublishedFacesShowNothing() {
        // A confirmed face whose name is missing, and a face whose cluster
        // publication is pending (no anchor), are left out, not shown as "+".
        assertTrue(photoPeopleOf("e", listOf(
            face(1, 0.1f, person = "p", name = null),
            face(2, 0.2f, person = "q", name = " "),
            face(3, 0.3f))).isEmpty())
        assertTrue(photoPeopleOf("e", emptyList()).isEmpty())
    }

    @Test fun everyEntryCarriesTheScopeItWasReadUnder() {
        val people = photoPeopleOf("epoch-Z", listOf(face(1, 0.1f, anchor = uid(1)),
            face(2, 0.2f, person = "p", name = "Ada")))
        assertTrue(people.all { it.epoch == "epoch-Z" })
    }

    @Test fun nameDialogAcceptsOnlyWhatTheReviewStoreAccepts() {
        assertEquals("Ada", photoPersonNameOrNull("  Ada "))
        assertEquals("x".repeat(80), photoPersonNameOrNull("x".repeat(80)))
        assertNull(photoPersonNameOrNull(""))
        assertNull(photoPersonNameOrNull("   "))
        assertNull(photoPersonNameOrNull("x".repeat(81)))
        assertNull(photoPersonNameOrNull("A\u0000da"))
        assertNull(photoPersonNameOrNull("A\nda"))
    }
}
