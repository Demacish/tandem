package casa.moller.tandem.feature.shell.lock

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.GroupHeading
import casa.moller.tandem.feature.shell.settings.RadioRow
import casa.moller.tandem.feature.shell.settings.SettingsPage
import casa.moller.tandem.feature.shell.settings.SwitchRow
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup

/**
 * Mer → Lås appen (Fas 5, las lane, plan §5.5): the switch (off until chosen; turning it on asks
 * the phone's screen lock once), "Lås efter" (Direkt, 1, 5 or 15 minutes in the background), and
 * what keeps working while the app is locked. Without a screen lock on the phone the switch
 * cannot be turned on: the page says why and opens the system settings.
 */
@Composable
internal fun AppLockSettingsScreen(
    state: AppLockSettingsUiState,
    onAction: (AppLockSettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.shell_lock_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.shell_lock_switch_title),
                body = stringResource(R.string.shell_lock_switch_body),
                checked = state.enabled,
                onCheckedChange = { onAction(AppLockSettingsAction.SetEnabled(it)) },
                icon = TandemIcons.Lock,
                enabled = state.switchEnabled,
            )
        }
        if (state.screenLockMissing) {
            CalloutCard(
                title = stringResource(R.string.shell_lock_no_screen_lock_title),
                body = stringResource(
                    if (state.enabled) R.string.shell_lock_no_screen_lock_on_body else R.string.shell_lock_no_screen_lock_body,
                ),
                icon = TandemIcons.Warning,
                tone = TandemTone.Warning,
                actions = {
                    TandemSecondaryButton(
                        text = stringResource(R.string.shell_lock_open_settings),
                        onClick = { onAction(AppLockSettingsAction.OpenScreenLockSettings) },
                        leadingIcon = TandemIcons.Settings,
                    )
                },
            )
        }
        if (state.enabled) {
            SettingsGroup(modifier = Modifier.selectableGroup()) {
                GroupHeading(text = stringResource(R.string.shell_lock_timeout_heading), icon = TandemIcons.Schedule)
                Text(
                    text = stringResource(R.string.shell_lock_timeout_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = TandemTheme.spacing.lg, end = TandemTheme.spacing.lg, bottom = TandemTheme.spacing.sm),
                )
                AppLockTimeout.entries.forEachIndexed { index, timeout ->
                    if (index > 0) GroupDivider()
                    RadioRow(
                        title = timeoutLabel(timeout),
                        selected = timeout == state.timeout,
                        onClick = { onAction(AppLockSettingsAction.SetTimeout(timeout)) },
                    )
                }
            }
        }
        InfoCard(
            title = stringResource(R.string.shell_lock_keeps_working_title),
            body = stringResource(R.string.shell_lock_keeps_working_body),
            icon = TandemIcons.Info,
        )
    }
}

/** "Direkt", "Efter 1 minut", ... as the choice is named. */
@Composable
internal fun timeoutLabel(timeout: AppLockTimeout): String = when (timeout) {
    AppLockTimeout.IMMEDIATELY -> stringResource(R.string.shell_lock_timeout_immediately)
    else -> (timeout.millis / MINUTE_MILLIS).toInt().let { pluralStringResource(R.plurals.shell_lock_timeout_minutes, it, it) }
}

/** "låses direkt", "låses efter 1 minut": the row in Mer. */
@Composable
internal fun timeoutSummary(timeout: AppLockTimeout): String = when (timeout) {
    AppLockTimeout.IMMEDIATELY -> stringResource(R.string.shell_lock_more_on_immediately)
    else -> (timeout.millis / MINUTE_MILLIS).toInt().let { pluralStringResource(R.plurals.shell_lock_more_on_minutes, it, it) }
}

private const val MINUTE_MILLIS = 60_000L
