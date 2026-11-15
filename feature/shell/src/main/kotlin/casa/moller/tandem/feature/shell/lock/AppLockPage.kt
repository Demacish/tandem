package casa.moller.tandem.feature.shell.lock

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import casa.moller.tandem.core.ui.security.DeviceLockAvailability
import casa.moller.tandem.core.ui.security.rememberDeviceAuthenticator
import casa.moller.tandem.core.ui.security.screenLockSettingsIntent
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.SectionHeader
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow

// Fas 5 (las lane, plan §5.5): Mer → Säkerhet → Lås appen as the shell shows it. Kept apart from
// SettingsPages (like WatchPage) so the shell's own tests show the stateless screen and Mer's
// references stay as they were without it.

/** The section "Säkerhet" in Mer and the page. Production: [RouteAppLockPage]; [NoAppLockPage] shows nothing. */
@Stable
internal interface AppLockPage {
    /** "Säkerhet" with the row "Lås appen" and its state. */
    @Composable
    fun MoreSection(selected: Boolean, onOpen: () -> Unit)

    @Composable
    fun AppLock(onNavigateBack: () -> Unit)
}

/** No section and no page (the shell's tests that are not about the lock). */
internal object NoAppLockPage : AppLockPage {
    @Composable
    override fun MoreSection(selected: Boolean, onOpen: () -> Unit) = Unit

    @Composable
    override fun AppLock(onNavigateBack: () -> Unit) = Unit
}

/** The real section and page with their [AppLockSettingsViewModel]s. */
internal object RouteAppLockPage : AppLockPage {
    @Composable
    override fun MoreSection(selected: Boolean, onOpen: () -> Unit) {
        val viewModel: AppLockSettingsViewModel = hiltViewModel(key = "app-lock-more-row")
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        AppLockMoreSection(state = state, selected = selected, onOpen = onOpen)
    }

    @Composable
    override fun AppLock(onNavigateBack: () -> Unit) {
        val viewModel: AppLockSettingsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val context = LocalContext.current
        val resources = LocalResources.current
        val authenticator = rememberDeviceAuthenticator()
        val promptTitle = stringResource(R.string.shell_lock_confirm_title)
        var cancelPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
        // Whether the phone has a screen lock, again every time the page comes back (from the settings).
        LifecycleResumeEffect(viewModel, authenticator) {
            val available = authenticator.availability() == DeviceLockAvailability.AVAILABLE
            viewModel.onAction(AppLockSettingsAction.ScreenLockChecked(available))
            onPauseOrDispose { }
        }
        // The page goes away with the prompt open (rotation, back): the prompt closes and the switch is free again.
        DisposableEffect(viewModel) {
            onDispose {
                cancelPrompt?.let { cancel ->
                    cancel()
                    viewModel.onAction(AppLockSettingsAction.Confirmed(DeviceAuthResult.Cancelled))
                }
            }
        }
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    AppLockSettingsEffect.ConfirmScreenLock -> cancelPrompt = authenticator.authenticate(promptTitle, null) { result ->
                        cancelPrompt = null
                        viewModel.onAction(AppLockSettingsAction.Confirmed(result))
                    }

                    AppLockSettingsEffect.OpenScreenLockSettings -> openScreenLockSettings(context)

                    is AppLockSettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                    is AppLockSettingsEffect.SystemMessage -> snackbarHostState.showSnackbar(effect.text)
                }
            }
        }
        AppLockSettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbarHostState,
        )
    }
}

/** Mer → Säkerhet → "Lås appen" with "Av" or "På · låses efter 1 minut". */
@Composable
internal fun AppLockMoreSection(state: AppLockSettingsUiState, selected: Boolean, onOpen: () -> Unit) {
    SectionHeader(stringResource(R.string.shell_lock_more_section))
    SettingsGroup {
        SettingsRow(
            icon = TandemIcons.Lock,
            title = stringResource(R.string.shell_lock_title),
            supporting = when {
                !state.loaded -> null
                state.enabled -> stringResource(R.string.shell_lock_more_on, timeoutSummary(state.timeout))
                else -> stringResource(R.string.shell_lock_more_off)
            },
            onClick = onOpen,
            selected = selected,
        )
    }
}

private fun openScreenLockSettings(context: Context) {
    try {
        context.startActivity(screenLockSettingsIntent())
    } catch (e: ActivityNotFoundException) {
        // No settings app (never expected): the page stays as it is.
    }
}
