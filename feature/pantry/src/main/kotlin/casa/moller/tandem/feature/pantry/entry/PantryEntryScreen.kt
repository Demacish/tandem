package casa.moller.tandem.feature.pantry.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.formatDay
import casa.moller.tandem.core.ui.pantry.isCountedExpiry
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R
import casa.moller.tandem.feature.pantry.ui.PantryCompactHeight
import casa.moller.tandem.feature.pantry.ui.PantryIcons
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Test tag of the entry page's save button. */
const val PANTRY_ENTRY_SAVE_TAG = "pantry-entry:save"

/** Test tag of "Ta bort varan". */
const val PANTRY_ENTRY_DELETE_TAG = "pantry-entry:delete"

/** Test tag of the food field. */
const val PANTRY_ENTRY_FOOD_TAG = "pantry-entry:food"

/** Test tag of the amount field. */
const val PANTRY_ENTRY_AMOUNT_TAG = "pantry-entry:amount"

/**
 * Adding or changing a stock entry (`PantryEntryKey`), stateless: food (with stored foods as
 * suggestions), amount and unit, place, best-before day (quick picks, longer in the freezer, or
 * any day) and a note. "Spara" (and "Ta bort" for an existing entry) stay pinned below the
 * scrolling form and above the keyboard. On a wide window the form keeps a readable width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantryEntryScreen(
    state: PantryEntryUiState,
    onAction: (PantryEntryAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    PantryEntryLayout(
        state = state,
        onAction = onAction,
        keyboardOpen = WindowInsets.isImeVisible,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * [PantryEntryScreen] with the keyboard state given ([keyboardOpen], tests have no keyboard).
 * Fas S review (fix-ui): in a low window (a phone in landscape, under [PantryCompactHeight]) the
 * pinned bar is only "Spara" ("Ta bort varan" moves to the end of the form), and while the
 * keyboard is open there it steps aside entirely, so the field being typed in stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PantryEntryLayout(
    state: PantryEntryUiState,
    onAction: (PantryEntryAction) -> Unit,
    keyboardOpen: Boolean,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxHeight < PantryCompactHeight
        val showActions = !state.loading && !state.missing && !state.readOnly && !(compact && keyboardOpen)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TandemTopAppBar(
                    title = stringResource(if (state.isNew) R.string.pantry_entry_new_title else R.string.pantry_entry_edit_title),
                    onNavigateBack = { onAction(PantryEntryAction.Close) },
                )
            },
            bottomBar = {
                if (showActions) EntryActions(state = state, onAction = onAction, compact = compact)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            when {
                state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

                state.missing -> EmptyState(
                    icon = PantryIcons.Kitchen,
                    title = stringResource(R.string.pantry_entry_missing_title),
                    body = stringResource(R.string.pantry_entry_missing_body),
                    modifier = Modifier.fillMaxSize().padding(padding),
                )

                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // Above the keyboard also when the pinned bar has stepped aside.
                        .consumeWindowInsets(padding)
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                            .fillMaxWidth()
                            .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EntryForm(
                            state = state,
                            onAction = onAction,
                            onPickDate = { pickingDate = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (compact && !state.isNew && !state.readOnly) DeleteEntryButton(onAction = onAction)
                    }
                }
            }
        }
    }
    if (pickingDate) {
        ExpiryDatePicker(
            initial = state.expires ?: state.today,
            onPicked = { date ->
                pickingDate = false
                onAction(PantryEntryAction.PickDate(date))
            },
            onDismiss = { pickingDate = false },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { onAction(PantryEntryAction.ConfirmDelete(false)) },
            // The theme's dialog surface (surfaceContainerHigh) gives the primary-coloured buttons
            // only 4.2:1 (ATF); the same surface as Veckan's dialogs.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text(stringResource(R.string.pantry_entry_delete_title, state.food)) },
            text = { Text(stringResource(R.string.pantry_entry_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(PantryEntryAction.ConfirmDelete(true)) }) {
                    Text(stringResource(R.string.pantry_entry_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(PantryEntryAction.ConfirmDelete(false)) }) { Text(stringResource(R.string.pantry_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryForm(
    state: PantryEntryUiState,
    onAction: (PantryEntryAction) -> Unit,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val enabled = !state.readOnly
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (state.readOnly) {
            InfoCard(
                title = stringResource(R.string.pantry_guest_title),
                body = stringResource(R.string.pantry_guest_body),
                icon = TandemIcons.Lock,
            )
        }
        OutlinedTextField(
            value = state.food,
            onValueChange = { onAction(PantryEntryAction.FoodText(it)) },
            label = { Text(stringResource(R.string.pantry_entry_food)) },
            supportingText = if (!state.isNew) {
                { Text(stringResource(R.string.pantry_entry_food_hint)) }
            } else {
                null
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(PANTRY_ENTRY_FOOD_TAG),
        )
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.suggestions.forEach { food ->
                    val description = stringResource(R.string.pantry_entry_pick_food, food.name)
                    AssistChip(
                        onClick = { onAction(PantryEntryAction.PickFood(food)) },
                        label = { Text(food.name) },
                        modifier = Modifier
                            .heightIn(min = TandemTheme.sizes.minTouchTarget)
                            .semantics { contentDescription = description },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            OutlinedTextField(
                value = state.amount,
                onValueChange = { onAction(PantryEntryAction.AmountText(it)) },
                label = { Text(stringResource(R.string.pantry_entry_amount)) },
                isError = state.amountError,
                supportingText = if (state.amountError) {
                    { Text(stringResource(R.string.pantry_entry_amount_error)) }
                } else {
                    null
                },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag(PANTRY_ENTRY_AMOUNT_TAG),
            )
            OutlinedTextField(
                value = state.unit,
                onValueChange = { onAction(PantryEntryAction.UnitText(it)) },
                label = { Text(stringResource(R.string.pantry_entry_unit)) },
                placeholder = { Text(stringResource(R.string.pantry_entry_unit_placeholder)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }
        if (state.units.isNotEmpty() && enabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.units.forEach { unit ->
                    FilterChip(
                        selected = unit.equals(state.unit.trim(), ignoreCase = true),
                        onClick = { onAction(PantryEntryAction.UnitText(unit)) },
                        label = { Text(unit) },
                        modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                    )
                }
            }
        }
        FormHeading(stringResource(R.string.pantry_entry_location))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            state.locations.forEach { location ->
                FilterChip(
                    selected = state.locationId == location.id,
                    onClick = { onAction(PantryEntryAction.PickLocation(location.id)) },
                    label = { Text(location.name) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                )
            }
        }
        ExpiryPicker(state = state, onAction = onAction, onPickDate = onPickDate, enabled = enabled)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value = state.note,
            onValueChange = { onAction(PantryEntryAction.NoteText(it)) },
            label = { Text(stringResource(R.string.pantry_entry_note)) },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions.Default,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The best-before day: quick picks from today (the freezer gets longer ones), any day, or none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryPicker(state: PantryEntryUiState, onAction: (PantryEntryAction) -> Unit, onPickDate: () -> Unit, enabled: Boolean) {
    val spacing = TandemTheme.spacing
    val today = state.today
    FormHeading(stringResource(R.string.pantry_entry_expiry))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        val choices = (state.expiryChoices + listOfNotNull(state.selectedChoice)).distinct()
        choices.forEach { choice ->
            FilterChip(
                selected = state.selectedChoice == choice,
                onClick = { onAction(PantryEntryAction.PickExpiry(choice)) },
                label = { Text(expiryChoiceLabel(choice)) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
        AssistChip(
            onClick = onPickDate,
            label = { Text(stringResource(R.string.pantry_entry_pick_date)) },
            leadingIcon = { Icon(PantryIcons.Event, contentDescription = null) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
        )
    }
    val expires = state.expires
    Text(
        text = if (expires != null && today != null && isCountedExpiry(expires, today)) {
            stringResource(R.string.pantry_entry_expiry_chosen, formatDay(expires, today, currentLocale()), expiryText(expires, today))
        } else if (expires != null && today != null) {
            stringResource(R.string.pantry_entry_expiry_chosen_far, formatDay(expires, today, currentLocale()))
        } else {
            stringResource(R.string.pantry_entry_expiry_none)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "+3 dagar", "+1 vecka", "+1 månad", "+3 månader", "Inget". */
@Composable
internal fun expiryChoiceLabel(choice: ExpiryChoice): String = stringResource(
    when (choice) {
        ExpiryChoice.NONE -> R.string.pantry_expiry_none
        ExpiryChoice.PLUS_3_DAYS -> R.string.pantry_expiry_3_days
        ExpiryChoice.PLUS_1_WEEK -> R.string.pantry_expiry_1_week
        ExpiryChoice.PLUS_1_MONTH -> R.string.pantry_expiry_1_month
        ExpiryChoice.PLUS_3_MONTHS -> R.string.pantry_expiry_3_months
    },
)

@Composable
private fun FormHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

/**
 * "Spara" and, for an existing entry, "Ta bort": pinned below the form, above the keyboard. In a
 * low window ([compact]) only "Spara", at the normal button height ("Ta bort" is in the form).
 */
@Composable
private fun EntryActions(state: PantryEntryUiState, onAction: (PantryEntryAction) -> Unit, compact: Boolean) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = spacing.screenHorizontal, vertical = if (compact) spacing.sm else spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            TandemPrimaryButton(
                text = stringResource(R.string.pantry_save),
                onClick = { onAction(PantryEntryAction.Save) },
                enabled = state.canSave,
                leadingIcon = TandemIcons.Check,
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .heightIn(min = if (compact) TandemTheme.sizes.minTouchTarget else TandemTheme.sizes.kitchenTouchTarget)
                    .testTag(PANTRY_ENTRY_SAVE_TAG),
            )
            if (!state.isNew && !compact) DeleteEntryButton(onAction = onAction)
        }
    }
}

@Composable
private fun DeleteEntryButton(onAction: (PantryEntryAction) -> Unit) {
    TandemTextButton(
        text = stringResource(R.string.pantry_entry_delete),
        onClick = { onAction(PantryEntryAction.AskDelete) },
        leadingIcon = TandemIcons.Delete,
        modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget).testTag(PANTRY_ENTRY_DELETE_TAG),
    )
}

/** The calendar for "Välj datum" (the picker works in UTC days). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDatePicker(initial: LocalDate?, onPicked: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    onPicked(millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date })
                },
            ) { Text(stringResource(R.string.pantry_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pantry_cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}
