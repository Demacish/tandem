package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft

/**
 * fix-ui (Phase 2 review): "Ångra" after a recipe's rows were taken off the list ("Ta bort från
 * listan" in the recipe sheet, or "Ta bort <recept> från listan" in the edit sheet). Tandoor
 * deletes a list recipe with all its rows, so the snapshot taken before the removal puts the rows
 * that were still to buy back: per list recipe one [ShoppingRepository.addRecipe] with the same
 * ingredients, servings, named lists and meal (so a planned meal is "on the list" again), or the
 * rows one by one when they have no recipe ingredient. Checked rows were already bought and stay
 * off the list.
 */
internal class RemovedRecipeRows private constructor(private val groups: List<List<ShoppingEntry>>) {
    /** How many rows were removed, checked ones included (what the message says). */
    val removed: Int = groups.sumOf { it.size }

    /** Puts the rows still to buy back; the first failure stops it. */
    suspend fun restore(shopping: ShoppingRepository): Outcome<Unit> {
        for (group in groups) {
            val open = group.filterNot { it.checked }
            if (open.isEmpty()) continue
            val label = requireNotNull(open.first().recipe)
            val recipeId = label.recipeId
            val ingredients = open.mapNotNull { it.ingredientId }.toSet()
            val result = if (recipeId != null && ingredients.size == open.size) {
                shopping.addRecipe(
                    RecipeShoppingRequest(
                        recipeId = recipeId,
                        servings = label.servings,
                        ingredientIds = ingredients,
                        mealPlanId = label.mealPlanId,
                        listIds = open.first().listIds,
                    ),
                )
            } else {
                shopping.addAll(open.map { it.toDraft() })
            }
            if (result is Outcome.Failure) return result
        }
        return Outcome.Success(Unit)
    }

    companion object {
        /** The rows of [listRecipeIds] among [entries], before they are removed. */
        fun of(entries: List<ShoppingEntry>, listRecipeIds: Collection<Long>): RemovedRecipeRows = RemovedRecipeRows(
            listRecipeIds.distinct().map { group -> entries.filter { it.recipe?.listRecipeId == group } }.filter { it.isNotEmpty() },
        )
    }
}

private fun ShoppingEntry.toDraft(): ShoppingEntryDraft = ShoppingEntryDraft(
    foodName = food.name,
    amount = amount,
    unitName = unit?.name,
    unitId = unit?.id,
    foodId = food.id,
    listIds = listIds,
)
