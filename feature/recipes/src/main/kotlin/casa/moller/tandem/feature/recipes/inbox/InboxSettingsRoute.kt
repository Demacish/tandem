package casa.moller.tandem.feature.recipes.inbox

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
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InboxSettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                InboxSettingsEffect.OpenInbox -> currentOpenInbox()
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
