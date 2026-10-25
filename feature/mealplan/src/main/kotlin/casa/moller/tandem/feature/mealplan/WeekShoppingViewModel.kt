package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * One meal of "Handla för veckan" as the user set it.
 *
 * @property selected the meal's ingredients go on the list (never for a meal already on it).
 * @property chosen the ingredient ids that go (on-hand foods start unticked, plan 6.2 step 4).
 */
data class WeekShoppingMealState(val meal: WeekShoppingMeal, val selected: Boolean, val chosen: Set<Long>, val expanded: Boolean = false) {
    val canSelect: Boolean
        get() = !meal.alreadyOnList && meal.ingredients.isNotEmpty()
}

/**
 * "Handla för veckan" (feature #16, plan 6.2 steps 1-4; Stitch handla_f_r_veckan).
 *
 * @property autoAdd the user's `mealplan_autoadd_shopping` (explained, since the web adds by itself).
 */
data class WeekShoppingUiState(
    val loading: Boolean = true,
    val weekStart: LocalDate = LocalDate(1970, 1, 5),
    val meals: List<WeekShoppingMealState> = emptyList(),
    val autoAdd: Boolean = false,
    val readOnly: Boolean = false,
    val adding: Boolean = false,
) {
    /** The meals that would be added now. */
    val toAdd: List<WeekShoppingMealState>
        get() = meals.filter { it.selected && it.canSelect && it.chosen.isNotEmpty() }
}

sealed interface WeekShoppingAction {
    data class ToggleMeal(val mealId: Long) : WeekShoppingAction

    data class ToggleIngredient(val mealId: Long, val ingredientId: Long) : WeekShoppingAction

    data class Expand(val mealId: Long) : WeekShoppingAction

    data object Add : WeekShoppingAction

    data object Close : WeekShoppingAction
}

sealed interface WeekShoppingEffect {
    /** [meals] meals are on the list now: show the list. */
    data class Added(val meals: Int) : WeekShoppingEffect

    data class Failed(val error: TandemError) : WeekShoppingEffect
}

/**
 * "Handla för veckan": the week's meals with recipes, those already on the list shown as "redan på
 * listan" and not selectable (so nothing is added twice, plan 6.2 step 2, also with Tandoor's
 * `mealplan_autoadd_shopping` on), their ingredients with on-hand foods unticked; "Lägg på listan"
 * adds the selection linked to each meal ([MealPlanRepository.addWeekToShopping]).
 */
@HiltViewModel(assistedFactory = WeekShoppingViewModel.Factory::class)
class WeekShoppingViewModel @AssistedInject internal constructor(
    @Assisted key: WeekShoppingKey,
    private val mealPlans: MealPlanRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: WeekShoppingKey): WeekShoppingViewModel
    }

    private val effectChannel = Channel<WeekShoppingEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<WeekShoppingEffect> = effectChannel.receiveAsFlow()

    private val form = MutableStateFlow(WeekShoppingUiState(weekStart = LocalDate.parse(key.weekStart)))

    val uiState: StateFlow<WeekShoppingUiState> = combine(form, permissions.permissions) { state, perms ->
        state.copy(readOnly = perms.readOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), form.value)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        when (val preview = mealPlans.weekShoppingPreview(form.value.weekStart)) {
            is Outcome.Success -> form.update { state ->
                state.copy(
                    loading = false,
                    autoAdd = preview.value.autoAddOnServer,
                    meals = preview.value.meals.map { meal ->
                        val chosen = meal.ingredients.filter { it.selected }.mapTo(LinkedHashSet()) { it.ingredientId }
                        WeekShoppingMealState(meal, selected = meal.selected && !meal.alreadyOnList && chosen.isNotEmpty(), chosen = chosen)
                    },
                )
            }

            is Outcome.Failure -> {
                form.update { it.copy(loading = false) }
                effectChannel.send(WeekShoppingEffect.Failed(preview.error))
            }
        }
    }

    fun onAction(action: WeekShoppingAction) {
        when (action) {
            is WeekShoppingAction.ToggleMeal -> meal(action.mealId) { if (it.canSelect) it.copy(selected = !it.selected) else it }

            is WeekShoppingAction.ToggleIngredient -> meal(action.mealId) { state ->
                val id = action.ingredientId
                val chosen = if (id in state.chosen) state.chosen - id else state.chosen + id
                state.copy(chosen = chosen, selected = chosen.isNotEmpty() && state.canSelect)
            }

            is WeekShoppingAction.Expand -> meal(action.mealId) { it.copy(expanded = !it.expanded) }

            WeekShoppingAction.Add -> add()

            WeekShoppingAction.Close -> Unit // The route closes the page.
        }
    }

    private fun meal(id: Long, change: (WeekShoppingMealState) -> WeekShoppingMealState) {
        form.update { state -> state.copy(meals = state.meals.map { if (it.meal.meal.id == id) change(it) else it }) }
    }

    private fun add() {
        val state = uiState.value
        val meals = state.toAdd
        if (state.readOnly || state.adding || meals.isEmpty()) return
        form.update { it.copy(adding = true) }
        viewModelScope.launch {
            val result = mealPlans.addWeekToShopping(WeekShoppingSelection(meals.associate { it.meal.meal.id to it.chosen }))
            form.update { it.copy(adding = false) }
            when (result) {
                is Outcome.Success -> effectChannel.send(WeekShoppingEffect.Added(meals.size))
                is Outcome.Failure -> effectChannel.send(WeekShoppingEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
