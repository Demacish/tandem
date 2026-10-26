package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekShoppingAction
import casa.moller.tandem.feature.mealplan.WeekShoppingAisle
import casa.moller.tandem.feature.mealplan.WeekShoppingLineState
import casa.moller.tandem.feature.mealplan.WeekShoppingMealState
import casa.moller.tandem.feature.mealplan.WeekShoppingUiState
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Test tag of the list of merged ingredients. */
const val WEEK_SHOPPING_LIST_TAG = "week-shopping:list"

/** Test tag of the "Lägg till n varor" button. */
const val WEEK_SHOPPING_ADD_TAG = "week-shopping:add"

/** Test tag of one merged line. */
fun weekShoppingLineTag(key: String): String = "week-shopping:line:$key"

/** Test tag of one meal chip. */
fun weekShoppingMealTag(id: Long): String = "week-shopping:meal:$id"

/** From this width the meals and the lines stand side by side (plan 6.5: tablets, phones in landscape). */
private val ShoppingTwoPaneMinWidth = 840.dp
private val ShoppingMealPaneWidth = 360.dp

/**
 * "Handla för veckan" (feature #16, plan 6.2 steps 1-4, 8.3; Stitch handla_f_r_veckan with the
 * corrections in docs/design/README.md), stateless: the days (a date range, "Ändra dagar"), how
 * many items are to buy and at home, "Bara det som saknas" / "Alla råvaror", the included meals
 * (one already on the list is marked and cannot be picked), the merged ingredients by aisle (a
 * "Summerad" line says which meals it adds up), the ones at home (unticked), and "Lägg till n varor
 * i inköpslistan". From 840 dp the meals stand beside the ingredients.
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
    var picking by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.mealplan_week_shopping_title),
                onNavigateBack = { onAction(WeekShoppingAction.Close) },
            )
        },
        bottomBar = {
            if (!state.loading && !state.readOnly && state.lineCount > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.navigationBarsPadding().padding(spacing.md), contentAlignment = Alignment.Center) {
                        val count = state.chosenCount
                        TandemPrimaryButton(
                            text = if (count > 0) {
                                pluralStringResource(R.plurals.mealplan_week_shopping_add_items, count, count)
                            } else {
                                stringResource(R.string.mealplan_week_shopping_add_none)
                            },
                            onClick = { onAction(WeekShoppingAction.Add) },
                            enabled = state.canAdd,
                            leadingIcon = TandemIcons.ShoppingCart,
                            modifier = Modifier
                                .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                                .fillMaxWidth()
                                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                                .testTag(WEEK_SHOPPING_ADD_TAG),
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
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val content = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.lg)
                if (maxWidth >= ShoppingTwoPaneMinWidth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        LazyColumn(
                            modifier = Modifier.width(ShoppingMealPaneWidth).fillMaxHeight(),
                            contentPadding = content,
                            verticalArrangement = Arrangement.spacedBy(spacing.md),
                        ) {
                            overview(state, format, onAction, onPickDays = { picking = true })
                            meals(state, format, onAction)
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag(WEEK_SHOPPING_LIST_TAG),
                            contentPadding = content,
                            verticalArrangement = Arrangement.spacedBy(spacing.md),
                        ) {
                            lines(state, format, onAction)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth).fillMaxSize().testTag(WEEK_SHOPPING_LIST_TAG),
                        contentPadding = content,
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        overview(state, format, onAction, onPickDays = { picking = true })
                        meals(state, format, onAction)
                        lines(state, format, onAction)
                    }
                }
            }
        }
    }
    if (picking) {
        DateRangePickerFor(
            from = state.from,
            to = state.to,
            onPick = { from, to -> onAction(WeekShoppingAction.SetRange(from, to)) },
            onDismiss = { picking = false },
        )
    }
}

/** The days, the counts, the explanation and "Bara det som saknas" / "Alla råvaror". */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.overview(
    state: WeekShoppingUiState,
    format: MealPlanFormat,
    onAction: (WeekShoppingAction) -> Unit,
    onPickDays: () -> Unit,
) {
    item(key = "days") {
        val resources = LocalResources.current
        val range = format.range(state.from, state.to, resources)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(text = range, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    text = pluralStringResource(R.plurals.mealplan_week_shopping_planned, state.meals.size, state.meals.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val description = stringResource(R.string.mealplan_week_shopping_change_days_description, range)
            TandemTextButton(
                text = stringResource(R.string.mealplan_week_shopping_change_days),
                onClick = onPickDays,
                leadingIcon = TandemIcons.CalendarMonth,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
    item(key = "intro") {
        Text(text = stringResource(R.string.mealplan_week_shopping_intro), style = MaterialTheme.typography.bodyLarge)
    }
    if (state.autoAdd) {
        item(key = "auto-add") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = stringResource(R.string.mealplan_week_shopping_auto_add),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(TandemTheme.spacing.md),
                )
            }
        }
    }
    if (state.lineCount > 0) {
        item(key = "summary") {
            val spacing = TandemTheme.spacing
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = pluralStringResource(R.plurals.mealplan_week_shopping_to_buy_count, state.missingCount, state.missingCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.atHome.isNotEmpty()) {
                        val atHome = state.atHome.size
                        Text(
                            text = pluralStringResource(R.plurals.mealplan_week_shopping_at_home_count, atHome, atHome),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!state.readOnly) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            FilterChip(
                                selected = state.onlyMissing,
                                onClick = { onAction(WeekShoppingAction.OnlyMissing) },
                                label = { Text(stringResource(R.string.mealplan_week_shopping_only_missing, state.missingCount)) },
                                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                            )
                            FilterChip(
                                selected = state.allChosen,
                                onClick = { onAction(WeekShoppingAction.AllIngredients) },
                                label = { Text(stringResource(R.string.mealplan_week_shopping_all, state.lineCount)) },
                                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Inkluderade måltider": one chip per meal (day and name), already listed ones marked. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.meals(state: WeekShoppingUiState, format: MealPlanFormat, onAction: (WeekShoppingAction) -> Unit) {
    if (state.meals.isEmpty()) {
        item(key = "empty") {
            Text(text = stringResource(R.string.mealplan_week_shopping_empty), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    item(key = "meals") {
        val spacing = TandemTheme.spacing
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.mealplan_week_shopping_meals),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (!state.readOnly && state.meals.any { it.canSelect }) {
                Text(
                    text = stringResource(R.string.mealplan_week_shopping_meals_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.meals.forEach { meal -> MealChip(meal = meal, readOnly = state.readOnly, format = format, onAction = onAction) }
            }
        }
    }
}

@Composable
private fun MealChip(meal: WeekShoppingMealState, readOnly: Boolean, format: MealPlanFormat, onAction: (WeekShoppingAction) -> Unit) {
    val entry = meal.meal.meal
    val label = stringResource(R.string.mealplan_week_shopping_meal_chip, format.shortWeekday(entry.fromDate), entry.displayTitle)
    val description = when {
        meal.meal.alreadyOnList -> stringResource(R.string.mealplan_week_shopping_meal_already, label)
        meal.meal.ingredients.isEmpty() -> stringResource(R.string.mealplan_week_shopping_meal_unavailable, label)
        else -> label
    }
    FilterChip(
        selected = meal.included,
        onClick = { onAction(WeekShoppingAction.ToggleMeal(entry.id)) },
        enabled = meal.canSelect && !readOnly,
        label = { Text(label) },
        leadingIcon = if (meal.meal.alreadyOnList) {
            { Icon(TandemIcons.CheckCircle, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .semantics { contentDescription = description }
            .testTag(weekShoppingMealTag(entry.id)),
    )
}

/** The merged ingredients by aisle, then "Finns redan hemma?". */
private fun LazyListScope.lines(state: WeekShoppingUiState, format: MealPlanFormat, onAction: (WeekShoppingAction) -> Unit) {
    if (state.meals.isNotEmpty() && state.lineCount == 0) {
        item(key = "nothing") {
            val text = if (state.meals.all { it.meal.alreadyOnList }) {
                stringResource(R.string.mealplan_week_shopping_all_on_list)
            } else {
                stringResource(R.string.mealplan_week_shopping_no_ingredients)
            }
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
    items(state.aisles, key = { "aisle-${it.key}" }) { aisle -> AisleCard(aisle, state.readOnly, format, onAction) }
    if (state.atHome.isNotEmpty()) {
        item(key = "at-home") {
            val spacing = TandemTheme.spacing
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = stringResource(R.string.mealplan_week_shopping_at_home),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.mealplan_week_shopping_at_home_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LineGroup(lines = state.atHome, readOnly = state.readOnly, format = format, onAction = onAction)
            }
        }
    }
}

@Composable
private fun AisleCard(aisle: WeekShoppingAisle, readOnly: Boolean, format: MealPlanFormat, onAction: (WeekShoppingAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = aisle.name ?: stringResource(R.string.mealplan_week_shopping_other_aisle),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                text = pluralStringResource(R.plurals.mealplan_week_shopping_aisle_count, aisle.lines.size, aisle.lines.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LineGroup(lines = aisle.lines, readOnly = readOnly, format = format, onAction = onAction)
    }
}

@Composable
private fun LineGroup(
    lines: List<WeekShoppingLineState>,
    readOnly: Boolean,
    format: MealPlanFormat,
    onAction: (WeekShoppingAction) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = TandemTheme.spacing.xs)) {
            lines.forEach { line -> LineRow(line = line, readOnly = readOnly, format = format, onAction = onAction) }
        }
    }
}

/**
 * One merged line: checkbox, food, "Summerad" when several meals add up, the total and where it
 * comes from ("5 dl totalt (3 dl Svamppasta + 2 dl Fisksoppa)"); one TalkBack item.
 */
@Composable
private fun LineRow(line: WeekShoppingLineState, readOnly: Boolean, format: MealPlanFormat, onAction: (WeekShoppingAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val resources = LocalResources.current
    val merged = line.line
    val amount = amountText(merged.amount, merged.unitName, format)
    val detail = when {
        merged.merged -> {
            val parts = merged.parts.joinToString(" + ") { part ->
                val partAmount = amountText(part.amount, merged.unitName, format)
                if (partAmount.isEmpty()) {
                    part.mealName
                } else {
                    resources.getString(R.string.mealplan_week_shopping_line_part, partAmount, part.mealName)
                }
            }
            if (amount.isEmpty()) parts else stringResource(R.string.mealplan_week_shopping_line_total, amount, parts)
        }

        amount.isEmpty() -> merged.parts.firstOrNull()?.mealName.orEmpty()

        else -> stringResource(R.string.mealplan_week_shopping_line_one, amount, merged.parts.firstOrNull()?.mealName.orEmpty())
    }
    val rowModifier = if (readOnly) {
        Modifier.semantics(mergeDescendants = true) {}
    } else {
        Modifier.toggleable(
            value = line.chosen,
            role = Role.Checkbox,
            onValueChange = { onAction(WeekShoppingAction.ToggleLine(merged.key)) },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(rowModifier)
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .padding(horizontal = spacing.sm, vertical = spacing.xs)
            .testTag(weekShoppingLineTag(merged.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (!readOnly) Checkbox(checked = line.chosen, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(text = merged.foodName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (merged.merged) StatusChip(label = stringResource(R.string.mealplan_week_shopping_merged), tone = TandemTone.Accent)
            }
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (merged.onHand) StatusChip(label = stringResource(R.string.mealplan_week_shopping_on_hand))
        }
        if (amount.isNotEmpty()) {
            Text(
                text = amount,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** "5 dl", "2", or empty for no amount. */
private fun amountText(amount: Double, unit: String?, format: MealPlanFormat): String =
    if (amount <= 0.0) "" else listOfNotNull(format.number(amount), unit?.takeIf { it.isNotBlank() }).joinToString(" ")

/** Material's date range picker (days are calendar days: the picker's UTC midnight). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerFor(from: LocalDate, to: LocalDate, onPick: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = from.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        initialSelectedEndDateMillis = to.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = pickerState.selectedStartDateMillis
                    if (start != null) {
                        val end = pickerState.selectedEndDateMillis ?: start
                        onPick(start.toDate(), end.toDate())
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.mealplan_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealplan_cancel)) } },
    ) {
        DateRangePicker(
            state = pickerState,
            title = {
                Text(
                    text = stringResource(R.string.mealplan_week_shopping_pick_days),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp).semantics { heading() },
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun Long.toDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
