package nl.atlas.gallery.featureintents

import android.content.Intent
import android.provider.MediaStore

/**
 * BENCH-2 (#573) Phase A: how ATLAS reads an intent another app sent it.
 *
 * Pure Kotlin over plain strings, so every rule is a JVM test (no Robolectric in
 * this module). The Android side ([ExternalIntentReader]) only copies the
 * intent's fields into [ExternalIntentFacts]; it never decides anything.
 *
 * The rules come from the platform contracts, not from another gallery app:
 * - `ACTION_VIEW`, `MediaStore.ACTION_REVIEW` and `MediaStore.ACTION_REVIEW_SECURE`
 *   open ONE caller-granted `content://` item. `Intent.data` wins; only when it is
 *   absent is `ClipData` item 0 used; if item 0 has no URI the intent is refused,
 *   and later clip items are never read, so a caller can never widen or swap
 *   what ATLAS shows.
 * - `ACTION_GET_CONTENT` / `ACTION_PICK` ask for a pick; the requested MIME types
 *   and `EXTRA_ALLOW_MULTIPLE` decide what may be returned.
 * - `file://` and every non-`content` scheme are refused, never followed.
 * The OEM `com.android.camera.action.REVIEW` alias is deliberately NOT accepted
 * (design revision on #573: standard actions only until a real sender is verified).
 */
internal class ExternalIntentFacts(
    val action: String?,
    val dataUri: String?,
    val type: String?,
    /**
     * The URI of `ClipData` item 0 only, null when there is no clip, item 0 is
     * text-only, or its URI is null. Later items are never read, so a bad first
     * item can't be skipped over to promote another URI.
     */
    val firstClipUri: String? = null,
    val allowMultiple: Boolean = false,
    val extraMimeTypes: List<String>? = null
)

internal enum class ExternalViewKind {
    /** `Intent.ACTION_VIEW` from a file manager, chat app or browser. */
    VIEW,
    /** `MediaStore.ACTION_REVIEW`: the camera's thumbnail, device unlocked. */
    REVIEW,
    /**
     * `MediaStore.ACTION_REVIEW_SECURE`: the camera's thumbnail over the lock
     * screen. Only this kind may show above the keyguard, and it never offers
     * neighbours, Details, sharing or any other library surface.
     */
    REVIEW_SECURE;

    val secure: Boolean get() = this == REVIEW_SECURE
}

internal enum class ExternalRejectReason {
    /** No `data` and no `ClipData` item. */
    MISSING_URI,
    /** `file://`, `http(s)://`, a bare path, or anything that is not `content://authority/…`. */
    NOT_CONTENT_URI,
    /** The caller asked for something that is not a photo or video. */
    UNSUPPORTED_TYPE
}

internal enum class ExternalMediaKind { IMAGE, VIDEO }

/** What the caller asked ATLAS to pick from, after normalising its MIME types. */
internal class PickMediaFilter private constructor(
    /** Lower-case `image/…` / `video/…` patterns, `*` allowed as the subtype. */
    val patterns: List<String>
) {
    val allowsImages: Boolean get() = patterns.any { it.startsWith("image/") }
    val allowsVideos: Boolean get() = patterns.any { it.startsWith("video/") }

    /** For the grid: may a catalogue row of this kind be offered at all? */
    fun offers(isVideo: Boolean): Boolean = if (isVideo) allowsVideos else allowsImages

    /**
     * For the result: does the provider-resolved MIME type of a chosen item
     * satisfy the request? A missing or non-media type never does.
     */
    fun accepts(resolvedMime: String?): Boolean {
        val mime = normaliseMime(resolvedMime) ?: return false
        if (ExternalIntentParser.mediaKindOf(mime) == null) return false
        return patterns.any { pattern ->
            pattern == mime || (pattern.endsWith("/*") &&
                mime.substringBefore('/') == pattern.substringBefore('/'))
        }
    }

    override fun equals(other: Any?): Boolean = other is PickMediaFilter && other.patterns == patterns
    override fun hashCode(): Int = patterns.hashCode()
    override fun toString(): String = "PickMediaFilter($patterns)"

    companion object {
        val ANY_MEDIA = PickMediaFilter(listOf("image/*", "video/*"))
        val IMAGES = PickMediaFilter(listOf("image/*"))
        val VIDEOS = PickMediaFilter(listOf("video/*"))

        /**
         * Keeps only the photo/video part of [requested]; `*`/`*` (or no type at
         * all) means any photo or video. Null when nothing requested is media.
         */
        fun of(requested: List<String>): PickMediaFilter? {
            val normalised = requested.mapNotNull(::normaliseMime)
            if (normalised.isEmpty() || normalised.any { it == "*/*" || it == "*" }) return ANY_MEDIA
            val media = normalised.filter { ExternalIntentParser.mediaKindOf(it) != null }.distinct()
            if (media.isEmpty()) return null
            // image/* already covers image/png; keep the narrowest honest list.
            val collapsed = media.filter { type ->
                type.endsWith("/*") || "${type.substringBefore('/')}/*" !in media
            }
            return when (collapsed.toSet()) {
                setOf("image/*", "video/*") -> ANY_MEDIA
                setOf("image/*") -> IMAGES
                setOf("video/*") -> VIDEOS
                else -> PickMediaFilter(collapsed.sorted())
            }
        }

        private fun normaliseMime(raw: String?): String? =
            raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}

internal sealed interface ExternalEntry {
    /** Launcher start, or an action ATLAS does not handle: the normal app. */
    data object Library : ExternalEntry

    /** Show exactly [uri]; [declaredType] is the caller's claim, re-checked on IO. */
    data class Viewer(
        val uri: String,
        val kind: ExternalViewKind,
        val declaredType: String?
    ) : ExternalEntry

    data class Pick(
        val filter: PickMediaFilter,
        val allowMultiple: Boolean,
        /** True for `ACTION_PICK`, false for `ACTION_GET_CONTENT`. */
        val legacyPick: Boolean
    ) : ExternalEntry

    /**
     * A viewer/review intent ATLAS refuses. [secure] keeps the refusal inside the
     * lock-screen contract (shown above the keyguard, Back to the camera).
     */
    data class Rejected(
        val reason: ExternalRejectReason,
        val secure: Boolean,
        val pick: Boolean = false
    ) : ExternalEntry
}

internal object ExternalIntentParser {
    // Compile-time constants from android.jar, so the JVM tests see the real values.
    const val ACTION_VIEW: String = Intent.ACTION_VIEW
    const val ACTION_GET_CONTENT: String = Intent.ACTION_GET_CONTENT
    const val ACTION_PICK: String = Intent.ACTION_PICK
    const val ACTION_REVIEW: String = MediaStore.ACTION_REVIEW
    const val ACTION_REVIEW_SECURE: String = MediaStore.ACTION_REVIEW_SECURE

    fun parse(facts: ExternalIntentFacts): ExternalEntry = when (facts.action) {
        ACTION_VIEW -> viewer(facts, ExternalViewKind.VIEW)
        ACTION_REVIEW -> viewer(facts, ExternalViewKind.REVIEW)
        ACTION_REVIEW_SECURE -> viewer(facts, ExternalViewKind.REVIEW_SECURE)
        ACTION_GET_CONTENT -> pick(facts, legacyPick = false)
        ACTION_PICK -> pick(facts, legacyPick = true)
        else -> ExternalEntry.Library
    }

    /** True only for the secure camera entry: the one case allowed over the lock screen. */
    fun showWhenLocked(entry: ExternalEntry): Boolean = when (entry) {
        is ExternalEntry.Viewer -> entry.kind.secure
        is ExternalEntry.Rejected -> entry.secure
        else -> false
    }

    /** `image/…` → IMAGE, `video/…` → VIDEO, anything else (or null) → null. */
    fun mediaKindOf(mime: String?): ExternalMediaKind? {
        val top = mime?.substringBefore('/')?.trim()?.lowercase() ?: return null
        if (!mime.contains('/')) return null
        return when (top) {
            "image" -> ExternalMediaKind.IMAGE
            "video" -> ExternalMediaKind.VIDEO
            else -> null
        }
    }

    /** `content://<authority>/…` with a non-empty authority; nothing else. */
    fun isContentUri(uri: String?): Boolean {
        if (uri == null) return false
        val colon = uri.indexOf(':')
        if (colon <= 0 || !uri.substring(0, colon).equals("content", ignoreCase = true)) return false
        val rest = uri.substring(colon + 1)
        if (!rest.startsWith("//")) return false
        val authority = rest.substring(2).substringBefore('/').substringBefore('?')
        return authority.isNotBlank() && authority.none { it.isWhitespace() }
    }

    private fun viewer(facts: ExternalIntentFacts, kind: ExternalViewKind): ExternalEntry {
        val uri = facts.dataUri?.takeIf { it.isNotBlank() }
            ?: facts.firstClipUri?.takeIf { it.isNotBlank() }
            ?: return ExternalEntry.Rejected(ExternalRejectReason.MISSING_URI, kind.secure)
        if (!isContentUri(uri)) {
            return ExternalEntry.Rejected(ExternalRejectReason.NOT_CONTENT_URI, kind.secure)
        }
        val declared = facts.type?.trim()?.takeIf { it.isNotEmpty() }
        // A declared wildcard (*/*) says nothing; the provider type is checked later.
        if (declared != null && declared != "*/*" && mediaKindOf(declared) == null) {
            return ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE, kind.secure)
        }
        return ExternalEntry.Viewer(uri, kind, declared?.takeIf { it != "*/*" })
    }

    private fun pick(facts: ExternalIntentFacts, legacyPick: Boolean): ExternalEntry {
        val requested = buildList {
            facts.type?.let(::add)
            facts.extraMimeTypes?.let(::addAll)
            // ACTION_PICK usually names a collection instead of a type, e.g.
            // MediaStore.Images.Media.EXTERNAL_CONTENT_URI.
            if (legacyPick && facts.type == null) collectionTypeOf(facts.dataUri)?.let(::add)
        }
        val filter = PickMediaFilter.of(requested)
            ?: return ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE,
                secure = false, pick = true)
        return ExternalEntry.Pick(filter, facts.allowMultiple, legacyPick)
    }

    /** A MediaStore images collection URI means any image; a video collection, any video. */
    private fun collectionTypeOf(dataUri: String?): String? {
        if (dataUri == null || !isContentUri(dataUri)) return null
        val segments = dataUri.substringAfter("://").substringBefore('?').split('/')
        if (segments.firstOrNull() != "media") return null
        return when (segments.getOrNull(2)) {
            "images" -> "image/*"
            "video" -> "video/*"
            else -> null
        }
    }
}

/** The result a finished pick hands back, before it becomes an Android Intent. */
internal class PickResultPlan(
    /** Goes in `Intent.data`: the first chosen item. */
    val primaryUri: String,
    /** Every chosen item in pick order; becomes `ClipData` (one item for a single pick). */
    val clipUris: List<String>
)

internal object PickResultPlanner {
    /**
     * Null when the choice is not a valid answer: nothing chosen, more than one
     * item for a single pick, a non-`content` URI, or a duplicate. The caller
     * then keeps the picker open; it never returns a partial or widened set.
     */
    fun plan(request: ExternalEntry.Pick, chosen: List<String>): PickResultPlan? {
        if (chosen.isEmpty()) return null
        if (!request.allowMultiple && chosen.size != 1) return null
        if (chosen.any { !ExternalIntentParser.isContentUri(it) }) return null
        if (chosen.toSet().size != chosen.size) return null
        return PickResultPlan(chosen.first(), chosen.toList())
    }

    /** Done is enabled only when [count] chosen items are a valid answer. */
    fun doneEnabled(request: ExternalEntry.Pick, count: Int): Boolean =
        count >= 1 && (request.allowMultiple || count == 1)
}
