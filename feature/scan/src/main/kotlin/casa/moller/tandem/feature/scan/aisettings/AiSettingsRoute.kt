package casa.moller.tandem.feature.scan.aisettings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Settings → AI: the ViewModel, the snackbar, and the key field's text. The text is kept with
 * `remember` (NOT `rememberSaveable`): a key must never land in the saved instance state, which
 * Android may write to disk. It is cleared as soon as the key is stored.
 */
@Composable
fun AiSettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AiSettingsEffect.KeyStored -> key = ""
                is AiSettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }

    AiSettingsScreen(
        state = state,
        key = key,
        onKeyChange = { key = it },
        onPaste = {
            scope.launch {
                val clip = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                val pasted = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!pasted.isNullOrBlank()) key = pasted.trim()
            }
        },
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}
