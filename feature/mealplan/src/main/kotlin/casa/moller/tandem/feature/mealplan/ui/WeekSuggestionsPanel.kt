package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.SuggestedMeal
import casa.moller.tandem.feature.mealplan.WeekAction
import casa.moller.tandem.feature.mealplan.WeekSuggestionsState

// Fas 4 "Förslag på veckans matsedel" (Stitch f_resl_veckan_notiser, upper half, without the AI
// part): a card in the week and a sheet with a suggestion per free day. fas4-kvar: the meal type
// and keywords are chosen in the sheet (plan §8.3 "Autoplan"), and a suggestion that uses
// soon-expiring pantry foods says so ("Använder 3 som går ut snart", the sketch's "räddar 3 råvaror").

/** The suggestion sheet's root. */
const val WEEK_SUGGESTIONS_TAG = "mealplan:suggestions"

/** The "Föreslå veckans matsedel" card. */
const val WEEK_SUGGEST_CARD_TAG = "mealplan:suggest-card"

/** The row of the suggestion on [date] (ISO). */
fun suggestionTag(date: String): String = "mealplan:suggestion:$date"

/** "Föreslå veckans matsedel": a card like "Handla för veckan", on a secondary surface. */
@Composable
internal fun SuggestWeekCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(WEEK_SUGGEST_CARD_TAG),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget).padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(TandemIcons.Restaurant, contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = stringResource(R.string.mealplan_suggest_title), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.mealplan_suggest_card_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(TandemIcons.ChevronRight, contentDescription = null)
        }
    }
}

/** The sheet's "Lägg n måltider i veckan" button. */
const val WEEK_SUGGESTIONS_ACCEPT_TAG = "mealplan:suggestions:accept"

/** A meal type chip of the sheet ([id] the meal type). */
fun suggestionMealTypeTag(id: Long): String = "mealplan:suggestions:meal-type:$id"

/** A keyword chip of the sheet ([id] the keyword). */
fun suggestionKeywordTag(id: Long): String = "mealplan:suggestions:keyword:$id"

/**
 * The sheet as Veckan shows it: [WeekSuggestionsPanel] scrolls and the buttons stay fixed below it,
 * reachable however many days, keywords or how large the text (like RecipeShoppingSheetLayout).
 */
@Composable
internal fun WeekSuggestionsSheetLayout(
    state: WeekSuggestionsState,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
        WeekSuggestionsPanel(
            state = state,
            format = format,
            onAction = onAction,
            actions = false,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )
        SuggestionActions(state = state, onAction = onAction)
    }
}

/**
 * The sheet's content: the meal type and keywords to choose, then one row per free day with the
 * suggested recipe, "Byt ut" and a remove button; "Slumpa om" draws again, "Lägg i veckan" plans
 * what is left. Explains itself when the week has no free day, nothing matches the keywords or
 * there is nothing to suggest.
 *
 * @param actions false when the caller pins the buttons below the content ([WeekSuggestionsSheetLayout]).
 */
@Composable
internal fun WeekSuggestionsPanel(
    state: WeekSuggestionsState,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
    actions: Boolean = true,
) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth().testTag(WEEK_SUGGESTIONS_TAG), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = stringResource(R.string.mealplan_suggest_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val typeName = state.mealType?.name.orEmpty()
        val body = when {
            state.noFreeDays -> stringResource(R.string.mealplan_suggest_no_free_days, typeName)
            state.noKeywordMatch -> stringResource(R.string.mealplan_suggest_no_keyword_match)
            state.noRecipes -> stringResource(R.string.mealplan_suggest_no_recipes)
            state.meals.isEmpty() -> stringResource(R.string.mealplan_suggest_all_removed)
            else -> stringResource(R.string.mealplan_suggest_body, typeName)
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SuggestionChoices(state = state, onAction = onAction)
        state.meals.forEach { meal -> SuggestionRow(meal = meal, format = format, enabled = !state.saving, onAction = onAction) }
        if (actions) SuggestionActions(state = state, onAction = onAction)
    }
}

/** The meal type (one) and the keywords (any number; a suggestion has them all). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChoices(state: WeekSuggestionsState, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    if (state.mealTypes.size > 1) {
        Text(
            text = stringResource(R.string.mealplan_suggest_meal_type),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            state.mealTypes.forEach { type ->
                FilterChip(
                    selected = type.id == state.mealType?.id,
                    onClick = { onAction(WeekAction.SuggestMealType(type.id)) },
                    label = { Text(type.name) },
                    enabled = !state.saving,
                    modifier = Modifier.testTag(suggestionMealTypeTag(type.id)),
                )
            }
        }
    }
    if (state.keywords.isNotEmpty()) {
        Text(
            text = stringResource(R.string.mealplan_suggest_keywords),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            state.keywords.forEach { keyword ->
                FilterChip(
                    selected = keyword.id in state.keywordIds,
                    onClick = { onAction(WeekAction.ToggleSuggestKeyword(keyword.id)) },
                    label = { Text(keyword.name) },
                    enabled = !state.saving,
                    modifier = Modifier.testTag(suggestionKeywordTag(keyword.id)),
                )
            }
        }
    }
}

/** "Slumpa om", "Avbryt" and "Lägg n måltider i veckan". */
@Composable
private fun SuggestionActions(state: WeekSuggestionsState, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        // The buttons on their own rows: "Lägg 4 måltider i veckan" is long in every language.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
            if (!state.noFreeDays && !state.noRecipes && !state.noKeywordMatch) {
                TandemTextButton(
                    text = stringResource(R.string.mealplan_suggest_reshuffle),
                    onClick = { onAction(WeekAction.Reshuffle) },
                    enabled = !state.saving,
                )
            }
            TandemTextButton(text = stringResource(R.string.mealplan_cancel), onClick = { onAction(WeekAction.CancelSuggestions) })
        }
        if (state.meals.isNotEmpty()) {
            TandemPrimaryButton(
                text = pluralStringResource(R.plurals.mealplan_suggest_accept, state.meals.size, state.meals.size),
                onClick = { onAction(WeekAction.AcceptSuggestions) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag(WEEK_SUGGESTIONS_ACCEPT_TAG),
            )
        }
    }
}

@Composable
private fun SuggestionRow(meal: SuggestedMeal, format: MealPlanFormat, enabled: Boolean, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val day = format.weekday(meal.date)
    val time = durationText(meal.recipe.totalMinutes)
    val expiring = meal.expiring.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.mealplan_suggest_expiring, it, it) }
    val description = if (expiring == null) {
        stringResource(R.string.mealplan_suggestion_description, day, meal.recipe.name)
    } else {
        stringResource(R.string.mealplan_suggestion_description_expiring, day, meal.recipe.name, expiring)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(suggestionTag(meal.date.toString()))
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.lg, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(text = meal.recipe.name, style = MaterialTheme.typography.titleMedium)
                if (time != null) {
                    Text(text = time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expiring != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Icon(
                            TandemIcons.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                        )
                        // bodyMedium in onSurfaceVariant like the time above: 12 sp text measures below
                        // 4.5:1 on a low-density screen (ATF on the tablet shots, 3.97:1).
                        Text(
                            text = expiring,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = { onAction(WeekAction.SwapSuggestion(meal.date)) }, enabled = enabled) {
                Icon(TandemIcons.Refresh, contentDescription = stringResource(R.string.mealplan_suggest_swap, day))
            }
            IconButton(onClick = { onAction(WeekAction.RemoveSuggestion(meal.date)) }, enabled = enabled) {
                Icon(TandemIcons.Close, contentDescription = stringResource(R.string.mealplan_suggest_remove, day))
            }
        }
    }
}
