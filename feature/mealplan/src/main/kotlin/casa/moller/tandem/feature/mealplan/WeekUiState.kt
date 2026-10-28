package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * What the Veckan tab shows (plan 6.2 "Veckan"; Stitch veckan_matsedel*).
 *
 * @property weekStart the Monday of the shown week.
 * @property today the device's calendar day ("Idag", "Laga" for today's meals).
 * @property days the 7 days, each with its meals by meal type order.
 * @property mealTypes the space's meal types (rows of the tablet grid, choices when moving).
 * @property readOnly a guest (#42): no plan, move, change or delete controls at all.
 * @property householdMissing (0.3.0 review, plan 8.6) the account is in no household: the plan is
 *   not shared, and a warning says how to fix it on the web.
 * @property sharedWith (0.3.0 komplettering, plan 8.6) the household's other members, shown as
 *   "Delad med: …" above the week; empty hides the line.
 * @property selectedDay the day open beside the week list on wider windows (two panes).
 * @property move the "Flytta" sheet, while open.
 * @property conflict the first change that collided with someone else's ("Ändrad på två ställen").
 * @property deleting "Ta bort måltiden?" for a meal with rows on the shopping list (fix-ui).
 */
data class WeekUiState(
    val loading: Boolean = true,
    val weekStart: LocalDate = LocalDate(1970, 1, 5),
    val today: LocalDate = LocalDate(1970, 1, 5),
    val days: List<MealPlanDay> = emptyList(),
    val mealTypes: List<MealType> = emptyList(),
    val readOnly: Boolean = false,
    val householdMissing: Boolean = false,
    val sharedWith: List<String> = emptyList(),
    val refreshing: Boolean = false,
    val selectedDay: LocalDate? = null,
    val move: MoveMealState? = null,
    val conflict: ConflictState? = null,
    val deleting: DeleteMealState? = null,
) {
    val weekEnd: LocalDate
        get() = weekStart.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY)

    val isThisWeek: Boolean
        get() = today >= weekStart && today <= weekEnd

    /** Days with at least one meal ("5 av 7 dagar planerade"). */
    val plannedDays: Int
        get() = days.count { it.meals.isNotEmpty() }

    val isEmpty: Boolean
        get() = !loading && days.all { it.meals.isEmpty() }

    /** The day shown in the detail pane: the chosen one, else today in this week, else Monday. */
    val shownDay: LocalDate
        get() = selectedDay?.takeIf { it >= weekStart && it <= weekEnd } ?: today.takeIf { isThisWeek } ?: weekStart
}

/**
 * The "Flytta" sheet (plan 6.2: "Lång tryckning → flytta"; drag and drop comes in Phase 3).
 *
 * @property date the chosen first day; the meal keeps its length.
 * @property mealTypeId the chosen meal type.
 */
data class MoveMealState(val meal: MealPlanEntry, val date: LocalDate, val mealTypeId: Long) {
    val changed: Boolean
        get() = date != meal.fromDate || mealTypeId != meal.mealType.id
}

/**
 * fix-ui: removing [meal] would also remove its [rows] on the shopping list (Tandoor deletes them
 * with the meal), so the user is asked first; 0 = the server says it is on the list, count unknown.
 */
data class DeleteMealState(val meal: MealPlanEntry, val rows: Int)

/** A conflict and the meal it belongs to (for its name in the dialog). */
data class ConflictState(val conflict: MealPlanConflict, val mealTitle: String)

/** What the user can do on the Veckan tab. Navigation is the route's (see [WeekRoute]). */
sealed interface WeekAction {
    data object PreviousWeek : WeekAction

    data object NextWeek : WeekAction

    data object ThisWeek : WeekAction

    data object Refresh : WeekAction

    data class SelectDay(val date: LocalDate) : WeekAction

    /** "+" on a day (and a meal type in the grid): the plan-meal sheet. */
    data class PlanMeal(val date: LocalDate, val mealTypeId: Long? = null) : WeekAction

    data class EditMeal(val meal: MealPlanEntry) : WeekAction

    data class OpenRecipe(val recipeId: Long) : WeekAction

    /** "Laga" on today's meal: cook mode with the planned servings. */
    data class Cook(val meal: MealPlanEntry) : WeekAction

    /** "Ta bort": asks first when the meal has rows on the shopping list (fix-ui). */
    data class Delete(val meal: MealPlanEntry) : WeekAction

    /** The answer to "Ta bort måltiden?" ([WeekUiState.deleting]). */
    data class ConfirmDelete(val confirmed: Boolean) : WeekAction

    data object Undo : WeekAction

    data class StartMove(val meal: MealPlanEntry) : WeekAction

    data class MoveDate(val date: LocalDate) : WeekAction

    data class MoveMealType(val mealTypeId: Long) : WeekAction

    data object ConfirmMove : WeekAction

    data object CancelMove : WeekAction

    /** "Behåll min ändring" (true) or "Ta deras ändring" (false) for [WeekUiState.conflict]. */
    data class ResolveConflict(val keepMine: Boolean) : WeekAction

    /** "Handla för veckan" for the shown week. */
    data object WeekShopping : WeekAction
}

/** One-off messages of the Veckan tab (snackbars). */
sealed interface WeekEffect {
    /** A change was stored; [undoable] shows "Ångra". */
    data class Show(val message: WeekMessage, val undoable: Boolean = true) : WeekEffect

    data class Failed(val error: TandemError) : WeekEffect
}

sealed interface WeekMessage {
    data class Removed(val title: String) : WeekMessage

    data class Moved(val title: String, val date: LocalDate) : WeekMessage
}
