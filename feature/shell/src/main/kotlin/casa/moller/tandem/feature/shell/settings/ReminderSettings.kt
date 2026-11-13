package casa.moller.tandem.feature.shell.settings

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.datetime.LocalTime

// Fas 5 (planera lane), plan §8.3 "Påminnelse kvällen innan" and §8.8 "Använd upp": the times of the
// daily reminders and the evening reminder's switch on Mer → Synk och lagring (docs/BYGGE.md
// "Påminnelser kvällen innan och på morgonen").

/** Test tag of a reminder's time row: `settings:reminder-time:<morning|evening>`. */
fun reminderTimeTag(which: String): String = "settings:reminder-time:$which"

/**
 * "Påminnelse kvällen innan": the switch, its explanation (the "Påminn:" line), the time while it
 * is on, and the same note as the other notifications when the system does not let the app post.
 */
@Composable
internal fun MealReminders(state: SyncSettingsUiState, onAction: (SettingsAction) -> Unit) {
    val spacing = TandemTheme.spacing
    SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.settings_meal_reminders),
            body = stringResource(R.string.settings_meal_reminders_body),
            icon = TandemIcons.CalendarMonth,
            checked = state.mealReminders,
            onCheckedChange = { onAction(SettingsAction.SetMealReminders(it)) },
        )
        if (state.mealReminders) {
            GroupDivider()
            ReminderTimeRow(
                time = state.mealReminderTime,
                body = R.string.settings_meal_reminder_time_body,
                dialogTitle = R.string.settings_time_dialog_evening,
                tag = reminderTimeTag(EVENING),
                onChange = { onAction(SettingsAction.SetMealReminderTime(it)) },
            )
            if (!state.notificationsAllowed) {
                Text(
                    text = stringResource(R.string.settings_shopping_notifications_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
                )
            }
        }
    }
}

/** The morning check's time, under its switch while it is on. */
@Composable
internal fun PantryExpiryTimeRow(state: SyncSettingsUiState, onAction: (SettingsAction) -> Unit) {
    ReminderTimeRow(
        time = state.pantryExpiryTime,
        body = R.string.settings_pantry_expiry_time_body,
        dialogTitle = R.string.settings_time_dialog_morning,
        tag = reminderTimeTag(MORNING),
        onChange = { onAction(SettingsAction.SetPantryExpiryTime(it)) },
    )
}

/**
 * "Tid – När kvällens påminnelse kommer – 19:00": one TalkBack item (a button, "Ändra tid") that
 * opens the time picker.
 */
@Composable
private fun ReminderTimeRow(
    time: LocalTime,
    @StringRes body: Int,
    @StringRes dialogTitle: Int,
    tag: String,
    onChange: (LocalTime) -> Unit,
) {
    val spacing = TandemTheme.spacing
    var picking by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.settings_reminder_time)
    val value = formatTime(time)
    val supporting = stringResource(body)
    val change = stringResource(R.string.settings_reminder_time_change)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .testTag(tag)
            // One TalkBack item: "Tid, När kvällens påminnelse kommer, 19:00", double tap to "Ändra tid".
            .clickable(role = Role.Button, onClickLabel = change) { picking = true }
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TandemIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        SettingText(title = title, body = supporting, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (picking) {
        ReminderTimeDialog(
            initial = time,
            title = stringResource(dialogTitle),
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onChange(it)
            },
        )
    }
}

/**
 * Material's time picker in a dialog: the dial, or the two text fields at a large font scale or on
 * a low window (it then fits without scrolling sideways); 24 hours follows the phone's setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeDialog(initial: LocalTime, title: String, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    val compact = LocalDensity.current.fontScale > COMPACT_FONT_SCALE || LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (compact) TimeInput(state = state) else TimePicker(state = state)
            }
        },
        confirmButton = {
            TandemTextButton(
                text = stringResource(R.string.settings_time_dialog_save),
                onClick = { onConfirm(LocalTime(state.hour, state.minute)) },
            )
        },
        dismissButton = { TandemTextButton(text = stringResource(R.string.shell_cancel), onClick = onDismiss) },
    )
}

/** "19:00" or "7:00 PM", as the app's language writes a time. */
@Composable
internal fun formatTime(time: LocalTime): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    return formatter.format(java.time.LocalTime.of(time.hour, time.minute))
}

private const val MORNING = "morning"
private const val EVENING = "evening"
private const val COMPACT_FONT_SCALE = 1.3f
private const val COMPACT_HEIGHT_DP = 560
