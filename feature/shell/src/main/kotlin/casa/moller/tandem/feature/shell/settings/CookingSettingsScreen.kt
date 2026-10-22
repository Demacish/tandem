package casa.moller.tandem.feature.shell.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow

/**
 * Mer → Kokläge och timers (plan 6.2 "Beteende: timer i appen eller i klockappen"): where timers
 * run, a warning when the system would let them ring late or silently (with the way to the
 * permissions overview), and a link to cook mode's look under Appearance.
 */
@Composable
internal fun CookingSettingsScreen(
    state: CookingUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.settings_cooking),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup(modifier = Modifier.selectableGroup()) {
            GroupHeading(text = stringResource(R.string.settings_timers), icon = TandemIcons.Schedule)
            RadioRow(
                title = stringResource(R.string.settings_timer_in_app),
                body = stringResource(R.string.settings_timer_in_app_body),
                selected = state.timerMode == TimerMode.IN_APP,
                onClick = { onAction(SettingsAction.SetTimerMode(TimerMode.IN_APP)) },
            )
            GroupDivider()
            RadioRow(
                title = stringResource(R.string.settings_timer_clock_app),
                body = stringResource(R.string.settings_timer_clock_app_body),
                selected = state.timerMode == TimerMode.CLOCK_APP,
                enabled = state.clockAppSelectable,
                onClick = { onAction(SettingsAction.SetTimerMode(TimerMode.CLOCK_APP)) },
            )
            if (!state.capabilities.clockAppAvailable) {
                Text(
                    text = stringResource(R.string.settings_timer_no_clock_app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = TandemTheme.spacing.lg, end = TandemTheme.spacing.lg, bottom = TandemTheme.spacing.lg),
                )
            }
        }

        val capabilities = state.capabilities
        if (state.timerMode == TimerMode.IN_APP && (capabilities.alarmMayBeSilent || capabilities.alarmMayBeLate)) {
            val reasons = buildList {
                if (capabilities.alarmMayBeSilent) add(stringResource(R.string.settings_timer_alarm_warning_silent))
                if (capabilities.alarmMayBeLate) add(stringResource(R.string.settings_timer_alarm_warning_late))
            }
            CalloutCard(
                title = stringResource(R.string.settings_timer_alarm_warning_title),
                body = reasons.joinToString("\n\n"),
                icon = TandemIcons.Warning,
                tone = TandemTone.Warning,
                actions = {
                    TandemTextButton(text = stringResource(R.string.settings_open_permissions), onClick = onOpenPermissions)
                },
            )
        }

        SettingsGroup {
            SettingsRow(
                icon = TandemIcons.Shield,
                title = stringResource(R.string.settings_permissions),
                supporting = stringResource(
                    if (capabilities.alarmMayBeSilent || capabilities.alarmMayBeLate) {
                        R.string.settings_permissions_summary_attention
                    } else {
                        R.string.settings_permissions_summary_ok
                    },
                ),
                onClick = onOpenPermissions,
            )
            GroupDivider()
            SettingsRow(
                icon = TandemIcons.DarkMode,
                title = stringResource(R.string.settings_open_appearance),
                supporting = stringResource(R.string.settings_open_appearance_body),
                onClick = onOpenAppearance,
            )
        }
    }
}
