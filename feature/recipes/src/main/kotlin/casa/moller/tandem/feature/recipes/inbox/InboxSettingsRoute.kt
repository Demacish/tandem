package casa.moller.tandem.feature.recipes.inbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Fas 5 (rss lane, plan §7 #35): Mer → "Inkorg för recept" (docs/BYGGE.md "Inkorgen för recept").
 *
 * @param onOpenInbox "Öppna inkorgen": the inbox on the Recipes tab.
 */
@Composable
fun InboxSettingsRoute(onBack: () -> Unit, onOpenInbox: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: InboxSettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOpenInbox by rememberUpdatedState(onOpenInbox)
    val context = LocalContext.current
    // Fas 5 (sdk37 lane): Android 17's local network dialog, only after "Allow" on the notice.
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(InboxSettingsAction.LocalNetworkResult(granted))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InboxSettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                InboxSettingsEffect.OpenInbox -> currentOpenInbox()
                is InboxSettingsEffect.RequestLocalNetwork -> localNetworkLauncher.launch(effect.permission)
                InboxSettingsEffect.OpenAppSettings -> openAppSettings(context)
            }
        }
    }
    InboxSettingsScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** Fas 5 (sdk37 lane): Tandem's page in Android's settings ("Permissions" -> "Nearby devices"). Only an Intent. */
private fun openAppSettings(context: Context) {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
