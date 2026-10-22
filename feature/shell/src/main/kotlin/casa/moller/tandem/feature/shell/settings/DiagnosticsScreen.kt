package casa.moller.tandem.feature.shell.settings

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
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.diagnostics.DiagnosticsSnapshot
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
