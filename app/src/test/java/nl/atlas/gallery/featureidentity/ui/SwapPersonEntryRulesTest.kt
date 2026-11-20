package nl.atlas.gallery.featureidentity.ui

import nl.atlas.gallery.featureidentity.profile.AppearanceVariant
import nl.atlas.gallery.featureidentity.profile.IdentityProfile
import nl.atlas.gallery.featureidentity.profile.IdentityReference
import nl.atlas.gallery.featureidentity.profile.PeopleFaceBasis
import nl.atlas.gallery.featureidentity.profile.PeopleFaceCandidate
import nl.atlas.gallery.featureidentity.profile.ReferenceFaceBox
import nl.atlas.gallery.featureidentity.profile.ReferenceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDS-11 (#478): eligibility, People-to-choice mapping, reference suggestion and face matching. */
class SwapPersonEntryRulesTest {

    // ---- eligibility: provider MIME AND leading bytes ----

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun be32(n: Int) = bytes(n ushr 24 and 255, n ushr 16 and 255, n ushr 8 and 255, n and 255)

    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16)
    private val pngSig = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun chunk(type: String, len: Int = 0) = be32(len) + ascii(type) + ByteArray(len) + ByteArray(4)
    private val png = pngSig + chunk("IHDR", 13) + chunk("IDAT", 2)
    private val apng = pngSig + chunk("IHDR", 13) + chunk("acTL", 8) + chunk("IDAT", 2)
    private fun webp(kind: String, flags: Int = 0) =
        ascii("RIFF") + be32(100) + ascii("WEBP") + ascii(kind) + be32(10) + bytes(flags, 0, 0, 0)
    private fun heif(brand: String) = be32(24) + ascii("ftyp") + ascii(brand) + be32(0)

    @Test fun supportedStillsPass() {
        assertTrue(SwapPersonEligibility.staticImage("image/jpeg", jpeg))
        assertTrue(SwapPersonEligibility.staticImage("IMAGE/JPEG; charset=binary", jpeg))
        assertTrue(SwapPersonEligibility.staticImage("image/png", png))
        assertTrue(SwapPersonEligibility.staticImage("image/webp", webp("VP8 ")))
        assertTrue(SwapPersonEligibility.staticImage("image/webp", webp("VP8L")))
        assertTrue(SwapPersonEligibility.staticImage("image/webp", webp("VP8X", flags = 0x10)))
        assertTrue(SwapPersonEligibility.staticImage("image/heic", heif("heic")))
        assertTrue(SwapPersonEligibility.staticImage("image/heif", heif("mif1")))
    }

    @Test fun animatedAndUnsupportedAreHidden() {
        assertFalse("animated GIF", SwapPersonEligibility.staticImage("image/gif", ascii("GIF89a")))
        assertFalse("APNG", SwapPersonEligibility.staticImage("image/png", apng))
        assertFalse("animated WebP", SwapPersonEligibility.staticImage("image/webp", webp("VP8X", flags = 0x02)))
        assertFalse("HEIF sequence", SwapPersonEligibility.staticImage("image/heif", heif("msf1")))
        assertFalse("AVIF", SwapPersonEligibility.staticImage("image/avif", heif("avif")))
        assertFalse("unknown type", SwapPersonEligibility.staticImage(null, jpeg))
        assertFalse("empty", SwapPersonEligibility.staticImage("image/jpeg", ByteArray(0)))
    }

    @Test fun typeAndBytesMustAgree() {
        // A file named or typed .jpg that is really a GIF stays hidden.
        assertFalse(SwapPersonEligibility.staticImage("image/jpeg", ascii("GIF89a....")))
        assertFalse(SwapPersonEligibility.staticImage("image/png", jpeg))
        assertFalse(SwapPersonEligibility.staticImage("image/webp", png))
    }

    @Test fun onlyMediaStoreStillsAreOffered() {
        assertTrue(SwapPersonEligibility.eligible(false, "content", "media", "image/jpeg", jpeg))
        assertFalse("video", SwapPersonEligibility.eligible(true, "content", "media", "image/jpeg", jpeg))
        assertFalse("file URI", SwapPersonEligibility.eligible(false, "file", null, "image/jpeg", jpeg))
        assertFalse("other provider", SwapPersonEligibility.eligible(false, "content", "downloads", "image/jpeg", jpeg))
    }

    // ---- People → S1 choices ----

    private fun id(n: Int) = "%032x".format(n)
    private fun ref(n: Int, variant: Int, media: String, uid: String? = null, stale: Boolean = false) = IdentityReference(
        id(n), id(variant), media, "r1", ReferenceFaceBox(.1f, .1f, .3f, .3f), null,
        if (uid != null) ReferenceOrigin.SUGGESTED_FROM_PEOPLE else ReferenceOrigin.MANUAL, uid, 0L,
        if (stale) nl.atlas.gallery.featureidentity.profile.StaleReason.MEDIA_GONE else null)
    private fun profile(n: Int, person: String?, label: String?, refs: List<IdentityReference> = emptyList(),
                        extraLook: Boolean = false) = IdentityProfile(id(n), person, label, 0L,
        listOf(AppearanceVariant(id(n + 100), id(n), "Current", refs)) +
            (if (extraLook) listOf(AppearanceVariant(id(n + 200), id(n), "Glasses", emptyList())) else emptyList()))

    @Test fun peopleWithoutProfileGetPlaceholdersAndNothingIsWritten() {
        val named = listOf(SwapNamedPerson("pk-ada", "Ada", 40, id(9)), SwapNamedPerson("pk-bo", "Bo", 12, id(8)))
        val linked = profile(1, "pk-bo", null, extraLook = true)
        val manual = profile(2, null, "Grandad", listOf(ref(3, 102, "m1"), ref(4, 102, "m2", stale = true)))
        val gone = profile(5, "pk-left-people", null)
        val choices = SwapPersonPeopleRules.choices(named, listOf(linked, manual, gone),
            recents = listOf(id(2), "people:pk-ada"), personFace = { null }, profileFace = { null })
        assertEquals(listOf("people:pk-ada", id(1), id(2)), choices.map { it.profileId })
        assertEquals(listOf("Ada", "Bo", "Grandad"), choices.map { it.name })
        assertEquals(listOf(1, null, 0), choices.map { it.recentRank })
        assertEquals("unused stale reference does not count", 1, choices[2].photoCount)
        assertEquals(listOf(SwapLook(SwapPersonPeopleRules.DEFAULT_LOOK, "Current")), choices[0].looks)
        assertEquals(listOf("Current", "Glasses"), choices[1].looks.map { it.label })
        assertEquals("pk-ada", SwapPersonPeopleRules.placeholderKey(choices[0].profileId))
        assertNull(SwapPersonPeopleRules.placeholderKey(id(1)))
        assertNull(SwapPersonPeopleRules.placeholderKey("people:"))
    }

    @Test fun unnamedOrEmptyPeopleAreLeftOut() {
        val named = listOf(SwapNamedPerson("a", " ", 10, id(1)), SwapNamedPerson("b", "Bo", 0, id(2)))
        assertTrue(SwapPersonPeopleRules.choices(named, emptyList(), emptyList(), { null }, { null }).isEmpty())
    }

    @Test fun recentsAreKeptByPersonForLinkedProfiles() {
        assertEquals("people:pk-bo", SwapPersonPeopleRules.recentKey(profile(1, "pk-bo", null)))
        assertEquals(id(2), SwapPersonPeopleRules.recentKey(profile(2, null, "Grandad")))
        assertEquals(listOf("b", "a", "c"), SwapPersonPeopleRules.touch(listOf("a", "b", "c"), "b"))
        assertEquals(2, SwapPersonPeopleRules.touch(listOf("a", "b"), "c", limit = 2).size)
    }

    // ---- reference suggestions ----

    private fun face(uid: Int, media: String, basis: PeopleFaceBasis = PeopleFaceBasis.CONFIRMED) =
        PeopleFaceCandidate(id(uid), media, "r1", ReferenceFaceBox(.1f, .1f, .2f, .2f), basis)

    @Test fun suggestionsPreferConfirmedOneFacePerPhotoAndSkipExisting() {
        val faces = listOf(face(1, "m1", PeopleFaceBasis.AUTO_ADDED), face(2, "m2"), face(3, "m2"),
            face(4, "m3"), face(5, "m4"), face(6, "m5", PeopleFaceBasis.AUTO_ADDED))
        val existing = listOf(ref(10, 100, "m3", uid = id(4)))
        val picked = SwapPersonPeopleRules.suggest(faces, existing, want = 3)
        assertEquals(listOf(id(2), id(5), id(1)), picked.map { it.faceUid })
        assertTrue(SwapPersonPeopleRules.suggest(faces, existing, want = 0).isEmpty())
    }

    // ---- "Which one is <name>?" ----

    @Test fun clearMatchSkipsTheQuestionOtherwiseAsk() {
        val ada = floatArrayOf(1f, 0f, 0f)
        val near = floatArrayOf(.95f, .1f, 0f)
        val other = floatArrayOf(0f, 1f, 0f)
        assertEquals(1, SwapFaceMatch.index(listOf(ada, near), listOf(other, near)))
        assertNull("two look-alikes: ask", SwapFaceMatch.index(listOf(ada), listOf(near, floatArrayOf(.94f, .12f, 0f))))
        assertNull("nobody close: ask", SwapFaceMatch.index(listOf(ada), listOf(other, floatArrayOf(0f, 0f, 1f))))
        assertNull("nothing chosen yet", SwapFaceMatch.index(emptyList(), listOf(ada)))
        assertEquals("a face without a vector is skipped", 1, SwapFaceMatch.index(listOf(ada), listOf(null, near)))
        assertNull("mismatched sizes", SwapFaceMatch.index(listOf(ada), listOf(floatArrayOf(1f, 0f))))
    }
}
