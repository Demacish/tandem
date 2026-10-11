package casa.moller.tandem.core.sync.remote

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
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
     */
    suspend fun <T> call(
        remote: ActiveSource.Remote,
        background: Boolean,
        confirmUnauthorized: Boolean = false,
        block: suspend TandoorApi.() -> T,
    ): Outcome<T> {
        guard.check(remote.accountId, background)?.let { return Outcome.Failure(it) }
        val result = apiCall { remote.api.block() }
        val error = (result as? Outcome.Failure)?.error
        if (confirmUnauthorized && error == TandemError.Unauthorized(UnauthorizedSource.TANDOOR)) {
            val probe = apiCall { remote.api.preferences.userPreferences() }
            if (probe is Outcome.Success) {
                guard.record(remote.accountId, sources.tokenFingerprint(remote.accountId), TandemError.NotFound)
                return Outcome.Failure(TandemError.NotFound)
            }
        }
        guard.record(remote.accountId, sources.tokenFingerprint(remote.accountId), error)
        return result
    }
}
