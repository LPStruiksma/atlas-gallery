package nl.atlas.gallery.featureidentity.profile

import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceLocation
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDS-4 (#472): pure rules of identity profiles; synthetic ids and keys only. */
class IdentityProfileModelsTest {
    private val box = ReferenceFaceBox(0.1f, 0.2f, 0.5f, 0.8f)
    private fun uid(n: Int) = n.toString(16).padStart(32, '0')
    private fun media(n: Int) = "content://synthetic/primary/image/$n#photo"

    private fun reference(
        n: Int, origin: ReferenceOrigin = ReferenceOrigin.SUGGESTED_FROM_PEOPLE,
        faceUid: String? = uid(n), revision: String = "generation:1"
    ) = IdentityReference(uid(100 + n), uid(900), media(n), revision, box, null, origin, faceUid, 1L, null)

    @Test fun idsAreLocalHexAndValidated() {
        val id = IdentityProfileIds.mint()
        assertTrue(IdentityProfileIds.valid(id))
        assertFalse(IdentityProfileIds.valid("person-1"))
        assertFalse(IdentityProfileIds.valid(id.uppercase()))
        assertThrows(IllegalArgumentException::class.java) { IdentityProfileIds.require("../x") }
    }

    @Test fun labelsAreTrimmedBoundedAndPrintable() {
        assertTrue(IdentityProfileRules.validLabel("Short hair"))
        assertFalse(IdentityProfileRules.validLabel(""))
        assertFalse(IdentityProfileRules.validLabel(" Glasses"))
        assertFalse(IdentityProfileRules.validLabel("a\nb"))
        assertFalse(IdentityProfileRules.validLabel("x".repeat(61)))
        assertTrue(IdentityProfileRules.sameLabel("Glasses", "glasses"))
        assertTrue(IdentityProfileRules.SUGGESTED_VARIANT_LABELS.all(IdentityProfileRules::validLabel))
    }

    @Test fun profileNeedsALinkOrALabelAndNeverStoresAPeopleName() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityProfile(uid(1), null, null, 0L, emptyList())
        }
        val linked = IdentityProfile(uid(1), "person-key-7", null, 0L, emptyList())
        assertTrue(linked.linkedToPeople)
        assertNull(linked.label)
        val manual = IdentityProfile(uid(2), null, "Grandpa", 0L, emptyList())
        assertFalse(manual.linkedToPeople)
        // The model has no name field: a People name can only be read from People.
        assertFalse(IdentityProfile::class.java.declaredFields.any { it.name.contains("name", ignoreCase = true) })
    }

    @Test fun variantsBelongToTheirProfile() {
        val variant = AppearanceVariant(uid(3), uid(1), "Younger", emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            IdentityProfile(uid(2), null, "Someone", 0L, listOf(variant))
        }
        assertThrows(IllegalArgumentException::class.java) { AppearanceVariant(uid(3), uid(1), "", emptyList()) }
    }

    @Test fun originsRoundTripAndConstrainPeopleFaces() {
        ReferenceOrigin.entries.forEach { assertEquals(it, ReferenceOrigin.fromCode(it.code)) }
        StaleReason.entries.forEach { assertEquals(it, StaleReason.fromCode(it.code)) }
        assertThrows(IllegalArgumentException::class.java) {
            IdentityReferenceDraft(media(1), "generation:1", box, null, ReferenceOrigin.SUGGESTED_FROM_PEOPLE, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentityReferenceDraft(media(1), "generation:1", box, null, ReferenceOrigin.MANUAL, uid(1))
        }
        IdentityReferenceDraft(media(1), "generation:1", box, null, ReferenceOrigin.ADDED_BY_USER, null)
        IdentityReferenceDraft(media(1), "generation:1", box, null, ReferenceOrigin.ADDED_BY_USER, uid(1))
    }

    @Test fun boxesAndLandmarksAreNormalisedAndRoundTrip() {
        assertThrows(IllegalArgumentException::class.java) { ReferenceFaceBox(0.5f, 0.2f, 0.4f, 0.8f) }
        assertThrows(IllegalArgumentException::class.java) { ReferenceFaceBox(0f, 0f, 1.2f, 1f) }
        val marks = ReferenceLandmarks(listOf(0.3f to 0.4f, 0.45f to 0.41f, 0.37f to 0.52f, 0.31f to 0.63f, -0.02f to 0.6f))
        assertEquals(marks, ReferenceLandmarks.decode(marks.encode()))
        assertThrows(IllegalArgumentException::class.java) { ReferenceLandmarks(listOf(Float.NaN to 0f)) }
        assertThrows(IllegalArgumentException::class.java) { ReferenceLandmarks(emptyList()) }
        assertThrows(IllegalStateException::class.java) { ReferenceLandmarks.decode("0.1,x") }
    }

    @Test fun sameFaceIsDetectedByPeopleUidOrByBox() {
        val existing = reference(1)
        assertTrue(IdentityReferenceDraft(media(1), "generation:1", ReferenceFaceBox(0f, 0f, 0.2f, 0.2f), null,
            ReferenceOrigin.ADDED_BY_USER, uid(1)).sameFaceAs(existing))
        assertFalse(IdentityReferenceDraft(media(1), "generation:1", box, null,
            ReferenceOrigin.ADDED_BY_USER, uid(2)).sameFaceAs(existing))
        val manual = reference(2, ReferenceOrigin.MANUAL, null)
        assertTrue(IdentityReferenceDraft(media(2), "generation:1", box, null, ReferenceOrigin.MANUAL).sameFaceAs(manual))
        assertFalse(IdentityReferenceDraft(media(2), "generation:2", box, null, ReferenceOrigin.MANUAL).sameFaceAs(manual))
    }

    @Test fun reorderMustBeAPermutation() {
        val current = listOf("a", "b", "c")
        assertTrue(ReferenceOrdering.validReorder(current, listOf("c", "a", "b")))
        assertFalse(ReferenceOrdering.validReorder(current, listOf("a", "b")))
        assertFalse(ReferenceOrdering.validReorder(current, listOf("a", "a", "b")))
        assertFalse(ReferenceOrdering.validReorder(current, listOf("a", "b", "d")))
    }

    @Test fun derivedNamesCannotEscapeTheProfileFolder() {
        assertTrue(DerivedFileNames.valid("aligned-112.png"))
        assertFalse(DerivedFileNames.valid("../x"))
        assertFalse(DerivedFileNames.valid("a/b"))
        assertFalse(DerivedFileNames.valid(".hidden"))
        assertFalse(DerivedFileNames.valid("a..b"))
    }

    @Test fun deletionIsCompleteOnlyWithNothingLeft() {
        assertTrue(IdentityProfileDeletion(true, 5, 3, 0, 0).complete)
        assertFalse(IdentityProfileDeletion(true, 5, 2, 0, 1).complete)
        assertFalse(IdentityProfileDeletion(true, 4, 3, 1, 0).complete)
    }

    // ---- People reference rules ----

    private val facts = PeopleFaceFacts(
        confirmed = mapOf(uid(1) to "ada", uid(2) to "ada", uid(3) to "bob", uid(4) to "ada"),
        autoAdded = mapOf(uid(5) to "ada", uid(6) to "bob", uid(2) to "bob"),
        rejected = setOf(uid(4) to "ada", uid(7) to "ada")
    )

    @Test fun onlyConfirmedAndEngineAddedFacesOfThePersonAreEligible() {
        val eligible = PeopleReferenceRules.eligibleFaces(facts, "ada")
        assertEquals(listOf(uid(1) to PeopleFaceBasis.CONFIRMED, uid(2) to PeopleFaceBasis.CONFIRMED,
            uid(5) to PeopleFaceBasis.AUTO_ADDED), eligible)
        // A confirmed face wins over a stale auto-added row for someone else.
        assertFalse(PeopleReferenceRules.eligibleFaces(facts, "bob").any { it.first == uid(2) })
        assertTrue(PeopleReferenceRules.eligibleFaces(facts, "nobody").isEmpty())
    }

    @Test fun takenOutOrRejectedFacesAreNoLongerHeld() {
        assertTrue(PeopleReferenceRules.stillHeld(facts, "ada", uid(1)))
        assertTrue(PeopleReferenceRules.stillHeld(facts, "ada", uid(5)))
        assertFalse(PeopleReferenceRules.stillHeld(facts, "ada", uid(4)))
        assertFalse(PeopleReferenceRules.stillHeld(facts, "ada", uid(3)))
        assertFalse(PeopleReferenceRules.stillHeld(facts, "ada", uid(9)))
    }

    @Test fun peopleFaceStalenessNeverSwapsTheFace() {
        val ref = reference(1)
        val here = IndexedFaceLocation(IndexedMedia(media(1), "generation:1"), IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f))
        assertEquals(ReferenceCheck.Fresh, PeopleReferenceRules.peopleFaceCheck(ref, here, true))
        assertEquals(ReferenceCheck.Fresh, PeopleReferenceRules.peopleFaceCheck(ref, here, null))
        assertEquals(ReferenceCheck.Stale(StaleReason.FACE_GONE), PeopleReferenceRules.peopleFaceCheck(ref, null, true))
        assertEquals(ReferenceCheck.Stale(StaleReason.MEDIA_REVISED), PeopleReferenceRules.peopleFaceCheck(ref,
            IndexedFaceLocation(IndexedMedia(media(1), "generation:2"), IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)), true))
        assertEquals(ReferenceCheck.Stale(StaleReason.LEFT_PERSON), PeopleReferenceRules.peopleFaceCheck(ref, here, false))
    }

    @Test fun manualMediaChecksLeaveUnknownAlone() {
        assertEquals(ReferenceCheck.Fresh, PeopleReferenceRules.mediaCheck(MediaPresence.PRESENT))
        assertEquals(ReferenceCheck.Stale(StaleReason.MEDIA_GONE), PeopleReferenceRules.mediaCheck(MediaPresence.GONE))
        assertEquals(ReferenceCheck.Stale(StaleReason.MEDIA_REVISED), PeopleReferenceRules.mediaCheck(MediaPresence.REVISED))
        assertEquals(ReferenceCheck.Unknown, PeopleReferenceRules.mediaCheck(MediaPresence.UNKNOWN))
    }

    // ---- PeopleReferenceSource over a fake, read-only People ----

    private class FakeReads(
        var epoch: String? = "epoch-A",
        var facts: PeopleFaceFacts?,
        val locations: MutableMap<String, IndexedFaceLocation>,
        val notFaces: Set<String> = emptySet(),
        val epochAfterRead: String? = null
    ) : PeopleFaceReads {
        var calls = 0
        override fun activeEpoch(): String? = if (calls++ > 0 && epochAfterRead != null) epochAfterRead else epoch
        override fun facts(epoch: String) = facts
        override fun location(epoch: String, faceUid: String) = locations[faceUid]
        override fun notAFace(epoch: String, faceUid: String) = faceUid in notFaces
    }

    private fun location(n: Int, frameMs: Long? = null, revision: String = "generation:1") =
        IndexedFaceLocation(IndexedMedia(media(n), revision), IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f), frameMs)

    @Test fun sourceReadsPhotoFacesAndSkipsVideoAndNotAFace() {
        val reads = FakeReads(facts = facts, locations = mutableMapOf(
            uid(1) to location(1), uid(2) to location(2, frameMs = 400L), uid(5) to location(5)),
            notFaces = setOf(uid(5)))
        val read = PeopleReferenceSource(reads).faces("ada") as PeopleFacesRead.Faces
        assertEquals(listOf(uid(1)), read.faces.map { it.faceUid })
        val draft = read.faces.single().toDraft()
        assertEquals(ReferenceOrigin.SUGGESTED_FROM_PEOPLE, draft.origin)
        assertEquals(uid(1), draft.peopleFaceUid)
        assertThrows(IllegalArgumentException::class.java) { read.faces.single().toDraft(ReferenceOrigin.MANUAL) }
    }

    @Test fun sourceReportsUnavailableInsteadOfGuessing() {
        assertEquals(PeopleFacesRead.Unavailable,
            PeopleReferenceSource(FakeReads(epoch = null, facts = facts, locations = mutableMapOf())).faces("ada"))
        assertEquals(PeopleFacesRead.Unavailable,
            PeopleReferenceSource(FakeReads(facts = null, locations = mutableMapOf())).faces("ada"))
        assertEquals(PeopleFacesRead.Unavailable, PeopleReferenceSource(FakeReads(facts = facts,
            locations = mutableMapOf(uid(1) to location(1)), epochAfterRead = "epoch-B")).faces("ada"))
    }

    @Test fun checkMarksOnlyWhatChangedAndKeepsUnknownUnknown() {
        val profile = IdentityProfile(uid(800), "ada", null, 0L, listOf(AppearanceVariant(uid(900), uid(800), "Current",
            listOf(reference(1), reference(4), reference(5), reference(2, revision = "generation:1"),
                reference(6, ReferenceOrigin.MANUAL, null), reference(7, ReferenceOrigin.MANUAL, null),
                reference(3, ReferenceOrigin.ADDED_BY_USER, uid(3))))))
        val reads = FakeReads(facts = facts, locations = mutableMapOf(
            uid(1) to location(1), uid(4) to location(4), uid(2) to location(2, revision = "generation:2"),
            uid(3) to location(3)))
        val probe = ReferenceMediaProbe { key, _ -> if (key == media(6)) MediaPresence.GONE else MediaPresence.UNKNOWN }
        val checks = PeopleReferenceSource(reads).check(profile, probe)
        assertEquals(ReferenceCheck.Fresh, checks[uid(101)])
        assertEquals(ReferenceCheck.Stale(StaleReason.LEFT_PERSON), checks[uid(104)])
        assertEquals(ReferenceCheck.Stale(StaleReason.FACE_GONE), checks[uid(105)])
        assertEquals(ReferenceCheck.Stale(StaleReason.MEDIA_REVISED), checks[uid(102)])
        assertEquals(ReferenceCheck.Stale(StaleReason.MEDIA_GONE), checks[uid(106)])
        assertEquals(ReferenceCheck.Unknown, checks[uid(107)])
        // The user's own pick of Bob's face is not stale just because it is not Ada's.
        assertEquals(ReferenceCheck.Fresh, checks[uid(103)])

        val revoked = PeopleReferenceSource(FakeReads(epoch = null, facts = facts, locations = mutableMapOf()))
            .check(profile, probe)
        assertEquals(ReferenceCheck.Unknown, revoked[uid(101)])
        assertEquals(ReferenceCheck.Unknown, revoked[uid(104)])
    }

    @Test fun sourceHasNoWritePath() {
        // PeopleReferenceSource only ever holds the read interface.
        val fields = PeopleReferenceSource::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.type }
        assertEquals(listOf(PeopleFaceReads::class.java), fields)
        assertTrue(PeopleFaceReads::class.java.methods.none {
            it.name.startsWith("commit") || it.name.startsWith("set") || it.name.startsWith("review") ||
                it.name.startsWith("write") || it.name.startsWith("delete")
        })
    }
}
