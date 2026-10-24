package casa.moller.tandem.feature.onboarding

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import kotlin.time.Duration

/**
 * The steps of the intro (plan 6.2). Welcome, server and login are "steg 1-3 av 3"; after a login
 * come the connected summary, the first sync and (Samsung only) the battery tip ("Efter
 * inloggning").
 */
enum class OnboardingStep {
    WELCOME,
    SERVER,
    LOGIN,
    CONNECTED,

    /** The first sync with progress (Stitch intro_f_rsta_synk). */
    FIRST_SYNC,

    /** A short tip about Samsung's battery optimisation; never a forced flow. */
    BATTERY_TIP,
}

/** How the user logs in on the login step. */
enum class LoginMethod {
    PASSWORD,

    /** Paste an API token created in Tandoor (plan 4.2 step 2). */
    TOKEN,
}

/** Result of "Connect" on the server step. */
sealed interface ServerCheck {
    data object Idle : ServerCheck

    data object Checking : ServerCheck

    data class Failed(val error: TandemError) : ServerCheck

    /** A newer or unrecognised version: the user must confirm to continue in safe mode. */
    data class NeedsConfirmation(val server: ServerInfo) : ServerCheck

    /** Older than 2.6.0: login is blocked. */
    data class TooOld(val server: ServerInfo) : ServerCheck
}

/** Why the login step failed, with the message the screen shows for it. */
sealed interface LoginError {
    /** 400 on `api-token-auth`. */
    data object WrongCredentials : LoginError

    /** 429: 10 login calls per day and IP, failures included. Never retried automatically. */
    data class Throttled(val retryAfter: Duration?) : LoginError

    /** The pasted token is unknown, expired or revoked. */
    data object InvalidToken : LoginError

    /** The user exists but has no space on the server. */
    data object NoSpace : LoginError

    /**
     * The password login endpoint is gone: Tandoor 2.7 removed `POST /api-token-auth/` (it now
     * answers with a redirect to the web login), or something in front of Tandoor sends it there.
     * The pasted token still works, so the step points to it.
     */
    data object PasswordLoginUnavailable : LoginError

    /** The token could not be saved in encrypted storage on this device. */
    data object StorageFailed : LoginError

    /** Anything else (network, TLS, server error): the generic text for [error]. */
    data class Other(val error: TandemError) : LoginError
}

/**
 * The first sync after a login (plan 6.2 "Efter inloggning": "Fetching 312 recipes…").
 *
 * @property progress the last progress reported, null before the first one.
 * @property recipeCount recipes on the server, once the index phase reported a total.
 * @property error why the sync stopped; the step offers "Try again" and "Continue anyway".
 */
data class FirstSyncState(val progress: SyncProgress? = null, val recipeCount: Int? = null, val error: TandemError? = null) {
    /** The phase running now; null before the first progress or after an error. */
    val currentPhase: SyncPhase?
        get() = progress?.phase?.takeIf { error == null && it != SyncPhase.FAILED }

    /**
     * Overall progress 0..1 across the [VisiblePhases] (the current phase's own fraction counts
     * within its slot), null before the first progress.
     */
    val overallFraction: Float?
        get() {
            val current = progress ?: return null
            if (current.phase == SyncPhase.DONE || current.phase == SyncPhase.OUTBOX) return 1f
            val index = VisiblePhases.indexOf(current.phase).takeIf { it >= 0 } ?: return null
            return ((index + (current.fraction ?: 0f)) / VisiblePhases.size).coerceIn(0f, 1f)
        }

    /** Where [phase] stands in this sync. */
    fun statusOf(phase: SyncPhase): PhaseStatus {
        val currentIndex = progress?.phase?.let { current ->
            when (current) {
                SyncPhase.DONE, SyncPhase.OUTBOX -> VisiblePhases.size
                else -> VisiblePhases.indexOf(current)
            }
        } ?: -1
        val index = VisiblePhases.indexOf(phase)
        return when {
            currentIndex < 0 -> PhaseStatus.WAITING
            index < currentIndex -> PhaseStatus.DONE
            index == currentIndex && error != null -> PhaseStatus.FAILED
            index == currentIndex -> PhaseStatus.RUNNING
            else -> PhaseStatus.WAITING
        }
    }

    companion object {
        /** The phases the first sync step lists, in order (plan 3.5). */
        val VisiblePhases: List<SyncPhase> = listOf(
            SyncPhase.SERVER_CHECK,
            SyncPhase.REFERENCE_DATA,
            SyncPhase.RECIPE_INDEX,
            SyncPhase.RECIPE_DETAILS,
            SyncPhase.IMAGES,
            SyncPhase.MEAL_PLAN,
        )
    }
}

/** A phase in the first sync step's list. */
enum class PhaseStatus {
    WAITING,
    RUNNING,
    DONE,
    FAILED,
}

/** What the connected step shows: "Connected to <server> · Tandoor <version>". */
data class ConnectedSummary(val serverAddress: String, val serverVersion: String, val accountLabel: String, val safeMode: Boolean)

/**
 * Everything the intro screens show. [password] and [token] live only in memory: they are never
 * written to the saved state, so they are gone after process death (plan 4.2).
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val serverUrl: String = "",
    val serverCheck: ServerCheck = ServerCheck.Idle,
    /** The confirmed server for the login step. */
    val server: ServerInfo? = null,
    val loginMethod: LoginMethod = LoginMethod.PASSWORD,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val loginInProgress: Boolean = false,
    val loginError: LoginError? = null,
    val connected: ConnectedSummary? = null,
    val firstSync: FirstSyncState = FirstSyncState(),
) {
    val canConnect: Boolean
        get() = serverUrl.isNotBlank() && serverCheck != ServerCheck.Checking

    val canLogIn: Boolean
        get() = server != null && !loginInProgress && when (loginMethod) {
            LoginMethod.PASSWORD -> username.isNotBlank() && password.isNotEmpty()
            LoginMethod.TOKEN -> token.isNotBlank()
        }

    /** Never prints the password or the token. */
    override fun toString(): String = "OnboardingUiState(step=$step, serverUrl=$serverUrl, serverCheck=$serverCheck, server=$server, " +
        "loginMethod=$loginMethod, username=$username, password=<${password.length} chars>, " +
        "token=<${token.length} chars>, loginInProgress=$loginInProgress, loginError=$loginError, " +
        "connected=$connected, firstSync=$firstSync)"
}

/** One-off events for the composable (plan 5.3: effects through a Channel). */
sealed interface OnboardingEvent {
    /** Demo mode is switched on; the app leaves the intro. */
    data object DemoStarted : OnboardingEvent

    /** Logged in, first sync done (or left to run in the background): the app leaves the intro. */
    data object Finished : OnboardingEvent
}

/** User actions of the intro. Implemented by [OnboardingViewModel]; screenshot tests use no-ops. */
interface OnboardingActions {
    fun onGetStarted()

    fun onBack()

    fun onServerUrlChange(value: String)

    fun onConnect()

    fun onContinueInSafeMode()

    fun onUsernameChange(value: String)

    fun onPasswordChange(value: String)

    fun onTokenChange(value: String)

    fun onLoginMethodChange(method: LoginMethod)

    fun onLogin()

    fun onStartDemo()

    /** "Continue" on the connected step: starts the first sync. */
    fun onContinueAfterLogin()

    /** "Try again" after the first sync failed. */
    fun onRetryFirstSync()

    /**
     * "Log in again" after Tandoor refused the new login during the first sync (a retry cannot
     * help, the sync pauses until a new login): back to the login step of the same server.
     */
    fun onLogInAgainAfterFirstSync()

    /**
     * "Continue in the background" while the first sync runs, or "Continue anyway" after it
     * failed: the intro ends and the sync goes on (or is retried) in the background.
     */
    fun onContinueFirstSyncInBackground()

    /** "Done" on the battery tip. */
    fun onBatteryTipDone()
}

/**
 * Values the app passes in (from `:app`, Hilt).
 *
 * @property defaultServerUrl pre-fills the server field. Empty in every build unless the
 *   sideload build sets the Gradle property `tandem.defaultServerUrl` (plan 4.5: no server
 *   address is hard-coded in the app).
 * @property showBatteryTip show the battery optimisation tip after the first sync: true on
 *   Samsung devices, whose battery optimisation stops background work more often (plan 6.2,
 *   open question 13).
 */
data class OnboardingConfig(val defaultServerUrl: String = "", val showBatteryTip: Boolean = false)
