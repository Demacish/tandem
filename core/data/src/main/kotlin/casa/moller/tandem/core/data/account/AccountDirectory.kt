package casa.moller.tandem.core.data.account

import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 extension of [AccountRepository] (a separate interface, so the login implementation and
 * its fakes stay as they are): the stored accounts, switching the active one, and the household
 * (plan 6.2 "Inställningar → Konto/server", 8.6 "Hushåll"; Stitch `konto_servrar`).
 *
 * One account is active at a time (decision B14). Switching keeps every account's data and
 * outbox; the sync and every repository flow follow the new account at once.
 */
interface AccountDirectory {
    /** Every account stored on the device, the active one marked. */
    fun observeAccounts(): Flow<List<AccountSummary>>

    /** Makes [accountId] the active account (demo mode is left). Fails with `DataProblem.NO_ACCOUNT` for an unknown id. */
    suspend fun switchTo(accountId: String): Outcome<Unit>

    /**
     * The active account's household (`/api/user-space/all_personal/` `household`), null without
     * one (plan 8.6: then the list is not shared, show the hint for the web).
     */
    fun observeHousehold(): Flow<Household?>

    /** Reads household and group again (at login, on "Synka nu", at most hourly). */
    suspend fun refreshHousehold(): Outcome<Household?>

    // --- Phase 2 (konto lane): removing an account and its unsent changes. ---

    /**
     * Removes the stored account [accountId] (the active one too: that is "log out") with its
     * token and every local row. Unsent changes are never deleted silently: while the account has
     * rows in the outbox and [discardPendingChanges] is false, nothing is removed and the result
     * is [AccountRemoval.PendingChanges] (show the count, offer [exportPendingChanges], ask again).
     * [revokeToken] follows `SessionRepository.logout` (only a token Tandem created). Fails with
     * `DataProblem.NO_ACCOUNT` for an unknown id and `DataProblem.SECURE_STORAGE` when the account
     * cannot be removed from the device (then nothing is revoked).
     */
    suspend fun remove(accountId: String, revokeToken: Boolean, discardPendingChanges: Boolean = false): Outcome<AccountRemoval>

    /**
     * The unsent changes of [accountId] as a JSON document for the share sheet (the same format as
     * the recovery screen's "Export pending changes", feature #37): only the queued operations,
     * never a token, password or server address. Fails with `TandemError.Unknown` when the outbox
     * cannot be read.
     */
    suspend fun exportPendingChanges(accountId: String): Outcome<String>
}
