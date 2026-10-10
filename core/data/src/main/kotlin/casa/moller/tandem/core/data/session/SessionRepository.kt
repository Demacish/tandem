package casa.moller.tandem.core.data.session

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/** The app-wide session: which account is active, demo mode, recovery and logout. */
interface SessionRepository {

    /**
     * The current [Session], derived from the stored settings, the encrypted accounts and the
     * storage health. Recovery wins over everything else, then demo mode, then the active
     * account. An `activeAccountId` that points to a missing account falls back to the first
     * stored account, or [Session.NoAccount].
     */
    val session: Flow<Session>

    /**
     * Probes the active account's server again (plan 3.6 step 3) and stores the version it now
     * reports. Fails with `DataProblem.NO_ACCOUNT` when nobody is logged in.
     */
    suspend fun refreshServerInfo(): Outcome<ServerInfo>

    /**
     * Logs out of the active account: removes the account and its token from the device, then,
     * if [revokeToken] is set, revokes the token on the server. Only a token Tandem created for
     * this device is ever revoked ([casa.moller.tandem.core.model.account.TokenOrigin.CREATED]);
     * a pasted token is left alone and always reported as [TokenRevocation.NOT_OWNED], whatever
     * [revokeToken] says, so that the user learns that it is still valid.
     *
     * The server is only contacted after the local removal worked, so a stored account never
     * points at a revoked token. If the local removal fails nothing is revoked and the result is
     * a failure. The network part runs to the end even if the caller is cancelled.
     */
    suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation>

    /** Switches to demo mode (local sample data, no server). */
    suspend fun startDemo(): Outcome<Unit>

    /** Leaves demo mode; with no account the session becomes [Session.NoAccount]. */
    suspend fun leaveDemo(): Outcome<Unit>

    /** "Log in again" on the recovery screen: the reset was seen, continue with what is left. */
    fun acknowledgeRecovery()

    /**
     * "Clear app data" on the recovery screen: removes every account and token, resets the
     * settings and acknowledges the recovery. Tokens are not revoked (they may be unreadable).
     */
    suspend fun clearAppData(): Outcome<Unit>
}
