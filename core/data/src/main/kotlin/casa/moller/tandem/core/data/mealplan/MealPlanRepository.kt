package casa.moller.tandem.core.data.mealplan

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The meal plan (plan 3.5, 6.2 "Veckan", features #15-#16), offline first: creating, moving and
 * removing meals is stored at once and queued (`meal_plan` / `meal_type` outbox rows); a meal
 * created offline has a negative id. Implemented in core:sync by the meal plan lane (until then a
 * temporary binding in :app). Same contract as `ShoppingRepository` (main-safe, account-scoped,
 * demo, `DataProblem.READ_ONLY` for guests, flows never throw).
 *
 * **Dates** are days in the server's time zone (FACTS "Fas 2": Tandoor lists a meal under the
 * server-local date of its `to_date`; a date-only `from_date` gets the meal type's time).
 * **Conflicts** (plan 3.5): `MealPlan` has no `updated_at`, so before a PATCH the sender reads the
 * meal and compares the fields it changes with the values the change started from; a difference
 * is reported through [observeConflicts] and waits for [resolveConflict].
 */
interface MealPlanRepository {
    /** The week starting [start] (a Monday, `MealPlanWeek.startOf`), 7 days, meals by meal type order. */
    fun observeWeek(start: LocalDate): Flow<MealPlanWeek>

    /** One meal (the plan-meal sheet when editing), null when it is gone. */
    fun observeMeal(id: Long): Flow<MealPlanEntry?>

    /** The space's meal types, by order. */
    fun observeMealTypes(): Flow<List<MealType>>

    /** Plans a meal. Returns its (negative, until sent) id. Sends `addshopping` only when [MealPlanDraft.addToShopping]. */
    suspend fun create(draft: MealPlanDraft): Outcome<Long>

    /** Changes a meal (a move sets both dates, `MealPlanUpdate.move`). */
    suspend fun update(id: Long, update: MealPlanUpdate): Outcome<Unit>

    /** Removes a meal. Tandoor removes its shopping entries with it (FACTS "Fas 2"). */
    suspend fun delete(id: Long): Outcome<Unit>

    /** A new meal type for the whole space. Returns its (negative, until sent) id. */
    suspend fun createMealType(draft: MealTypeDraft): Outcome<Long>

    /** Meals whose offline change collided with someone else's (plan 3.5), until resolved. */
    fun observeConflicts(): Flow<List<MealPlanConflict>>

    /** "Behåll min" ([keepMine] = true) or "ta deras" for one conflict. */
    suspend fun resolveConflict(conflict: MealPlanConflict, keepMine: Boolean): Outcome<Unit>

    /** Re-reads the plan window now (plan 3.5: −14..+42 days around today). */
    suspend fun refresh(): Outcome<Unit>

    /**
     * "Handla för veckan" (#16, plan 6.2 steps 1-4): the week's meals with recipes, which are
     * already on the list (`?mealplan=` / `shopping`), and their ingredients with on-hand foods
     * deselected. Online it checks the server; offline it uses what is stored.
     */
    suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview>

    /** Adds the selection to the list, linked to each meal (`shopping-list-recipe` with `mealplan`), never twice. */
    suspend fun addWeekToShopping(selection: WeekShoppingSelection): Outcome<Unit>
}
