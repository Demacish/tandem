package casa.moller.tandem.core.data.account

import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.error.Outcome

// Phase 2 (konto lane): switching and removing stored accounts (decision B14, plan 6.2 "Konto/server").

/**
 * The account half of switching and removing (implemented next to the login, by the same object
 * as [AccountRepository] and `SessionRepository`, so all three share one write lock). Used by the
 * [AccountDirectory] implementation in core:sync, which adds the local data (outbox, household,
 * role) and the clean-up of the removed account's rows; screens use [AccountDirectory].
 */
interface AccountSessions {
    /**
     * Makes the stored account [accountId] the active one and leaves demo mode. Every account's
     * data stays on the device. Fails with `DataProblem.NO_ACCOUNT` for an unknown id and with
     * `DataProblem.SECURE_STORAGE` when the settings cannot be written.
     */
    suspend fun activate(accountId: String): Outcome<Unit>

    /**
     * Removes the stored account [accountId] and its token from the device, then (only if
     * [revokeToken] and Tandem created the token) revokes the token on the server, the same rules
     * as `SessionRepository.logout`. Removing the active account makes the first remaining one
     * active (or none: the intro). Fails with `DataProblem.NO_ACCOUNT` for an unknown id.
     */
    suspend fun remove(accountId: String, revokeToken: Boolean): Outcome<TokenRevocation>
}
