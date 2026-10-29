package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.pantry.InventoryEntry
import kotlinx.datetime.LocalDate

/**
 * "Använd upp" (#28, plan §8.8): pantry entries that expire within [InventoryEntry.EXPIRING_SOON_DAYS]
 * days lift the recipes that use them to the top of their "Kan laga nu" group.
 *
 * Order within a group: recipes that use an expiring food first, the soonest expiry first, then
 * the one using more expiring foods; everything else keeps the server's order (stable).
 */
object UseUpRanking {
    /**
     * @param recipes one group, in the server's order.
     * @param recipeFoods the food ids each recipe uses (from the stored ingredients); a recipe
     *   without stored ingredients is never lifted.
     * @param pantry the household's entries (amount > 0); the expiring ones are picked here.
     */
    fun rank(
        recipes: List<MakeNowRecipe>,
        recipeFoods: Map<Long, Set<Long>>,
        pantry: List<InventoryEntry>,
        today: LocalDate,
        days: Int = InventoryEntry.EXPIRING_SOON_DAYS,
    ): List<MakeNowRecipe> {
        val expiring = expiringByFood(pantry, today, days)
        if (expiring.isEmpty()) return recipes.map { it.copy(expiring = emptyList()) }
        val withFoods = recipes.map { recipe ->
            val used = recipeFoods[recipe.recipe.id].orEmpty().mapNotNull { expiring[it] }.sortedBy { it.expires }
            recipe.copy(expiring = used)
        }
        return withFoods.withIndex()
            .sortedWith(
                compareBy<IndexedValue<MakeNowRecipe>> { it.value.expiring.isEmpty() }
                    .thenBy(nullsLast()) { it.value.expiring.firstOrNull()?.expires }
                    .thenByDescending { it.value.expiring.size }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** The soonest-expiring entry per food, among entries with stock that expire within [days] of [today]. */
    fun expiringByFood(
        pantry: List<InventoryEntry>,
        today: LocalDate,
        days: Int = InventoryEntry.EXPIRING_SOON_DAYS,
    ): Map<Long, ExpiringFood> = pantry.asSequence()
        .filter { !it.isEmpty && it.expiresSoon(today, days) }
        .mapNotNull { entry ->
            val foodId = entry.food.id ?: return@mapNotNull null
            val expires = entry.expires ?: return@mapNotNull null
            ExpiringFood(foodId, entry.food.name, expires)
        }
        .groupBy { it.foodId }
        .mapValues { (_, list) -> list.minBy { it.expires } }
}
