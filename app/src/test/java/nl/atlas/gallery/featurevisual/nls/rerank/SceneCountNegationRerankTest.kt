package nl.atlas.gallery.featurevisual.nls.rerank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic relational cruxes: one count/negated object differs, not the scene. */
class SceneCountNegationRerankTest {
    private val core = SceneCountNegationRerank
    private fun hit(
        id: String, cosine: Float, faces: Int? = null, persons: Int? = null,
        negative: Float? = null
    ) = SceneCountNegationRerank.Candidate(id, cosine, faces, persons, negative)

    @Test fun englishAndDutchPersonCountsAreExplicit() {
        val exact = SceneCountNegationRerank.CountConstraint.Exact(2)
        for (query in listOf(
            "Two people on a beach.",
            "Twee mensen op het strand.",
            "A man and a woman inside a church.",
            "Een man en een vrouw in een kerk.",
            "Two people, one standing behind the other.",
        )) assertEquals(query, exact, core.parse(query).count)
        assertEquals(SceneCountNegationRerank.CountConstraint.Exact(1),
            core.parse("A single person alone on a beach").count)
        assertEquals(SceneCountNegationRerank.CountConstraint.Exact(1),
            core.parse("Eén persoon alleen op het strand").count)
        assertEquals(SceneCountNegationRerank.CountConstraint.AtLeast(3),
            core.parse("At least three people at a party").count)
        assertEquals(SceneCountNegationRerank.CountConstraint.AtLeast(3),
            core.parse("Minstens drie mensen op een feest").count)
        assertEquals(SceneCountNegationRerank.CountConstraint.Crowd,
            core.parse("A crowd at a concert").count)
        assertEquals(SceneCountNegationRerank.CountConstraint.Crowd,
            core.parse("Een menigte op een concert").count)
    }

    @Test fun negatedClauseAndZeroPeopleInBothLanguages() {
        val zero = SceneCountNegationRerank.CountConstraint.Exact(0)
        for (query in listOf(
            "A church with no people in it", "An empty beach without people",
            "Een kerk zonder mensen", "Een strand met geen mensen",
        )) assertEquals(query, zero, core.parse(query).count)
        assertEquals("people", core.parse("A church without people in it").negatedClause)
        assertEquals("mensen", core.parse("Een kerk zonder mensen op de foto").negatedClause)
        assertEquals("a dog", core.parse("A beach without a dog in sight").negatedClause)
        assertEquals("rode jas", core.parse("Een vrouw zonder rode jas op het strand").negatedClause)
        assertEquals(null, core.parse("Two dogs on a beach").count)
        assertEquals(null, core.parse("A sunset without a boat").count)
        assertEquals(null, core.parse("Not a crowded beach").negatedClause)
    }

    @Test fun oneObjectCruxTwoVersusOneWithExistingDetectorObservations() {
        val ranked = core.rerank(core.parse("Two people on the beach"), listOf(
            hit("one-person-hard-negative", 0.91f, faces = 1, persons = 1),
            hit("two-people-positive", 0.90f, faces = 1, persons = 2),
            hit("same-beach-other", 0.82f),
        ))
        assertEquals("two-people-positive", ranked.first().id)
        assertEquals(3, ranked.size)
        assertEquals(0.90f, ranked.first().semantic, 0f)
        assertTrue(ranked.first().score > ranked.first().semantic)
    }

    @Test fun oneObjectCruxNoPeopleVersusSomePeople() {
        val ranked = core.rerank(core.parse("Empty beach with no people"), listOf(
            hit("some-people-hard-negative", 0.86f, faces = 2, persons = 2),
            hit("empty-positive", 0.85f, faces = 0, persons = 0),
            hit("other", 0.77f),
        ))
        assertEquals("empty-positive", ranked.first().id)
        assertEquals(3, ranked.size) // Reranking is never filtering.
    }

    @Test fun negatedClauseSimilarityIsOptionalAndContrastive() {
        val candidateList = listOf(
            hit("with-dog-hard-negative", 0.81f, negative = 0.95f),
            hit("dog-absent-positive", 0.80f, negative = 0.10f),
            hit("other", 0.70f),
        )
        assertEquals("dog-absent-positive",
            core.rerank(core.parse("Beach without a dog"), candidateList).first().id)
        val withoutNegation = core.rerank(core.parse("Beach at noon"), candidateList)
        assertEquals("with-dog-hard-negative", withoutNegation.first().id)
        assertEquals(withoutNegation.map { it.semantic }, withoutNegation.map { it.score })
    }

    @Test fun noSignalsKeepsEveryOriginalCosineAndSemanticOrder() {
        val input = listOf(hit("b", 0.6f), hit("a", 0.6f), hit("c", 0.5f))
        val ranked = core.rerank(core.parse("Two people without umbrellas"), input)
        assertEquals(listOf("a", "b", "c"), ranked.map { it.id })
        assertEquals(ranked.map { it.semantic }, ranked.map { it.score })
        assertEquals(input.size, ranked.size)
    }

    @Test fun missingDetectionsAreUnknownNotZero() {
        val ranked = core.rerank(core.parse("No people in a church"), listOf(
            hit("unknown", 0.79f),
            hit("known-zero", 0.78f, faces = 0),
            hit("known-two", 0.72f, persons = 2),
        ))
        assertEquals(0.79f, ranked.first { it.id == "unknown" }.score, 0f)
        assertTrue(ranked.first { it.id == "known-zero" }.score > 0.78f)
        assertTrue(ranked.first { it.id == "known-two" }.score < 0.72f)
    }

    @Test fun oneAvailableNegatedSimilarityOrInvalidSignalIsNoEvidence() {
        val ranked = core.rerank(core.parse("Without a dog"), listOf(
            hit("a", 0.9f, faces = -1, negative = Float.NaN),
            hit("b", 0.8f, negative = 0.9f),
            hit("c", 0.7f),
        ))
        assertEquals(ranked.map { it.semantic }, ranked.map { it.score })
        assertEquals(listOf("a", "b", "c"), ranked.map { it.id })
    }

    @Test fun flatAndNearFlatScoresNeverInventRank() {
        for (gap in listOf(0f, 0.00000001f)) {
            val ranked = core.rerank(core.parse("Two people without a dog"), listOf(
                hit("a", 0.6f + gap, faces = 1, negative = 0.9f),
                hit("b", 0.6f, faces = 2, negative = 0.1f),
                hit("c", 0.6f, faces = 2, negative = 0.2f),
            ))
            assertEquals(ranked.map { it.semantic }, ranked.map { it.score })
            assertEquals(listOf("a", "b", "c"), ranked.map { it.id })
        }
    }

    @Test fun deterministicTiesAndTop100Boundary() {
        val tied = listOf(hit("z", 0.5f), hit("a", 0.5f), hit("m", 0.5f))
        assertEquals(listOf("a", "m", "z"),
            core.rerank(core.parse("two people"), tied.reversed()).map { it.id })
        val many = (0..100).map { i ->
            hit("item-%03d".format(i), 1f - i * 0.001f,
                persons = if (i == 100) 2 else 1)
        }
        val ranked = core.rerank(core.parse("two people"), many.reversed())
        assertEquals(101, ranked.size)
        assertEquals("item-100", ranked.last().id)
        assertEquals(many.last().semantic, ranked.last().score, 0f)
        assertEquals(many.take(100).map { it.id }.toSet(),
            ranked.take(100).map { it.id }.toSet())
        assertFalse(ranked.take(100).any { it.id == "item-100" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSemanticCosineCannotEnterRanking() {
        core.rerank(core.parse("two people"), listOf(hit("bad", Float.NaN)))
    }
}
