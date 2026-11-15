package casa.moller.tandem.feature.shell.assistant

import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.assistant.AssistantSettingsRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.feature.shell.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Mer → Assistenten as the page shows it (Fas 5, funktioner lane, plan §8.7).
 *
 * @property loaded the stored settings have been read.
 * @property enabled "Låt assistenten använda Tandem".
 * @property allowWhenLocked "Tillåt assistenten när appen är låst".
 * @property supported the phone runs Android 16 or newer, where assistants can use App Functions.
 * @property guest the user is a guest in the Tandoor space: the assistant can only read.
 * @property demo demo mode: the assistant uses the sample data.
 */
data class AssistantSettingsUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val allowWhenLocked: Boolean = false,
    val supported: Boolean = true,
    val guest: Boolean = false,
    val demo: Boolean = false,
)

/** What the user does on Mer → Assistenten. */
sealed interface AssistantSettingsAction {
    data class SetEnabled(val enabled: Boolean) : AssistantSettingsAction

    data class SetAllowWhenLocked(val allowed: Boolean) : AssistantSettingsAction
}

/** One-off effects for the route. */
sealed interface AssistantSettingsEffect {
    /** A snackbar with one of our texts. */
    data class Message(@StringRes val text: Int) : AssistantSettingsEffect
}

/**
 * Mer → Assistenten (docs/BYGGE.md "App Functions"): the switches through core:data's
 * [AssistantSettingsRepository]; the App Functions in :app follow them.
 */
@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
    private val settings: AssistantSettingsRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    private val effects = Channel<AssistantSettingsEffect>(Channel.BUFFERED)

    val effect: Flow<AssistantSettingsEffect> = effects.receiveAsFlow()

    val uiState: StateFlow<AssistantSettingsUiState> = combine(settings.settings, permissions.permissions) { choice, space ->
        AssistantSettingsUiState(
            loaded = true,
            enabled = choice.enabled,
            allowWhenLocked = choice.allowWhenLocked,
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA,
            guest = space.readOnly,
            demo = space.isDemo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AssistantSettingsUiState())

    fun onAction(action: AssistantSettingsAction) {
        viewModelScope.launch {
            val outcome = when (action) {
                is AssistantSettingsAction.SetEnabled -> settings.setEnabled(action.enabled)
                is AssistantSettingsAction.SetAllowWhenLocked -> settings.setAllowWhenLocked(action.allowed)
            }
            if (outcome is Outcome.Failure) effects.send(AssistantSettingsEffect.Message(R.string.shell_settings_not_saved))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
