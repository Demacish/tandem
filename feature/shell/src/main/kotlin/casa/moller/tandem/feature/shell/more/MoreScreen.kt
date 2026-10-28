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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.CollectionsBookmark
import casa.moller.tandem.core.designsystem.icon.Restaurant
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.core.ui.DemoBanner
import casa.moller.tandem.core.ui.household.HouseholdMissingCard
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.recipes.navigation.BooksKey
import casa.moller.tandem.feature.shell.AccountSummary
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.SettingsSummary
import casa.moller.tandem.feature.shell.ShellActions
import casa.moller.tandem.feature.shell.ShellMode
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.accounts.roleLabel
import casa.moller.tandem.feature.shell.navigation.AboutKey
import casa.moller.tandem.feature.shell.navigation.AccountsKey
import casa.moller.tandem.feature.shell.navigation.AppearanceSettingsKey
import casa.moller.tandem.feature.shell.navigation.CookingSettingsKey
import casa.moller.tandem.feature.shell.navigation.DiagnosticsKey
import casa.moller.tandem.feature.shell.navigation.LanguageKey
import casa.moller.tandem.feature.shell.navigation.PermissionsKey
import casa.moller.tandem.feature.shell.navigation.SyncSettingsKey
import casa.moller.tandem.feature.shell.settings.label
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SectionHeader
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow
import casa.moller.tandem.feature.shell.ui.ShellScreenScaffold

/**
 * "Mer" (plan 6.2 "Inställningar", design mer_inst_llningar with the corrections in
 * docs/design/README.md): the account with server address, space and the Tandoor version (checked
 * again by pull-to-refresh), the settings sub-screens with a short summary each (Phase 1b: sync
 * and storage, appearance, cook mode and timers, permissions, language), diagnostics, About and
 * logout. [onOpenPage] opens a sub-screen's key; on wide windows the shell shows it beside this
 * list (design mer_inst_llningar_surfplatta) and [selectedPage] is highlighted.
 *
 * In demo mode the account card is replaced by the demo banner, and "Reset demo data" puts the
 * sample recipes back. [crashNotice] (the app's "Tandem crashed last time" card, plan 4.2 p7)
 * comes first when there is a crash log.
 */
@Composable
internal fun MoreScreen(
    state: ShellUiState,
    actions: ShellActions,
    language: AppLanguage,
    onOpenPage: (NavKey) -> Unit,
    onConnectTandoor: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPage: NavKey? = null,
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

                    // Phase 2 (konto lane): Mer → Konton och servrar (also in demo mode with stored accounts).
                    if (connected != null || state.storedAccounts > 0) {
                        SettingsGroup {
                            SettingsRow(
                                icon = TandemIcons.Dns,
                                title = stringResource(R.string.accounts_more_row),
                                supporting = state.storedAccounts.takeIf { it > 0 }?.let { count ->
                                    pluralStringResource(R.plurals.accounts_more_row_count, count, count)
                                },
                                onClick = { onOpenPage(AccountsKey) },
                                selected = selectedPage == AccountsKey,
                            )
                        }
                    }

                    // Phase 2 (books lane): Mer → Receptböcker (plan 6.1 "Mer: Böcker").
                    SettingsGroup {
                        SettingsRow(
                            icon = TandemIcons.CollectionsBookmark,
                            title = stringResource(R.string.shell_more_books),
                            supporting = stringResource(R.string.shell_more_books_supporting),
                            onClick = { onOpenPage(BooksKey) },
                            selected = selectedPage == BooksKey,
                        )
                    }

                    // Phase 1b (inställningar lane): the settings sub-screens (plan 6.2 "Inställningar").
                    SectionHeader(stringResource(R.string.settings_section))
                    SettingsGroup {
                        SettingsRow(
                            icon = TandemIcons.Sync,
                            title = stringResource(R.string.settings_sync),
                            supporting = syncSummary(state.settings),
                            onClick = { onOpenPage(SyncSettingsKey) },
                            selected = selectedPage == SyncSettingsKey,
                        )
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.DarkMode,
                            title = stringResource(R.string.settings_appearance),
                            supporting = stringResource(
                                if (state.settings.dynamicColor) {
                                    R.string.settings_appearance_summary_dynamic
                                } else {
                                    R.string.settings_appearance_summary_brand
                                },
                                stringResource(state.themeMode.label()),
                            ),
                            onClick = { onOpenPage(AppearanceSettingsKey) },
                            selected = selectedPage == AppearanceSettingsKey,
                        )
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.Restaurant,
                            title = stringResource(R.string.settings_cooking),
                            supporting = stringResource(
                                when (state.settings.timerMode) {
                                    TimerMode.IN_APP -> R.string.settings_cooking_summary_in_app
                                    TimerMode.CLOCK_APP -> R.string.settings_cooking_summary_clock_app
                                },
                            ),
                            onClick = { onOpenPage(CookingSettingsKey) },
                            selected = selectedPage == CookingSettingsKey,
                        )
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.Shield,
                            title = stringResource(R.string.settings_permissions),
                            supporting = stringResource(
                                if (state.settings.timersNeedAttention) {
                                    R.string.settings_permissions_summary_attention
                                } else {
                                    R.string.settings_permissions_summary_ok
                                },
                            ),
                            onClick = { onOpenPage(PermissionsKey) },
                            selected = selectedPage == PermissionsKey,
                        )
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.Language,
                            title = stringResource(R.string.shell_language),
                            supporting = languageLabel(language),
                            onClick = { onOpenPage(LanguageKey) },
                            selected = selectedPage == LanguageKey,
                        )
                    }

                    if (state.isDemo) {
                        SectionHeader(stringResource(R.string.shell_more_section_demo))
                        SettingsGroup {
                            SettingsRow(
                                icon = TandemIcons.Refresh,
                                title = stringResource(R.string.shell_reset_demo_data),
                                supporting = stringResource(R.string.shell_reset_demo_data_body),
                                onClick = actions::resetDemoData,
                            )
                        }
                    }

                    SectionHeader(stringResource(R.string.settings_section_about))
                    SettingsGroup {
                        SettingsRow(
                            icon = TandemIcons.Science,
                            title = stringResource(R.string.settings_diagnostics),
                            supporting = stringResource(R.string.settings_diagnostics_summary),
                            onClick = { onOpenPage(DiagnosticsKey) },
                            selected = selectedPage == DiagnosticsKey,
                        )
                        GroupDivider()
                        SettingsRow(
                            icon = TandemIcons.Info,
                            title = stringResource(R.string.shell_about),
                            supporting = stringResource(R.string.shell_about_version, state.appVersion.name),
                            onClick = { onOpenPage(AboutKey) },
                            selected = selectedPage == AboutKey,
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
            pendingChanges = state.pendingChanges,
            canSendNow = !state.syncOffline && !state.loginExpired && !state.isSafeMode,
            onSendFirst = actions::retrySync,
            onExport = actions::exportPendingChanges,
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
            // Phase 2 (konto lane): the household (plan 8.6) and the role; a guest is shown clearly (#42).
            state.household?.let {
                DetailLine(icon = TandemIcons.HomeFilled, label = stringResource(R.string.accounts_household), value = it)
            }
            // 0.3.0 review (fix-data, plan 6.2 "kontroll av hushåll", 8.6).
            if (state.householdMissing) HouseholdMissingCard()
            if (state.role == SpaceRole.GUEST) {
                StatusChip(label = stringResource(R.string.accounts_role_guest), icon = TandemIcons.Lock, tone = TandemTone.Warning)
            } else {
                roleLabel(state.role)?.let {
                    DetailLine(icon = TandemIcons.Shield, label = stringResource(R.string.accounts_role), value = it)
                }
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
            if (state.serverFeatures.isNotEmpty()) {
                ServerFeatures(features = state.serverFeatures)
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

/**
 * What Tandem found on the server (plan 3.6 capability map, §6.2 "Konto/server" kapabiliteter):
 * the optional features in plain words, so a missing one (e.g. AI import switched off in the
 * space) explains a hidden button. Never the API's own names.
 */
@Composable
private fun ServerFeatures(features: Set<ServerFeature>) {
    val names = ServerFeature.entries.filter { it in features }.map { stringResource(it.label()) }
    DetailLine(
        icon = TandemIcons.CheckCircle,
        label = stringResource(R.string.shell_account_features),
        value = names.joinToString(", "),
        maxLines = Int.MAX_VALUE,
    )
}

/** A server feature in the user's words. */
internal fun ServerFeature.label(): Int = when (this) {
    ServerFeature.RECIPE_INDEX -> R.string.shell_feature_recipe_index
    ServerFeature.VIEW_LOG -> R.string.shell_feature_view_log
    ServerFeature.COOK_LOG -> R.string.shell_feature_cook_log
    ServerFeature.SHARE_LINK -> R.string.shell_feature_share_link
    ServerFeature.HOUSEHOLDS -> R.string.shell_feature_households
    ServerFeature.AI_IMPORT -> R.string.shell_feature_ai_import
    ServerFeature.AUTO_PLAN -> R.string.shell_feature_auto_plan
    ServerFeature.ICAL -> R.string.shell_feature_ical
    ServerFeature.INVENTORY -> R.string.shell_feature_inventory
}

/** First letter of the name on a tomato circle; decorative (the name is next to it). */
@Composable
internal fun Avatar(name: String, modifier: Modifier = Modifier) {
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

/**
 * Label and value with an icon, read as one TalkBack item. [mergeDescendants] false (Phase 2, konto
 * lane): the line is part of a larger item that is read as one (an account in the switcher).
 */
@Composable
internal fun DetailLine(icon: ImageVector, label: String, value: String, maxLines: Int = 2, mergeDescendants: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (mergeDescendants) Modifier.semantics(mergeDescendants = true) {} else Modifier),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun CompatibilityChip(compatibility: ServerCompatibility) {
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

/** "Every 15 minutes", with "all recipes offline" when they are all kept on the phone. */
@Composable
private fun syncSummary(settings: SettingsSummary): String {
    val interval = stringResource(settings.syncInterval.label())
    return if (settings.downloadAllRecipes) {
        stringResource(R.string.settings_sync_summary, interval, stringResource(R.string.settings_sync_summary_all_offline))
    } else {
        interval
    }
}

@Composable
internal fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.shell_language_system
        AppLanguage.ENGLISH -> R.string.shell_language_name_en
        AppLanguage.SWEDISH -> R.string.shell_language_name_sv
    },
)

/**
 * "Log out?" (plan 4.2 step 4). With [pendingChanges] waiting in the outbox (cook log entries with
 * their rating and comment, "recently viewed") it says how many and that logging out deletes them
 * (fix-ui, 0.2.0 review), and offers "Send first" while a sync could send them ([canSendNow]); the
 * dialog stays open and the count goes down as they are sent. Logging out stays possible.
 */
@Composable
private fun LogoutDialog(
    tokenOrigin: TokenOrigin,
    pendingChanges: Int,
    canSendNow: Boolean,
    onSendFirst: () -> Unit,
    onExport: () -> Unit,
    onConfirm: (revokeToken: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
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
                if (pendingChanges > 0) {
                    PendingChangesWarning(count = pendingChanges, canSendNow = canSendNow, onSendFirst = onSendFirst, onExport = onExport)
                    Text(stringResource(R.string.shell_logout_body_pending))
                } else {
                    Text(stringResource(R.string.shell_logout_body))
                }
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
        // Filled and tonal: a text button's colour is too faint on the dialog's surface (ATF contrast 4.2:1).
        confirmButton = {
            TandemPrimaryButton(
                text = stringResource(R.string.shell_logout_confirm),
                onClick = { onConfirm(canRevoke && revoke) },
                size = TandemButtonSize.Small,
            )
        },
        dismissButton = {
            TandemSecondaryButton(text = stringResource(R.string.shell_cancel), onClick = onDismiss, size = TandemButtonSize.Small)
        },
    )
}

/**
 * "2 changes have not been sent" with what logging out does to them, "Export changes" (Phase 2,
 * konto lane: never deleted without a way to keep them) and "Send first".
 */
@Composable
private fun PendingChangesWarning(count: Int, canSendNow: Boolean, onSendFirst: () -> Unit, onExport: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.testTag(LOGOUT_PENDING_TAG),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(TandemIcons.Warning, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(
                        text = pluralStringResource(R.plurals.shell_logout_pending, count, count),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(text = stringResource(R.string.shell_logout_pending_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemPrimaryButton(
                    text = stringResource(R.string.accounts_export),
                    onClick = onExport,
                    size = TandemButtonSize.Small,
                    leadingIcon = TandemIcons.Share,
                )
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

/** Test tag of the logout dialog's warning about unsent changes. */
internal const val LOGOUT_PENDING_TAG = "shell_logout_pending"
