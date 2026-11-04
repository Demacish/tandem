package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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

// Fas 4 "Förslag på veckans matsedel" (Stitch f_resl_veckan_notiser, upper half, without the
// pantry and AI parts): a card in the week and a sheet with a suggestion per free day.

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

/**
 * The sheet: one row per free day with the suggested recipe, "Byt ut" and a remove button;
 * "Slumpa om" draws again, "Lägg i veckan" plans what is left. Explains itself when the week has
 * no free day or there is nothing to suggest.
 */
@Composable
internal fun WeekSuggestionsPanel(
    state: WeekSuggestionsState,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
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
            state.noRecipes -> stringResource(R.string.mealplan_suggest_no_recipes)
            state.meals.isEmpty() -> stringResource(R.string.mealplan_suggest_all_removed)
            else -> stringResource(R.string.mealplan_suggest_body, typeName)
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.meals.forEach { meal -> SuggestionRow(meal = meal, format = format, enabled = !state.saving, onAction = onAction) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
            if (!state.noFreeDays && !state.noRecipes) {
                TandemTextButton(
                    text = stringResource(R.string.mealplan_suggest_reshuffle),
                    onClick = { onAction(WeekAction.Reshuffle) },
                    enabled = !state.saving,
                )
            }
            TandemTextButton(text = stringResource(R.string.mealplan_cancel), onClick = { onAction(WeekAction.CancelSuggestions) })
            if (state.meals.isNotEmpty()) {
                TandemPrimaryButton(
                    text = pluralStringResource(R.plurals.mealplan_suggest_accept, state.meals.size, state.meals.size),
                    onClick = { onAction(WeekAction.AcceptSuggestions) },
                    enabled = !state.saving,
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(meal: SuggestedMeal, format: MealPlanFormat, enabled: Boolean, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val day = format.weekday(meal.date)
    val time = durationText(meal.recipe.totalMinutes)
    val description = stringResource(R.string.mealplan_suggestion_description, day, meal.recipe.name)
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
                    Text(text = time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
