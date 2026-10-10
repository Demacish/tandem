package casa.moller.tandem.feature.onboarding

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.model.error.TandemError
import kotlin.time.Duration

/** The steps of the intro (plan 6.2). Welcome, server and login are "steg 1-3 av 3". */
enum class OnboardingStep {
    WELCOME,
    SERVER,
    LOGIN,
    CONNECTED,
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

    /** The token could not be saved in encrypted storage on this device. */
    data object StorageFailed : LoginError

    /** Anything else (network, TLS, server error): the generic text for [error]. */
    data class Other(val error: TandemError) : LoginError
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
        "connected=$connected)"
}

/** One-off events for the composable (plan 5.3: effects through a Channel). */
sealed interface OnboardingEvent {
    /** Demo mode is switched on; the app leaves the intro. */
    data object DemoStarted : OnboardingEvent
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
}

/**
 * Values the app passes in (from `:app`, Hilt).
 *
 * @property defaultServerUrl pre-fills the server field. Empty in every build unless the
 *   sideload build sets the Gradle property `tandem.defaultServerUrl` (plan 4.5: no server
 *   address is hard-coded in the app).
 */
data class OnboardingConfig(val defaultServerUrl: String = "")
