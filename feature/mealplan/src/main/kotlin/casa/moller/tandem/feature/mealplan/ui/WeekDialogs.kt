package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.feature.mealplan.ConflictState
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.MoveMealState
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekAction
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Days offered as chips when moving or planning a meal: two weeks from the Monday of [around]. */
internal fun dayChoices(around: LocalDate, selected: LocalDate): List<LocalDate> {
    val start = MealPlanWeek.startOf(around)
    val days = (0 until DAY_CHOICES).map { start.plus(it, DateTimeUnit.DAY) }
    return if (selected in days) days else (days + selected).sorted()
}

private const val DAY_CHOICES = 14

/**
 * "Flytta" (plan 6.2: long press → move; drag and drop is Phase 3): the meal's new day (two
 * weeks of chips, or any date) and meal type. The meal keeps its length.
 */
@Composable
internal fun MoveMealPanel(
    state: MoveMealState,
    mealTypes: List<MealType>,
    today: LocalDate,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = stringResource(R.string.mealplan_move_title, state.meal.name()),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.mealplan_move_from, format.fullDay(state.meal.fromDate)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DayChooser(
            selected = state.date,
            around = maxOf(state.meal.fromDate, today),
            format = format,
            onSelect = { onAction(WeekAction.MoveDate(it)) },
            onPickOther = { picking = true },
        )
        Text(
            text = stringResource(R.string.mealplan_meal_type_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            mealTypes.forEach { type ->
                FilterChip(
                    selected = type.id == state.mealTypeId,
                    onClick = { onAction(WeekAction.MoveMealType(type.id)) },
                    label = { Text(type.name) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
            TandemTextButton(text = stringResource(R.string.mealplan_cancel), onClick = { onAction(WeekAction.CancelMove) })
            TandemPrimaryButton(
                text = stringResource(R.string.mealplan_move),
                onClick = { onAction(WeekAction.ConfirmMove) },
                enabled = state.changed,
            )
        }
    }
    if (picking) {
        DatePickerFor(initial = state.date, onPick = { onAction(WeekAction.MoveDate(it)) }, onDismiss = { picking = false })
    }
}

/** "Dag": chips for two weeks and "Annat datum…" (a date picker). */
@Composable
internal fun DayChooser(
    selected: LocalDate,
    around: LocalDate,
    format: MealPlanFormat,
    onSelect: (LocalDate) -> Unit,
    onPickOther: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.mealplan_day_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            dayChoices(around, selected).forEach { day ->
                FilterChip(selected = day == selected, onClick = { onSelect(day) }, label = { Text(format.shortDay(day)) })
            }
            AssistChip(
                onClick = onPickOther,
                label = { Text(stringResource(R.string.mealplan_other_date)) },
                leadingIcon = { Icon(TandemIcons.CalendarMonth, contentDescription = null) },
            )
        }
    }
}

/** Material's date picker for one day (days are calendar days: the picker's UTC midnight). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerFor(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onPick(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                }
                onDismiss()
            }) { Text(stringResource(R.string.mealplan_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mealplan_cancel)) } },
    ) {
        DatePicker(
            state = pickerState,
            title = { Text(stringResource(R.string.mealplan_pick_date), modifier = Modifier.semantics { heading() }) },
        )
    }
}

/**
 * "Ändrad på två ställen" (plan 3.5): a field the user changed offline was changed on another
 * device too. "Behåll min" sends the user's value; "Ta deras" keeps the server's. The dialog stays
 * until one is chosen; the other fields of the change were sent already.
 */
@Composable
internal fun ConflictDialog(state: ConflictState, format: MealPlanFormat, onAction: (WeekAction) -> Unit) {
    val conflict = state.conflict
    val field = stringResource(
        when (conflict.field) {
            "from_date", "to_date" -> R.string.mealplan_field_day
            "meal_type" -> R.string.mealplan_field_meal_type
            "recipe" -> R.string.mealplan_field_recipe
            "servings" -> R.string.mealplan_field_servings
            "note" -> R.string.mealplan_field_note
            else -> R.string.mealplan_field_title
        },
    )
    val title = state.mealTitle.ifBlank { stringResource(R.string.mealplan_untitled) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.mealplan_conflict_title)) },
        text = {
            Text(
                stringResource(
                    R.string.mealplan_conflict_body,
                    title,
                    field,
                    conflictValue(conflict.field, conflict.mine, format),
                    conflictValue(conflict.field, conflict.theirs, format),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(WeekAction.ResolveConflict(keepMine = true)) }) {
                Text(stringResource(R.string.mealplan_conflict_keep_mine))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(WeekAction.ResolveConflict(keepMine = false)) }) {
                Text(stringResource(R.string.mealplan_conflict_take_theirs))
            }
        },
    )
}

/** A conflicting value as the user reads it: a day by name, servings as a number, else the text. */
@Composable
private fun conflictValue(field: String, value: String?, format: MealPlanFormat): String {
    if (value.isNullOrBlank()) return stringResource(R.string.mealplan_conflict_empty)
    return when (field) {
        "from_date", "to_date" -> runCatching { format.fullDay(LocalDate.parse(value)) }.getOrDefault(value)
        "servings" -> value.toDoubleOrNull()?.let(format::number) ?: value
        else -> value
    }
}
