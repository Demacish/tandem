package casa.moller.tandem.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.ui.language.LanguageController
import casa.moller.tandem.core.ui.language.rememberLanguageController
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen

/**
 * Entry point of the intro: welcome -> server -> login -> connected (plan 6.2), with demo mode
 * reachable from every step.
 *
 * @param onFinished called when the user is logged in and taps "Continue" on the connected step.
 * @param onStartDemo called after demo mode was switched on (plan 4.5).
 * @param startAtServerStep skip the welcome step, e.g. for "Connect your Tandoor" in demo mode.
 * @param viewModel injected by Hilt; tests pass one with fake repositories.
 * @param languageController per-app language for the picker on the welcome step.
 * @param crashNotice shown at the top of the welcome step: the app's "Tandem crashed last time"
 *   card while a crash log is stored (plan 4.2 p7); emits nothing otherwise.
 */
@Composable
fun OnboardingFlow(
    onFinished: () -> Unit,
    onStartDemo: () -> Unit,
    modifier: Modifier = Modifier,
    startAtServerStep: Boolean = false,
    viewModel: OnboardingViewModel = hiltViewModel(),
    languageController: LanguageController = rememberLanguageController(),
    crashNotice: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnStartDemo by rememberUpdatedState(onStartDemo)
    var language by remember(languageController) { mutableStateOf(languageController.current) }

    LaunchedEffect(viewModel) {
        viewModel.start(startAtServerStep)
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.DemoStarted -> currentOnStartDemo()
            }
        }
    }

    // System back moves between the steps; on the welcome step it leaves the app as usual.
    BackHandler(
        enabled = (state.step == OnboardingStep.SERVER || state.step == OnboardingStep.LOGIN) &&
            !state.loginInProgress,
    ) {
        viewModel.onBack()
    }

    OnboardingScreen(
        state = state,
        actions = viewModel,
        language = language,
        onLanguageSelected = {
            language = it
            languageController.select(it)
        },
        onFinished = onFinished,
        modifier = modifier,
        crashNotice = crashNotice,
    )
}
