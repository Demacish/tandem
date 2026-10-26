package casa.moller.tandem.core.sync.remote

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.guard.SyncGuard
import javax.inject.Inject

/**
 * The one way the sync and the repositories talk to Tandoor: every request passes [SyncGuard]
 * before it is sent and is recorded afterwards, and every failure becomes a typed
 * [Outcome.Failure] (`apiCall`). The shared OkHttp client adds the global limits (2 requests per
 * host at a time, about 5 per second, plan 4.1).
 */
internal class RemoteCaller @Inject constructor(private val guard: SyncGuard, private val sources: ActiveSources) {

    /**
     * Sends [block] for [remote] unless the guard holds it back.
     *
     * @param background true when the user did not start the request (sync, prefetch).
     * @param confirmUnauthorized for single-object reads: Tandoor also answers a JSON 403 for an
     *   object the user may not see (another member's private recipe, FACTS "Fas 1"). Before such
     *   a 403 stops every request, one cheap authenticated read checks the token; if the token
     *   works, the object is reported as [TandemError.NotFound] instead.
     * @param confirmForbidden for writes (the outbox senders): a JSON 403 is checked the same way;
     *   when the token works, the write was refused (a guest's bulk check or recipe edit, FACTS
     *   "Fas 2"), so [SyncGuard.FORBIDDEN_ERROR] is returned and the account is NOT treated as
     *   logged out (0.3.0 review, fix-data). Only a failed check keeps the "log in again" answer.
     *
     * Every 404 and 400 counts in the circuit breaker (plan 3.5 "404 och 400 räknas ändå", 4.1),
     * also one a Phase 2 sender treats as done (a row another device deleted): each is a line in
     * the proxy's log that CrowdSec's `http-probing` counts, so five within 100 s pause the outbox.
     * The senders check the list or the meal first, so such a 404 is rare (Phase 2 koppling lane;
     * the inköp-data lane's `expectedNotFound` that kept them out is gone).
     */
    suspend fun <T> call(
        remote: ActiveSource.Remote,
        background: Boolean,
        confirmUnauthorized: Boolean = false,
        confirmForbidden: Boolean = false,
        block: suspend TandoorApi.() -> T,
    ): Outcome<T> {
        guard.check(remote.accountId, background)?.let { return Outcome.Failure(it) }
        val result = apiCall { remote.api.block() }
        val error = (result as? Outcome.Failure)?.error
        if ((confirmUnauthorized || confirmForbidden) && error == TandemError.Unauthorized(UnauthorizedSource.TANDOOR)) {
            val probe = apiCall { remote.api.preferences.userPreferences() }
            if (probe is Outcome.Success) {
                val refused = if (confirmUnauthorized) TandemError.NotFound else SyncGuard.FORBIDDEN_ERROR
                guard.record(remote.accountId, sources.tokenFingerprint(remote.accountId), refused)
                return Outcome.Failure(refused)
            }
        }
        guard.record(remote.accountId, sources.tokenFingerprint(remote.accountId), error)
        return result
    }

    /**
     * A write of the outbox (background, [confirmForbidden]): a refused write never logs the account out.
     */
    suspend fun <T> write(remote: ActiveSource.Remote, block: suspend TandoorApi.() -> T): Outcome<T> =
        call(remote, background = true, confirmForbidden = true, block = block)

    /**
     * Phase 2 (konto lane): [remote] is still the active source. Work for an account the user
     * switched away from (an outbox drain) stops at the next request instead of going on.
     */
    fun isCurrent(remote: ActiveSource.Remote): Boolean = sources.source.value.identity == remote.identity
}
