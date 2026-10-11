package casa.moller.tandem.feature.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.ui.language.LanguageController
import casa.moller.tandem.core.ui.language.rememberLanguageController
import casa.moller.tandem.feature.onboarding.ui.OnboardingScreen

/**
 * Entry point of the intro: welcome -> server -> login -> connected -> first sync -> battery tip
 * (Samsung only) (plan 6.2), with demo mode reachable from the first three steps.
 *
 * @param onFinished called when the user is logged in and the first sync is done or was left to
 *   run in the background (and the battery tip, if shown, is closed).
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
    val currentOnFinished by rememberUpdatedState(onFinished)
    val context = LocalContext.current
    var language by remember(languageController) { mutableStateOf(languageController.current) }

    LaunchedEffect(viewModel) {
        viewModel.start(startAtServerStep)
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.DemoStarted -> currentOnStartDemo()
                OnboardingEvent.Finished -> currentOnFinished()
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
        onOpenBatterySettings = { openBatterySettings(context) },
        modifier = modifier,
        crashNotice = crashNotice,
    )
}

/**
 * Opens this app's page in the system settings, where "Battery" lets the user allow background
 * use (Samsung: "Unrestricted"). Falls back to the list of battery optimisations. Only an Intent:
 * no permission and no forced flow (plan 6.2 "Efter inloggning", design README "Behörigheter").
 */
private fun openBatterySettings(context: Context) {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    // The first page that opens wins; a device without either settings page just keeps the tip.
    listOf(appDetails, fallback).any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
