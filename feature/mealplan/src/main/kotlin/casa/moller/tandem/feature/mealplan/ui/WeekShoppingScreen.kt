package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.WeekShoppingIngredient
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekShoppingAction
import casa.moller.tandem.feature.mealplan.WeekShoppingMealState
import casa.moller.tandem.feature.mealplan.WeekShoppingUiState

/** Test tag of the list of meals. */
const val WEEK_SHOPPING_LIST_TAG = "week-shopping:list"

/**
 * "Handla för veckan" (feature #16, plan 6.2; Stitch handla_f_r_veckan), stateless: the week's
 * meals with recipes, each with a checkbox (meals already on the list are marked and cannot be
 * picked again), their ingredients (on-hand foods unticked), and "Lägg n måltider på listan".
 */
@Composable
fun WeekShoppingScreen(
    state: WeekShoppingUiState,
    onAction: (WeekShoppingAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { MealPlanFormat(locale) }
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier,
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.mealplan_week_shopping_title),
                onNavigateBack = { onAction(WeekShoppingAction.Close) },
            )
        },
        bottomBar = {
            if (!state.loading && !state.readOnly && state.meals.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.navigationBarsPadding().padding(spacing.md), contentAlignment = Alignment.Center) {
                        val count = state.toAdd.size
                        TandemPrimaryButton(
                            text = pluralStringResource(R.plurals.mealplan_week_shopping_add, count, count),
                            onClick = { onAction(WeekShoppingAction.Add) },
                            enabled = count > 0 && !state.adding,
                            modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth).fillMaxWidth(),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth).fillMaxSize().testTag(WEEK_SHOPPING_LIST_TAG),
                    contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item(key = "intro") {
                        Text(text = stringResource(R.string.mealplan_week_shopping_intro), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (state.autoAdd) {
                        item(key = "auto-add") {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(
                                    text = stringResource(R.string.mealplan_week_shopping_auto_add),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(spacing.md),
                                )
                            }
                        }
                    }
                    if (state.meals.isEmpty()) {
                        item(key = "empty") {
                            Text(text = stringResource(R.string.mealplan_week_shopping_empty), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(state.meals, key = { it.meal.meal.id }) { meal ->
                        WeekShoppingMealCard(state = meal, readOnly = state.readOnly, format = format, onAction = onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekShoppingMealCard(
    state: WeekShoppingMealState,
    readOnly: Boolean,
    format: MealPlanFormat,
    onAction: (WeekShoppingAction) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val meal = state.meal.meal
    val resources = LocalResources.current
    val label = stringResource(R.string.mealplan_week_shopping_meal_label, format.fullDay(meal.fromDate), meal.mealType.name)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .toggleable(
                        value = state.selected,
                        enabled = state.canSelect && !readOnly,
                        role = Role.Checkbox,
                        onValueChange = { onAction(WeekShoppingAction.ToggleMeal(meal.id)) },
                    )
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Checkbox(checked = state.selected, onCheckedChange = null, enabled = state.canSelect && !readOnly)
                RecipeImage(
                    imageUrl = meal.recipe?.imageUrl,
                    placeholderIconSize = 20.dp,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(text = meal.name(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = format.servings(meal.servings, resources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.meal.alreadyOnList) {
                        StatusChip(label = stringResource(R.string.mealplan_week_shopping_already), tone = TandemTone.Success)
                    } else if (state.meal.ingredients.isEmpty()) {
                        Text(
                            text = stringResource(R.string.mealplan_week_shopping_no_ingredients),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.canSelect && !readOnly) {
                val expandLabel = stringResource(R.string.mealplan_week_shopping_ingredients, meal.name())
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.mealplan_week_shopping_chosen,
                            state.meal.ingredients.size,
                            state.chosen.size,
                            state.meal.ingredients.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onAction(WeekShoppingAction.Expand(meal.id)) }) {
                        Icon(
                            imageVector = TandemIcons.ExpandMore,
                            contentDescription = expandLabel,
                            modifier = Modifier.rotate(if (state.expanded) 180f else 0f),
                        )
                    }
                }
                if (state.expanded) {
                    Column(modifier = Modifier.padding(start = spacing.sm, end = spacing.md, bottom = spacing.sm)) {
                        state.meal.ingredients.forEach { ingredient ->
                            IngredientChoice(
                                ingredient = ingredient,
                                chosen = ingredient.ingredientId in state.chosen,
                                format = format,
                                onToggle = { onAction(WeekShoppingAction.ToggleIngredient(meal.id, ingredient.ingredientId)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientChoice(ingredient: WeekShoppingIngredient, chosen: Boolean, format: MealPlanFormat, onToggle: () -> Unit) {
    val spacing = TandemTheme.spacing
    val onHand = stringResource(R.string.mealplan_week_shopping_on_hand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = chosen, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = TandemTheme.sizes.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(checked = chosen, onCheckedChange = null)
        val amount = listOfNotNull(
            ingredient.amount.takeIf { it > 0.0 }?.let(format::number),
            ingredient.unitName?.takeIf { it.isNotBlank() },
            ingredient.foodName,
        ).joinToString(" ")
        Text(text = amount, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (ingredient.onHand) StatusChip(label = onHand)
    }
}
