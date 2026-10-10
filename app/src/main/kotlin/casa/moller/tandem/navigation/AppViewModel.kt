package casa.moller.tandem.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.feature.shell.ShellMessage
import casa.moller.tandem.feature.shell.ShellMessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Top-level destinations (Navigation 3 keys). The app-level back stack always holds exactly one
 * of them, derived from the persisted session plus [SavedStateHandle] flags, so it is restored
 * after process death without saving the stack itself. Navigation inside the shell has its own
 * saved per-tab back stacks (feature:shell).
 */
sealed interface AppDestination {
    /** The session is not known yet; the splash screen stays up. */
    data object Loading : AppDestination

    /** The intro: welcome, server, login, connected (feature:onboarding). */
    data object Onboarding : AppDestination

    /** The tabbed app for a logged-in account or demo mode (feature:shell). */
    data object Shell : AppDestination

    /** Stored logins or settings could not be read (plan 4.2 step 7). */
    data object Recovery : AppDestination
}

data class AppUiState(
    val destination: AppDestination = AppDestination.Loading,
    val session: Session? = null,
    val startOnboardingAtServer: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** False until the stored theme has been read; [themeMode] is only a default before that. */
    val themeModeLoaded: Boolean = false,
)

/** One-off messages shown as a toast. */
sealed interface AppMessage {
    /** From the shell, e.g. the result of a logout (the shell is gone when it arrives). */
    data class FromShell(val message: ShellMessage) : AppMessage

    data object ClearDataFailed : AppMessage
}

/**
 * Chooses the top-level destination from the session (NoAccount -> intro, LoggedIn/Demo ->
 * shell, RecoveryNeeded -> recovery). The intro is held on screen after a login until the user
 * taps "Continue" on its connected step, even though the session already says LoggedIn.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    appearanceRepository: AppearanceRepository,
    shellMessages: ShellMessageQueue,
) : ViewModel() {

    private val session: StateFlow<Session?> =
        sessionRepository.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val appMessages = Channel<AppMessage>(Channel.BUFFERED)

    val messages: Flow<AppMessage> = merge(
        appMessages.receiveAsFlow(),
        shellMessages.messages.map { AppMessage.FromShell(it) },
    )

    val uiState: StateFlow<AppUiState> = combine(
        session,
        savedStateHandle.getStateFlow(KEY_HOLD_ONBOARDING, false),
        savedStateHandle.getStateFlow(KEY_START_AT_SERVER, false),
        appearanceRepository.themeMode,
    ) { current, hold, atServer, theme ->
        AppUiState(
            destination = resolveDestination(current, hold),
            session = current,
            startOnboardingAtServer = atServer,
            themeMode = theme,
            themeModeLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        viewModelScope.launch {
            session.filterNotNull()
                .map { current ->
                    when (current) {
                        Session.NoAccount -> SessionKind.NO_ACCOUNT
                        is Session.RecoveryNeeded -> SessionKind.RECOVERY
                        else -> SessionKind.OTHER
                    }
                }
                .distinctUntilChanged()
                .collect { kind ->
                    when (kind) {
                        // Whenever the app arrives at "no account" (first start, logout, leaving
                        // demo), keep the intro on screen until it reports that it is done.
                        SessionKind.NO_ACCOUNT -> savedStateHandle[KEY_HOLD_ONBOARDING] = true

                        // The recovery screen replaced the intro (its ViewModel is gone), so the
                        // hold must not outlive it: after "Log in again" a stored account goes to
                        // the shell, and no account sets the hold again (NO_ACCOUNT above).
                        SessionKind.RECOVERY -> savedStateHandle[KEY_HOLD_ONBOARDING] = false

                        SessionKind.OTHER -> Unit
                    }
                }
        }
    }

    private enum class SessionKind { NO_ACCOUNT, RECOVERY, OTHER }

    fun onOnboardingFinished() = releaseOnboarding()

    fun onDemoStarted() = releaseOnboarding()

    private fun releaseOnboarding() {
        savedStateHandle[KEY_HOLD_ONBOARDING] = false
        savedStateHandle[KEY_START_AT_SERVER] = false
    }

    /** "Connect your Tandoor" in demo mode: leave demo and open the intro at the server step. */
    fun connectFromDemo() {
        savedStateHandle[KEY_START_AT_SERVER] = true
        viewModelScope.launch { sessionRepository.leaveDemo() }
    }

    /** "Log in again" on the recovery screen. */
    fun acknowledgeRecovery() = sessionRepository.acknowledgeRecovery()

    /** "Clear app data" on the recovery screen. */
    fun clearAppData() {
        viewModelScope.launch {
            if (sessionRepository.clearAppData() is Outcome.Failure) appMessages.send(AppMessage.ClearDataFailed)
        }
    }

    internal companion object {
        const val KEY_HOLD_ONBOARDING = "app.holdOnboarding"
        const val KEY_START_AT_SERVER = "app.startAtServer"

        fun resolveDestination(session: Session?, holdOnboarding: Boolean): AppDestination = when (session) {
            null -> AppDestination.Loading

            is Session.RecoveryNeeded -> AppDestination.Recovery

            Session.NoAccount -> AppDestination.Onboarding

            Session.Demo, is Session.LoggedIn ->
                if (holdOnboarding) AppDestination.Onboarding else AppDestination.Shell
        }
    }
}
