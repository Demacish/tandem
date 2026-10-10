package casa.moller.tandem.core.data.session

import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.settings.RecoveryReason

/** What the app should show at the top level (start destination, plan 4.2 / 6.2). */
sealed interface Session {

    /** No usable account and not in demo mode: show the intro / login. */
    data object NoAccount : Session

    /** The app runs on local demo data without a server (plan 4.5 / 6.2). */
    data object Demo : Session

    /**
     * Logged in to [account].
     *
     * @property serverVersion Tandoor version seen at login or at the last
     *   [SessionRepository.refreshServerInfo], e.g. `2.6.13`.
     * @property compatibility how the app treats [serverVersion] (safe mode for untested ones).
     */
    data class LoggedIn(val account: ServerAccount, val serverVersion: String?, val compatibility: ServerCompatibility) : Session

    /**
     * Local storage had to be reset (plan 4.2 step 7, feature #37). The app shows the recovery
     * screen ("Log in again" / "Clear app data") instead of crashing.
     */
    data class RecoveryNeeded(val reasons: Set<RecoveryReason>) : Session
}

/** What happened to this device's token on logout (plan 4.2 step 4). */
enum class TokenRevocation {
    /** The token was deleted on the server. */
    REVOKED,

    /** The server no longer knew the token (already deleted, expired or revoked). */
    ALREADY_INVALID,

    /**
     * Revocation was requested but did not happen: the server could not be reached, or it (or
     * something in front of it, such as an SSO proxy answering 401) refused. The token may still
     * be valid; the user should delete it in Tandoor.
     */
    FAILED,

    /** The token's id is unknown, so the app cannot revoke it; delete it in Tandoor. */
    UNKNOWN_TOKEN_ID,

    /**
     * The token was pasted by the user (it may be in use by other apps or devices), so the app
     * left it on the server. The user can delete it in Tandoor if it is no longer needed.
     */
    NOT_OWNED,

    /** Revocation was not requested (or there was no token). */
    SKIPPED,
}
