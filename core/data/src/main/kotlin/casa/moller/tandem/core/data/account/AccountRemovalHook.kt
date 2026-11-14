package casa.moller.tandem.core.data.account

import casa.moller.tandem.core.model.account.ServerAccount

// Fas 5 (integration-08, 0.8.0; plan §3.1 "Andra klienter får egna, smalare tokens").

/**
 * Runs just before an account and its token leave the phone (logout, or removal under Konton och
 * servrar), while the account's token still exists. Modules that made tokens of their own with
 * it (the watch's token, the calendar link's `mealplan` token) revoke them here, so no token is
 * left on the server that nothing on the phone knows about any more.
 *
 * Bound into a Hilt set (`@IntoSet`); the account repository calls every hook once per removal,
 * whatever the user chose for the account's own token (those tokens were always made by Tandem).
 * All hooks together get a few seconds ([TIME_LIMIT_MILLIS]); offline or slow, the removal goes on
 * without them. A hook must not throw: it reports nothing and keeps what it could not revoke to
 * its own fallback (the watch deletes its own token when it is next reachable).
 */
fun interface AccountRemovalHook {
    /**
     * Revokes what this module made for [account] with [token] (the account's token, in memory
     * only: never stored, logged or passed on).
     */
    suspend fun beforeRemoval(account: ServerAccount, token: String)

    companion object {
        /** How long all hooks of one removal may take together. */
        const val TIME_LIMIT_MILLIS: Long = 10_000
    }
}
