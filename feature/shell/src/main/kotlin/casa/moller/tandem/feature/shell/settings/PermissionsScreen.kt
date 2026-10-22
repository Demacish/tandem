package casa.moller.tandem.feature.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.BatteryAlert
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup

/**
 * Mer → Behörigheter (design k_kets_beh_righeter_systemguide with the corrections in
 * docs/design/README.md): an OVERVIEW of what Tandem may do, never a forced step. Each row says
 * what the permission is for in plain words (no Android permission names), whether it is allowed,
 * and opens the right Android settings screen. Permissions are still asked in context (the
 * notification dialog at the first timer, plan 8.7).
 */
@Composable
internal fun PermissionsScreen(
    state: PermissionsUiState,
    onOpenSystemSetting: (SystemSetting) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val capabilities = state.capabilities
    SettingsPage(
        title = stringResource(R.string.settings_permissions),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        Text(
            text = stringResource(R.string.settings_permissions_intro),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = TandemTheme.spacing.xs),
        )
        InfoCard(
            title = stringResource(R.string.settings_permissions_privacy_title),
            body = stringResource(R.string.settings_permissions_privacy_body),
            icon = TandemIcons.Shield,
            tone = TandemTone.Success,
        )
        SettingsGroup {
            PermissionRow(
                icon = TandemIcons.Info,
                title = stringResource(R.string.settings_permission_notifications),
                body = stringResource(R.string.settings_permission_notifications_body),
                allowed = capabilities.notificationsAllowed,
                onOpen = { onOpenSystemSetting(SystemSetting.NOTIFICATIONS) },
            )
            GroupDivider()
            PermissionRow(
                icon = TandemIcons.Schedule,
                title = stringResource(R.string.settings_permission_alarm_channel),
                body = stringResource(R.string.settings_permission_alarm_channel_body),
                allowed = capabilities.notificationsAllowed && capabilities.alarmChannelEnabled,
                onOpen = {
                    // Without notifications at all, the channel cannot be switched on by itself.
                    onOpenSystemSetting(if (capabilities.notificationsAllowed) SystemSetting.ALARM_CHANNEL else SystemSetting.NOTIFICATIONS)
                },
            )
            GroupDivider()
            PermissionRow(
                icon = TandemIcons.Schedule,
                title = stringResource(R.string.settings_permission_exact),
                body = stringResource(R.string.settings_permission_exact_body),
                allowed = capabilities.exactAlarmsAllowed,
                note = if (state.exactAlarmsFixed) stringResource(R.string.settings_permission_exact_always) else null,
                onOpen = if (capabilities.exactAlarmsRequestable) {
                    { onOpenSystemSetting(SystemSetting.EXACT_ALARMS) }
                } else {
                    null
                },
            )
            if (state.showFullScreen) {
                GroupDivider()
                PermissionRow(
                    icon = TandemIcons.Lock,
                    title = stringResource(R.string.settings_permission_full_screen),
                    body = stringResource(R.string.settings_permission_full_screen_body),
                    allowed = capabilities.fullScreenAlarmAllowed,
                    onOpen = { onOpenSystemSetting(SystemSetting.FULL_SCREEN) },
                )
            }
            if (state.showBattery) {
                GroupDivider()
                PermissionRow(
                    icon = TandemIcons.BatteryAlert,
                    title = stringResource(R.string.settings_permission_battery),
                    body = stringResource(R.string.settings_permission_battery_body),
                    allowed = state.batteryUnrestricted,
                    allowedText = stringResource(R.string.settings_permission_unrestricted),
                    notAllowedText = stringResource(R.string.settings_permission_restricted),
                    onOpen = { onOpenSystemSetting(SystemSetting.APP_DETAILS) },
                )
            }
        }
    }
}

/**
 * One permission: what it is for, a state chip (text, icon and colour), and "Open settings" as
 * the row's one action. TalkBack reads the text and the state as one item, then the button.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    allowed: Boolean?,
    onOpen: (() -> Unit)?,
    note: String? = null,
    allowedText: String = stringResource(R.string.settings_permission_allowed),
    notAllowedText: String = stringResource(R.string.settings_permission_not_allowed),
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SettingText(title = title, body = body)
                if (allowed != null) StateChip(ok = allowed, okText = allowedText, notOkText = notAllowedText)
                if (note != null) {
                    Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (onOpen != null) {
            val description = stringResource(R.string.settings_permission_open_description, title)
            TandemTextButton(
                text = stringResource(R.string.settings_permission_open),
                onClick = onOpen,
                trailingIcon = TandemIcons.OpenInNew,
                modifier = Modifier
                    .align(Alignment.End)
                    .semantics { contentDescription = description },
            )
        }
    }
}
