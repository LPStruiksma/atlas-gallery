package nl.atlas.gallery.featurevisual.nls.query

import java.text.Normalizer
import java.util.Locale

/**
 * NLS-1C (#218): deterministic, offline input preparation for a future SigLIP 2 text encoder.
 *
 * This is NOT translation, a tokenizer, a content filter, or a claim of better retrieval.
 * Pass the query's known locale explicitly: only a Dutch ("nl", including "nl-NL" and
 * "nl-BE") locale enables the small lexical map below. English and unknown locales
 * normalize whitespace/Unicode only. The device's default locale is never guessed.
 *
 * The untouched [Outcome.originalQuery] is for display/editing. Only the accepted
 * [Outcome.Accepted.normalizedQuery] should be passed to a later text encoder.
 * The limit is measured in UTF-16 code units, before Unicode normalization; neither
 * oversized nor blank input is silently truncated or sent for encoding.
 *
 * Normalization preserves punctuation, case, names, dates, numbers, negation, and all
 * relation words. In particular it never removes stop words or filters content classes.
 * Capitalized words and hyphenated/apostrophized/underscored compounds are left intact
 * by the exact-match dictionary (conservatively preserving potential names).
 */
object SceneQueryNormalizer {
    const val MAX_QUERY_LENGTH = 512

    enum class RejectionReason { EMPTY, TOO_LONG }

    sealed class Outcome(open val originalQuery: String) {
        data class Accepted(
            override val originalQuery: String,
            val normalizedQuery: String
        ) : Outcome(originalQuery)

        data class Rejected(
            override val originalQuery: String,
            val reason: RejectionReason
        ) : Outcome(originalQuery)
    }

    /**
     * Deliberately tiny, exact lowercase Dutch colloquial/verb equivalents.
     * "zoenen → kussen" is the NLS-0 counterexample. Keep synonyms one-to-one,
     * without altering a subject, negation, relationship or person count.
     * No English rewrite, stemming, guesswork, multiword expansion or general
     * stop-word list. New entries require explicit regression evidence.
     */
    private val DUTCH_EQUIVALENTS = mapOf(
        "zoen" to "kus",
        "zoenen" to "kussen",
        "zoent" to "kust",
        "gezoend" to "gekust",
        "knuffelen" to "omhelzen"
    )

    private val WHITESPACE = Regex("""[\s\p{Z}]+""")

    // Treat compounds as a whole token: never rewrite only the "zoenen" segment
    // of a hyphenated name or username. Unicode letters, marks and digits remain.
    private val WORD = Regex("""[\p{L}\p{M}\p{N}]+(?:[_'’-][\p{L}\p{M}\p{N}]+)*""")

    fun normalize(originalQuery: String, locale: Locale = Locale.ROOT): Outcome {
        if (originalQuery.length > MAX_QUERY_LENGTH) {
            return Outcome.Rejected(originalQuery, RejectionReason.TOO_LONG)
        }

        val canonical = Normalizer.normalize(originalQuery, Normalizer.Form.NFC)
            .replace(WHITESPACE, " ")
            .trim()
        if (canonical.isEmpty()) {
            return Outcome.Rejected(originalQuery, RejectionReason.EMPTY)
        }

        val normalized = if (locale.language.equals("nl", ignoreCase = true)) {
            WORD.replace(canonical) { match ->
                // Exact lowercase lookup is intentional: potential capitalized names
                // ("Zoenen") and arbitrary Unicode tokens are never altered.
                DUTCH_EQUIVALENTS[match.value] ?: match.value
            }
        } else {
            canonical
        }

        // Future map changes must not introduce an unbounded encoder input.
        if (normalized.length > MAX_QUERY_LENGTH) {
            return Outcome.Rejected(originalQuery, RejectionReason.TOO_LONG)
        }
        return Outcome.Accepted(originalQuery, normalized)
    }
}
