package casa.moller.tandem.core.data.account

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome

/**
 * Connecting to a Tandoor server and logging in (plan 3.1, 4.2). Works with any Tandoor: the
 * address always comes from the user, never from the app.
 *
 * Passwords are used for one request and never stored; only this device's own token is saved,
 * encrypted (plan 5.5). No call is retried automatically: the login endpoint allows 10 calls per
 * day and IP, failures included.
 */
interface AccountRepository {

    /**
     * Checks whether [url] (as typed: `https://` is added, deep links are cut) is a Tandoor
     * server and which version it runs. Sends no token. At most 3 requests.
     */
    suspend fun probe(url: String): Outcome<ServerInfo>

    /**
     * Logs in with username and password, creates this device's own token, stores the account
     * encrypted and makes it the active one (demo mode is switched off).
     *
     * Errors to expect: `BadRequest` (wrong username or password), `Throttled` (10 attempts per
     * day), network and TLS errors, and the [casa.moller.tandem.core.data.DataProblem]s
     * `SERVER_TOO_OLD`, `NO_SPACE` and `SECURE_STORAGE`.
     */
    suspend fun login(server: ServerInfo, username: String, password: String): Outcome<ServerAccount>

    /**
     * Logs in with a token the user created in Tandoor (Settings -> API) and pasted (plan 4.2
     * step 2). An invalid token gives `Unauthorized`; a blank one `BadRequest`.
     */
    suspend fun loginWithToken(server: ServerInfo, token: String): Outcome<ServerAccount>
}
