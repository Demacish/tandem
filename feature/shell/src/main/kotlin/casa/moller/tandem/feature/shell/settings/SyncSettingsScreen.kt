package casa.moller.tandem.feature.shell.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.DownloadForOffline
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.sync.OfflineStorage
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/**
 * Mer → Synk och lagring (plan 6.2 "Synk: intervall, ladda ner alla recept, lagringsstorlek"):
 * the background sync's interval (wired to the periodic worker), "Sync now", "Download all
 * recipes" with an estimate and its progress, and the storage the app uses with "Clear photo
 * cache". Demo mode has nothing to sync and says so.
 */
@Composable
internal fun SyncSettingsScreen(
    state: SyncSettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.settings_sync),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        if (state.isDemo) {
            InfoCard(
                title = stringResource(R.string.settings_sync),
                body = stringResource(R.string.settings_demo_no_sync),
            )
        } else {
            SyncNowCard(status = state.status, onSyncNow = { onAction(SettingsAction.SyncNow) })
            SettingsGroup(modifier = Modifier.selectableGroup()) {
                GroupHeading(text = stringResource(R.string.settings_sync_interval), icon = TandemIcons.Schedule)
                SyncInterval.entries.forEachIndexed { index, interval ->
                    if (index > 0) GroupDivider()
                    RadioRow(
                        title = stringResource(interval.label()),
                        selected = interval == state.interval,
                        onClick = { onAction(SettingsAction.SetSyncInterval(interval)) },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_sync_interval_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(TandemTheme.spacing.lg),
                )
            }
            DownloadAll(state = state, onChange = { onAction(SettingsAction.SetDownloadAll(it)) })
        }
        Storage(
            storage = state.storage,
            clearing = state.clearingImageCache,
            onClear = { onAction(SettingsAction.ClearImageCache) },
        )
    }
}

internal fun SyncInterval.label(): Int = when (this) {
    SyncInterval.FIFTEEN_MINUTES -> R.string.settings_sync_interval_15
    SyncInterval.ONE_HOUR -> R.string.settings_sync_interval_60
    SyncInterval.SIX_HOURS -> R.string.settings_sync_interval_360
    SyncInterval.MANUAL -> R.string.settings_sync_interval_manual
}

/** Last sync, a pause, waiting changes and "Sync now". */
@Composable
private fun SyncNowCard(status: SyncStatus, onSyncNow: () -> Unit) {
    val spacing = TandemTheme.spacing
    SettingsGroup {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                val lastSuccess = status.lastSuccess
                Text(
                    text = when {
                        status.running -> stringResource(R.string.settings_sync_running)
                        lastSuccess != null -> stringResource(R.string.settings_sync_last, formatDateTime(lastSuccess))
                        else -> stringResource(R.string.settings_sync_never)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                status.pause?.let { pause ->
                    Text(
                        text = stringResource(R.string.settings_sync_paused, stringResource(pause.label())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.pendingChanges > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.settings_sync_pending, status.pendingChanges, status.pendingChanges),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (status.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TandemTonalButton(
                text = stringResource(R.string.settings_sync_now),
                onClick = onSyncNow,
                enabled = !status.running,
                leadingIcon = TandemIcons.Sync,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun SyncPause.label(): Int = when (this) {
    SyncPause.OFFLINE -> R.string.settings_pause_offline
    SyncPause.UNAUTHORIZED -> R.string.settings_pause_unauthorized
    SyncPause.BLOCKED -> R.string.settings_pause_blocked
    SyncPause.CIRCUIT_BREAKER -> R.string.settings_pause_breaker
    SyncPause.SAFE_MODE -> R.string.settings_pause_safe_mode
    SyncPause.IMPORT_RUNNING -> R.string.settings_pause_import
}

/** "Download all recipes": the switch, an estimate before, progress while it runs. */
@Composable
private fun DownloadAll(state: SyncSettingsUiState, onChange: (Boolean) -> Unit) {
    val spacing = TandemTheme.spacing
    val storage = state.storage
    SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.settings_download_all),
            body = stringResource(R.string.settings_download_all_body),
            icon = TandemIcons.DownloadForOffline,
            checked = state.downloadAll,
            onCheckedChange = onChange,
        )
        if (storage.recipeCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                when {
                    storage.remainingCount == 0 -> Text(
                        text = pluralStringResource(R.plurals.settings_download_done, storage.recipeCount, storage.recipeCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            text = pluralStringResource(
                                R.plurals.settings_download_progress,
                                storage.recipeCount,
                                storage.downloadedCount,
                                storage.recipeCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(progress = { storage.downloadedFraction }, modifier = Modifier.fillMaxWidth())
                        if (!state.downloadAll && storage.estimatedDownloadBytes > 0) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.settings_download_all_estimate,
                                    storage.remainingCount,
                                    formatBytes(storage.estimatedDownloadBytes),
                                    storage.remainingCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the app keeps on the phone, and "Clear photo cache". */
@Composable
private fun Storage(storage: OfflineStorage, clearing: Boolean, onClear: () -> Unit) {
    val spacing = TandemTheme.spacing
    SettingsGroup {
        GroupHeading(text = stringResource(R.string.settings_storage))
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StorageLine(label = stringResource(R.string.settings_storage_database), bytes = storage.databaseBytes)
            StorageLine(label = stringResource(R.string.settings_storage_images), bytes = storage.imageCacheBytes)
            Text(
                text = stringResource(R.string.settings_storage_total, formatBytes(storage.totalBytes)),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.settings_clear_image_cache_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TandemSecondaryButton(
                text = stringResource(R.string.settings_clear_image_cache),
                onClick = onClear,
                enabled = !clearing && storage.imageCacheBytes > 0,
                leadingIcon = TandemIcons.Delete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StorageLine(label: String, bytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = formatBytes(bytes), style = MaterialTheme.typography.bodyLarge)
    }
}

/** "45 MB" in the app's language (Android's own file size format). */
@Composable
internal fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** A short date and time in the app's language and the phone's time zone. */
@Composable
internal fun formatDateTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale) }
    return formatter.format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}
