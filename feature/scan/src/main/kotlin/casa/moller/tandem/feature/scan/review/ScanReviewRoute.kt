package casa.moller.tandem.feature.scan.review

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import casa.moller.tandem.feature.scan.ui.scanErrorText
import kotlinx.coroutines.launch

/**
 * The shared review list for [key] (docs/BYGGE.md "Skanning"): closes itself after "Spara" (the
 * scanner below sees the session saved and closes or starts over); a failure stays with a snackbar.
 */
@Composable
fun ScanReviewRoute(key: ScanReviewKey, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ScanReviewViewModel, ScanReviewViewModel.Factory>(
        key = "scan-review-${key.sessionId}",
        creationCallback = { factory -> factory.create(key) },
    )
    ScanReviewRoute(viewModel = viewModel, onBack = onBack, modifier = modifier)
}

@Composable
internal fun ScanReviewRoute(viewModel: ScanReviewViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val currentBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ScanReviewEffect.Saved -> currentBack()

                is ScanReviewEffect.Failed -> launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(resources.scanErrorText(effect.error))
                }
            }
        }
    }
    ScanReviewScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}
