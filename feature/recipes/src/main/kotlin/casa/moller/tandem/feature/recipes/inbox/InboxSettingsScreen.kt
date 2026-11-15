package casa.moller.tandem.feature.recipes.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.FormatListBulleted
import casa.moller.tandem.core.designsystem.icon.Inbox
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Tune
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.recipes.R

/** Test tag of the scrolling column. */
const val INBOX_SETTINGS_CONTENT_TAG = "recipes:inbox-settings:content"

/** Test tag of "Anslut". */
const val INBOX_SETTINGS_CONNECT_TAG = "recipes:inbox-settings:connect"

/** Test tag of one category's row: `recipes:inbox-settings:category:<id>`. */
fun inboxCategoryTag(id: Long): String = "recipes:inbox-settings:category:$id"

/** Test tag of "Spara kategorier". */
const val INBOX_SETTINGS_SAVE_TAG = "recipes:inbox-settings:save"

/**
 * Mer → "Inkorg för recept" (Fas 5, rss lane, plan §7 #35), stateless. One column at the reading width
 * (a detail pane beside Mer on wide windows), like the other settings pages:
 *
 * - **Not connected:** what the inbox is, the address and the API key (hidden, with show/hide), where
 *   the key is made in Miniflux, "Anslut", and why a connection failed.
 * - **Connected:** the server with "Koppla från" (asks first), the categories with their unread
 *   counts as checkboxes and "Spara kategorier", the switches "Markera som läst efter import" and
 *   "Visa på Idag", that it refreshes in the background, and "Öppna inkorgen".
 * - **Demo mode / no account:** an explanation.
 *
 * The content keeps clear of the keyboard (edge-to-edge).
 */
@Composable
fun InboxSettingsScreen(
    state: InboxSettingsUiState,
    onAction: (InboxSettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.inbox_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag(INBOX_SETTINGS_CONTENT_TAG)
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .padding(bottom = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.inbox_settings_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.availability) {
                    InboxAvailability.DEMO -> InfoCard(
                        title = stringResource(R.string.inbox_settings_demo_title),
                        body = stringResource(R.string.inbox_settings_demo_body),
                    )

                    InboxAvailability.NO_ACCOUNT, InboxAvailability.LOADING -> InfoCard(
                        title = stringResource(R.string.inbox_settings_no_account_title),
                        body = stringResource(R.string.inbox_settings_no_account_body),
                    )

                    InboxAvailability.AVAILABLE -> if (state.settings.connected) {
                        ConnectedGroups(state = state, onAction = onAction)
                    } else {
                        ConnectGroup(state = state, onAction = onAction)
                    }
                }
            }
        }
    }
    if (state.confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { onAction(InboxSettingsAction.DismissDialog) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text(stringResource(R.string.inbox_settings_disconnect_title)) },
            text = { Text(stringResource(R.string.inbox_settings_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(InboxSettingsAction.ConfirmDisconnect) }) {
                    Text(stringResource(R.string.inbox_settings_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(InboxSettingsAction.DismissDialog) }) {
                    Text(stringResource(R.string.inbox_settings_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectGroup(state: InboxSettingsUiState, onAction: (InboxSettingsAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Group {
        GroupHeading(stringResource(R.string.inbox_settings_connect_heading), TandemIcons.Inbox)
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            TandemTextField(
                value = state.address,
                onValueChange = { onAction(InboxSettingsAction.SetAddress(it)) },
                label = stringResource(R.string.inbox_settings_address),
                type = TandemTextFieldType.Url,
                placeholder = stringResource(R.string.inbox_settings_address_placeholder),
                isError = state.connectProblem == InboxProblem.INVALID_URL,
                enabled = state.busy == null,
                modifier = Modifier.fillMaxWidth(),
            )
            TandemTextField(
                value = state.apiKey,
                onValueChange = { onAction(InboxSettingsAction.SetApiKey(it)) },
                label = stringResource(R.string.inbox_settings_key),
                type = TandemTextFieldType.Password,
                leadingIcon = TandemIcons.Key,
                supportingText = stringResource(R.string.inbox_settings_key_help),
                enabled = state.busy == null,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onAction(InboxSettingsAction.Connect) }),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TandemPrimaryButton(
                    text = stringResource(R.string.inbox_settings_connect),
                    onClick = { onAction(InboxSettingsAction.Connect) },
                    enabled = state.canConnect,
                    modifier = Modifier.testTag(INBOX_SETTINGS_CONNECT_TAG),
                )
                if (state.busy == InboxSettingsBusy.CONNECT) TandemLoadingIndicator()
            }
            Text(
                text = stringResource(R.string.inbox_settings_key_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val problem = state.connectProblem
    if (problem == InboxProblem.LOCAL_NETWORK && !state.secureStorageFailed) {
        LocalNetworkCallout(refused = state.localNetworkRefused, onAction = onAction)
    } else if (problem != null) {
        CalloutCard(
            title = stringResource(R.string.inbox_problem_title),
            body = stringResource(if (state.secureStorageFailed) R.string.inbox_settings_secure_storage else problem.text),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Fas 5 (sdk37 lane): the Miniflux address is on the local network, which Android 17 opens only
 * with "Nearby devices": why, and "Allow" (Android's dialog), or "Open settings" after a refusal.
 */
@Composable
private fun LocalNetworkCallout(refused: Boolean, onAction: (InboxSettingsAction) -> Unit) {
    CalloutCard(
        title = stringResource(R.string.inbox_problem_title),
        body = stringResource(R.string.inbox_problem_local_network),
        icon = TandemIcons.Shield,
        tone = TandemTone.Warning,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        actions = {
            if (refused) {
                TandemTonalButton(
                    text = stringResource(R.string.inbox_settings_local_network_settings),
                    onClick = { onAction(InboxSettingsAction.OpenAppSettings) },
                    trailingIcon = TandemIcons.OpenInNew,
                )
            } else {
                TandemPrimaryButton(
                    text = stringResource(R.string.inbox_settings_local_network_allow),
                    onClick = { onAction(InboxSettingsAction.AllowLocalNetwork) },
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedGroups(state: InboxSettingsUiState, onAction: (InboxSettingsAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val idle = state.busy == null
    Group {
        GroupHeading(stringResource(R.string.inbox_settings_server_heading), TandemIcons.Inbox)
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.inbox_settings_connected_to, state.settings.serverUrl.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.settings.enabled) {
                    TandemTonalButton(
                        text = stringResource(R.string.inbox_settings_open_inbox),
                        onClick = { onAction(InboxSettingsAction.OpenInbox) },
                        leadingIcon = TandemIcons.Inbox,
                    )
                }
                TandemTextButton(
                    text = stringResource(R.string.inbox_settings_disconnect),
                    onClick = { onAction(InboxSettingsAction.AskDisconnect) },
                    enabled = idle,
                )
            }
        }
    }
    Group {
        GroupHeading(stringResource(R.string.inbox_settings_categories_heading), TandemIcons.FormatListBulleted)
        Text(
            text = stringResource(R.string.inbox_settings_categories_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.lg),
        )
        state.categoryChoices.forEach { category ->
            CategoryRow(
                category = category,
                chosen = category.id in state.selected,
                enabled = idle,
                onChange = { onAction(InboxSettingsAction.ToggleCategory(category.id, it)) },
            )
        }
        Column(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            state.categoriesProblem?.let { problem ->
                Text(
                    text = stringResource(R.string.inbox_settings_categories_failed) + " " + stringResource(problem.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.selected.isEmpty()) {
                Text(
                    text = stringResource(R.string.inbox_settings_none_chosen),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TandemPrimaryButton(
                    text = stringResource(R.string.inbox_settings_categories_save),
                    onClick = { onAction(InboxSettingsAction.SaveCategories) },
                    enabled = idle && state.selectionChanged,
                    modifier = Modifier.testTag(INBOX_SETTINGS_SAVE_TAG),
                )
                if (state.categoriesProblem != null) {
                    TandemSecondaryButton(
                        text = stringResource(R.string.inbox_settings_retry),
                        onClick = { onAction(InboxSettingsAction.RetryCategories) },
                        leadingIcon = TandemIcons.Refresh,
                        enabled = idle,
                    )
                }
                if (state.busy == InboxSettingsBusy.CATEGORIES || state.busy == InboxSettingsBusy.SAVE) TandemLoadingIndicator()
            }
        }
    }
    Group {
        GroupHeading(stringResource(R.string.inbox_settings_options_heading), TandemIcons.Tune)
        SwitchRow(
            title = stringResource(R.string.inbox_settings_mark_read),
            body = stringResource(R.string.inbox_settings_mark_read_body),
            checked = state.settings.markReadOnImport,
            onChange = { onAction(InboxSettingsAction.SetMarkRead(it)) },
        )
        Divider()
        SwitchRow(
            title = stringResource(R.string.inbox_settings_today),
            body = stringResource(R.string.inbox_settings_today_body),
            checked = state.settings.showOnToday,
            onChange = { onAction(InboxSettingsAction.SetShowOnToday(it)) },
        )
        Text(
            text = stringResource(R.string.inbox_settings_background),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.lg),
        )
    }
}

/** One category: TalkBack reads its name, unread count and state as one item with the role "checkbox". */
@Composable
private fun CategoryRow(category: InboxCategory, chosen: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .testTag(inboxCategoryTag(category.id)),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = chosen, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = category.title, style = MaterialTheme.typography.bodyLarge)
            category.unread?.let { unread ->
                Text(
                    text = pluralStringResource(R.plurals.inbox_settings_unread, unread, unread),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One switch; TalkBack reads title, body and state as one item with the role "switch". */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupHeading(text: String, icon: ImageVector) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
}
