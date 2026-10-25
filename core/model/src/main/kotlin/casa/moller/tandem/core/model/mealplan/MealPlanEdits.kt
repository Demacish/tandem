package casa.moller.tandem.core.model.mealplan

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// Phase 2 (grund lane): planning and changing meals (plan 6.2 "Veckan", features #15-#16). Dates
// are days in the SERVER's time zone (FACTS "Fas 2": Tandoor files a meal under the server-local
// date of `to_date`; a date-only `from_date` gets the meal type's time). A meal created offline
// has a negative id until it is sent.

/**
 * A new meal ("+" on a day, the plan-meal sheet).
 *
 * @property recipeId the recipe, or null for a title-only meal ("Rester").
 * @property toDate the last day; null = the same day (Tandoor's default).
 * @property addToShopping "Lägg på listan": put the recipe's ingredients on the list. Tandoor does
 *   NOT do this by itself for `mealplan_autoadd_shopping` (FACTS "Fas 2"): the sheet's checkbox
 *   starts as that preference, and the app sends `addshopping` exactly when it is ticked.
 */
data class MealPlanDraft(
    val date: LocalDate,
    val mealTypeId: Long,
    val recipeId: Long? = null,
    val title: String = "",
    val servings: Double = 1.0,
    val note: String = "",
    val toDate: LocalDate? = null,
    val addToShopping: Boolean = false,
)

/**
 * A change of an existing meal; only non-null fields change (plan 3.5: only the changed fields are
 * sent, after comparing them with the server's current values). A move sets [fromDate] and
 * [toDate] together: Tandoor keeps `to_date` when only `from_date` changes (FACTS "Fas 2").
 */
data class MealPlanUpdate(
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val mealTypeId: Long? = null,
    val recipeId: Long? = null,
    val title: String? = null,
    val servings: Double? = null,
    val note: String? = null,
) {
    companion object {
        /** Moves a meal of [days] days to start on [date]. */
        fun move(date: LocalDate, days: Int = 1): MealPlanUpdate =
            MealPlanUpdate(fromDate = date, toDate = date.plus((days - 1).coerceAtLeast(0), DateTimeUnit.DAY))
    }
}

/**
 * "Ändrad av någon annan" (plan 3.5 "Konflikter: Matsedel"): a field this device changed offline
 * was also changed on the server since the device last read it. The user picks "behåll min" or
 * "ta deras"; other fields are sent as usual.
 *
 * @property field the Tandoor field name (`from_date`, `title`, ...).
 */
data class MealPlanConflict(val mealPlanId: Long, val field: String, val mine: String?, val theirs: String?)

/** A new meal type (the plan-meal sheet's "Ny måltidstyp"); Tandoor shares meal types with the whole space. */
data class MealTypeDraft(val name: String, val time: LocalTime? = null, val color: String? = null)

/** One day of the agenda (plan 6.2 "Veckan": 7 days per page). */
data class MealPlanDay(val date: LocalDate, val meals: List<MealPlanEntry> = emptyList())

/**
 * One week of the plan, Monday first (Swedish weeks; plan 6.7 decides the first day per locale
 * later).
 */
data class MealPlanWeek(val start: LocalDate, val days: List<MealPlanDay>) {
    val end: LocalDate
        get() = start.plus(DAYS - 1, DateTimeUnit.DAY)

    companion object {
        const val DAYS: Int = 7

        /** The Monday of the week [date] is in. */
        fun startOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal, DateTimeUnit.DAY)
    }
}

/**
 * "Handla för veckan" (#16): the meals offered and what would be added (plan 6.2 steps 1-4).
 *
 * @property autoAddOnServer the user's `mealplan_autoadd_shopping` (the web adds when a meal is
 *   created with it on; the API does not, FACTS "Fas 2").
 */
data class WeekShoppingPreview(val meals: List<WeekShoppingMeal>, val autoAddOnServer: Boolean = false)

/**
 * A meal in "Handla för veckan".
 *
 * @property alreadyOnList the meal already has entries (`GET /api/shopping-list-entry/?mealplan=`
 *   or `shopping == true`): shown as "redan på listan" and not selected, so nothing is added twice.
 */
data class WeekShoppingMeal(
    val meal: MealPlanEntry,
    val alreadyOnList: Boolean,
    val selected: Boolean,
    val ingredients: List<WeekShoppingIngredient> = emptyList(),
)

/** One ingredient of a meal in the preview; on-hand foods start deselected (plan 6.2 step 4). */
data class WeekShoppingIngredient(
    val ingredientId: Long,
    val foodName: String,
    val amount: Double,
    val unitName: String? = null,
    val onHand: Boolean = false,
    val selected: Boolean = !onHand,
)

/** What the user picked in "Handla för veckan": per meal, the ingredient ids to add. */
data class WeekShoppingSelection(val ingredientIdsByMeal: Map<Long, Set<Long>>)
