package casa.moller.tandem.core.data.sync

import casa.moller.tandem.core.model.error.TandemError

/**
 * The sync's protection of the server (plan §5.4: unauthorized, blocked, throttled, circuit
 * breaker) for requests sent OUTSIDE core:sync, such as the watch token of core:watch (0.8.0
 * review, fix-08): every such request asks [check] before it is sent and hands its result to
 * [record], exactly as core:sync's `RemoteCaller` does for its own requests, so a revoked token, a
 * firewall's 403 or a 429 stops these requests too.
 *
 * - **Threading:** main-safe and non-blocking (bookkeeping in memory).
 */
interface RequestGuard {
    /**
     * Null when a request for [accountId] may go out now; otherwise the error to report instead of
     * sending (it is not recorded again).
     *
     * @param background true when the user did not start the request (an automatic renewal).
     */
    fun check(accountId: String, background: Boolean): TandemError?

    /** Records the result of one request of [accountId]: null for a success. */
    fun record(accountId: String, error: TandemError?)
}
