package casa.moller.tandem.recovery

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.R
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.ui.RecoveryScreen

/**
 * The recovery screen with its ViewModel (feature #37): the session actions come from the app
 * ([onLogInAgain], [onClearAppData]); the outbox export from [RecoveryViewModel].
 */
@Composable
internal fun RecoveryRoute(
    reasons: Set<RecoveryReason>,
    onLogInAgain: () -> Unit,
    onClearAppData: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecoveryEffect.SharePendingChanges -> sharePendingChanges(context, effect.json)
                RecoveryEffect.ExportFailed -> Toast.makeText(context, R.string.app_recovery_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    RecoveryScreen(
        reasons = reasons,
        onLogInAgain = onLogInAgain,
        onClearAppData = onClearAppData,
        modifier = modifier,
        export = export,
        onExportPendingChanges = viewModel::exportPendingChanges,
    )
}

/** ACTION_SEND text/plain through the system share sheet; the user picks where it goes. */
private fun sharePendingChanges(context: Context, json: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_recovery_export_subject))
        .putExtra(Intent.EXTRA_TEXT, json)
    // No app that can receive text must not crash the recovery screen.
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.app_recovery_export_chooser))) }
}
