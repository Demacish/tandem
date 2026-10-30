package casa.moller.tandem.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/**
 * A pantry entry that expires within three days, with how many "Kan laga nu" recipes use it
 * ("Grädden går ut i morgon – 4 recept", plan §8.8 "Använd upp").
 */
data class TodayExpiring(val entry: InventoryEntry, val urgency: ExpiryUrgency, val recipeCount: Int)

/**
 * Fas S (hemma-ui lane): what Idag shows from the pantry (plan §8.8, #23, #28): "Kan laga nu" (the
 * first recipes, those that have it all first) and "Går ut snart". Both are hidden when empty.
 *
 * @property makeNow recipes of "Har allt", then "Saknar 1" (drinks left out: they have no ingredients).
 * @property hasAll how many of [makeNow] have it all.
 */
@Immutable
data class TodayPantryState(
    val today: LocalDate? = null,
    val makeNow: List<RecipeOverview> = emptyList(),
    val hasAll: Int = 0,
    val expiring: List<TodayExpiring> = emptyList(),
) {
    val isEmpty: Boolean
        get() = makeNow.isEmpty() && expiring.isEmpty()
}

/** [TodayPantryState] from "Kan laga nu" and the pantry, counted from the device's day. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayPantryViewModel @Inject internal constructor(makeNow: MakeNowRepository, pantry: PantryRepository, calendar: CalendarDays) :
    ViewModel() {
    val uiState: StateFlow<TodayPantryState> = calendar.observeToday()
        .flatMapLatest { day ->
            combine(makeNow.observe(MakeNowFilter(), day), pantry.observeExpiring(day)) { state, expiring -> build(day, state, expiring) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TodayPantryState())

    private fun build(day: LocalDate, state: MakeNowState, expiring: List<InventoryEntry>): TodayPantryState {
        val hasAll = state.recipes(MakeNowGroup.HAS_ALL).filterNot { it.recipe.isDrink() }
        val missingOne = state.recipes(MakeNowGroup.MISSING_1).filterNot { it.recipe.isDrink() }
        val all = MakeNowGroup.entries.flatMap { state.recipes(it) }.filterNot { it.recipe.isDrink() }
        val rows = expiring.take(MAX_EXPIRING).map { entry ->
            val foodId = entry.food.id
            TodayExpiring(
                entry = entry,
                urgency = ExpiryUrgency.of(entry.expires, day),
                recipeCount = if (foodId == null) 0 else all.count { recipe -> recipe.expiring.any { it.foodId == foodId } },
            )
        }
        return TodayPantryState(
            today = day,
            // Fas S review (fix-ui): only "Har allt" when there is any (the subtitle says "där allt
            // finns hemma"); "Saknar 1" only when nothing has everything (its own subtitle).
            makeNow = hasAll.ifEmpty { missingOne }.map { it.recipe }.distinctBy { it.id }.take(MAX_RECIPES),
            hasAll = hasAll.size.coerceAtMost(MAX_RECIPES),
            expiring = rows,
        )
    }

    private fun RecipeOverview.isDrink(): Boolean = keywords.any { it.name.equals(DrinkKeywords.DRINK, ignoreCase = true) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** As many as the other carousels show. */
        const val MAX_RECIPES = 12
        const val MAX_EXPIRING = 4
    }
}
