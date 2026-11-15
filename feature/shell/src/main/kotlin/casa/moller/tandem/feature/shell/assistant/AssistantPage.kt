package casa.moller.tandem.feature.shell.assistant

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.icon.Mic
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.GroupDivider
import casa.moller.tandem.feature.shell.ui.SettingsRow

// Fas 5 (funktioner lane, plan §8.7): Mer → Assistenten as the shell shows it. Kept apart from
// SettingsPages (like WatchPage) so the shell's own tests show the stateless screen and the Mer row
// without Hilt.

/** The row in Mer and its page. Production: [RouteAssistantPage]; [NoAssistantPage] shows nothing. */
@Stable
internal interface AssistantPage {
    /** "Assistenten" with "Av" or "På: …", below a divider (it is the last row of Mer's settings group). */
    @Composable
    fun MoreRow(selected: Boolean, onOpen: () -> Unit)

    @Composable
    fun Assistant(onNavigateBack: () -> Unit)
}

/** No row and no page (the shell's tests that are not about the assistant). */
internal object NoAssistantPage : AssistantPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) = Unit

    @Composable
    override fun Assistant(onNavigateBack: () -> Unit) = Unit
}

/** The real row and page with their [AssistantSettingsViewModel]s. */
internal object RouteAssistantPage : AssistantPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) {
        val viewModel: AssistantSettingsViewModel = hiltViewModel(key = "assistant-more-row")
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        GroupDivider()
        AssistantMoreRow(state = state, selected = selected, onOpen = onOpen)
    }

    @Composable
    override fun Assistant(onNavigateBack: () -> Unit) {
        val viewModel: AssistantSettingsViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val resources = LocalResources.current
        LaunchedEffect(viewModel) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is AssistantSettingsEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                }
            }
        }
        AssistantSettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbarHostState,
        )
    }
}

/** Mer → "Assistenten" with "Av" or "På: telefonens assistent kan använda listan och matsedeln". */
@Composable
internal fun AssistantMoreRow(state: AssistantSettingsUiState, selected: Boolean, onOpen: () -> Unit) {
    SettingsRow(
        icon = TandemIcons.Mic,
        title = stringResource(R.string.shell_assistant_title),
        supporting = when {
            !state.loaded -> null
            state.enabled -> stringResource(R.string.shell_assistant_more_on)
            else -> stringResource(R.string.shell_assistant_more_off)
        },
        onClick = onOpen,
        selected = selected,
    )
}
