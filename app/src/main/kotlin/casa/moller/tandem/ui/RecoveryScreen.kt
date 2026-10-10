package casa.moller.tandem.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.ui.AdaptiveContentCard

/**
 * Basic recovery screen (plan 4.2 step 7, feature #37): shown instead of a crash loop when
 * stored logins or settings could not be read. "Export pending changes" comes with the outbox
 * in Phase 1.
 */
@Composable
internal fun RecoveryScreen(
    reasons: Set<RecoveryReason>,
    onLogInAgain: () -> Unit,
    onClearAppData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdaptiveContentCard(modifier = Modifier.safeDrawingPadding()) {
            TandemIconHero(
                icon = TandemIcons.Warning,
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
            TandemPrimaryButton(
                text = stringResource(R.string.app_recovery_log_in_again),
                onClick = onLogInAgain,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = TandemIcons.Key,
            )
            TandemSecondaryButton(
                text = stringResource(R.string.app_recovery_clear_data),
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
            )
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

private fun RecoveryReason.text(): Int = when (this) {
    RecoveryReason.KEY_UNUSABLE -> R.string.app_recovery_reason_key
    RecoveryReason.ACCOUNTS_UNREADABLE -> R.string.app_recovery_reason_accounts
    RecoveryReason.SETTINGS_UNREADABLE -> R.string.app_recovery_reason_settings
    RecoveryReason.SECURE_STORAGE_UNAVAILABLE -> R.string.app_recovery_reason_unavailable
}
