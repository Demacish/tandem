package casa.moller.tandem.core.data.push

import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushServiceInfo
import casa.moller.tandem.core.model.push.FastPushState
import casa.moller.tandem.core.model.push.PushDistributor
import kotlinx.coroutines.flow.Flow

/**
 * Fas 4 (push lane): the optional fast push for the shopping list (plan §4.3, B11, #30;
 * docs/PUSH.md). Implemented in core:sync on the UnifiedPush connector; per account, off by
 * default. Without it the background sync and its list notification work as before.
 *
 * Registration: [enable] saves the address and the distributor and asks the distributor for an
 * endpoint; when it arrives, the endpoint is sent to the service with the account's Tandoor token
 * (the service checks the household once and forgets the token). Main-safe.
 */
interface FastPushRepository {
    /** The active account's settings and status (demo, guests and logged-out are reported too). */
    val state: Flow<FastPushState>

    /** The UnifiedPush distributor apps installed on the phone (empty: explain what to install). */
    fun distributors(): List<PushDistributor>

    /** Asks the service at [serviceUrl] what it is (`GET /v1/info`); nothing is saved. */
    suspend fun check(serviceUrl: String): FastPushResult<FastPushServiceInfo>

    /** Turns fast push on for the active account with [serviceUrl] and the distributor [distributor]. */
    suspend fun enable(serviceUrl: String, distributor: String): FastPushResult<Unit>

    /** Turns fast push off: unregisters at the service (best effort) and at the distributor. */
    suspend fun disable(): FastPushResult<Unit>

    /** Asks the service for one test wake-up; the phone then shows a test notification. */
    suspend fun sendTest(): FastPushResult<Unit>
}
