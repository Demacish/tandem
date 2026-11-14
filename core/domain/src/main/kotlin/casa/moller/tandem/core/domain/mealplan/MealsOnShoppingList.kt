package casa.moller.tandem.core.domain.mealplan

/**
 * 0.8.0 (enhetstestet, B7): which planned meals "Handla för veckan" shows as "redan på listan".
 * Pure, so the rule is unit tested.
 *
 * - A meal is on the list when a list recipe or an entry is linked to it (`mealplan`, the normal
 *   case: "Handla för veckan", `addshopping`, Idag's "Ikväll" since 0.8.0).
 * - Fallback: a list recipe of the SAME recipe without a meal that still has rows to buy (added
 *   from the recipe, cook mode, or Idag before 0.8.0, or a server that did not return the link)
 *   covers one meal of that recipe. Each such list recipe covers one meal only, the earliest in
 *   [Meal] order, so the same recipe planned twice can still be added for the other day. Bought
 *   rows do not count: that recipe was shopped for already.
 */
object MealsOnShoppingList {
    /** A planned meal: its id and recipe, in the order the meals are shown (by date and meal type). */
    data class Meal(val id: Long, val recipeId: Long?)

    /**
     * A row on the stored list: its list recipe, recipe and meal (null = none), and whether it is
     * checked.
     */
    data class Row(val listRecipeId: Long?, val recipeId: Long?, val mealPlanId: Long?, val checked: Boolean)

    /**
     * The ids of [meals] already on the list: those in [linkedMealIds] (a list recipe or entry
     * linked to the meal), plus, per recipe, as many of its other meals as there are list recipes
     * of that recipe without a meal and with at least one unchecked row in [rows].
     */
    fun of(meals: List<Meal>, linkedMealIds: Set<Long>, rows: List<Row>): Set<Long> {
        val unlinked = rows.asSequence()
            .filter { it.mealPlanId == null && !it.checked && it.listRecipeId != null && it.recipeId != null }
            .groupBy { requireNotNull(it.recipeId) }
            .mapValuesTo(HashMap()) { (_, recipeRows) -> recipeRows.mapTo(HashSet()) { it.listRecipeId }.size }
        return meals.filterTo(LinkedHashSet()) { meal -> meal.id in linkedMealIds || takeOne(unlinked, meal.recipeId) }
            .mapTo(LinkedHashSet()) { it.id }
    }

    /** Uses up one unlinked list recipe of [recipeId] from [left]; false when none is left. */
    private fun takeOne(left: MutableMap<Long, Int>, recipeId: Long?): Boolean {
        val count = recipeId?.let { left[it] } ?: 0
        if (count <= 0 || recipeId == null) return false
        left[recipeId] = count - 1
        return true
    }
}
