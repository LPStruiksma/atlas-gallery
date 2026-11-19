package nl.atlas.gallery.featurevisual.nls.query

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NLS-1C (#218): pure JVM contract, independent of the model and Android runtime. */
class SceneQueryNormalizerTest {
    private val dutch = Locale.forLanguageTag("nl-NL")

    private fun accepted(
        input: String,
        locale: Locale = dutch
    ): SceneQueryNormalizer.Outcome.Accepted {
        val result = SceneQueryNormalizer.normalize(input, locale)
        assertTrue("Expected accepted query, got $result", result is SceneQueryNormalizer.Outcome.Accepted)
        return result as SceneQueryNormalizer.Outcome.Accepted
    }

    @Test fun dutchColloquialismsUseOnlyTheDocumentedTinyMap() {
        assertEquals(
            "twee mensen kussen en omhelzen elkaar voor de kerk",
            accepted("twee mensen zoenen en knuffelen elkaar voor de kerk").normalizedQuery
        )
        assertEquals(
            "hij kust haar, zij heeft hem gekust; een kus",
            accepted("hij zoent haar, zij heeft hem gezoend; een zoen").normalizedQuery
        )
        assertEquals("een kiekje van de brug", accepted("een kiekje van de brug").normalizedQuery)
    }

    @Test fun englishAndUnrelatedLocalesNeverReceiveDutchLexicalChanges() {
        val input = "two people zoenen without Anna, no other person"
        assertEquals(input, accepted(input, Locale.ENGLISH).normalizedQuery)
        assertEquals(input, accepted(input, Locale.forLanguageTag("fr-FR")).normalizedQuery)
        assertEquals(input, accepted(input, Locale.ROOT).normalizedQuery)
        assertEquals("kussen", accepted("zoenen", Locale.forLanguageTag("nl-BE")).normalizedQuery)
    }

    @Test fun namesDatesNumbersAndCompoundTokensArePreserved() {
        val input = "Zoenen en Jan-Luc zoenen bij O'Connor, zoenen-club, zoenen_2026 op 12-11-2026"
        val output = accepted(input)
        assertEquals(
            "Zoenen en Jan-Luc kussen bij O'Connor, zoenen-club, zoenen_2026 op 12-11-2026",
            output.normalizedQuery
        )
        assertEquals(input, output.originalQuery)
    }

    @Test fun dutchNegationAndCentralRelationshipsAreNeverDropped() {
        val input = "geen man zonder vrouw naast de deur, voor het altaar, achter 2 kinderen, tussen 3 stoelen, met een hond"
        assertEquals(input, accepted(input).normalizedQuery)
        val joined = "man en vrouw staan samen in de deuropening, maar niet naast elkaar"
        assertEquals(joined, accepted(joined).normalizedQuery)
    }

    @Test fun englishNegationAndRelationshipTermsRemainVerbatim() {
        val input = "no man without a woman between 2 doors, beside a child, behind the bride, not kissing"
        assertEquals(input, accepted(input, Locale.US).normalizedQuery)
    }

    @Test fun noContentCategoryFilteringOrImplicitStopWordRemoval() {
        val input = "naakt protest, geweld en wapens: geen mensen zoenen zonder toestemming"
        assertEquals(
            "naakt protest, geweld en wapens: geen mensen kussen zonder toestemming",
            accepted(input).normalizedQuery
        )
        assertEquals("de een het en is", accepted("de een het en is").normalizedQuery)
    }

    @Test fun whitespaceAndUnicodeAreCanonicallyNormalizedWithoutDiscardingOriginal() {
        val input = "  cafe\u0301  met\tAna\nnaast\u00A0de  kerk  "
        val result = accepted(input)
        assertEquals(input, result.originalQuery)
        assertEquals("café met Ana naast de kerk", result.normalizedQuery)
    }

    @Test fun normalizationIsIdempotent() {
        val cases = listOf(
            " zoenen  met  Anna ",
            "Geen man zonder vrouw naast de kerk",
            "Jan-Luc en Zoenen, zoenen-club, zoenen_2026",
            "cafe\u0301 met\tAna",
            "een zoen, twee zoenen; hij zoent en heeft gezoend",
            "naakt protest en geweld zonder mensen"
        )
        for (locale in listOf(dutch, Locale.ENGLISH, Locale.ROOT)) {
            for (input in cases) {
                val once = accepted(input, locale).normalizedQuery
                assertEquals("not idempotent for $input in $locale", once, accepted(once, locale).normalizedQuery)
            }
        }
    }

    @Test fun emptyAndOversizeInputsAreRejectedWithoutTruncation() {
        val blank = " \t\u00A0\n "
        assertEquals(
            SceneQueryNormalizer.Outcome.Rejected(blank, SceneQueryNormalizer.RejectionReason.EMPTY),
            SceneQueryNormalizer.normalize(blank, dutch)
        )
        val tooLong = "x".repeat(SceneQueryNormalizer.MAX_QUERY_LENGTH + 1)
        assertEquals(
            SceneQueryNormalizer.Outcome.Rejected(tooLong, SceneQueryNormalizer.RejectionReason.TOO_LONG),
            SceneQueryNormalizer.normalize(tooLong, dutch)
        )
        assertEquals(
            SceneQueryNormalizer.MAX_QUERY_LENGTH,
            accepted("x".repeat(SceneQueryNormalizer.MAX_QUERY_LENGTH)).normalizedQuery.length
        )
    }
}
