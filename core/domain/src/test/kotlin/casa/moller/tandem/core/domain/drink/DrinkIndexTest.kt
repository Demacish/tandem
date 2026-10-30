package casa.moller.tandem.core.domain.drink

import casa.moller.tandem.core.model.drink.Pairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drinks and "Passar med" from the cached keywords (#43, B20, plan §8.8). */
class DrinkIndexTest {
    private val keywords = listOf(
        DrinkKeywordNode(1, "Dryck"),
        DrinkKeywordNode(2, "Vin", parentId = 1),
        DrinkKeywordNode(3, "Öl", parentId = 1),
        DrinkKeywordNode(4, "Passar med"),
        DrinkKeywordNode(5, "Rioja Crianza", parentId = 4),
        DrinkKeywordNode(6, "Kraftigt rött", parentId = 4),
        DrinkKeywordNode(7, "Middag"),
        // Made on the web without the tree: still a type.
        DrinkKeywordNode(8, "Cider"),
    )

    // 100 = Rioja (a drink), 101 = a lager without pairings, 1 = Köttbullar, 2 = Lasagne, 3 = Sallad.
    private val links = mapOf(
        100L to setOf(1L, 2L, 5L),
        101L to setOf(3L),
        102L to setOf(8L, 1L),
        1L to setOf(5L, 7L),
        2L to setOf(5L, 6L),
        3L to setOf(7L),
    )

    private val index = DrinkIndex.build(keywords, links)

    /** Fas S review (fix-data): one rule for "is a drink" (Kan laga nu uses it too), also for an English root. */
    @Test
    fun aDrinkKeywordIsARootOrAChildOfOneWhateverItsName() {
        assertTrue(index.isDrinkKeyword(1, "Dryck"))
        assertTrue(index.isDrinkKeyword(2, "Vin"))
        assertFalse(index.isDrinkKeyword(5, "Rioja Crianza"))
        assertFalse(index.isDrinkKeyword(7, "Middag"))
        // Not in the index yet: its own parent or name decides.
        assertTrue(index.isDrinkKeyword(99, "Rosé", parentId = 1))
        assertTrue(index.isDrinkKeyword(98, "Beverages"))
        assertFalse(index.isDrinkKeyword(97, "Pasta"))

        val english = DrinkIndex.build(
            listOf(DrinkKeywordNode(10, "Beverages"), DrinkKeywordNode(11, "Vin", parentId = 10), DrinkKeywordNode(12, "Middag")),
            emptyMap(),
        )
        // A cocktail tagged only "Beverages > Vin", and one only with the root.
        assertTrue(english.isDrink(500, listOf(DrinkKeywordNode(11, "Vin"))))
        assertTrue(english.isDrink(501, listOf(DrinkKeywordNode(10, "Beverages"))))
        assertFalse(english.isDrink(502, listOf(DrinkKeywordNode(12, "Middag"))))
    }

    @Test
    fun drinksAreRecipesWithDryckOrOneOfItsChildren() {
        assertEquals(setOf(100L, 101L, 102L), index.drinkIds)
        assertFalse(index.isDrink(1))
        assertEquals(listOf("Vin"), index.typesOf(100))
        assertEquals(listOf("Cider"), index.typesOf(102))
        assertEquals(listOf("Alkoholfritt", "Cider", "Drink", "Vin", "Öl"), index.typeNames.sortedWith(compareBy { it }))
    }

    @Test
    fun pairingsWorkFromBothSidesAndATypeOnlyPairingIsText() {
        assertEquals(listOf(Pairing(6, "Kraftigt rött", isTypeOnly = true), Pairing(5, "Rioja Crianza")), index.pairingsOf(2))
        assertEquals(listOf(100L), index.drinksFor(2))
        assertEquals(listOf(1L, 2L), index.dishesFor(100))
        assertEquals(emptyList<Long>(), index.drinksFor(3))
        assertEquals(Pairing(5, "Rioja Crianza"), index.ownPairingOf(100, "Rioja Crianza"))
        assertNull(index.ownPairingOf(101, "Lager"))
    }

    @Test
    fun queuedChangesOverlayTheStoredLinksInOrder() {
        val overlaid = DrinkIndex.build(
            keywords,
            links,
            changes = listOf(
                PendingKeywordChange.ById(recipeId = 3, keywordId = 5, add = true),
                PendingKeywordChange.ById(recipeId = 1, keywordId = 5, add = false),
                PendingKeywordChange.PairingByName(recipeId = 3, name = "Friskt vitt", add = true),
            ),
        )
        assertEquals(listOf(2L, 3L), overlaid.dishesFor(100))
        val pending = overlaid.pairingsOf(3).first { it.name == "Friskt vitt" }
        assertTrue(overlaid.isPending(pending.keywordId))
        assertTrue(pending.isTypeOnly)
        assertEquals(DrinkIndex.pendingKeywordId("friskt VITT"), pending.keywordId)

        val removedAgain = DrinkIndex.build(
            keywords,
            links,
            changes = listOf(
                PendingKeywordChange.PairingByName(recipeId = 3, name = "Friskt vitt", add = true),
                PendingKeywordChange.PairingByName(recipeId = 3, name = "friskt vitt", add = false),
            ),
        )
        assertEquals(emptyList<Pairing>(), removedAgain.pairingsOf(3))
    }

    @Test
    fun aDrinkCreatedOfflineIsADrinkWithItsTypeAndPairing() {
        val overlaid = DrinkIndex.build(
            keywords = emptyList(),
            links = mapOf(1L to emptySet()),
            changes = listOf(PendingKeywordChange.PairingByName(recipeId = 1, name = "Husets rosé", add = true)),
            pendingDrinks = listOf(
                PendingDrink(
                    id = -1,
                    name = "Husets rosé",
                    keywords = listOf(
                        DraftKeyword("Dryck", DraftKeywordRole.DRINK),
                        DraftKeyword("Vin", DraftKeywordRole.TYPE),
                        DraftKeyword("Husets rosé", DraftKeywordRole.PAIRING),
                    ),
                ),
            ),
        )
        assertTrue(overlaid.isDrink(-1))
        assertEquals(listOf("Vin"), overlaid.typesOf(-1))
        assertEquals(listOf(-1L), overlaid.drinksFor(1))
        assertEquals(listOf(1L), overlaid.dishesFor(-1))
        assertFalse(overlaid.pairingsOf(1).single().isTypeOnly)
    }

    @Test
    fun aNameOfAnotherKeywordIsTakenButAPairingNameIsNot() {
        assertTrue(index.nameTakenOutsidePairings("middag"))
        assertFalse(index.nameTakenOutsidePairings("Rioja crianza"))
        assertFalse(index.nameTakenOutsidePairings("Champagne"))
        assertEquals(5L, index.pairingNamed("rioja CRIANZA")?.keywordId)
    }

    @Test
    fun anEnglishRootIsRecognisedButDrinkIsAType() {
        val english = DrinkIndex.build(
            keywords = listOf(
                DrinkKeywordNode(1, "Beverages"),
                DrinkKeywordNode(2, "Drink", parentId = 1),
                DrinkKeywordNode(3, "Pairs with"),
            ),
            links = mapOf(10L to setOf(2L)),
        )
        assertTrue(english.isDrink(10))
        assertEquals(listOf("Drink"), english.typesOf(10))
        assertEquals(3L, english.pairsWithRootId)
    }
}
