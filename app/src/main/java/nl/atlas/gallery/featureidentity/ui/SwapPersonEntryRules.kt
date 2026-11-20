package nl.atlas.gallery.featureidentity.ui

import nl.atlas.gallery.featureidentity.profile.IdentityProfile
import nl.atlas.gallery.featureidentity.profile.IdentityReference
import nl.atlas.gallery.featureidentity.profile.PeopleFaceBasis
import nl.atlas.gallery.featureidentity.profile.PeopleFaceCandidate
import kotlin.math.sqrt

/*
 * IDS-11 (#478): pure rules behind the "Swap person" entry points and their production host
 * (design brief rev 2, https://github.com/LPStruiksma/atlas-gallery/issues/478#issuecomment-7027593393).
 * No Android types, so JVM tests cover every branch. Nothing here logs names, boxes or pixels.
 */

/**
 * Which media may offer "Swap person". AI Edit's own guard is left unchanged; this one adds a
 * supported static image type, decided from the provider's MIME type AND the leading bytes, never
 * from a file name. The row is hidden (not greyed out) whenever this says no.
 */
object SwapPersonEligibility {
    /** Leading bytes read for the check; enough for every container header below. */
    const val HEADER_BYTES = 4096

    private val STATIC_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** HEIF major brands of a still image; image sequences (msf1, hevc, hevs...) are animated. */
    private val HEIF_STILL_BRANDS = setOf("heic", "heix", "heim", "heis", "mif1", "mif2")

    /** The existing AI Edit / MediaStore conditions: a still MediaStore image, nothing else. */
    fun mediaStoreStill(isVideo: Boolean, scheme: String?, authority: String?): Boolean =
        !isVideo && scheme == "content" && authority == "media"

    /** True for JPEG, PNG, WebP or HEIC/HEIF whose bytes confirm the type and show no animation. */
    fun staticImage(mime: String?, header: ByteArray): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (type !in STATIC_TYPES) return false
        return when (type) {
            "image/jpeg" -> header.size >= 3 && u8(header, 0) == 0xFF && u8(header, 1) == 0xD8 && u8(header, 2) == 0xFF
            "image/png" -> stillPng(header)
            "image/webp" -> stillWebp(header)
            else -> stillHeif(header)
        }
    }

    fun eligible(isVideo: Boolean, scheme: String?, authority: String?, mime: String?, header: ByteArray): Boolean =
        mediaStoreStill(isVideo, scheme, authority) && staticImage(mime, header)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun ascii(b: ByteArray, at: Int, n: Int): String? =
        if (at + n > b.size) null else String(CharArray(n) { (b[at + it].toInt() and 0xFF).toChar() })
    private fun u32(b: ByteArray, at: Int): Long =
        (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * PNG signature, and the first `IDAT` is reached with no `acTL` chunk (APNG) before it. Fails
     * closed: bytes that end before `IDAT` (a large ancillary chunk such as iCCP, or a truncated
     * file) never count as a still. Callers with a stream use [pngChunkSkeleton] so a long PNG
     * header is still decided from its real chunk sequence.
     */
    private fun stillPng(b: ByteArray): Boolean {
        if (b.size < 8 || PNG_SIGNATURE.indices.any { u8(b, it) != PNG_SIGNATURE[it] }) return false
        var at = 8
        while (at + 8 <= b.size) {
            val length = u32(b, at)
            when (ascii(b, at + 4, 4)) {
                "acTL" -> return false
                "IDAT" -> return true
            }
            if (length > Int.MAX_VALUE - 12L) return false
            at += 12 + length.toInt()
        }
        return false
    }

    /** Most chunks walked before giving up (and failing closed) in [pngChunkSkeleton]. */
    const val PNG_MAX_CHUNKS = 1024

    /**
     * Streams a PNG's chunk headers up to the first `IDAT` or `acTL`, skipping chunk data, and
     * returns the signature plus each chunk as an empty chunk (length 0, type, CRC 0). The result
     * feeds [staticImage] like a header, but is decided over the whole pre-IDAT structure however
     * large its ancillary chunks are. A stream that is not PNG, ends early or is too long
     * returns what was read, which [staticImage] rejects.
     */
    fun pngChunkSkeleton(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val sig = readFully(input, 8) ?: return out.toByteArray()
        out.write(sig)
        if (PNG_SIGNATURE.indices.any { u8(sig, it) != PNG_SIGNATURE[it] }) return out.toByteArray()
        repeat(PNG_MAX_CHUNKS) {
            val head = readFully(input, 8) ?: return out.toByteArray()
            out.write(byteArrayOf(0, 0, 0, 0)); out.write(head, 4, 4); out.write(byteArrayOf(0, 0, 0, 0))
            val type = ascii(head, 4, 4)
            if (type == "IDAT" || type == "acTL") return out.toByteArray()
            if (!skipFully(input, u32(head, 0) + 4)) return out.toByteArray()
        }
        return out.toByteArray()
    }

    private fun readFully(input: java.io.InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) return null
            got += r
        }
        return b
    }

    private fun skipFully(input: java.io.InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) return false
            left--
        }
        return true
    }

    /** RIFF/WEBP with a simple (VP8/VP8L) image, or VP8X without the animation flag. */
    private fun stillWebp(b: ByteArray): Boolean {
        if (ascii(b, 0, 4) != "RIFF" || ascii(b, 8, 4) != "WEBP") return false
        return when (ascii(b, 12, 4)) {
            "VP8 ", "VP8L" -> true
            "VP8X" -> b.size > 20 && (u8(b, 20) and 0x02) == 0
            else -> false
        }
    }

    /** ISO-BMFF `ftyp` box whose major brand is a still HEIF image. */
    private fun stillHeif(b: ByteArray): Boolean {
        if (ascii(b, 4, 4) != "ftyp") return false
        return ascii(b, 8, 4)?.lowercase() in HEIF_STILL_BRANDS
    }
}

/** A named ATLAS People person, as the People home summary already lists them. */
data class SwapNamedPerson(
    val personKey: String,
    val name: String,
    val photoCount: Int,
    val coverFaceUid: String
)

/**
 * How People persons and identity profiles become S1 choices. A People person without a profile
 * gets a placeholder id (`people:<key>`); the host creates the profile only when the person is
 * actually chosen, so opening the sheet never writes anything.
 */
object SwapPersonPeopleRules {
    const val PLACEHOLDER = "people:"
    const val MIN_REFERENCES = 3
    const val MAX_REFERENCES = 8

    fun placeholderKey(profileId: String): String? =
        profileId.takeIf { it.startsWith(PLACEHOLDER) }?.removePrefix(PLACEHOLDER)?.takeIf { it.isNotBlank() }

    /** The id recents are kept under: People persons by person key, manual profiles by id. */
    fun recentKey(profile: IdentityProfile): String =
        profile.peoplePersonKey?.let { PLACEHOLDER + it } ?: profile.id

    /**
     * Named People persons (People stays the authority for names and who exists) plus manual
     * profiles. A linked profile whose person is no longer named is left out. Looks come from
     * the profile's variants; a person without a profile has the single default look.
     */
    fun choices(
        named: List<SwapNamedPerson>,
        profiles: List<IdentityProfile>,
        recents: List<String>,
        personFace: (SwapNamedPerson) -> SwapFace?,
        profileFace: (IdentityProfile) -> SwapFace?
    ): List<SwapPersonChoice> {
        val byPerson = profiles.filter { it.peoplePersonKey != null }.associateBy { it.peoplePersonKey!! }
        fun rank(key: String) = recents.indexOf(key).takeIf { it >= 0 }
        val people = named.filter { it.name.isNotBlank() && it.photoCount > 0 }.map { person ->
            val profile = byPerson[person.personKey]
            SwapPersonChoice(
                profileId = profile?.id ?: (PLACEHOLDER + person.personKey),
                name = person.name,
                photoCount = person.photoCount,
                recentRank = rank(PLACEHOLDER + person.personKey),
                looks = looks(profile),
                face = personFace(person)
            )
        }
        val manual = profiles.filter { it.peoplePersonKey == null }.map { profile ->
            SwapPersonChoice(
                profileId = profile.id,
                name = profile.label,
                photoCount = profile.variants.sumOf { it.usableReferences.size },
                recentRank = rank(profile.id),
                looks = looks(profile),
                face = profileFace(profile)
            )
        }
        return people + manual
    }

    private fun looks(profile: IdentityProfile?): List<SwapLook> =
        profile?.variants?.map { SwapLook(it.id, it.label) }?.takeIf { it.isNotEmpty() }
            ?: listOf(SwapLook(DEFAULT_LOOK, "Current"))

    /** Look id used before a person has a profile; the host maps it to the first variant. */
    const val DEFAULT_LOOK = "current"

    /**
     * People faces to suggest as references when a look has fewer than [want]: confirmed faces
     * first (People's own order), one face per photo, never a face that is already a reference.
     */
    fun suggest(
        candidates: List<PeopleFaceCandidate>,
        existing: List<IdentityReference>,
        want: Int
    ): List<PeopleFaceCandidate> {
        if (want <= 0) return emptyList()
        val usedMedia = existing.mapTo(HashSet()) { it.mediaKey }
        val usedFaces = existing.mapNotNullTo(HashSet()) { it.peopleFaceUid }
        val ordered = candidates.filter { it.basis == PeopleFaceBasis.CONFIRMED } +
            candidates.filter { it.basis != PeopleFaceBasis.CONFIRMED }
        val out = ArrayList<PeopleFaceCandidate>()
        for (c in ordered) {
            if (out.size >= want) break
            if (c.faceUid in usedFaces || c.mediaKey in usedMedia) continue
            usedMedia += c.mediaKey
            out += c
        }
        return out
    }

    /** Most recent first, at most [limit], without duplicates. */
    fun touch(recents: List<String>, key: String, limit: Int = 24): List<String> =
        (listOf(key) + recents.filter { it != key }).take(limit)
}

/** "Which one is <name>?" is skipped only when one face clearly matches the photos chosen so far. */
object SwapFaceMatch {
    /** SFace cosine for the same person (the People band); below it, ask. */
    const val SAME_PERSON = 0.363f
    const val MARGIN = 0.08f

    fun index(chosen: List<FloatArray>, faces: List<FloatArray?>): Int? {
        if (chosen.isEmpty() || faces.isEmpty()) return null
        val dim = chosen[0].size
        if (dim == 0 || chosen.any { it.size != dim }) return null
        val centre = unit(FloatArray(dim) { i -> chosen.sumOf { it[i].toDouble() }.toFloat() }) ?: return null
        val scores = faces.map { f -> f?.takeIf { it.size == dim }?.let(::unit)?.let { dot(it, centre) } }
        val ranked = scores.withIndex().filter { it.value != null }.sortedByDescending { it.value!! }
        val best = ranked.firstOrNull() ?: return null
        if (best.value!! < SAME_PERSON) return null
        val second = ranked.getOrNull(1)?.value
        return if (second == null || best.value!! - second >= MARGIN) best.index else null
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun unit(v: FloatArray): FloatArray? {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return if (n <= 1e-6f || !n.isFinite()) null else FloatArray(v.size) { v[it] / n }
    }
}
