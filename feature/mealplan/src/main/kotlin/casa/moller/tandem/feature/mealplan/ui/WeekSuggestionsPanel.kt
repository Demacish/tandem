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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.ServingsText
import casa.moller.tandem.core.ui.accessibility.announce
import casa.moller.tandem.core.ui.recipe.ServingsStepper
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

/** "Fler nyckelord". */
const val WEEK_SUGGESTIONS_MORE_KEYWORDS_TAG = "mealplan:suggestions:more-keywords"

/** How many keywords the search shows at a time. */
private const val MAX_SEARCHED_KEYWORDS = 20

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
    SuggestionAnnouncements(state = state, format = format)
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
        // A node that stays while the sheet changes: TalkBack says "nothing matches" and the like.
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SuggestionChoices(state = state, onAction = onAction)
        if (state.uncoveredDays > 0 && state.meals.isNotEmpty()) UncoveredDays(state)
        SuggestionServings(state = state, onAction = onAction)
        state.meals.forEach { meal ->
            SuggestionRow(meal = meal, format = format, enabled = !state.saving, canSwap = state.canSwap, onAction = onAction)
        }
        if (actions) SuggestionActions(state = state, onAction = onAction)
    }
}

/**
 * TalkBack hears the result of "Byt ut" (the new recipe) and of a new round of suggestions (how
 * many): focus stays on the button, and live regions on every row would queue up to seven texts.
 */
@Composable
private fun SuggestionAnnouncements(state: WeekSuggestionsState, format: MealPlanFormat) {
    val view = LocalView.current
    val resources = LocalResources.current
    var previous by remember { mutableStateOf(state.meals) }
    LaunchedEffect(state.meals) {
        val before = previous
        previous = state.meals
        if (before == state.meals) return@LaunchedEffect
        val sameDays = before.map { it.date } == state.meals.map { it.date }
        val changed = state.meals.filter { it !in before }
        val text = when {
            sameDays && changed.size == 1 -> changed.single().let {
                resources.getString(R.string.mealplan_suggest_swapped, format.weekday(it.date), it.recipe.name)
            }

            state.meals.size < before.size && sameDays.not() && changed.isEmpty() -> null

            else -> resources.getQuantityString(R.plurals.mealplan_suggest_count, state.meals.size, state.meals.size)
        }
        text?.let(view::announce)
    }
}

/** Fewer recipes than free days: says so, and how to get more when keywords are the reason. */
@Composable
private fun UncoveredDays(state: WeekSuggestionsState) {
    val count = state.meals.size
    Text(
        text = if (state.keywordIds.isEmpty()) {
            pluralStringResource(R.plurals.mealplan_suggest_too_few, count, count)
        } else {
            pluralStringResource(R.plurals.mealplan_suggest_too_few_keywords, count, count)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Plan §8.3 "nyckelord, portioner": what the meals are planned with. */
@Composable
private fun SuggestionServings(state: WeekSuggestionsState, onAction: (WeekAction) -> Unit) {
    if (state.meals.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val amount = ServingsText.number(state.servings, locale.toNumberStyle())
    val count = state.servings.toInt()
    ServingsStepper(
        label = stringResource(R.string.mealplan_suggest_servings),
        valueText = pluralStringResource(R.plurals.mealplan_suggest_servings_value, count, amount),
        amountText = amount,
        onDecrease = { onAction(WeekAction.FewerSuggestServings) },
        onIncrease = { onAction(WeekAction.MoreSuggestServings) },
        decreaseEnabled = state.servings > 1,
    )
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
        if (state.allKeywords.size > state.keywords.size) MoreKeywords(state = state, onAction = onAction)
    }
}

/**
 * "Fler nyckelord": the chips are only the most used; this finds any keyword of the recipes. A
 * chosen one then stands among the chips above.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreKeywords(state: WeekSuggestionsState, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    if (!open) {
        TandemTextButton(
            text = stringResource(R.string.mealplan_suggest_more_keywords),
            onClick = { open = true },
            modifier = Modifier.testTag(WEEK_SUGGESTIONS_MORE_KEYWORDS_TAG),
        )
        return
    }
    TandemTextField(
        value = query,
        onValueChange = { query = it },
        label = stringResource(R.string.mealplan_suggest_keyword_search),
        leadingIcon = TandemIcons.Search,
        modifier = Modifier.fillMaxWidth(),
    )
    val shown = state.keywords.map { it.id }.toSet()
    val found = state.allKeywords
        .filter { it.id !in shown && it.name.contains(query.trim(), ignoreCase = true) }
        .take(MAX_SEARCHED_KEYWORDS)
    if (found.isEmpty()) {
        Text(
            text = stringResource(R.string.mealplan_suggest_keyword_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        found.forEach { keyword ->
            FilterChip(
                selected = false,
                onClick = { onAction(WeekAction.ToggleSuggestKeyword(keyword.id)) },
                label = { Text(keyword.name) },
                enabled = !state.saving,
                modifier = Modifier.testTag(suggestionKeywordTag(keyword.id)),
            )
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
private fun SuggestionRow(meal: SuggestedMeal, format: MealPlanFormat, enabled: Boolean, canSwap: Boolean, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val day = format.weekday(meal.date)
    val time = durationText(meal.recipe.totalMinutes)
    val expiring = meal.expiring.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.mealplan_suggest_expiring, it, it) }
    // The row's description replaces its texts, so it says everything they say.
    val recipe = if (time == null) meal.recipe.name else stringResource(R.string.mealplan_suggestion_with_time, meal.recipe.name, time)
    val description = if (expiring == null) {
        stringResource(R.string.mealplan_suggestion_description, day, recipe)
    } else {
        stringResource(R.string.mealplan_suggestion_description_expiring, day, recipe, expiring)
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
            IconButton(onClick = { onAction(WeekAction.SwapSuggestion(meal.date)) }, enabled = enabled && canSwap) {
                Icon(TandemIcons.Refresh, contentDescription = stringResource(R.string.mealplan_suggest_swap, day))
            }
            IconButton(onClick = { onAction(WeekAction.RemoveSuggestion(meal.date)) }, enabled = enabled) {
                Icon(TandemIcons.Close, contentDescription = stringResource(R.string.mealplan_suggest_remove, day))
            }
        }
    }
}
