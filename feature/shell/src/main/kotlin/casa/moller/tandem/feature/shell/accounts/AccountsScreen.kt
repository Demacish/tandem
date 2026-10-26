package casa.moller.tandem.feature.shell.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.more.Avatar
import casa.moller.tandem.feature.shell.more.CompatibilityChip
import casa.moller.tandem.feature.shell.more.DetailLine
import casa.moller.tandem.feature.shell.settings.SettingsPage
import casa.moller.tandem.feature.shell.ui.SectionHeader
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow

/**
 * Mer → Konton och servrar (Phase 2, konto lane; decision B14, plan 6.2 "Konto/server", Stitch
 * konto_servrar with the corrections in docs/design/README.md): the active account with server,
 * space, household, role (a guest is shown clearly), version, login and unsent changes; the other
 * stored accounts with "Switch to …" and "Remove …"; "Add account" (the intro's login, 2.6 token
 * or 2.7 allauth); and "Log out of …". In demo mode there is no active account (demo mode is never
 * an account): a note leads to connecting a Tandoor, and stored accounts can be switched to.
 *
 * Every account's information is one TalkBack item; its buttons follow it. On wide windows the
 * page is the detail pane beside the More list (plan 6.5).
 */
@Composable
internal fun AccountsScreen(
    state: AccountsUiState,
    onAction: (AccountsAction) -> Unit,
    onAddAccount: () -> Unit,
    onConnectTandoor: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.accounts_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier.testTag(ACCOUNTS_SCREEN_TAG),
        snackbarHostState = snackbarHostState,
    ) {
        Text(
            text = stringResource(R.string.accounts_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isDemo) DemoNote(onConnectTandoor = onConnectTandoor)

        val active = state.active
        if (active != null) {
            SectionHeader(stringResource(R.string.accounts_section_active))
            AccountCard(item = active) {
                TandemPrimaryButton(
                    text = stringResource(R.string.accounts_sync_now),
                    onClick = { onAction(AccountsAction.SyncNow) },
                    leadingIcon = TandemIcons.Sync,
                    size = TandemButtonSize.Small,
                )
                // fix-ui: the place Diagnostik sends the user to for saving refused or waiting changes.
                if (active.pendingChanges > 0) {
                    TandemTextButton(
                        text = stringResource(R.string.accounts_export),
                        onClick = { onAction(AccountsAction.ExportPending) },
                        leadingIcon = TandemIcons.Share,
                        size = TandemButtonSize.Small,
                    )
                }
            }
        }

        if (!state.loading) {
            SectionHeader(stringResource(if (active != null) R.string.accounts_section_other else R.string.accounts_section_saved))
            if (state.others.isEmpty()) {
                Text(
                    text = stringResource(R.string.accounts_none_other),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TandemTheme.spacing.xs),
                )
            }
            state.others.forEach { item ->
                AccountCard(item = item) {
                    TandemSecondaryButton(
                        text = stringResource(R.string.accounts_switch_to, item.displayName),
                        onClick = { onAction(AccountsAction.SwitchTo(item.id)) },
                        enabled = !state.busy,
                        leadingIcon = TandemIcons.ArrowForward,
                        size = TandemButtonSize.Small,
                    )
                    TandemTextButton(
                        text = stringResource(R.string.accounts_remove_named, item.displayName),
                        onClick = { onAction(AccountsAction.RequestRemoval(item.id)) },
                        enabled = !state.busy,
                        leadingIcon = TandemIcons.Delete,
                        size = TandemButtonSize.Small,
                    )
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = TandemIcons.Add,
                title = stringResource(R.string.accounts_add),
                supporting = stringResource(R.string.accounts_add_body),
                onClick = if (state.isDemo) onConnectTandoor else onAddAccount,
            )
        }

        if (active != null) {
            TandemSecondaryButton(
                text = stringResource(R.string.shell_log_out_of, active.serverAddress),
                onClick = { onAction(AccountsAction.RequestRemoval(active.id)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                leadingIcon = TandemIcons.Logout,
            )
        }
    }

    state.removal?.let { prompt ->
        RemovalDialog(
            prompt = prompt,
            canSendNow = state.canSendNow && prompt.account.isActive,
            busy = state.busy,
            onExport = { onAction(AccountsAction.ExportPending) },
            onSendFirst = { onAction(AccountsAction.SyncNow) },
            onConfirm = { revoke -> onAction(AccountsAction.ConfirmRemoval(revoke)) },
            onDismiss = { onAction(AccountsAction.DismissRemoval) },
        )
    }
}

/** Demo mode: not an account; connect a Tandoor or switch to a saved account below. */
@Composable
private fun DemoNote(onConnectTandoor: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(R.string.accounts_demo_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = stringResource(R.string.accounts_demo_body), style = MaterialTheme.typography.bodyMedium)
            }
            TandemPrimaryButton(
                text = stringResource(R.string.accounts_connect),
                onClick = onConnectTandoor,
                size = TandemButtonSize.Small,
            )
        }
    }
}

/**
 * One stored account: its information as ONE TalkBack item (name, server, space, household, role,
 * version, login, unsent changes), then [actions] (wrapping at large font sizes).
 */
@Composable
private fun AccountCard(item: AccountItem, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ACCOUNT_CARD_TAG_PREFIX + item.id),
        shape = MaterialTheme.shapes.largeIncreased,
        color = if (item.isActive) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = item.displayName)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.shell_account_signed_in_as, item.username),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AccountFacts(item)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

/** The lines of an account card (part of its one TalkBack item). */
@Composable
private fun AccountFacts(item: AccountItem) {
    val spacing = TandemTheme.spacing
    if (item.isActive || item.isGuest) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (item.isActive) {
                StatusChip(label = stringResource(R.string.accounts_active_chip), icon = TandemIcons.CheckCircle, tone = TandemTone.Success)
            }
            if (item.isGuest) {
                StatusChip(label = stringResource(R.string.accounts_role_guest), icon = TandemIcons.Lock, tone = TandemTone.Warning)
            }
        }
    }
    DetailLine(
        icon = TandemIcons.Dns,
        label = stringResource(R.string.shell_account_server),
        value = item.serverUrl,
        mergeDescendants = false,
    )
    item.spaceName?.let {
        DetailLine(icon = TandemIcons.Home, label = stringResource(R.string.shell_account_space), value = it, mergeDescendants = false)
    }
    item.householdName?.let {
        DetailLine(icon = TandemIcons.HomeFilled, label = stringResource(R.string.accounts_household), value = it, mergeDescendants = false)
    }
    roleLabel(item.role)?.let { role ->
        DetailLine(
            icon = if (item.isGuest) TandemIcons.Lock else TandemIcons.Shield,
            label = stringResource(R.string.accounts_role),
            value = if (item.isGuest) stringResource(R.string.accounts_role_guest_body) else role,
            maxLines = Int.MAX_VALUE,
            mergeDescendants = false,
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.serverVersion?.let { stringResource(R.string.shell_account_version, it) }
                ?: stringResource(R.string.shell_account_version_unknown),
            style = MaterialTheme.typography.titleMedium,
        )
        CompatibilityChip(item.compatibility)
    }
    if (item.isActive) {
        DetailLine(
            icon = TandemIcons.Key,
            label = stringResource(R.string.accounts_login),
            value = stringResource(
                when (item.tokenOrigin) {
                    TokenOrigin.CREATED -> R.string.accounts_token_created
                    TokenOrigin.PASTED -> R.string.accounts_token_pasted
                },
            ),
            mergeDescendants = false,
        )
    }
    if (item.pendingChanges > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(TandemIcons.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = pluralStringResource(R.plurals.accounts_pending, item.pendingChanges, item.pendingChanges),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** The role in the user's words, null while unknown. */
@Composable
internal fun roleLabel(role: SpaceRole): String? = when (role) {
    SpaceRole.ADMIN -> stringResource(R.string.accounts_role_admin)
    SpaceRole.USER -> stringResource(R.string.accounts_role_user)
    SpaceRole.GUEST -> stringResource(R.string.accounts_role_guest)
    SpaceRole.UNKNOWN -> null
}

/**
 * "Remove account?" (for the active account "Log out?", the 0.2.0 logout dialog's rules): what is
 * removed, unsent changes with "Export changes" (and "Send first" while a sync could send them),
 * and for a token Tandem created the choice to revoke it. A pasted token is never revoked.
 */
@Composable
private fun RemovalDialog(
    prompt: RemovalPrompt,
    canSendNow: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onSendFirst: () -> Unit,
    onConfirm: (revokeToken: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val account = prompt.account
    val canRevoke = account.tokenOrigin == TokenOrigin.CREATED
    var revoke by rememberSaveable { mutableStateOf(true) }
    val spacing = TandemTheme.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (account.isActive) TandemIcons.Logout else TandemIcons.Delete, contentDescription = null) },
        title = { Text(stringResource(if (account.isActive) R.string.shell_logout_title else R.string.accounts_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                if (prompt.warnedPending > 0) {
                    UnsentChangesWarning(
                        count = prompt.warnedPending,
                        body = stringResource(
                            if (account.isActive) R.string.shell_logout_pending_body else R.string.accounts_remove_pending_body,
                        ),
                        canSendNow = canSendNow,
                        onExport = onExport,
                        onSendFirst = onSendFirst,
                    )
                }
                Text(
                    if (account.isActive) {
                        stringResource(if (prompt.warnedPending > 0) R.string.shell_logout_body_pending else R.string.shell_logout_body)
                    } else {
                        stringResource(R.string.accounts_remove_body, account.displayName, account.serverAddress)
                    },
                )
                if (canRevoke) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TandemTheme.sizes.minTouchTarget)
                            .toggleable(value = revoke, role = Role.Checkbox, onValueChange = { revoke = it }),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Checkbox(checked = revoke, onCheckedChange = null)
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                            Text(text = stringResource(R.string.shell_logout_revoke), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.shell_logout_revoke_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.shell_logout_pasted_token_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TandemPrimaryButton(
                text = stringResource(if (account.isActive) R.string.shell_logout_confirm else R.string.accounts_remove_confirm),
                onClick = { onConfirm(canRevoke && revoke) },
                enabled = !busy,
                size = TandemButtonSize.Small,
            )
        },
        dismissButton = {
            TandemSecondaryButton(text = stringResource(R.string.shell_cancel), onClick = onDismiss, size = TandemButtonSize.Small)
        },
    )
}

/** "2 changes have not been sent", what removing does to them, "Export changes" and "Send first". */
@Composable
internal fun UnsentChangesWarning(count: Int, body: String, canSendNow: Boolean, onExport: (() -> Unit)?, onSendFirst: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.testTag(UNSENT_WARNING_TAG),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(modifier = Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(TandemIcons.Warning, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(
                        text = pluralStringResource(R.plurals.shell_logout_pending, count, count),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (onExport != null) {
                    TandemPrimaryButton(
                        text = stringResource(R.string.accounts_export),
                        onClick = onExport,
                        size = TandemButtonSize.Small,
                        leadingIcon = TandemIcons.Share,
                    )
                }
                if (canSendNow) {
                    TandemPrimaryButton(
                        text = stringResource(R.string.shell_logout_send_first),
                        onClick = onSendFirst,
                        size = TandemButtonSize.Small,
                        leadingIcon = TandemIcons.Sync,
                    )
                }
            }
        }
    }
}

/** Test tag of the accounts page. */
internal const val ACCOUNTS_SCREEN_TAG = "accounts_screen"

/** Test tag prefix of an account card; the suffix is the account id. */
internal const val ACCOUNT_CARD_TAG_PREFIX = "accounts_card:"

/** Test tag of the unsent-changes warning in the removal (and logout) dialog. */
internal const val UNSENT_WARNING_TAG = "accounts_unsent_warning"
