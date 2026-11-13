package nl.atlas.gallery.featurepersistence

import org.junit.Assert.*
import org.junit.Test

/** A failure here must produce zero writes when called inside the SQLite transaction. */
class PeopleMergePlannerTest {
    private val a = PeopleBackupRow(
        11, 101, "Confirmed Person", "/storage/emulated/0/DCIM/fiction-a.jpg"
    )
    private val b = PeopleBackupRow(
        12, 102, "Other Person", "/storage/emulated/0/DCIM/fiction-b.jpg"
    )

    @Test fun emptyAndIdenticalImportsAreIdempotent() {
        assertEquals(listOf(a, b), PeopleMergePlanner.missingAssignments(emptyList(), listOf(a, b)))
        assertTrue(PeopleMergePlanner.missingAssignments(listOf(a, b), listOf(b, a)).isEmpty())
        assertTrue(PeopleMergePlanner.missingAssignments(emptyList(), emptyList()).isEmpty())
    }

    @Test fun addsOnlyMissingRowsWithoutChangingExistingMembership() {
        val samePersonNewFace = a.copy(
            faceId = 13, imagePath = "/storage/emulated/0/DCIM/fiction-c.jpg"
        )
        assertEquals(listOf(samePersonNewFace, b),
            PeopleMergePlanner.missingAssignments(
                listOf(a), listOf(a, samePersonNewFace, b)
            ))
    }

    @Test fun anExistingFaceIdCollisionFailsEntirePlan() {
        val wrongIdentity = a.copy(personId = 999, personName = "Impostor")
        assertRejected {
            PeopleMergePlanner.missingAssignments(listOf(a), listOf(b, wrongIdentity))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(listOf(a), listOf(
                b, a.copy(imagePath = "/storage/emulated/0/DCIM/other.jpg")
            ))
        }
    }

    @Test fun inconsistentPersonNamesAndDuplicateSourceRowsFailClosed() {
        val samePersonDifferentName = b.copy(personId = a.personId)
        assertRejected {
            PeopleMergePlanner.missingAssignments(listOf(a), listOf(b, samePersonDifferentName))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(
                listOf(a.copy(faceId = 10), a.copy(faceId = 11, personName = "Inconsistent")),
                listOf(b)
            )
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(), listOf(a, a))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(), listOf(a, a.copy(personId = 303)))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(listOf(a, a), listOf(b))
        }
    }

    @Test fun rejectsMalformedIncomingFieldsEvenWhenCalledWithoutCodec() {
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(),
                listOf(a.copy(personName = "")))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(),
                listOf(a.copy(imagePath = "/unapproved/location.jpg")))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(),
                listOf(a, a.copy(faceId = 15, personName = "Other Label")))
        }
    }

    @Test fun refusesNegativeIncomingIdentifiers() {
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(), listOf(a.copy(faceId = -1)))
        }
        assertRejected {
            PeopleMergePlanner.missingAssignments(emptyList(), listOf(a.copy(personId = -2)))
        }
    }

    @Test fun largeSyntheticRosterIsLinearAndStable() {
        val existing = (1..25_000).map { i ->
            PeopleBackupRow(i.toLong(), (i % 101).toLong(),
                "Person ${i % 101}", "/storage/emulated/0/DCIM/image_${i}.jpg")
        }
        val newRows = (25_001..30_000).map { i ->
            PeopleBackupRow(i.toLong(), (i % 101).toLong(),
                "Person ${i % 101}", "/storage/emulated/0/DCIM/image_${i}.jpg")
        }
        assertEquals(newRows, PeopleMergePlanner.missingAssignments(existing, existing + newRows))
        assertTrue(PeopleMergePlanner.missingAssignments(
            existing + newRows, existing + newRows
        ).isEmpty())
    }

    @Test fun aggregateBackupCapRejectsTwoIndividuallyValidCollections() {
        // Each side remains below the 128 MiB document cap. Their additive
        // UNION crosses it, so no SQLite insertion plan may be returned.
        val longPath = "/storage/emulated/0/DCIM/" + "x".repeat(8100)
        val existing = (0 until 8_500).map { i ->
            PeopleBackupRow(i.toLong(), 1L, "Fictional", longPath)
        }
        val incoming = (8_500 until 17_000).map { i ->
            PeopleBackupRow(i.toLong(), 1L, "Fictional", longPath)
        }
        assertTrue(PeopleBackupCodec.checkedDocumentSize(existing) <
            PeopleBackupCodec.MAX_BYTES)
        assertTrue(PeopleBackupCodec.checkedDocumentSize(incoming) <
            PeopleBackupCodec.MAX_BYTES)
        assertRejected { PeopleMergePlanner.missingAssignments(existing, incoming) }
    }

    @Test fun countCapChecksCombinedRowsBeforeMaterializingHugeCollection() {
        // nCopies is a constant-space *virtual* Collection. The helper must
        // reject count overflow before iterating/allocating 500,000 rows.
        val fakeFull = java.util.Collections.nCopies(
            PeopleBackupCodec.MAX_ROWS, a
        )
        assertRejected {
            PeopleBackupCodec.checkedMergedDocumentSize(fakeFull, listOf(b))
        }
        val existing = listOf(a,b)
        assertEquals(PeopleBackupCodec.checkedDocumentSize(existing),
            PeopleBackupCodec.checkedMergedDocumentSize(existing,emptyList()))
        assertEquals(PeopleBackupCodec.checkedDocumentSize(existing),
            PeopleBackupCodec.checkedMergedDocumentSize(listOf(a),listOf(b)))
        assertTrue(PeopleMergePlanner.missingAssignments(existing,existing).isEmpty())
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Unsafe identity merge was accepted")
        } catch (_: IllegalArgumentException) {
            // The SQLite caller will roll back and leave all original rows unchanged.
        }
    }
}
