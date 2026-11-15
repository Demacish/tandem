package casa.moller.tandem.feature.shell.assistant

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Mic
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.SettingsPage
import casa.moller.tandem.feature.shell.settings.SwitchRow
import casa.moller.tandem.feature.shell.ui.SettingsGroup

/**
 * Mer → Assistenten (Fas 5, funktioner lane, plan §8.7): "Låt assistenten använda Tandem" (off
 * until chosen: what the assistant reads may leave the phone with it), with Lås appen on whether the
 * assistant gets an answer while the app is locked (the switch "Tillåt assistenten när appen är
 * låst" sits next to the lock, on Mer → Lås appen), and what the assistant can and cannot do. Below
 * Android 16 the page says the setting does nothing and the switch is off limits; a guest is told
 * the assistant only reads, and demo mode that it uses the sample data.
 */
@Composable
internal fun AssistantSettingsScreen(
    state: AssistantSettingsUiState,
    onAction: (AssistantSettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.shell_assistant_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        if (!state.supported) {
            CalloutCard(
                title = stringResource(R.string.shell_assistant_unsupported_title),
                body = stringResource(R.string.shell_assistant_unsupported_body),
                icon = TandemIcons.Info,
                tone = TandemTone.Neutral,
            )
        }
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.shell_assistant_switch_title),
                body = stringResource(R.string.shell_assistant_switch_body),
                checked = state.enabled,
                onCheckedChange = { onAction(AssistantSettingsAction.SetEnabled(it)) },
                icon = TandemIcons.Mic,
                enabled = state.loaded && state.supported,
            )
        }
        if (state.lockEnabled && state.supported) {
            InfoCard(
                title = stringResource(R.string.shell_assistant_lock_title),
                body = stringResource(
                    if (state.allowWhenLocked) R.string.shell_assistant_lock_allowed_body else R.string.shell_assistant_lock_blocked_body,
                ),
                icon = TandemIcons.Lock,
            )
        }
        if (state.guest) {
            InfoCard(
                title = stringResource(R.string.shell_assistant_guest_title),
                body = stringResource(R.string.shell_assistant_guest_body),
                icon = TandemIcons.Visibility,
            )
        }
        if (state.demo) {
            InfoCard(
                title = stringResource(R.string.shell_assistant_demo_title),
                body = stringResource(R.string.shell_assistant_demo_body),
                icon = TandemIcons.Science,
            )
        }
        InfoCard(
            title = stringResource(R.string.shell_assistant_can_title),
            body = stringResource(R.string.shell_assistant_can_body),
            icon = TandemIcons.Info,
        )
    }
}
