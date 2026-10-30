package casa.moller.tandem.feature.shell.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
import casa.moller.tandem.core.model.diagnostics.OutboxKind
import casa.moller.tandem.core.model.diagnostics.OutboxProblem
import casa.moller.tandem.core.model.diagnostics.OutboxSummary
import casa.moller.tandem.core.model.diagnostics.SyncHealth
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Mer → Diagnostik (plan 6.2 "Övrigt: diagnostik (Exportera logg)"): versions, the measured cold
 * start and last full sync against Phase 1's goals (so they can be checked on the real phone),
 * and "Export log", which opens the share sheet with a sanitized text.
 */
@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val snapshot = state.snapshot
    SettingsPage(
        title = stringResource(R.string.settings_diagnostics),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        if (snapshot != null) {
            SettingsGroup {
                GroupHeading(text = stringResource(R.string.settings_diagnostics_versions), icon = TandemIcons.Info)
                Facts(snapshot)
            }
            // Phase 2 (koppling lane): "blockerad"-diagnostik, only with a real account.
            if (snapshot.serverVersion != null) {
                SettingsGroup {
                    GroupHeading(text = stringResource(R.string.settings_diagnostics_sync), icon = TandemIcons.Sync)
                    SyncHealthFacts(snapshot.syncHealth)
                }
            }
            SettingsGroup {
                GroupHeading(text = stringResource(R.string.settings_diagnostics_timings), icon = TandemIcons.Science)
                Text(
                    text = stringResource(R.string.settings_diagnostics_timings_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg),
                )
                Timing(
                    title = stringResource(R.string.settings_diagnostics_cold_start),
                    value = snapshot.lastColdStart?.let { seconds(it.duration) },
                    withinTarget = snapshot.lastColdStart?.let { it.duration < COLD_START_TARGET },
                    measuredAt = snapshot.lastColdStart?.let { formatDateTime(it.measuredAt) },
                    hint = stringResource(R.string.settings_diagnostics_cold_start_hint),
                )
                GroupDivider()
                val fullSync = snapshot.lastFullSync
                Timing(
                    title = stringResource(R.string.settings_diagnostics_full_sync),
                    value = fullSync?.let {
                        val duration = seconds(it.duration)
                        pluralStringResource(R.plurals.settings_diagnostics_full_sync_value, it.recipeCount, duration, it.recipeCount)
                    },
                    withinTarget = fullSync?.let { it.duration < fullSyncTarget(it.recipeCount) },
                    measuredAt = fullSync?.let { formatDateTime(it.finishedAt) },
                    hint = stringResource(R.string.settings_diagnostics_full_sync_hint),
                )
            }
        }
        SettingsGroup {
            Column(
                modifier = Modifier.padding(TandemTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_export_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TandemPrimaryButton(
                    text = stringResource(
                        if (state.exporting) R.string.settings_diagnostics_exporting else R.string.settings_diagnostics_export,
                    ),
                    onClick = { onAction(SettingsAction.ExportLog) },
                    enabled = !state.exporting,
                    leadingIcon = TandemIcons.Share,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Facts(snapshot: DiagnosticsSnapshot) {
    Column(
        modifier = Modifier.padding(start = TandemTheme.spacing.lg, end = TandemTheme.spacing.lg, bottom = TandemTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        Fact(
            label = stringResource(R.string.settings_diagnostics_app),
            value = stringResource(
                R.string.settings_diagnostics_app_value,
                snapshot.appVersion,
                snapshot.appVersionCode,
                snapshot.distribution,
            ),
        )
        Fact(
            label = stringResource(R.string.settings_diagnostics_android),
            value = stringResource(R.string.settings_diagnostics_android_value, snapshot.androidSdk),
        )
        Fact(
            label = stringResource(R.string.settings_diagnostics_server),
            value = snapshot.serverVersion?.let { stringResource(R.string.shell_account_version, it) }
                ?: stringResource(R.string.settings_diagnostics_server_none),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Phase 2 (koppling lane): whether the sync is held back and why ("blockerad"), the 404/400
 * answers the circuit breaker counts, and the waiting changes per kind with the last error.
 * Each kind is one TalkBack item.
 */
@Composable
private fun SyncHealthFacts(health: SyncHealth) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StateChip(
                ok = health.pause == null,
                okText = stringResource(R.string.settings_diagnostics_sync_ok),
                notOkText = stringResource(R.string.settings_diagnostics_sync_held),
            )
            health.pause?.let { pause ->
                Text(text = pauseText(pause, health.pausedUntil?.let { formatDateTime(it) }), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            text = stringResource(R.string.settings_diagnostics_client_errors, health.recentClientErrors, SyncHealth.CLIENT_ERROR_LIMIT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (health.outbox.isEmpty()) {
            Text(text = stringResource(R.string.settings_diagnostics_outbox_empty), style = MaterialTheme.typography.bodyLarge)
        }
        health.outbox.forEach { summary -> OutboxKindFact(summary) }
        if (health.failed > 0) {
            Text(
                text = stringResource(R.string.settings_diagnostics_refused_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutboxKindFact(summary: OutboxSummary) {
    val counts = buildList {
        if (summary.waiting > 0) add(pluralStringResource(R.plurals.settings_diagnostics_waiting, summary.waiting, summary.waiting))
        if (summary.failed > 0) add(pluralStringResource(R.plurals.settings_diagnostics_failed, summary.failed, summary.failed))
    }.joinToString(" · ")
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(text = stringResource(kindLabel(summary.kind)), style = MaterialTheme.typography.titleSmall)
        Text(text = counts, style = MaterialTheme.typography.bodyLarge)
        summary.lastProblem?.let { problem ->
            Text(
                text = stringResource(R.string.settings_diagnostics_last_error, stringResource(problemLabel(problem))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun pauseText(pause: SyncPause, until: String?): String = when (pause) {
    SyncPause.BLOCKED -> until?.let { stringResource(R.string.settings_diagnostics_pause_blocked, it) }
        ?: stringResource(R.string.settings_diagnostics_pause_blocked_now)

    SyncPause.CIRCUIT_BREAKER -> until?.let { stringResource(R.string.settings_diagnostics_pause_breaker, it) }
        ?: stringResource(R.string.settings_diagnostics_pause_blocked_now)

    SyncPause.UNAUTHORIZED -> stringResource(R.string.settings_diagnostics_pause_unauthorized)

    SyncPause.OFFLINE -> stringResource(R.string.settings_diagnostics_pause_offline)

    SyncPause.SAFE_MODE -> stringResource(R.string.settings_diagnostics_pause_safe_mode)

    SyncPause.IMPORT_RUNNING -> stringResource(R.string.settings_diagnostics_pause_import)
}

@StringRes
private fun kindLabel(kind: OutboxKind): Int = when (kind) {
    OutboxKind.SHOPPING_ENTRY -> R.string.settings_diagnostics_kind_shopping_entry
    OutboxKind.SHOPPING_RECIPE -> R.string.settings_diagnostics_kind_shopping_recipe
    OutboxKind.MEAL_PLAN -> R.string.settings_diagnostics_kind_meal_plan
    OutboxKind.MEAL_TYPE -> R.string.settings_diagnostics_kind_meal_type
    OutboxKind.RECIPE_BOOK -> R.string.settings_diagnostics_kind_recipe_book
    OutboxKind.RECIPE_BOOK_ENTRY -> R.string.settings_diagnostics_kind_recipe_book_entry
    OutboxKind.WANT_TO_COOK -> R.string.settings_diagnostics_kind_want_to_cook
    OutboxKind.FOOD -> R.string.settings_diagnostics_kind_food
    OutboxKind.AISLE_ORDER -> R.string.settings_diagnostics_kind_aisle_order
    OutboxKind.PREFERENCES -> R.string.settings_diagnostics_kind_preferences
    OutboxKind.PANTRY -> R.string.settings_diagnostics_kind_pantry
    OutboxKind.LEARNED_ALIAS -> R.string.settings_diagnostics_kind_learned_alias
    OutboxKind.DRINK -> R.string.settings_diagnostics_kind_drink
    OutboxKind.PAIRING -> R.string.settings_diagnostics_kind_pairing
    OutboxKind.COOK_LOG -> R.string.settings_diagnostics_kind_cook_log
    OutboxKind.VIEW_LOG -> R.string.settings_diagnostics_kind_view_log
    OutboxKind.OTHER -> R.string.settings_diagnostics_kind_other
}

@StringRes
private fun problemLabel(problem: OutboxProblem): Int = when (problem) {
    OutboxProblem.OFFLINE -> R.string.settings_diagnostics_problem_offline
    OutboxProblem.TIMEOUT -> R.string.settings_diagnostics_problem_timeout
    OutboxProblem.NOT_FOUND -> R.string.settings_diagnostics_problem_not_found
    OutboxProblem.BAD_REQUEST -> R.string.settings_diagnostics_problem_bad_request
    OutboxProblem.UNAUTHORIZED -> R.string.settings_diagnostics_problem_unauthorized
    OutboxProblem.BLOCKED -> R.string.settings_diagnostics_problem_blocked
    OutboxProblem.THROTTLED -> R.string.settings_diagnostics_problem_throttled
    OutboxProblem.SERVER_ERROR -> R.string.settings_diagnostics_problem_server_error
    OutboxProblem.CIRCUIT_BREAKER -> R.string.settings_diagnostics_problem_circuit_breaker
    OutboxProblem.CONFLICT -> R.string.settings_diagnostics_problem_conflict
    OutboxProblem.OTHER -> R.string.settings_diagnostics_problem_other
}

/** One measurement: the value with a "within the goal" chip, when it was measured, or how it will be. */
@Composable
private fun Timing(title: String, value: String?, withinTarget: Boolean?, measuredAt: String?, hint: String) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (value == null) {
            Text(text = stringResource(R.string.settings_diagnostics_not_measured), style = MaterialTheme.typography.bodyLarge)
            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(text = value, style = MaterialTheme.typography.headlineSmall)
            if (withinTarget != null) {
                StateChip(
                    ok = withinTarget,
                    okText = stringResource(R.string.settings_diagnostics_within_target),
                    notOkText = stringResource(R.string.settings_diagnostics_over_target),
                )
            }
            if (measuredAt != null) {
                Text(
                    text = stringResource(R.string.settings_diagnostics_measured_at, measuredAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "0.84 s" / "0,84 s" in the app's language. */
@Composable
private fun seconds(duration: Duration): String {
    val locale = LocalConfiguration.current.locales[0]
    val value = duration.toDouble(DurationUnit.SECONDS)
    val text = if (value < 10) String.format(locale, "%.2f", value) else String.format(locale, "%.1f", value)
    return stringResource(R.string.settings_diagnostics_seconds, text)
}

/** Phase 1 "Klart när": cold start under 1.5 s. */
internal val COLD_START_TARGET: Duration = 1_500.milliseconds

/** Phase 1 "Klart när": about 300 recipes in under 60 s, so 0.2 s per recipe (at least 60 s). */
internal fun fullSyncTarget(recipeCount: Int): Duration = maxOf(60.seconds, (recipeCount * 200).milliseconds)
