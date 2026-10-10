package casa.moller.tandem.feature.shell.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.core.ui.DemoBanner
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.shell.AccountSummary
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellActions
import casa.moller.tandem.feature.shell.ShellMode
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SectionHeader
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold

/**
 * "Mer" in Phase 0 (plan 6.2 "Inställningar", design mer_inst_llningar with the corrections in
 * docs/design/README.md): the account with server address and the Tandoor version (checked again
 * by pull-to-refresh), language, theme, About and logout. In demo mode the account card is
 * replaced by the demo banner. [crashNotice] (the app's "Tandem crashed last time" card, plan
 * 4.2 p7) comes first when there is a crash log.
 */
@Composable
internal fun MoreScreen(
    state: ShellUiState,
    actions: ShellActions,
    language: AppLanguage,
    onOpenLanguage: () -> Unit,
    onOpenAbout: () -> Unit,
    onConnectTandoor: () -> Unit,
    modifier: Modifier = Modifier,
    crashNotice: @Composable () -> Unit = {},
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val spacing = TandemTheme.spacing
    val connected = state.mode as? ShellMode.Connected

    ShellScreenScaffold(title = stringResource(R.string.shell_tab_more), state = state, modifier = modifier) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refreshServerInfo,
            modifier = Modifier.fillMaxSize().padding(padding),
            enabled = connected != null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
                    crashNotice()
                    when (val mode = state.mode) {
                        is ShellMode.Connected -> AccountCard(
                            account = mode.account,
                            state = state,
                            onCheckAgain = actions::refreshServerInfo,
                        )

                        ShellMode.Demo -> DemoBanner(onConnect = onConnectTandoor)

                        ShellMode.Unavailable -> Unit
                    }

                    SectionHeader(stringResource(R.string.shell_more_section_app))
                    SettingsGroup {
                        SettingsRow(
                            icon = TandemIcons.Language,
                            title = stringResource(R.string.shell_language),
                            supporting = languageLabel(language),
                            onClick = onOpenLanguage,
                        )
                        GroupDivider()
                        ThemeSetting(selected = state.themeMode, onSelect = actions::setThemeMode)
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.Info,
                            title = stringResource(R.string.shell_about),
                            supporting = stringResource(R.string.shell_about_version, state.appVersion.name),
                            onClick = onOpenAbout,
                        )
                    }

                    if (connected != null) {
                        TandemSecondaryButton(
                            text = stringResource(R.string.shell_log_out_of, connected.account.serverAddress),
                            onClick = { confirmLogout = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loggingOut,
                            leadingIcon = TandemIcons.Logout,
                        )
                    }
                }
            }
        }
    }

    if (confirmLogout && connected != null) {
        LogoutDialog(
            tokenOrigin = connected.account.tokenOrigin,
            onConfirm = { revoke ->
                confirmLogout = false
                actions.logout(revoke)
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** Account, server and the server's Tandoor version (Fas 0 "Klart när" #3). */
@Composable
private fun AccountCard(account: AccountSummary, state: ShellUiState, onCheckAgain: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(name = account.displayName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.shell_account_signed_in_as, account.username),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DetailLine(icon = TandemIcons.Dns, label = stringResource(R.string.shell_account_server), value = account.serverUrl)
            account.spaceName?.let {
                DetailLine(icon = TandemIcons.Home, label = stringResource(R.string.shell_account_space), value = it)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account.serverVersion?.let { stringResource(R.string.shell_account_version, it) }
                        ?: stringResource(R.string.shell_account_version_unknown),
                    style = MaterialTheme.typography.titleMedium,
                )
                CompatibilityChip(account.compatibility)
            }
            if (account.compatibility.isSafeMode) {
                Text(
                    text = stringResource(R.string.shell_safe_mode_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val error = state.refreshError
            when {
                state.refreshing -> Text(
                    text = stringResource(R.string.shell_account_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val text = error.toErrorText()
                    Icon(text.icon, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = stringResource(R.string.shell_account_check_failed, text.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TandemTextButton(
                        text = stringResource(R.string.shell_account_check_again),
                        onClick = onCheckAgain,
                    )
                }
            }
        }
    }
}

/** First letter of the name on a tomato circle; decorative (the name is next to it). */
@Composable
private fun Avatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DetailLine(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompatibilityChip(compatibility: ServerCompatibility) {
    when (compatibility) {
        ServerCompatibility.SUPPORTED -> StatusChip(
            label = stringResource(R.string.shell_compat_supported),
            contentDescription = stringResource(R.string.shell_compat_supported_description),
            icon = TandemIcons.CheckCircle,
            tone = TandemTone.Success,
        )

        ServerCompatibility.UNTESTED_NEWER,
        ServerCompatibility.UNTESTED_OLDER,
        ServerCompatibility.UNRECOGNIZED,
        -> AppStatusChip(AppStatus.SafeMode)

        ServerCompatibility.TOO_OLD -> StatusChip(
            label = stringResource(R.string.shell_compat_too_old),
            contentDescription = stringResource(R.string.shell_compat_too_old_description),
            icon = TandemIcons.Error,
            tone = TandemTone.Error,
        )
    }
}

/** Tema: System / Ljust / Mörkt as one segmented choice (plan 6.4). */
@Composable
private fun ThemeSetting(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TandemIcons.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.shell_theme),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    modifier = Modifier.weight(1f).heightIn(min = TandemTheme.sizes.minTouchTarget),
                    label = { Text(text = stringResource(mode.label())) },
                )
            }
        }
    }
}

private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.shell_theme_system
    ThemeMode.LIGHT -> R.string.shell_theme_light
    ThemeMode.DARK -> R.string.shell_theme_dark
}

@Composable
internal fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.shell_language_system
        AppLanguage.ENGLISH -> R.string.shell_language_name_en
        AppLanguage.SWEDISH -> R.string.shell_language_name_sv
    },
)

@Composable
private fun LogoutDialog(tokenOrigin: TokenOrigin, onConfirm: (revokeToken: Boolean) -> Unit, onDismiss: () -> Unit) {
    // A token Tandem created: revoked by default (plan 4.2 step 4), only this device's token.
    // A pasted token belongs to the user and may be in use elsewhere: the app never deletes it,
    // so there is no checkbox, only a note.
    val canRevoke = tokenOrigin == TokenOrigin.CREATED
    var revoke by rememberSaveable { mutableStateOf(true) }
    val spacing = TandemTheme.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(TandemIcons.Logout, contentDescription = null) },
        title = { Text(stringResource(R.string.shell_logout_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(stringResource(R.string.shell_logout_body))
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
            TextButton(onClick = { onConfirm(canRevoke && revoke) }) { Text(stringResource(R.string.shell_logout_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) }
        },
    )
}
