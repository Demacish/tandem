package casa.moller.tandem.feature.cook.kitchen

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One planned meal on the kitchen display.
 *
 * @property title the meal's own title, else its recipe's name.
 * @property recipeId the recipe to cook; null for a title-only meal ("Rester"), which has no "Laga".
 * @property servings the planned servings (cook mode opens with them).
 * @property mealType the meal type's name from Tandoor ("Middag"), shown as it is.
 */
@Immutable
data class KitchenMeal(
    val id: Long,
    val title: String,
    val recipeId: Long?,
    val imageUrl: String?,
    val servings: Double,
    val mealType: String,
)

/**
 * The kitchen display (plan §8.7 #41).
 *
 * @property loading nothing read yet.
 * @property todayMeals today's planned meals in meal type order (the meal plan in the phone, also offline).
 * @property tomorrowMeals tomorrow's.
 * @property timers the device's timers that are not finished, ringing first.
 */
@Immutable
data class KitchenUiState(
    val loading: Boolean = true,
    val todayMeals: List<KitchenMeal> = emptyList(),
    val tomorrowMeals: List<KitchenMeal> = emptyList(),
    val timers: List<CookTimer> = emptyList(),
)

/** What the kitchen display can do with a timer. */
sealed interface KitchenAction {
    data class PauseTimer(val id: Long) : KitchenAction

    data class ResumeTimer(val id: Long) : KitchenAction

    data class AddMinute(val id: Long) : KitchenAction

    data class CancelTimer(val id: Long) : KitchenAction

    data class StopRinging(val id: Long) : KitchenAction
}

/**
 * Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41): today's and tomorrow's meals from the stored
 * meal plan (core:data [HomeRepository], the same as Idag: offline and in demo mode) and the
 * device's timers ([TimerController]). Nothing ticks here: the clock and the countdowns tick in the
 * screen while it is shown, so the state only changes when the data does (the 3-hour test,
 * docs/BYGGE.md "Köksskärmen").
 */
@HiltViewModel
class KitchenViewModel @Inject constructor(home: HomeRepository, private val timerController: TimerController) : ViewModel() {

    val uiState: StateFlow<KitchenUiState> = combine(
        home.observeUpcomingMeals().distinctUntilChanged(),
        timerController.timers.map { timers -> timers.filter { it.state != TimerState.FINISHED } }.distinctUntilChanged(),
    ) { meals, timers ->
        KitchenUiState(
            loading = false,
            todayMeals = meals.todayMeals.map { it.toKitchenMeal() },
            tomorrowMeals = meals.tomorrowMeals.map { it.toKitchenMeal() },
            timers = timers.sortedWith(compareBy<CookTimer>({ it.state != TimerState.RINGING }, { it.createdAt }, { it.id })),
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), KitchenUiState())

    fun onAction(action: KitchenAction) {
        viewModelScope.launch {
            when (action) {
                is KitchenAction.PauseTimer -> timerController.pause(action.id)
                is KitchenAction.ResumeTimer -> timerController.resume(action.id)
                is KitchenAction.AddMinute -> timerController.addOneMinute(action.id)
                is KitchenAction.CancelTimer -> timerController.cancel(action.id)
                is KitchenAction.StopRinging -> timerController.stopRinging(action.id)
            }
        }
    }

    private fun MealPlanEntry.toKitchenMeal(): KitchenMeal = KitchenMeal(
        id = id,
        title = displayTitle,
        recipeId = recipe?.id,
        imageUrl = recipe?.imageUrl,
        servings = servings,
        mealType = mealType.name,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
