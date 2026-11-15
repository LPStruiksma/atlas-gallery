package nl.atlas.gallery.featurevisual.nls.rerank

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * NLS-2A (#230). Pure JVM reranking of the existing SigLIP 2 cosine top 100.
 *
 * A caller supplies cosine-ranked photo/video-moment IDs, cached/previously observed
 * YuNet face counts and/or person-detector counts, and optionally the cosine to a
 * separately embedded negative clause. No detector, embedding, index read or I/O
 * happens here. Unknown detector results are null, never an inferred zero.
 *
 * Reranking is strictly a reorder of the semantic head: the tail is appended
 * untouched even if some head scores are numerically below tail scores.
 * There is no result filtering, thresholding or additional retrieval.
 */
object SceneCountNegationRerank {
    const val TOP_K = 100
    const val COUNT_WEIGHT = 1.0
    const val NEGATION_WEIGHT = 1.0
    /** Relative cosine differences below this do not support evidence-based reranking. */
    const val MIN_DISPERSION = 1e-6

    sealed interface CountConstraint {
        data class Exact(val value: Int) : CountConstraint {
            init { require(value >= 0) }
        }
        data class AtLeast(val value: Int) : CountConstraint {
            init { require(value > 0) }
        }
        data object Crowd : CountConstraint
    }

    /** Explicit EN/NL terms, not a general natural-language understanding model. */
    data class Semantics(
        val count: CountConstraint? = null,
        val negatedClause: String? = null,
    )

    /**
     * [faceCount]/[personCount] are observed detection counts on THIS moment.
     * Null means not observed, while zero means observed and empty. When either
     * count is observed we use max(faces, persons), as NLS-0's people_count did.
     * [negatedClauseCosine] is optional: caller may have no negative text tower.
     */
    data class Candidate(
        val id: String,
        val semantic: Float,
        val faceCount: Int? = null,
        val personCount: Int? = null,
        val negatedClauseCosine: Float? = null,
    )

    /** [semantic] is never overwritten by the evidence-based [score]. */
    data class Ranked(val id: String, val semantic: Float, val score: Float)

    private val negative = Regex(
        """\b(?:without|with no|no|zonder|geen)\s+([\p{L}\p{M}\d\s-]+?)(?=\s+(?:in|on|at|op|bij|met|while|maar|die|dat)\b|[,.!?;:]|$)"""
    )
    private val genericPeople = Regex("""^(?:(?:any|enige)\s+)?(?:people|persons|mensen|personen)$""")
    private val emptyScene = Regex(
        """\b(?:empty|deserted|leeg|lege)\s+(?:beach|church|street|room|strand|kerk|straat|kamer)\b"""
    )
    private val quantity = Regex(
        """\b(?:(at least|ten minste|minstens)\s+)?(one|two|three|four|1|2|3|4|een|één|twee|drie|vier)\s+(?:[\p{L}]+\s+){0,2}(?:people|persons|person|men|man|women|woman|mensen|personen|persoon|mannen|man|vrouwen|vrouw)\b"""
    )
    private val singleton = Regex(
        """\b(?:a single|single|an|a|een enkele|één|een)\s+(?:person|persoon|man|woman|vrouw|bride|bruid)\b"""
    )
    private val individual = Regex(
        """\b(?:a|an|een|één)\s+(?:(?:young|old|jonge|oude)\s+)?(?:man|woman|person|persoon|vrouw|bride|bruid)\b"""
    )
    private val crowd = Regex("""\b(?:crowd|crowded|menigte|mensenmassa)\b""")
    private val singleActor = Regex("""\b(?:someone|somebody|iemand)\b""")
    private val couple = Regex("""\b(?:a couple|een stel)\b""")
    private val numbers = mapOf(
        "one" to 1, "1" to 1, "een" to 1, "één" to 1,
        "two" to 2, "2" to 2, "twee" to 2,
        "three" to 3, "3" to 3, "drie" to 3,
        "four" to 4, "4" to 4, "vier" to 4,
    )

    /**
     * Extract only explicit scene-person count cues and a single bounded
     * "without/no/zonder/geen" clause; never treat unrelated numbers ("two dogs")
     * as people counts. "Not ...", gender, viewpoint and spatial relation parsing
     * are outside this contract and receive no speculative evidence.
     */
    fun parse(query: String): Semantics {
        val text = query.lowercase(Locale.ROOT).trim()
        val clause = negative.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val count = when {
            clause != null && genericPeople.matches(clause) -> CountConstraint.Exact(0)
            emptyScene.containsMatchIn(text) -> CountConstraint.Exact(0)
            else -> {
                val number = quantity.find(text)
                when {
                    number != null -> {
                        val value = numbers.getValue(number.groupValues[2])
                        if (number.groupValues[1].isNotEmpty()) CountConstraint.AtLeast(value)
                        else CountConstraint.Exact(value)
                    }
                    couple.containsMatchIn(text) -> CountConstraint.Exact(2)
                    individual.findAll(text).count() >= 2 -> CountConstraint.Exact(2)
                    singleton.containsMatchIn(text) || individual.containsMatchIn(text) ||
                        singleActor.containsMatchIn(text) -> CountConstraint.Exact(1)
                    crowd.containsMatchIn(text) -> CountConstraint.Crowd
                    else -> null
                }
            }
        }
        return Semantics(count, clause)
    }

    /**
     * NLS-0 uses a population standard deviation of the semantic top-100 cosine
     * to scale count agreement (+1 exact match, -0.5 one-person mismatch, -1
     * two-or-more mismatch). Here the optional negated text cosine is centred,
     * normalised and clipped within the SAME score dispersion budget: common
     * phrase similarity cannot overwhelm scene relevance.
     *
     * A flat semantic head, unavailable counts, or fewer than two usable
     * negated similarities preserves the semantic scores (and tie ordering).
     */
    fun rerank(
        semantics: Semantics,
        candidates: List<Candidate>,
        topK: Int = TOP_K,
    ): List<Ranked> {
        require(topK in 0..TOP_K) { "Only the semantic top $TOP_K can be reranked" }
        val ids = HashSet<String>()
        require(candidates.all { it.id.isNotBlank() && it.semantic.isFinite() && ids.add(it.id) }) {
            "Each candidate needs a unique, nonblank ID and finite cosine"
        }
        val ordered = candidates.sortedWith(
            compareByDescending<Candidate> { it.semantic }.thenBy { it.id }
        )
        fun original(): List<Ranked> = ordered.map { Ranked(it.id, it.semantic, it.semantic) }
        if (topK == 0 || candidates.size < 2 ||
            (semantics.count == null && semantics.negatedClause.isNullOrBlank())) return original()

        val head = ordered.take(topK)
        if (head.size < 2) return original()
        val mean = head.sumOf { it.semantic.toDouble() } / head.size
        val dispersion = sqrt(head.sumOf {
            val d = it.semantic.toDouble() - mean
            d * d
        } / head.size)
        if (!dispersion.isFinite() || dispersion <= MIN_DISPERSION) return original()

        val negativeValues = if (!semantics.negatedClause.isNullOrBlank()) {
            head.mapNotNull { usableNegative(it.negatedClauseCosine) }
        } else emptyList()
        val negMean = if (negativeValues.isNotEmpty()) negativeValues.average() else 0.0
        val negStd = if (negativeValues.size >= 2) sqrt(negativeValues.sumOf {
            val d = it - negMean
            d * d
        } / negativeValues.size) else 0.0
        val useNegative = negStd > MIN_DISPERSION && negStd.isFinite()

        val rankedHead = head.map { item ->
            var adjustment = 0.0
            val seen = observedPeople(item)
            if (semantics.count != null && seen != null) {
                adjustment += COUNT_WEIGHT * agreement(semantics.count, seen)
            }
            val neg = usableNegative(item.negatedClauseCosine)
            if (useNegative && neg != null) {
                val relative = ((neg - negMean) / negStd).coerceIn(-1.0, 1.0)
                adjustment -= NEGATION_WEIGHT * relative
            }
            Ranked(item.id, item.semantic, (item.semantic + dispersion * adjustment).toFloat())
        }.sortedWith(
            compareByDescending<Ranked> { it.score }
                .thenByDescending { it.semantic }
                .thenBy { it.id }
        )
        return rankedHead + ordered.drop(head.size).map {
            Ranked(it.id, it.semantic, it.semantic)
        }
    }

    private fun observedPeople(candidate: Candidate): Int? {
        val faces = candidate.faceCount?.takeIf { it >= 0 }
        val persons = candidate.personCount?.takeIf { it >= 0 }
        return if (faces == null && persons == null) null else maxOf(faces ?: 0, persons ?: 0)
    }

    private fun usableNegative(cosine: Float?): Double? =
        cosine?.takeIf { it.isFinite() && it >= -1f && it <= 1f }?.toDouble()

    private fun agreement(want: CountConstraint, seen: Int): Double = when (want) {
        is CountConstraint.Exact ->
            if (seen == want.value) 1.0 else -minOf(abs(seen - want.value), 2) / 2.0
        is CountConstraint.AtLeast ->
            if (seen >= want.value) 1.0 else -minOf(want.value - seen, 2) / 2.0
        CountConstraint.Crowd -> when {
            seen >= 5 -> 1.0
            seen <= 2 -> -0.5
            else -> 0.0
        }
    }
}
