package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedPeopleSortTest {
    private val people = listOf(
        PeopleProfileUi(9, "Zara", 4, 8),
        PeopleProfileUi(5, "Alex", 9, 10),
        PeopleProfileUi(2, "alex", 9, 10),
        PeopleProfileUi(4, "Bryn", 0, 3)
    )

    @Test fun defaultKeepsOriginalOrderAndListReference() {
        assertTrue(orderReviewedPeopleProfiles(people, ReviewedPeopleOrder.AS_PROVIDED) === people)
    }

    @Test fun mostAccessiblePhotosFirstWithDeterministicTies() {
        assertEquals(listOf(2L, 5L, 9L, 4L),
            orderReviewedPeopleProfiles(people, ReviewedPeopleOrder.MOST_PHOTOS).map { it.personId })
    }

    @Test fun alphabeticSortingDoesNotMergeHomonyms() {
        assertEquals(listOf(2L, 5L, 4L, 9L),
            orderReviewedPeopleProfiles(people, ReviewedPeopleOrder.NAME).map { it.personId })
    }

    @Test fun neverMutatesConfirmedProfilesOrTheirCounts() {
        val original = people.toList()
        for (choice in ReviewedPeopleOrder.entries) {
            val sorted = orderReviewedPeopleProfiles(people, choice)
            assertEquals(original.toSet(), sorted.toSet())
        }
        assertEquals(original, people)
    }

    @Test fun handlesEmptyRosterWithoutInventingPeople() {
        for (choice in ReviewedPeopleOrder.entries) {
            assertTrue(orderReviewedPeopleProfiles(emptyList(), choice).isEmpty())
        }
    }
}
