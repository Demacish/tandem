package casa.moller.tandem.core.database

import casa.moller.tandem.core.database.fts.FtsQuery
import casa.moller.tandem.core.database.fts.SearchText
import casa.moller.tandem.core.database.mapper.IngredientConversions
import casa.moller.tandem.core.database.mapper.decodeKeywordList
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.UnitConversion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts of the module that need no database. */
class PureLogicTest {
    @Test
    fun ftsTermsAreQuotedPrefixes() {
        assertEquals("\"svamp*\" \"pasta*\"", FtsQuery.build("svamp pasta"))
        assertEquals("\"kräm*\"", FtsQuery.build("  kräm!! "))
        assertEquals("\"OR*\" \"NEAR*\"", FtsQuery.build("\"OR\" (NEAR) -*"))
        assertNull(FtsQuery.build(" *\"()- "))
        assertEquals(FtsQuery.MAX_TERMS, FtsQuery.terms("a b c d e f g h i j").size)
        // Decomposed input (a + combining diaeresis) stays one word.
        assertEquals(listOf("Kräm"), FtsQuery.terms("Kräm"))
    }

    @Test
    fun searchTextFoldsLikeTheTokenizer() {
        assertEquals("kramig artsoppa", SearchText.normalize("Krämig Ärtsoppa"))
        assertEquals("olbrod pa sondag", SearchText.normalize("ÖLBRÖD på söndag"))
        assertEquals("kram", SearchText.normalize("Kräm"))
    }

    @Test
    fun conversionsRoundTrip() {
        val conversions = listOf(UnitConversion(250.0, "g"), UnitConversion(2.5, "dl", "dl"))
        assertEquals(conversions, IngredientConversions.decode(IngredientConversions.encode(conversions)))
        assertNull(IngredientConversions.encode(emptyList()))
        assertEquals(emptyList<UnitConversion>(), IngredientConversions.decode("not json"))
        assertEquals(emptyList<UnitConversion>(), IngredientConversions.decode(null))
        assertEquals(listOf(UnitConversion(1.0, "st")), IngredientConversions.decode("""[{"amount":1,"unit":"st"},{"unit":"x"}]"""))
    }

    @Test
    fun packedKeywordsAreDecoded() {
        val packed = "2\u001fSoppa\u001e1\u001fFisk: lax\u001ebroken\u001e\u001fnoid"
        assertEquals(listOf(Keyword(1, "Fisk: lax"), Keyword(2, "Soppa")), decodeKeywordList(packed))
        assertEquals(emptyList<Keyword>(), decodeKeywordList(null))
    }
}
