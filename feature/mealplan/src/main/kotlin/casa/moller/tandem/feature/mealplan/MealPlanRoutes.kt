// Placeholder routes (Phase 2 grund lane): the parameters are the contract with feature:shell and
// are used once the lane builds the real screens.
@file:Suppress("UnusedParameter")

package casa.moller.tandem.feature.mealplan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import casa.moller.tandem.feature.mealplan.ui.MealPlanPlaceholderScreen

// Phase 2 (grund lane): the routes feature:shell shows for the Veckan tab (docs/BYGGE.md "Fas 2 –
// kontrakt"). The signatures are the contract with the shell; the meal plan lane replaces the
// placeholder bodies with the real screens.

/**
 * The week agenda (Veckan tab root, or a [casa.moller.tandem.feature.mealplan.navigation.WeekAgendaKey]).
 *
 * @param weekStart the week's Monday (`yyyy-MM-dd`), null = this week.
 * @param onOpenRecipe a planned recipe.
 * @param onPlanMeal "+" on a day/meal or tapping a meal: the plan-meal sheet.
 * @param onWeekShopping "Handla för veckan" for the shown week.
 * @param onStartCooking "Laga" on a meal: cook mode with the meal's servings (as on Idag).
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
    MealPlanPlaceholderScreen(
        title = stringResource(R.string.mealplan_title),
        body = stringResource(R.string.mealplan_placeholder_body),
        icon = TandemIcons.CalendarMonth,
        modifier = modifier,
    )
}

/**
 * The plan-meal sheet. [onDone] closes it after saving or cancelling; [onPickRecipe] would open
 * the recipe search to choose a recipe (the lane decides whether the sheet searches itself).
 */
@Composable
fun PlanMealRoute(key: PlanMealKey, onDone: () -> Unit, modifier: Modifier = Modifier) {
    MealPlanPlaceholderScreen(
        title = stringResource(R.string.mealplan_plan_meal_title),
        body = stringResource(R.string.mealplan_placeholder_body),
        icon = TandemIcons.CalendarMonth,
        onNavigateBack = onDone,
        modifier = modifier,
    )
}

/** "Handla för veckan"; [onOpenShoppingList] shows the list after adding, [onDone] goes back. */
@Composable
fun WeekShoppingRoute(key: WeekShoppingKey, onDone: () -> Unit, onOpenShoppingList: () -> Unit, modifier: Modifier = Modifier) {
    MealPlanPlaceholderScreen(
        title = stringResource(R.string.mealplan_week_shopping_title),
        body = stringResource(R.string.mealplan_placeholder_body),
        icon = TandemIcons.ShoppingCart,
        onNavigateBack = onDone,
        modifier = modifier,
    )
}
