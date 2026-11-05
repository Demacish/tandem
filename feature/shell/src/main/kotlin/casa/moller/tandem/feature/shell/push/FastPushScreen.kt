package casa.moller.tandem.feature.shell.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.icon.NotificationsActive
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.push.FastPushAvailability
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.GroupHeading
import casa.moller.tandem.feature.shell.settings.RadioRow
import casa.moller.tandem.feature.shell.settings.SettingText
import casa.moller.tandem.feature.shell.settings.SettingsPage
import casa.moller.tandem.feature.shell.settings.formatDateTime
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup

/** Test tag of the service address field. */
internal const val FAST_PUSH_URL_TAG = "fast_push_url"

/**
 * Mer → Synk och lagring → **Snabb push (egen tjänst, experimentell)** (Fas 4, push lane; plan
 * §4.3, B11; docs/PUSH.md): what it is, the status, the service's address, the distributor app
 * (with what to install when there is none), turn on/off and "Send test notification". Demo mode,
 * guests and logged-out show only why it is not available. Stateless; [FastPushViewModel] drives it.
 */
@Composable
internal fun FastPushScreen(
    state: FastPushUiState,
    onAction: (FastPushAction) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.fast_push_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier.imePadding(),
        snackbarHostState = snackbarHostState,
    ) {
        when (state.availability) {
            FastPushAvailability.AVAILABLE -> if (!state.loading) Available(state, onAction, onOpenPermissions)
            FastPushAvailability.DEMO -> Unavailable(R.string.fast_push_demo)
            FastPushAvailability.GUEST -> Unavailable(R.string.fast_push_guest)
            FastPushAvailability.NO_ACCOUNT -> Unavailable(R.string.fast_push_no_account)
        }
    }
}

@Composable
private fun Unavailable(body: Int) {
    InfoCard(title = stringResource(R.string.fast_push_title), body = stringResource(body))
}

@Composable
private fun Available(state: FastPushUiState, onAction: (FastPushAction) -> Unit, onOpenPermissions: () -> Unit) {
    InfoCard(
        title = stringResource(R.string.fast_push_intro_title),
        body = stringResource(R.string.fast_push_intro_body),
        icon = TandemIcons.Science,
    )
    Status(state, onOpenPermissions)
    Service(state, onAction)
    Distributors(state, onAction)
    Buttons(state, onAction)
    Text(
        text = stringResource(R.string.fast_push_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg),
    )
}

/** Where the registration stands, one TalkBack item; a failure or a blocked notification below. */
@Composable
private fun Status(state: FastPushUiState, onOpenPermissions: () -> Unit) {
    val spacing = TandemTheme.spacing
    SettingsGroup {
        GroupHeading(text = stringResource(R.string.fast_push_status_heading), icon = TandemIcons.Sync)
        val title = when (state.status) {
            FastPushStatus.OFF -> stringResource(R.string.fast_push_status_off)
            FastPushStatus.REGISTERING -> stringResource(R.string.fast_push_status_registering)
            FastPushStatus.ACTIVE -> stringResource(R.string.fast_push_status_active)
            FastPushStatus.FAILED -> stringResource(R.string.fast_push_status_failed)
        }
        val lines = buildList {
            when (state.status) {
                FastPushStatus.OFF -> add(stringResource(R.string.fast_push_status_off_body))
                FastPushStatus.REGISTERING -> add(stringResource(R.string.fast_push_status_registering_body))
                FastPushStatus.ACTIVE -> state.pollSeconds?.let {
                    add(pluralStringResource(R.plurals.fast_push_status_active_body, it, it))
                }

                FastPushStatus.FAILED -> add(stringResource(explanationOf(state.error)))
            }
            state.lastWakeUp?.let { add(stringResource(R.string.fast_push_last_wake_up, formatDateTime(it))) }
        }
        SettingText(
            title = title,
            body = lines.joinToString("\n").ifEmpty { null },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        )
        if (state.isOn && (!state.notificationsAllowed || !state.listNotificationsOn)) {
            GroupDivider()
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(
                        if (!state.notificationsAllowed) R.string.fast_push_notifications_blocked else R.string.fast_push_list_notifications_off,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!state.notificationsAllowed) {
                    TandemTextButton(
                        text = stringResource(R.string.fast_push_open_permissions),
                        onClick = onOpenPermissions,
                        leadingIcon = TandemIcons.NotificationsActive,
                    )
                }
            }
        }
    }
}

/** The service's address. Locked while push is on (turn it off to change it). */
@Composable
private fun Service(state: FastPushUiState, onAction: (FastPushAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val focus = LocalFocusManager.current
    SettingsGroup {
        GroupHeading(text = stringResource(R.string.fast_push_service_heading))
        TandemTextField(
            value = state.url,
            onValueChange = { onAction(FastPushAction.UrlChanged(it)) },
            label = stringResource(R.string.fast_push_service_url),
            type = TandemTextFieldType.Url,
            placeholder = stringResource(R.string.fast_push_service_url_placeholder),
            supportingText = stringResource(
                if (state.urlInvalid) R.string.fast_push_error_invalid_url else R.string.fast_push_service_url_help,
            ),
            isError = state.urlInvalid,
            enabled = !state.isOn && !state.busy,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg)
                .testTag(FAST_PUSH_URL_TAG),
        )
    }
}

/** The UnifiedPush distributor apps, or what to install when there is none. */
@Composable
private fun Distributors(state: FastPushUiState, onAction: (FastPushAction) -> Unit) {
    val spacing = TandemTheme.spacing
    SettingsGroup(modifier = Modifier.selectableGroup()) {
        GroupHeading(text = stringResource(R.string.fast_push_distributor_heading))
        if (state.distributors.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(text = stringResource(R.string.fast_push_distributor_none), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.fast_push_distributor_none_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TandemTextButton(
                text = stringResource(R.string.fast_push_distributor_refresh),
                onClick = { onAction(FastPushAction.Refresh) },
                modifier = Modifier.padding(start = spacing.sm, bottom = spacing.sm),
            )
        } else {
            state.distributors.forEachIndexed { index, distributor ->
                if (index > 0) GroupDivider()
                RadioRow(
                    title = distributor.label,
                    selected = distributor.packageName == state.selectedDistributor,
                    onClick = { onAction(FastPushAction.SelectDistributor(distributor.packageName)) },
                    enabled = !state.isOn && !state.busy,
                )
            }
            Text(
                text = stringResource(R.string.fast_push_distributor_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(spacing.lg),
            )
        }
    }
}

@Composable
private fun Buttons(state: FastPushUiState, onAction: (FastPushAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (!state.isOn) {
            TandemPrimaryButton(
                text = stringResource(
                    if (state.status == FastPushStatus.FAILED) R.string.fast_push_try_again else R.string.fast_push_enable,
                ),
                onClick = { onAction(FastPushAction.Enable) },
                enabled = state.canEnable,
                leadingIcon = TandemIcons.NotificationsActive,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.status == FastPushStatus.ACTIVE && state.testSupported) {
            TandemTonalButton(
                text = stringResource(R.string.fast_push_send_test),
                onClick = { onAction(FastPushAction.SendTest) },
                enabled = state.canTest,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.status != FastPushStatus.OFF) {
            TandemSecondaryButton(
                text = stringResource(R.string.fast_push_disable),
                onClick = { onAction(FastPushAction.Disable) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The longer explanation of a failure, shown under the status. */
internal fun explanationOf(error: FastPushError?): Int = when (error) {
    null -> R.string.fast_push_error_service
    else -> messageOf(error)
}
