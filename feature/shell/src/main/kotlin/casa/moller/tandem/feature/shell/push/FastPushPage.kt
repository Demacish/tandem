package casa.moller.tandem.feature.shell.push

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Fas 4 (push lane): Mer → Synk och lagring → Snabb push as the shell shows it. Kept apart from
// SettingsPages (like AccountsPage) so the shell's own tests show the stateless screen without Hilt.

/** The fast push page. Production: [RouteFastPushPage]; shell tests pass a fixed state. */
@Stable
internal interface FastPushPage {
    @Composable
    fun FastPush(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit)
}

/** The real page with its [FastPushViewModel]. */
internal object RouteFastPushPage : FastPushPage {
    @Composable
    override fun FastPush(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit) {
        val viewModel: FastPushViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val resources = LocalResources.current
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onAction(FastPushAction.NotificationPermissionResult(granted))
        }
        // Back from installing a distributor or from the system settings: read again.
        LifecycleResumeEffect(viewModel) {
            viewModel.onAction(FastPushAction.Refresh)
            onPauseOrDispose { }
        }
        LaunchedEffect(viewModel, snackbarHostState) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is FastPushEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                    FastPushEffect.RequestNotificationPermission ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onAction(FastPushAction.NotificationPermissionResult(granted = true))
                        }
                }
            }
        }
        FastPushScreen(
            state = state,
            onAction = viewModel::onAction,
            onNavigateBack = onNavigateBack,
            onOpenPermissions = onOpenPermissions,
            snackbarHostState = snackbarHostState,
        )
    }
}
