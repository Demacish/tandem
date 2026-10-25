package casa.moller.tandem.feature.mealplan.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

// Phase 2 (grund lane): the meal plan's destinations (plan 6.1/6.2 "Veckan"). The tab root is the
// shell's `WeekKey` (this week). Dates are ISO strings (`yyyy-MM-dd`, days in the server's zone) so
// the keys stay plain @Serializable data without a custom serializer; `LocalDate.parse` reads them.

/**
 * The agenda of one week, Monday first (plan 6.2: 7 days per page, a week grid on tablets).
 *
 * @property weekStart the week's Monday as `yyyy-MM-dd`; null = the current week.
 */
@Serializable
data class WeekAgendaKey(val weekStart: String? = null) : NavKey {
    init {
        require(runCatching { weekStart?.let { LocalDate.parse(it) } }.isSuccess) { "weekStart is yyyy-MM-dd" }
    }
}

/**
 * The plan-meal sheet (Stitch `planera_m_ltid_ark`): a new meal on [date] (and [mealTypeId], from
 * the "+" of a day/meal), or editing the meal [mealPlanId]. [recipeId] preselects a recipe
 * ("Planera" on a recipe).
 *
 * @property date `yyyy-MM-dd`.
 */
@Serializable
data class PlanMealKey(val date: String, val mealTypeId: Long? = null, val mealPlanId: Long? = null, val recipeId: Long? = null) : NavKey {
    init {
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "date is yyyy-MM-dd" }
    }
}

/** "Handla för veckan" (Stitch `handla_f_r_veckan`, feature #16) for the week starting [weekStart] (`yyyy-MM-dd`). */
@Serializable
data class WeekShoppingKey(val weekStart: String) :
    NavKey,
    FullScreenDestination {
    init {
        require(runCatching { LocalDate.parse(weekStart) }.isSuccess) { "weekStart is yyyy-MM-dd" }
    }
}
