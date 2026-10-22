package casa.moller.tandem.feature.shell.settings

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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.model.diagnostics.LogExport
import casa.moller.tandem.feature.shell.R

// Phase 1b (inställningar lane): the settings sub-screens under "More" as the shell shows them.
// Kept apart from ShellFeatures so the shell's own tests can show the stateless screens without
// Hilt, and so parallel lanes that add feature routes do not touch the same lines.

/**
 * The settings sub-screens the shell hosts (plan 6.2 "Inställningar"). Production uses
 * [RouteSettingsPages] (a [SettingsViewModel] per entry); shell tests pass fixed states.
 */
@Stable
internal interface SettingsPages {
    /** Mer → Utseende. */
    @Composable
    fun Appearance(onNavigateBack: () -> Unit)

    /** Mer → Kokläge och timers; it links to the permissions overview and to Appearance. */
    @Composable
    fun Cooking(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenAppearance: () -> Unit)

    /** Mer → Synk och lagring. */
    @Composable
    fun Sync(onNavigateBack: () -> Unit)

    /** Mer → Behörigheter (an overview, never a forced step). */
    @Composable
    fun Permissions(onNavigateBack: () -> Unit)

    /** Mer → Diagnostik. */
    @Composable
    fun Diagnostics(onNavigateBack: () -> Unit)
}

/** The real settings screens, each with its own [SettingsViewModel]. */
internal object RouteSettingsPages : SettingsPages {
    @Composable
    override fun Appearance(onNavigateBack: () -> Unit) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.appearance.collectAsStateWithLifecycle()
        val snackbar = settingsEffects(viewModel)
        AppearanceSettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbar,
        )
    }

    @Composable
    override fun Cooking(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenAppearance: () -> Unit) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.cooking.collectAsStateWithLifecycle()
        val snackbar = settingsEffects(viewModel)
        // The warnings depend on what the user allowed in Android's settings meanwhile.
        LifecycleResumeEffect(viewModel) {
            viewModel.onAction(SettingsAction.RefreshPermissions(batteryUnrestricted = null))
            onPauseOrDispose { }
        }
        CookingSettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            onNavigateBack = onNavigateBack,
            onOpenPermissions = onOpenPermissions,
            onOpenAppearance = onOpenAppearance,
            snackbarHostState = snackbar,
        )
    }

    @Composable
    override fun Sync(onNavigateBack: () -> Unit) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.sync.collectAsStateWithLifecycle()
        val snackbar = settingsEffects(viewModel)
        SyncSettingsScreen(state = state, onAction = viewModel::onAction, onNavigateBack = onNavigateBack, snackbarHostState = snackbar)
    }

    @Composable
    override fun Permissions(onNavigateBack: () -> Unit) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.permissions.collectAsStateWithLifecycle()
        val snackbar = settingsEffects(viewModel)
        val context = LocalContext.current
        // Read again every time the screen comes back from Android's settings.
        LifecycleResumeEffect(viewModel, context) {
            viewModel.onAction(SettingsAction.RefreshPermissions(SystemSetting.batteryUnrestricted(context)))
            onPauseOrDispose { }
        }
        PermissionsScreen(
            state = state,
            onOpenSystemSetting = { it.open(context) },
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbar,
        )
    }

    @Composable
    override fun Diagnostics(onNavigateBack: () -> Unit) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val state by viewModel.diagnosticsState.collectAsStateWithLifecycle()
        val snackbar = settingsEffects(viewModel)
        DiagnosticsScreen(state = state, onAction = viewModel::onAction, onNavigateBack = onNavigateBack, snackbarHostState = snackbar)
    }
}

/**
 * Shows the [SettingsViewModel]'s one-off effects while the screen is shown: messages as a
 * snackbar, the exported log in the system share sheet. Returns the screen's snackbar host.
 */
@Composable
private fun settingsEffects(viewModel: SettingsViewModel): SnackbarHostState {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is SettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                is SettingsEffect.ShareLog -> shareLog(context, effect.export)
            }
        }
    }
    return snackbarHostState
}

/**
 * "Export log": ACTION_SEND text/plain through the system share sheet; the user picks where it
 * goes. Nothing is sent by the app itself.
 */
private fun shareLog(context: Context, export: LogExport) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, export.fileName)
        .putExtra(Intent.EXTRA_TEXT, export.text)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_diagnostics_share_title)))
    } catch (e: ActivityNotFoundException) {
        // No app can receive text: nothing to do (the screen stays as it was).
    }
}
