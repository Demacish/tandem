package casa.moller.tandem.core.domain.mealplan

import casa.moller.tandem.core.model.mealplan.WeekShoppingLine
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.mealplan.WeekShoppingPart
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection

/**
 * "Handla för veckan" (feature #16, plan 6.2 steps 1-4, 8.3; Stitch handla_f_r_veckan): the chosen
 * meals' ingredients merged per food and unit, amounts added. Pure, so the rules are unit tested.
 *
 * - Two ingredients are the same line when their food names and units are equal, ignoring case and
 *   surrounding space (Tandoor's food names are unique per space; an offline food has no id yet).
 *   Different units stay apart ("2 dl grädde" and "100 g grädde"): Tandoor does not convert them.
 * - A meal already on the list never contributes (plan 6.2 step 2): it is not selectable, so
 *   nothing is added twice whether the server added its rows (`mealplan_autoadd_shopping` on the
 *   web, `addshopping`) or the app did.
 * - A line is "har hemma" when any of its foods is: it starts unticked (step 4).
 * - The selection keeps the link to each meal: the entries go on the list per meal
 *   (`shopping-list-recipe` with `mealplan`), so removing a meal removes exactly its own rows.
 */
object WeekShoppingMerge {
    /** The line key of [foodName] in [unitName]. */
    fun key(foodName: String, unitName: String?): String =
        foodName.trim().lowercase() + KEY_SEPARATOR + unitName.orEmpty().trim().lowercase()

    /** Meals whose ingredients can still go on the list (not already on it, with ingredients). */
    fun selectable(meal: WeekShoppingMeal): Boolean = !meal.alreadyOnList && meal.ingredients.isNotEmpty()

    /**
     * The merged lines of [meals] (only those [selectable] and in [included], by meal id), in the
     * order the foods first appear (meals in the given order, ingredients in recipe order).
     */
    fun lines(meals: List<WeekShoppingMeal>, included: Set<Long>): List<WeekShoppingLine> {
        val builders = LinkedHashMap<String, MutableList<Pair<WeekShoppingMeal, Int>>>()
        for (meal in meals) {
            if (!selectable(meal) || meal.meal.id !in included) continue
            meal.ingredients.forEachIndexed { index, ingredient ->
                if (ingredient.foodName.isBlank()) return@forEachIndexed
                builders.getOrPut(key(ingredient.foodName, ingredient.unitName)) { mutableListOf() } += meal to index
            }
        }
        return builders.map { (key, refs) ->
            val first = refs.first().let { (meal, index) -> meal.ingredients[index] }
            val ingredients = refs.map { (meal, index) -> meal to meal.ingredients[index] }
            WeekShoppingLine(
                key = key,
                foodName = first.foodName.trim(),
                unitName = first.unitName?.trim()?.takeIf { it.isNotEmpty() },
                amount = ingredients.sumOf { (_, ingredient) -> ingredient.amount.coerceAtLeast(0.0) },
                onHand = ingredients.any { (_, ingredient) -> ingredient.onHand },
                categoryId = ingredients.firstNotNullOfOrNull { (_, ingredient) -> ingredient.categoryId },
                parts = ingredients.map { (meal, ingredient) ->
                    WeekShoppingPart(meal.meal.id, meal.meal.displayTitle, ingredient.ingredientId, ingredient.amount)
                },
            )
        }
    }

    /** The lines that start ticked: everything not on hand. */
    fun defaultChosen(lines: List<WeekShoppingLine>): Set<String> = lines.filterNot { it.onHand }.mapTo(LinkedHashSet()) { it.key }

    /**
     * What to add: per included meal, the ingredients whose line is in [chosen]. Meals with nothing
     * chosen are left out, so they stay "not on the list".
     */
    fun selection(meals: List<WeekShoppingMeal>, included: Set<Long>, chosen: Set<String>): WeekShoppingSelection {
        val byMeal = LinkedHashMap<Long, Set<Long>>()
        for (meal in meals) {
            if (!selectable(meal) || meal.meal.id !in included) continue
            val ids = meal.ingredients
                .filter { it.foodName.isNotBlank() && key(it.foodName, it.unitName) in chosen }
                .mapTo(LinkedHashSet()) { it.ingredientId }
            if (ids.isNotEmpty()) byMeal[meal.meal.id] = ids
        }
        return WeekShoppingSelection(byMeal)
    }

    private const val KEY_SEPARATOR = "\u001F"
}
