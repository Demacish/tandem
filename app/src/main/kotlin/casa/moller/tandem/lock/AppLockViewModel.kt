package casa.moller.tandem.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The lock screen's own state (Fas 5, las lane, plan §5.5).
 *
 * @property prompting the system's prompt is open (no second one, no automatic one).
 * @property noScreenLock the phone has no screen lock (any more): the screen explains it and
 *   offers the system settings, or to go on without the lock this time.
 * @property failure the system's own text after a prompt that ended badly (too many attempts),
 *   null when there is none or the prompt was only closed.
 * @property failed a prompt ended badly without a text of its own.
 */
data class AppLockUiState(
    val prompting: Boolean = false,
    val noScreenLock: Boolean = false,
    val failure: String? = null,
    val failed: Boolean = false,
)

/** What the lock screen can do. */
sealed interface AppLockAction {
    /** "Lås upp": show the system's prompt. */
    data object Unlock : AppLockAction

    /** No screen lock: open the system's security settings. */
    data object OpenScreenLockSettings : AppLockAction

    /** No screen lock: go on without the lock this time (the setting stays on). */
    data object ContinueWithoutLock : AppLockAction
}

/**
 * Mer → Lås appen as the whole app sees it (docs/BYGGE.md "Lås appen"): [status] decides whether
 * the content is hidden; the lock screen reports the system prompt's results here.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private val screen = MutableStateFlow(AppLockUiState())

    /** Where the lock stands; [AppLockStatus.UNKNOWN] keeps the splash screen. */
    val status: StateFlow<AppLockStatus> = appLock.status

    /** The lock is on (Recents shows no picture of the app). Null until read. */
    val lockEnabled: StateFlow<Boolean?> = appLock.settings
        .map { it.enabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<AppLockUiState> = screen.asStateFlow()

    /** A prompt is about to open. */
    fun onPromptShown() = screen.update { it.copy(prompting = true, failure = null, failed = false) }

    /** How the prompt ended. */
    fun onAuthResult(result: DeviceAuthResult) {
        when (result) {
            DeviceAuthResult.Succeeded -> {
                screen.value = AppLockUiState()
                appLock.onUnlocked()
            }

            DeviceAuthResult.Cancelled -> screen.update { it.copy(prompting = false) }

            DeviceAuthResult.NoScreenLock -> screen.update { it.copy(prompting = false, noScreenLock = true) }

            is DeviceAuthResult.Failed -> screen.update {
                it.copy(prompting = false, failure = result.message, failed = result.message == null)
            }
        }
    }

    /** The phone has a screen lock again (back from the system settings): unlock with it as usual. */
    fun onScreenLockAvailable() = screen.update { it.copy(noScreenLock = false) }

    /**
     * "Fortsätt utan lås": the phone has no screen lock, so there is nothing to confirm; anyone
     * holding the open phone could set one and confirm it. The lock stays on for the next time.
     */
    fun continueWithoutLock() {
        if (!screen.value.noScreenLock) return
        screen.value = AppLockUiState()
        appLock.onUnlocked()
    }
}
