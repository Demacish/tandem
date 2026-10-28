package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * 0.3.0 komplettering: "Ångra" after a meal was removed on its own page (Planera måltid → "Ta
 * bort från matsedeln"). The page closes at once, so it hands the removed meal to the week agenda
 * behind it ([WeekViewModel]), which shows the same snackbar with "Ångra" as for a meal removed
 * in the agenda: the meal is planned again and its rows still to buy go back on the list, linked
 * to the new meal (`ShoppingRepository.addRecipe` with `mealPlanId`).
 *
 * One removal at a time (the latest wins), taken once; in memory only, like the agenda's own undo.
 */
@Singleton
class RemovedMeals @Inject constructor() {
    /** A removed [meal] and its [rows] that were still to buy (Tandoor deleted them with it). */
    data class Removal(val meal: MealPlanEntry, val rows: List<ShoppingEntry>)

    private val pending = MutableStateFlow<Removal?>(null)

    /** The removal waiting for the agenda, null when there is none. */
    val removals: StateFlow<Removal?> = pending.asStateFlow()

    fun offer(removal: Removal) {
        pending.value = removal
    }

    /** The waiting removal, once: whoever takes it shows the snackbar. */
    fun take(): Removal? = pending.getAndUpdate { null }
}
