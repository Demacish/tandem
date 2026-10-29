package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** "Använd upp" (#28): a food that expires tomorrow lifts its recipes to the top (Fas S "Klart när"). */
class UseUpRankingTest {
    private val today = LocalDate(2026, 10, 29)

    private fun recipe(id: Long, name: String) = MakeNowRecipe(RecipeOverview(id = id, name = name), MakeNowGroup.HAS_ALL)

    private fun entry(id: Long, foodId: Long, name: String, expires: LocalDate?, amount: Double = 1.0) =
        InventoryEntry(id = id, locationId = 1, food = PantryFood(foodId, name), amount = amount, expires = expires)

    @Test
    fun recipesUsingSoonExpiringFoodsComeFirstSoonestFirst() {
        val recipes = listOf(recipe(1, "Pannkakor"), recipe(2, "Gratäng"), recipe(3, "Gröt"), recipe(4, "Sallad"))
        val foods = mapOf(1L to setOf(10L), 2L to setOf(20L, 30L), 3L to setOf(30L), 4L to setOf(40L))
        val pantry = listOf(
            entry(1, 20, "grädde", today.plusDays(1)),
            entry(2, 30, "mjölk", today.plusDays(3)),
            // Expires in 10 days: not "soon".
            entry(3, 40, "sallad", today.plusDays(10)),
            // "Slut": never lifts anything.
            entry(4, 10, "ägg", today, amount = 0.0),
        )

        val ranked = UseUpRanking.rank(recipes, foods, pantry, today)

        assertEquals(listOf("Gratäng", "Gröt", "Pannkakor", "Sallad"), ranked.map { it.recipe.name })
        assertEquals(listOf("grädde", "mjölk"), ranked.first().expiring.map { it.name })
        assertEquals(emptyList<Any>(), ranked.last().expiring)
    }

    @Test
    fun withoutExpiringFoodsTheServersOrderStays() {
        val recipes = listOf(recipe(2, "B"), recipe(1, "A"))
        assertEquals(listOf("B", "A"), UseUpRanking.rank(recipes, emptyMap(), emptyList(), today).map { it.recipe.name })
    }

    @Test
    fun anExpiredEntryStillCountsAsExpiring() {
        val expired = UseUpRanking.expiringByFood(listOf(entry(1, 20, "grädde", today.plusDays(-1))), today)
        assertEquals(setOf(20L), expired.keys)
    }

    private fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)
}
