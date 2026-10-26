package casa.moller.tandem.feature.cook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.theme.TandemDarkTheme
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.timers.TimersScreen
import casa.moller.tandem.feature.cook.timers.TimersViewModel
import casa.moller.tandem.feature.cook.ui.CookScreen
import casa.moller.tandem.feature.cook.ui.DarkSystemBars

/**
 * Cook mode for [key] (plan 6.2 "Kokläge", features #6–#8). Full screen on every width
 * ([CookKey] hides the navigation); keeps the screen on, can be forced dark ("Kokläge alltid
 * mörkt"), asks for notifications at the first timer and leaves after "Klart!" was saved.
 *
 * Navigation contract (docs/BYGGE.md "Navigering"): the shell pushes [CookKey] from the recipe
 * detail's and Today's "Laga" (and from a timer notification) and shows this route.
 *
 * @param onClose leaves cook mode (✕, system back is the shell's, and after "Klart!" was saved).
 * @param pageRequest a page this open cook mode should show now (0-based step), e.g. a tapped
 *   notification of one of its timers while it is open on another step; null for none. Cook mode
 *   keeps its servings and "Klart!" draft and turns to that page, then calls [onPageRequestHandled].
 * @param shoppingAction Phase 2 (koppling lane, #12): "Lägg på inköpslistan" on the last page,
 *   given cook mode's servings and its snackbar (feature:shopping's route, passed in by the shell).
 */
@Composable
fun CookRoute(
    key: CookKey,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pageRequest: Int? = null,
    onPageRequestHandled: () -> Unit = {},
    shoppingAction: (@Composable (servings: Double?, snackbarHostState: SnackbarHostState) -> Unit)? = null,
) {
    val viewModel = hiltViewModel<CookViewModel, CookViewModel.Factory>(
        key = "cook-${key.recipeId}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentOnClose by rememberUpdatedState(onClose)
    val view = LocalView.current
    val currentOnPageRequestHandled by rememberUpdatedState(onPageRequestHandled)
    LaunchedEffect(viewModel, pageRequest) {
        if (pageRequest != null) {
            viewModel.onAction(CookAction.GoToPage(pageRequest))
            currentOnPageRequestHandled()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(CookAction.NotificationPermissionResult(granted))
    }
    // No visible message (the timer bar shows the new timer); only TalkBack says it.
    LaunchedEffect(viewModel, view) {
        viewModel.announcements.collect { view.announce(resources.getString(it.text, it.argument)) }
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
    CookTheme(alwaysDark = state.alwaysDark) {
        CookScreen(
            state = state,
            onAction = viewModel::onAction,
            onClose = onClose,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            onOpenNotificationSettings = { context.openSettings(notificationSettingsIntent(context)) },
            onOpenAlarmChannelSettings = { context.openSettings(alarmChannelSettingsIntent(context)) },
            onOpenExactAlarmSettings = { context.openSettings(exactAlarmSettingsIntent(context)) },
            shoppingActions = shoppingAction?.let { action -> { action(state.servings?.current, snackbarHostState) } },
        )
    }
}

/**
 * All timers of the device (plan 8.1 "Löpande timers syns i alla skärmar"), from cook mode or a
 * notification of a timer without a recipe.
 *
 * @param onOpenCook opens cook mode at the timer's recipe and step.
 * @param activeSource the app's source now (core:model `TimerSource`); only timers of it open cook mode.
 */
@Composable
fun TimersRoute(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?, modifier: Modifier = Modifier) {
    val viewModel: TimersViewModel = hiltViewModel()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    TimersScreen(
        timers = timers,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenCook = onOpenCook,
        activeSource = activeSource,
        modifier = modifier,
    )
}

/**
 * "Kokläge alltid mörkt": a dark theme (and light system bar icons) around cook mode only. One
 * call site for [content] whatever the setting, so turning it on (or the settings arriving) never
 * throws cook mode's state away; the surrounding theme's dark/light and dynamic colour choice
 * stays otherwise (core:designsystem `TandemDarkTheme`).
 */
@Composable
private fun CookTheme(alwaysDark: Boolean, content: @Composable () -> Unit) {
    DarkSystemBars(enabled = alwaysDark)
    TandemDarkTheme(enabled = alwaysDark, content = content)
}

private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** The "Timers (alarm)" channel's own page (its sound was switched off). */
private fun alarmChannelSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    .putExtra(Settings.EXTRA_CHANNEL_ID, TandemNotificationChannel.TIMER_ALARM.id)

/** TalkBack says [text] (announceForAccessibility is deprecated from API 36, there is no polite alternative without a visible node). */
@Suppress("DEPRECATION")
private fun View.announce(text: String) {
    announceForAccessibility(text)
}

/** Play build: the system page where the user allows exact alarms for Tandem (plan 4.4). */
private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())

/** Opens a system settings page; a device without it falls back to the app's details page. */
private fun Context.openSettings(intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
    runCatching { startActivity(intent) }.recoverCatching { startActivity(fallback) }
}
