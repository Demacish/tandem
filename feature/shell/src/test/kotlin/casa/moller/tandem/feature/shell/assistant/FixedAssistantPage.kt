package casa.moller.tandem.feature.shell.assistant

import androidx.compose.runtime.Composable
import casa.moller.tandem.feature.shell.ui.GroupDivider

// Fas 5 (funktioner lane): Mer → Assistenten in the shell's tests without Hilt.

internal val ASSISTANT_OFF = AssistantSettingsUiState(loaded = true)
internal val ASSISTANT_ON = ASSISTANT_OFF.copy(enabled = true)
internal val ASSISTANT_LOCKED = ASSISTANT_ON.copy(lockEnabled = true)
internal val ASSISTANT_LOCKED_ALLOWED = ASSISTANT_LOCKED.copy(allowWhenLocked = true)
internal val ASSISTANT_UNSUPPORTED = ASSISTANT_OFF.copy(supported = false)
internal val ASSISTANT_GUEST = ASSISTANT_ON.copy(guest = true)
internal val ASSISTANT_DEMO = ASSISTANT_ON.copy(demo = true)

/** The row and the page with a fixed state. */
internal class FixedAssistantPage(
    private val state: AssistantSettingsUiState = ASSISTANT_ON,
    private val onAction: (AssistantSettingsAction) -> Unit = {},
) : AssistantPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) {
        GroupDivider()
        AssistantMoreRow(state, selected, onOpen)
    }

    @Composable
    override fun Assistant(onNavigateBack: () -> Unit) = AssistantSettingsScreen(state, onAction, onNavigateBack)
}
