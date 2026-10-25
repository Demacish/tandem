package casa.moller.tandem.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.settings.AppearanceRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.feature.shell.ShellMessage
import casa.moller.tandem.feature.shell.ShellMessageQueue
import casa.moller.tandem.feature.shell.navigation.TimerOpenRequest
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
    /** The server address the intro starts with ("Log in again"), null for an empty field. */
    val onboardingServerUrl: String? = null,
    /**
     * The intro was opened by "Log in again" while the account is still logged in: it offers
     * "Cancel" (and system back on its first step) to return to the app unchanged.
     */
    val canCancelOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** False until the stored theme has been read; [themeMode] is only a default before that. */
    val themeModeLoaded: Boolean = false,
    /** Settings → Appearance → "Dynamisk färg" (Phase 1b), applied by the whole app at once. */
    val dynamicColor: Boolean = false,
    /**
     * A tapped timer notification or lock-screen alarm (Phase 1b) that the shell has not opened
     * yet. Saved, so a request that arrives while the splash is up or just before process death
     * is still opened once.
     */
    val timerRequest: TimerOpenRequest? = null,
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
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val session: StateFlow<Session?> =
        sessionRepository.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val appMessages = Channel<AppMessage>(Channel.BUFFERED)

    val messages: Flow<AppMessage> = merge(
        appMessages.receiveAsFlow(),
        shellMessages.messages.map { AppMessage.FromShell(it) },
    )

    private val introFlags: Flow<IntroFlags> = combine(
        savedStateHandle.getStateFlow(KEY_HOLD_ONBOARDING, false),
        savedStateHandle.getStateFlow(KEY_START_AT_SERVER, false),
        savedStateHandle.getStateFlow<String?>(KEY_SERVER_URL, null),
        savedStateHandle.getStateFlow(KEY_LOG_IN_AGAIN, false),
    ) { hold, atServer, serverUrl, logInAgain -> IntroFlags(hold, atServer, serverUrl, logInAgain) }

    /** [TimerOpenRequest] as `[recipeId or -1, stepIndex or -1]` (Bundle-friendly). */
    private val timerRequest: Flow<TimerOpenRequest?> = combine(
        savedStateHandle.getStateFlow<LongArray?>(KEY_TIMER_REQUEST, null),
        savedStateHandle.getStateFlow<String?>(KEY_TIMER_REQUEST_SOURCE, null),
    ) { ids, source -> ids?.toTimerRequest()?.copy(sourceKey = source) }

    val uiState: StateFlow<AppUiState> = combine(
        session,
        introFlags,
        appearanceRepository.themeMode,
        settingsRepository.settings.map { it.dynamicColor }.distinctUntilChanged(),
        timerRequest,
    ) { current, flags, theme, dynamicColor, openTimer ->
        AppUiState(
            destination = resolveDestination(current, flags.hold),
            session = current,
            startOnboardingAtServer = flags.atServer,
            onboardingServerUrl = flags.serverUrl,
            canCancelOnboarding = flags.logInAgain && flags.hold && current is Session.LoggedIn,
            themeMode = theme,
            themeModeLoaded = true,
            dynamicColor = dynamicColor,
            timerRequest = openTimer,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    private data class IntroFlags(val hold: Boolean, val atServer: Boolean, val serverUrl: String?, val logInAgain: Boolean)

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
                        // A timer request from before the logout has no shell to open in.
                        SessionKind.NO_ACCOUNT -> {
                            savedStateHandle[KEY_HOLD_ONBOARDING] = true
                            savedStateHandle[KEY_TIMER_REQUEST] = null
                        }

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
        savedStateHandle[KEY_SERVER_URL] = null
        savedStateHandle[KEY_LOG_IN_AGAIN] = false
    }

    /**
     * "Log in again" after Tandoor refused the stored token (plan 4.1, 5.4): the intro opens at
     * the server step with the account's address filled in, while the account stays stored.
     * Logging in to the same user and space replaces only its token, so the stored recipes and
     * the outbox are kept and the paused sync goes on. "Cancel" (or system back on the server
     * step) calls [cancelLogInAgain] and returns to the app with the old session as it was.
     */
    fun logInAgain(serverUrl: String) {
        savedStateHandle[KEY_SERVER_URL] = serverUrl
        savedStateHandle[KEY_START_AT_SERVER] = true
        savedStateHandle[KEY_LOG_IN_AGAIN] = true
        savedStateHandle[KEY_HOLD_ONBOARDING] = true
    }

    /**
     * Leaves the intro opened by [logInAgain] without logging in: back to the app (offline
     * recipes, Today) with the stored account and its outbox unchanged. Does nothing without a
     * logged-in account, so an intro that must be completed can never be skipped.
     */
    fun cancelLogInAgain() {
        if (session.value is Session.LoggedIn && savedStateHandle.get<Boolean>(KEY_LOG_IN_AGAIN) == true) releaseOnboarding()
    }

    /** "Connect your Tandoor" in demo mode: leave demo and open the intro at the server step. */
    fun connectFromDemo() {
        savedStateHandle[KEY_START_AT_SERVER] = true
        viewModelScope.launch { sessionRepository.leaveDemo() }
    }

    /**
     * A timer notification or the lock-screen alarm opened the app (`MainActivity` reads
     * core:timer's `TimerIntents.ACTION_OPEN_TIMER`): the shell opens cook mode at the timer's
     * step, or the timer list, as soon as it is shown ([onTimerRequestHandled]).
     */
    fun onOpenTimer(request: TimerOpenRequest) {
        // The source first: the request is complete when the ids arrive.
        savedStateHandle[KEY_TIMER_REQUEST_SOURCE] = request.sourceKey
        savedStateHandle[KEY_TIMER_REQUEST] = longArrayOf(request.recipeId ?: NONE, request.stepIndex?.toLong() ?: NONE)
    }

    /** The shell opened [AppUiState.timerRequest]. */
    fun onTimerRequestHandled() {
        savedStateHandle[KEY_TIMER_REQUEST] = null
        savedStateHandle[KEY_TIMER_REQUEST_SOURCE] = null
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
        const val KEY_SERVER_URL = "app.onboardingServerUrl"
        const val KEY_LOG_IN_AGAIN = "app.logInAgain"
        const val KEY_TIMER_REQUEST = "app.timerRequest"
        const val KEY_TIMER_REQUEST_SOURCE = "app.timerRequestSource"
        private const val NONE = -1L

        private fun LongArray.toTimerRequest(): TimerOpenRequest? {
            if (size != 2) return null
            val recipeId = this[0].takeIf { it > 0 }
            val step = this[1].takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
            return TimerOpenRequest(recipeId, step)
        }

        fun resolveDestination(session: Session?, holdOnboarding: Boolean): AppDestination = when (session) {
            null -> AppDestination.Loading

            is Session.RecoveryNeeded -> AppDestination.Recovery

            Session.NoAccount -> AppDestination.Onboarding

            Session.Demo, is Session.LoggedIn ->
                if (holdOnboarding) AppDestination.Onboarding else AppDestination.Shell
        }
    }
}
