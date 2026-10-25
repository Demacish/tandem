package casa.moller.tandem.core.domain.book

import casa.moller.tandem.core.model.book.RecipeBookKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How books are recognised (plan 3.7, B17) and what a smart book's filter means on the device. */
class RecipeBookRulesTest {
    private fun kind(name: String, description: String = "", filter: String? = null, wantIds: Set<Long> = setOf(8)) =
        RecipeBookRules.kind(name, description, hasFilter = filter != null, filterSearch = filter, wantToCookKeywordIds = wantIds)

    @Test
    fun favouritesBooksAreRecognisedByNameOrKitshnTag() {
        assertEquals(RecipeBookKind.FAVORITES, kind("Favoriter (contract-admin)"))
        assertEquals(RecipeBookKind.FAVORITES, kind("Favoriter"))
        assertEquals(RecipeBookKind.FAVORITES, kind("favourites (Erik)"))
        assertEquals(RecipeBookKind.FAVORITES, kind("Favorites"))
        assertEquals(RecipeBookKind.FAVORITES, kind("Mina sparade", description = "Från appen #KTSHNFAV"))
        assertEquals(RecipeBookKind.BOOK, kind("Favoritsoppor"))
        assertEquals(RecipeBookKind.BOOK, kind("Vardag"))
    }

    @Test
    fun theWantToCookBookIsASmartBookOnTheKeyword() {
        assertEquals(RecipeBookKind.WANT_TO_COOK, kind("Vill laga", filter = """{"keywords_or":[8]}"""))
        assertEquals(RecipeBookKind.WANT_TO_COOK, kind("Kö", filter = """{"keywords_or":[8]}"""))
        assertEquals(RecipeBookKind.WANT_TO_COOK, kind("Want to cook", filter = """{"keywords_or":[3]}"""))
        assertEquals(RecipeBookKind.SMART, kind("Snabbt", filter = """{"keywords_or":[3]}"""))
        // A favourites name with a filter is a smart book, not a book the app writes entries to.
        assertEquals(RecipeBookKind.SMART, kind("Favoriter", filter = """{"keywords_or":[3]}"""))
        // Without a filter "Vill laga" is a plain book.
        assertEquals(RecipeBookKind.BOOK, kind("Vill laga"))
    }

    @Test
    fun keywordFiltersAreEvaluatedOnTheDevice() {
        val any = SmartFilter.parse("""{"keywords_or":[8, 9]}""")!!
        assertTrue(any.localOnly)
        assertTrue(any.matches(setOf(9L, 1L)))
        assertFalse(any.matches(setOf(1L)))
        val all = SmartFilter.parse("""{"keywords_and":[1,2],"query":"","foods_or":[]}""")!!
        assertTrue(all.localOnly)
        assertTrue(all.matches(setOf(1L, 2L, 3L)))
        assertFalse(all.matches(setOf(1L)))
    }

    @Test
    fun otherFiltersNeedTheServer() {
        assertFalse(SmartFilter.parse("""{"keywords_or":[8],"query":"pasta"}""")!!.localOnly)
        assertFalse(SmartFilter.parse("""{"foods_or":[4]}""")!!.localOnly)
        // Keyword objects instead of ids (a 500 in Tandoor's recipe list, FACTS "Fas 2 g").
        assertFalse(SmartFilter.parse("""{"keywords_or":[{"id":8,"name":"vill-laga"}]}""")!!.localOnly)
        assertNull(SmartFilter.parse("not json"))
        assertNull(SmartFilter.parse(null))
        assertFalse(SmartFilter.parse("{}")!!.matches(setOf(1L)))
    }

    @Test
    fun theAppNamesItsFavouritesBookAfterTheOwner() {
        assertEquals("Favoriter (Anna)", RecipeBookRules.favoritesBookName("Favoriter", " Anna "))
        assertEquals("Favourites", RecipeBookRules.favoritesBookName("Favourites", ""))
        assertTrue(RecipeBookRules.isWantToCookKeyword("Vill-Laga "))
    }
}
