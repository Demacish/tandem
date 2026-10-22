package casa.moller.tandem.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import casa.moller.tandem.R
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.ui.CrashReportNotice
import casa.moller.tandem.crash.CrashReportViewModel
import casa.moller.tandem.feature.onboarding.OnboardingFlow
import casa.moller.tandem.feature.shell.TandemShell
import casa.moller.tandem.navigation.AppDestination
import casa.moller.tandem.navigation.AppMessage
import casa.moller.tandem.navigation.AppViewModel
import casa.moller.tandem.recovery.RecoveryRoute

/**
 * Top level: one Navigation 3 back stack whose single entry follows the session (intro, shell or
 * recovery). Each entry gets its own ViewModelStore, so the intro starts fresh after a logout and
 * the shell's state ends with the account.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TandemApp(viewModel: AppViewModel, modifier: Modifier = Modifier, crashReports: CrashReportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A crash log from an earlier run (plan 4.2 p7): a card on the welcome step and in Mer until
    // the user deletes it. Shown, copied or shared only when the user taps it.
    val crashReport by crashReports.report.collectAsStateWithLifecycle()
    val crashNotice: @Composable () -> Unit = {
        crashReport?.let { CrashReportNotice(report = it, onDelete = crashReports::delete) }
    }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel, resources) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, resources.getString(message.text()), Toast.LENGTH_LONG).show()
        }
    }

    NavDisplay(
        backStack = listOf(state.destination),
        // The theme's background behind every top-level screen: a cross-fade never shows the
        // window background (which follows the system's night mode until the app's own choice
        // has been applied to the window).
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Test tags become resource ids in the accessibility tree, so UiAutomator (the release
            // smoke test, later the macrobenchmarks) can find e.g. a loaded recipe photo
            // (core:ui RecipeImageTags). They carry no user data.
            .semantics { testTagsAsResourceId = true },
        // Single-entry stack: system back on a top-level screen leaves the app as usual (the
        // intro and the shell handle back inside themselves).
        onBack = {},
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = topLevelTransition,
        popTransitionSpec = topLevelTransition,
        entryProvider = entryProvider {
            entry<AppDestination.Loading>(metadata = mapOf(LOADING_ENTRY to true)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
            entry<AppDestination.Onboarding> {
                OnboardingFlow(
                    onFinished = viewModel::onOnboardingFinished,
                    onStartDemo = viewModel::onDemoStarted,
                    startAtServerStep = state.startOnboardingAtServer,
                    serverUrl = state.onboardingServerUrl,
                    crashNotice = crashNotice,
                    onCancel = if (state.canCancelOnboarding) viewModel::cancelLogInAgain else null,
                )
            }
            entry<AppDestination.Shell> {
                TandemShell(
                    onConnectTandoor = viewModel::connectFromDemo,
                    crashNotice = crashNotice,
                    onLogInAgain = viewModel::logInAgain,
                    // Phase 1b: a tapped timer notification or lock-screen alarm (MainActivity).
                    timerRequest = state.timerRequest,
                    onTimerRequestHandled = viewModel::onTimerRequestHandled,
                )
            }
            entry<AppDestination.Recovery> {
                RecoveryRoute(
                    reasons = (state.session as? Session.RecoveryNeeded)?.reasons.orEmpty(),
                    onLogInAgain = viewModel::acknowledgeRecovery,
                    onClearAppData = viewModel::clearAppData,
                )
            }
        },
    )
}

/** Metadata that marks the Loading entry (shown only behind the splash screen). */
private const val LOADING_ENTRY = "tandem.app.loading"

/** Cross-fade between the intro, the shell and the recovery screen. */
private const val TOP_LEVEL_FADE_MILLIS = 300

/**
 * No animation when leaving the Loading entry: the splash screen was on top of it, so the first
 * real screen (intro or shell) must be there at once instead of fading in after the splash is
 * gone (cold start target, plan 4.4). Other top-level switches cross-fade briefly.
 */
private val topLevelTransition: AnimatedContentTransitionScope<Scene<AppDestination>>.() -> ContentTransform = {
    if (initialState.entries.any { it.metadata[LOADING_ENTRY] == true }) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn(tween(TOP_LEVEL_FADE_MILLIS)) togetherWith fadeOut(tween(TOP_LEVEL_FADE_MILLIS))
    }
}

@StringRes
private fun AppMessage.text(): Int = when (this) {
    is AppMessage.FromShell -> message.text
    AppMessage.ClearDataFailed -> R.string.app_clear_data_failed
}
