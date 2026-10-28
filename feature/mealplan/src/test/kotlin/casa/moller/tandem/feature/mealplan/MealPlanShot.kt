package casa.moller.tandem.feature.mealplan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import casa.moller.tandem.feature.mealplan.ui.MoveMealPanel
import casa.moller.tandem.feature.mealplan.ui.PlanMealScreen
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
import casa.moller.tandem.feature.mealplan.ui.WeekShoppingScreen

/**
 * Every screen and state of the Veckan tab with sample data, for the screenshot and accessibility
 * tests (one list, so both cover the same states).
 */
internal enum class MealPlanShot(val content: @Composable () -> Unit) {
    WEEK({ WeekShot(MealPlanSamples.state()) }),
    WEEK_EMPTY({ WeekShot(MealPlanSamples.state(meals = emptyList())) }),
    WEEK_GUEST({ WeekShot(MealPlanSamples.state(readOnly = true)) }),
    WEEK_NO_HOUSEHOLD({ WeekShot(MealPlanSamples.state(householdMissing = true)) }),
    WEEK_NEXT({ WeekShot(MealPlanSamples.state(start = MealPlanSamples.day(7), meals = listOf(MealPlanSamples.nextWednesday))) }),
    WEEK_DAY_SELECTED({ WeekShot(MealPlanSamples.state(selectedDay = MealPlanSamples.day(6))) }),
    WEEK_CONFLICT({ WeekShot(MealPlanSamples.state(conflict = MealPlanSamples.conflictState)) }),
    MOVE({ Sheet { MoveMealPanel(MealPlanSamples.moveState, MealPlanSamples.mealTypes, MealPlanSamples.today, format(), onAction = {}) } }),
    PLAN_SEARCH({ PlanMealScreen(state = MealPlanSamples.planState(), onAction = {}) }),
    PLAN_CHOSEN({ PlanMealScreen(state = MealPlanSamples.planState(recipe = MealPlanSamples.salmon), onAction = {}) }),
    PLAN_TITLE_ERROR({ PlanMealScreen(state = MealPlanSamples.planState(kind = MealKind.TITLE, showErrors = true), onAction = {}) }),
    PLAN_EDIT({ PlanMealScreen(state = MealPlanSamples.planState(recipe = MealPlanSamples.pasta, editing = true), onAction = {}) }),
    WEEK_SHOPPING({ WeekShoppingScreen(state = MealPlanSamples.weekShopping, onAction = {}) }),

    // fix-ui: "Vill laga" and "Favoriter" while the search is empty, and removing a meal asks first.
    PLAN_COLLECTIONS({ PlanMealScreen(state = MealPlanSamples.planCollectionsState, onAction = {}) }),
    PLAN_DELETE({ PlanMealScreen(state = MealPlanSamples.planDeleteState, onAction = {}) }),
    WEEK_DELETE({ WeekShot(MealPlanSamples.state().copy(deleting = DeleteMealState(MealPlanSamples.todayDinner, rows = 6))) }),

    // Phase 2 (koppling lane): "Handla för veckan" for a guest, with everything on the list, and without meals.
    WEEK_SHOPPING_GUEST({ WeekShoppingShot(MealPlanSamples.weekShoppingState(MealPlanSamples.weekShoppingMeals, readOnly = true)) }),
    WEEK_SHOPPING_ALL_ON_LIST({
        WeekShoppingShot(MealPlanSamples.weekShoppingState(MealPlanSamples.weekShoppingMeals.filter { it.alreadyOnList }))
    }),
    WEEK_SHOPPING_EMPTY({ WeekShoppingScreen(state = MealPlanSamples.weekShoppingState(emptyList(), autoAdd = false), onAction = {}) }),
}

@Composable
private fun WeekShot(state: WeekUiState) = WeekScreen(state = state, onAction = {})

@Composable
private fun WeekShoppingShot(state: WeekShoppingUiState) = WeekShoppingScreen(state = state, onAction = {})

@Composable
private fun format(): MealPlanFormat = MealPlanFormat(LocalConfiguration.current.locales[0])

/** A bottom sheet's content as it is laid out in the sheet (the sheet window itself is the platform's). */
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) { content() }
    }
}
