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
}
