package casa.moller.tandem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.Share
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.ui.AdaptiveContentCard
import casa.moller.tandem.recovery.PendingExportState

/**
 * Recovery screen (plan 4.2 step 7, feature #37, Stitch terst_llning_fels_kert_l_ge): shown
 * instead of a crash loop when stored logins or settings could not be read. What happened, that
 * the server is untouched, and three ways on: "Log in again", "Export pending changes" (the
 * outbox as JSON text through the share sheet, so nothing queued is lost) and "Clear app data"
 * (with a confirmation).
 */
@Composable
internal fun RecoveryScreen(
    reasons: Set<RecoveryReason>,
    onLogInAgain: () -> Unit,
    onClearAppData: () -> Unit,
    modifier: Modifier = Modifier,
    export: PendingExportState = PendingExportState(),
    onExportPendingChanges: () -> Unit = {},
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val spacing = TandemTheme.spacing
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdaptiveContentCard(modifier = Modifier.safeDrawingPadding(), maxContentWidth = TandemTheme.sizes.maxReadingWidth) {
            TandemIconHero(
                icon = TandemIcons.Shield,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                tone = TandemTone.Warning,
            )
            Text(
                text = stringResource(R.string.app_recovery_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            RecoveryReason.entries.filter { it in reasons }.forEach { reason ->
                Text(
                    text = stringResource(reason.text()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InfoCard(
                title = stringResource(R.string.app_recovery_server_safe_title),
                body = stringResource(R.string.app_recovery_server_safe_body),
                icon = TandemIcons.Dns,
                tone = TandemTone.Success,
            )
            Text(
                text = stringResource(R.string.app_recovery_actions_heading),
                modifier = Modifier
                    .padding(top = spacing.sm)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            // Card titles say what the card is about, the button what it does: the same text twice is
            // one name for two things for TalkBack (ATF DuplicateSpeakableTextCheck).
            RecoveryAction(
                title = stringResource(R.string.app_recovery_log_in_title),
                body = stringResource(R.string.app_recovery_log_in_body),
            ) {
                TandemPrimaryButton(
                    text = stringResource(R.string.app_recovery_log_in_again),
                    onClick = onLogInAgain,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = TandemIcons.Key,
                )
            }
            RecoveryAction(
                title = stringResource(R.string.app_recovery_export_title),
                body = export.description(),
            ) {
                TandemSecondaryButton(
                    text = stringResource(R.string.app_recovery_export_button),
                    onClick = onExportPendingChanges,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = export.canExport,
                    leadingIcon = TandemIcons.Share,
                )
            }
            RecoveryAction(
                title = stringResource(R.string.app_recovery_clear_card_title),
                body = stringResource(R.string.app_recovery_clear_card_body),
                container = MaterialTheme.colorScheme.errorContainer.copy(alpha = ERROR_CARD_ALPHA),
            ) {
                TandemSecondaryButton(
                    text = stringResource(R.string.app_recovery_clear_data),
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.app_recovery_clear_title)) },
            text = { Text(stringResource(R.string.app_recovery_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearAppData()
                    },
                ) { Text(stringResource(R.string.app_recovery_clear_data)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.app_cancel)) }
            },
        )
    }
}

/** One recovery action: what it does and its button. */
@Composable
private fun RecoveryAction(
    title: String,
    body: String,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    button: @Composable ColumnScope.() -> Unit,
) {
    val spacing = TandemTheme.spacing
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            button()
        }
    }
}

/** What the export card says for the current outbox. */
@Composable
private fun PendingExportState.description(): String {
    val count = pendingChanges
    return when {
        !available -> stringResource(R.string.app_recovery_export_unavailable)
        count == null -> stringResource(R.string.app_recovery_export_unknown)
        count == 0 -> stringResource(R.string.app_recovery_export_none)
        else -> pluralStringResource(R.plurals.app_recovery_export_pending, count, count)
    }
}

private fun RecoveryReason.text(): Int = when (this) {
    RecoveryReason.KEY_UNUSABLE -> R.string.app_recovery_reason_key
    RecoveryReason.ACCOUNTS_UNREADABLE -> R.string.app_recovery_reason_accounts
    RecoveryReason.SETTINGS_UNREADABLE -> R.string.app_recovery_reason_settings
    RecoveryReason.SECURE_STORAGE_UNAVAILABLE -> R.string.app_recovery_reason_unavailable
}

/** The clear-data card is tinted with the error colour, faintly (design: a pale red card). */
private const val ERROR_CARD_ALPHA = 0.45f
