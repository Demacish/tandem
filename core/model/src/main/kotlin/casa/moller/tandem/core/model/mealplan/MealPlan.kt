package casa.moller.tandem.core.model.mealplan

import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * A meal type of the space (Tandoor `MealType`, shared by the whole space in 2.6), e.g.
 * "Middag". Names come from the server and are shown as they are.
 *
 * @property id Tandoor meal type id.
 * @property name the name.
 * @property order sort position (ascending).
 * @property time the usual time of the meal, if set.
 * @property color hex colour such as `#FF9800`, if set.
 */
data class MealType(val id: Long, val name: String, val order: Int = 0, val time: LocalTime? = null, val color: String? = null)

/**
 * One planned meal (Tandoor `MealPlan`). Phase 1 only reads them (Today: tonight and tomorrow).
 *
 * Dates: Tandoor stores `from_date`/`to_date` as date-times in the SERVER's time zone. The data
 * layer converts them to the calendar day in that zone (plan 3.4/5.7), so [fromDate] is the day
 * the household planned the meal for, whatever zone the phone is in.
 *
 * @property id Tandoor meal plan id.
 * @property title the entry's own title; empty when it only points to a recipe.
 * @property recipe the planned recipe, null for a title-only entry.
 * @property servings planned servings (may be fractional).
 * @property note free text note (Markdown), empty when none.
 * @property fromDate first day of the entry.
 * @property toDate last day of the entry (same as [fromDate] for a single day).
 * @property mealType the meal type.
 * @property onShoppingList Tandoor's read-only `shopping`: there are shopping list entries for it.
 */
data class MealPlanEntry(
    val id: Long,
    val title: String = "",
    val recipe: RecipeOverview? = null,
    val servings: Double = 1.0,
    val note: String = "",
    val fromDate: LocalDate,
    val toDate: LocalDate = fromDate,
    val mealType: MealType,
    val onShoppingList: Boolean = false,
) {
    /** What to call the meal: its own title, else the recipe's name. */
    val displayTitle: String
        get() = title.ifBlank { recipe?.name.orEmpty() }

    /** True when the entry covers [date]. */
    fun isOn(date: LocalDate): Boolean = date in fromDate..toDate
}

/**
 * The meals the Today tab shows (plan 6.2 "Idag": tonight and tomorrow).
 *
 * @property today the current day in the server's time zone.
 * @property todayMeals meals planned for [today], by meal type order.
 * @property tomorrowMeals meals planned for the day after [today], by meal type order.
 */
data class UpcomingMeals(
    val today: LocalDate,
    val todayMeals: List<MealPlanEntry> = emptyList(),
    val tomorrowMeals: List<MealPlanEntry> = emptyList(),
) {
    /** Nothing planned today or tomorrow ("Inget planerat idag. Planera veckan →"). */
    val isEmpty: Boolean
        get() = todayMeals.isEmpty() && tomorrowMeals.isEmpty()
}
