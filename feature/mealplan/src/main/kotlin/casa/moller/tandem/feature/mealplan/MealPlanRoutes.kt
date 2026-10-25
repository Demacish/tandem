package casa.moller.tandem.feature.mealplan

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import casa.moller.tandem.feature.mealplan.ui.PlanMealScreen
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
import casa.moller.tandem.feature.mealplan.ui.WeekShoppingScreen
import kotlinx.coroutines.launch

// Phase 2 (matsedel lane): the routes feature:shell shows for the Veckan tab (docs/BYGGE.md "Fas 2
// – kontrakt", "Veckan"). Each route gets its ViewModel from Hilt, shows a stateless
// XScreen(state, onAction) and turns navigation actions into the shell's callbacks.

/**
 * The week agenda (Veckan tab root, or a [casa.moller.tandem.feature.mealplan.navigation.WeekAgendaKey]).
 *
 * @param weekStart the week's Monday (`yyyy-MM-dd`), null = this week.
 * @param onOpenRecipe a planned recipe.
 * @param onPlanMeal "+" on a day/meal or "Ändra" on a meal: the plan-meal page.
 * @param onWeekShopping "Handla för veckan" for the shown week.
 * @param onStartCooking "Laga" on today's meal: cook mode with the meal's servings (as on Idag).
 */
@Composable
fun WeekRoute(
    onOpenRecipe: (Long) -> Unit,
    onPlanMeal: (PlanMealKey) -> Unit,
    onWeekShopping: (WeekShoppingKey) -> Unit,
    onStartCooking: (recipeId: Long, servings: Double?) -> Unit,
    modifier: Modifier = Modifier,
    weekStart: String? = null,
) {
    val viewModel = hiltViewModel<WeekViewModel, WeekViewModel.Factory>(
        key = "week-$weekStart",
        creationCallback = { factory -> factory.create(weekStart) },
    )
    WeekRoute(
        viewModel = viewModel,
        onOpenRecipe = onOpenRecipe,
        onPlanMeal = onPlanMeal,
        onWeekShopping = onWeekShopping,
        onStartCooking = onStartCooking,
        modifier = modifier,
    )
}

/** [WeekRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun WeekRoute(
    viewModel: WeekViewModel,
    onOpenRecipe: (Long) -> Unit,
    onPlanMeal: (PlanMealKey) -> Unit,
    onWeekShopping: (WeekShoppingKey) -> Unit,
    onStartCooking: (recipeId: Long, servings: Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { MealPlanFormat(locale) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(viewModel, snackbarHostState, resources, format) {
        viewModel.effects.collect { effect ->
            launch { snackbarHostState.show(effect, resources, format) { viewModel.onAction(WeekAction.Undo) } }
        }
    }
    WeekScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is WeekAction.PlanMeal -> onPlanMeal(PlanMealKey(date = action.date.toString(), mealTypeId = action.mealTypeId))
                is WeekAction.EditMeal -> onPlanMeal(PlanMealKey(date = action.meal.fromDate.toString(), mealPlanId = action.meal.id))
                is WeekAction.OpenRecipe -> onOpenRecipe(action.recipeId)
                is WeekAction.Cook -> action.meal.recipe?.let { onStartCooking(it.id, action.meal.servings) }
                WeekAction.WeekShopping -> onWeekShopping(WeekShoppingKey(currentState.weekStart.toString()))
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** The plan-meal page (a new meal, or the meal in [PlanMealKey.mealPlanId]). [onDone] closes it after saving or cancelling. */
@Composable
fun PlanMealRoute(key: PlanMealKey, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<PlanMealViewModel, PlanMealViewModel.Factory>(
        key = "plan-meal-${key.date}-${key.mealTypeId}-${key.mealPlanId}-${key.recipeId}",
        creationCallback = { factory -> factory.create(key) },
    )
    PlanMealRoute(viewModel = viewModel, onDone = onDone, modifier = modifier)
}

/** [PlanMealRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun PlanMealRoute(viewModel: PlanMealViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PlanMealEffect.Done -> currentOnDone()
                is PlanMealEffect.Failed -> launch { snackbarHostState.showSnackbar(resources.errorText(effect.error)) }
            }
        }
    }
    PlanMealScreen(
        state = state,
        onAction = { action -> if (action == PlanMealAction.Close) onDone() else viewModel.onAction(action) },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** "Handla för veckan"; [onOpenShoppingList] shows the list after adding, [onDone] goes back. */
@Composable
fun WeekShoppingRoute(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<WeekShoppingViewModel, WeekShoppingViewModel.Factory>(
        key = "week-shopping-${key.weekStart}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnDone by rememberUpdatedState(onDone)
    val currentOnOpenList by rememberUpdatedState(onOpenShoppingList)
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WeekShoppingEffect.Added -> {
                    currentOnDone()
                    currentOnOpenList()
                }

                is WeekShoppingEffect.Failed -> launch { snackbarHostState.showSnackbar(resources.errorText(effect.error)) }
            }
        }
    }
    WeekShoppingScreen(
        state = state,
        onAction = { action -> if (action == WeekShoppingAction.Close) onDone() else viewModel.onAction(action) },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** Shows [effect] as a snackbar; "Ångra" calls [onUndo]. A new message replaces the one shown. */
private suspend fun SnackbarHostState.show(effect: WeekEffect, resources: Resources, format: MealPlanFormat, onUndo: () -> Unit) {
    val (text, undoable) = when (effect) {
        is WeekEffect.Show -> resources.messageText(effect.message, format) to effect.undoable
        is WeekEffect.Failed -> resources.errorText(effect.error) to false
    }
    currentSnackbarData?.dismiss()
    val result = showSnackbar(
        message = text,
        actionLabel = if (undoable) resources.getString(R.string.mealplan_undo) else null,
        duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

internal fun Resources.messageText(message: WeekMessage, format: MealPlanFormat): String = when (message) {
    is WeekMessage.Removed -> getString(R.string.mealplan_msg_removed, message.title.ifBlank { getString(R.string.mealplan_untitled) })
    is WeekMessage.Moved -> getString(
        R.string.mealplan_msg_moved,
        message.title.ifBlank { getString(R.string.mealplan_untitled) },
        format.fullDay(message.date),
    )
}

internal fun Resources.errorText(error: TandemError): String = when {
    error.dataProblem() == DataProblem.READ_ONLY -> getString(R.string.mealplan_error_read_only)
    error.isConnectivityProblem -> getString(R.string.mealplan_error_refresh)
    else -> getString(R.string.mealplan_error_save)
}
