package casa.moller.tandem.feature.cook

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.timers.TimersScreen
import casa.moller.tandem.feature.cook.timers.TimersViewModel
import casa.moller.tandem.feature.cook.ui.CookScreen

/**
 * Cook mode for [key] (plan 6.2 "Kokläge", features #6–#8). Full screen on every width
 * ([CookKey] hides the navigation); keeps the screen on, can be forced dark ("Kokläge alltid
 * mörkt"), asks for notifications at the first timer and leaves after "Klart!" was saved.
 *
 * Navigation contract (docs/BYGGE.md "Navigering"): the shell pushes [CookKey] from the recipe
 * detail's and Today's "Laga" (and from a timer notification) and shows this route.
 *
 * @param onClose leaves cook mode (✕, system back is the shell's, and after "Klart!" was saved).
 */
@Composable
fun CookRoute(key: CookKey, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<CookViewModel, CookViewModel.Factory>(
        key = "cook-${key.recipeId}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentOnClose by rememberUpdatedState(onClose)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(CookAction.NotificationPermissionResult(granted))
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CookEffect.RequestNotificationPermission -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                is CookEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                is CookEffect.Finished -> {
                    // The screen goes away right after, so a toast (announced by TalkBack) instead of a snackbar.
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                    currentOnClose()
                }
            }
        }
    }
    // The user may have changed notifications or exact alarms in the system settings meanwhile.
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(CookAction.RefreshCapabilities)
        onPauseOrDispose { }
    }
    CookTheme(alwaysDark = state.alwaysDark, dynamicColor = state.dynamicColor) {
        CookScreen(
            state = state,
            onAction = viewModel::onAction,
            onClose = onClose,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            onOpenNotificationSettings = { context.openSettings(notificationSettingsIntent(context)) },
            onOpenExactAlarmSettings = { context.openSettings(exactAlarmSettingsIntent(context)) },
        )
    }
}

/**
 * All timers of the device (plan 8.1 "Löpande timers syns i alla skärmar"), from cook mode or a
 * notification of a timer without a recipe.
 *
 * @param onOpenCook opens cook mode at a timer's recipe and step.
 */
@Composable
fun TimersRoute(onBack: () -> Unit, onOpenCook: (recipeId: Long, step: Int) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TimersViewModel = hiltViewModel()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    TimersScreen(timers = timers, onAction = viewModel::onAction, onBack = onBack, onOpenCook = onOpenCook, modifier = modifier)
}

/** "Kokläge alltid mörkt": a dark theme (and light system bar icons) around cook mode only. */
@Composable
private fun CookTheme(alwaysDark: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
    if (!alwaysDark) {
        content()
        return
    }
    TandemTheme(darkTheme = true, dynamicColor = dynamicColor) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = view.context.findActivity()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val lightStatus = controller?.isAppearanceLightStatusBars
            val lightNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                lightStatus?.let { controller.isAppearanceLightStatusBars = it }
                lightNavigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
        content()
    }
}

private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** Play build: the system page where the user allows exact alarms for Tandem (plan 4.4). */
private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

/** Opens a system settings page; a device without it falls back to the app's details page. */
private fun Context.openSettings(intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }.recoverCatching { startActivity(fallback) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
