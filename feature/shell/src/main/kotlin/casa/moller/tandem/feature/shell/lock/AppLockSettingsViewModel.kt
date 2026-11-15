package casa.moller.tandem.feature.shell.lock

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import casa.moller.tandem.feature.shell.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mer → Lås appen as the page shows it (Fas 5, las lane, plan §5.5).
 *
 * @property loaded the stored setting has been read.
 * @property enabled the lock is on.
 * @property timeout "Lås efter".
 * @property screenLockMissing the phone has no screen lock: the lock cannot be turned on, and the
 *   page explains it with the way to the system settings.
 * @property confirming the system's prompt is open to confirm turning the lock on.
 */
data class AppLockSettingsUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val timeout: AppLockTimeout = AppLockTimeout.DEFAULT,
    val screenLockMissing: Boolean = false,
    val confirming: Boolean = false,
) {
    /** The switch can be used: off with a screen lock to confirm, or on (turning it off needs nothing). */
    val switchEnabled: Boolean
        get() = loaded && !confirming && (enabled || !screenLockMissing)
}

/** What the user (and the page's route) does on Mer → Lås appen. */
sealed interface AppLockSettingsAction {
    /** The switch: turning it on first confirms the screen lock ([AppLockSettingsEffect.ConfirmScreenLock]). */
    data class SetEnabled(val enabled: Boolean) : AppLockSettingsAction

    data class SetTimeout(val timeout: AppLockTimeout) : AppLockSettingsAction

    /** "Ställ in skärmlås". */
    data object OpenScreenLockSettings : AppLockSettingsAction

    /** The route looked whether the phone has a screen lock (when the page comes up, and back from the settings). */
    data class ScreenLockChecked(val available: Boolean) : AppLockSettingsAction

    /** How the prompt that confirms turning the lock on ended. */
    data class Confirmed(val result: DeviceAuthResult) : AppLockSettingsAction
}

/** One-off effects for the route. */
sealed interface AppLockSettingsEffect {
    /** Show the system's prompt; answer with [AppLockSettingsAction.Confirmed]. */
    data object ConfirmScreenLock : AppLockSettingsEffect

    /** Open the system's security settings. */
    data object OpenScreenLockSettings : AppLockSettingsEffect

    /** A snackbar with one of our texts. */
    data class Message(@StringRes val text: Int) : AppLockSettingsEffect

    /** A snackbar with the system's own text (already in the phone's language). */
    data class SystemMessage(val text: String) : AppLockSettingsEffect
}

/**
 * Mer → Lås appen (docs/BYGGE.md "Lås appen"): the setting through core:data's [AppLock]. Turning
 * it on asks the phone's screen lock once first, so nobody locks themselves out with a screen lock
 * they cannot confirm; turning it off needs nothing (the app is open).
 */
@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private val effects = Channel<AppLockSettingsEffect>(Channel.BUFFERED)
    private val page = MutableStateFlow(PageState())

    val effect: Flow<AppLockSettingsEffect> = effects.receiveAsFlow()

    val uiState: StateFlow<AppLockSettingsUiState> = combine(appLock.settings, page) { settings, local ->
        AppLockSettingsUiState(
            loaded = true,
            enabled = settings.enabled,
            timeout = settings.timeout,
            screenLockMissing = local.screenLockMissing,
            confirming = local.confirming,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppLockSettingsUiState())

    fun onAction(action: AppLockSettingsAction) {
        when (action) {
            is AppLockSettingsAction.SetEnabled -> setEnabled(action.enabled)
            is AppLockSettingsAction.SetTimeout -> viewModelScope.launch { report(appLock.setTimeout(action.timeout)) }
            AppLockSettingsAction.OpenScreenLockSettings -> effects.trySend(AppLockSettingsEffect.OpenScreenLockSettings)
            is AppLockSettingsAction.ScreenLockChecked -> page.update { it.copy(screenLockMissing = !action.available) }
            is AppLockSettingsAction.Confirmed -> onConfirmed(action.result)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { report(appLock.setEnabled(false)) }
            return
        }
        val current = page.value
        if (current.confirming || current.screenLockMissing) return
        page.update { it.copy(confirming = true) }
        effects.trySend(AppLockSettingsEffect.ConfirmScreenLock)
    }

    private fun onConfirmed(result: DeviceAuthResult) {
        page.update { it.copy(confirming = false) }
        when (result) {
            DeviceAuthResult.Succeeded -> viewModelScope.launch { report(appLock.setEnabled(true)) }

            DeviceAuthResult.Cancelled -> Unit

            DeviceAuthResult.NoScreenLock -> page.update { it.copy(screenLockMissing = true) }

            is DeviceAuthResult.Failed -> effects.trySend(
                result.message?.let { AppLockSettingsEffect.SystemMessage(it) }
                    ?: AppLockSettingsEffect.Message(R.string.shell_lock_confirm_failed),
            )
        }
    }

    private suspend fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failure) effects.send(AppLockSettingsEffect.Message(R.string.shell_settings_not_saved))
    }

    private data class PageState(val screenLockMissing: Boolean = false, val confirming: Boolean = false)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
