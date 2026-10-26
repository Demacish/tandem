package casa.moller.tandem.feature.shell.accounts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.shell.R

// Phase 2 (konto lane): the accounts page as the shell shows it. Kept apart from ShellFeatures and
// SettingsPages (like those) so the shell's own tests can show the stateless screen without Hilt.

/** Mer → Konton och servrar. Production: [RouteAccountsPage]; shell tests pass a fixed state. */
@Stable
internal interface AccountsPage {
    /**
     * @param onAddAccount "Add account": the app opens the intro's server step (cancellable).
     * @param onConnectTandoor in demo mode: leave demo mode and connect a Tandoor.
     */
    @Composable
    fun Accounts(onNavigateBack: () -> Unit, onAddAccount: () -> Unit, onConnectTandoor: () -> Unit)
}

/** The real accounts page with its [AccountsViewModel]. */
internal object RouteAccountsPage : AccountsPage {
    @Composable
    override fun Accounts(onNavigateBack: () -> Unit, onAddAccount: () -> Unit, onConnectTandoor: () -> Unit) {
        val viewModel: AccountsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val context = LocalContext.current
        val resources = LocalResources.current
        LaunchedEffect(viewModel, snackbarHostState) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is AccountsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                    is AccountsEffect.SharePending -> sharePendingChanges(context, effect.json)
                }
            }
        }
        AccountsScreen(
            state = state,
            onAction = viewModel::onAction,
            onAddAccount = onAddAccount,
            onConnectTandoor = onConnectTandoor,
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbarHostState,
        )
    }
}

/**
 * "Export changes": the unsent changes (JSON, no token, no server address) through the system share
 * sheet (ACTION_SEND text/plain), the same way as the recovery screen's export. Nothing is sent by
 * the app itself; the user picks where it goes.
 */
internal fun sharePendingChanges(context: Context, json: String) {
    val title = context.getString(R.string.accounts_export_share_title)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, json)
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        // No app can receive text: nothing to do (the dialog stays open, nothing is deleted).
    }
}
