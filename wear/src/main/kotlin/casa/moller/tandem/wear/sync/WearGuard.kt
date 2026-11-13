package casa.moller.tandem.wear.sync

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.wear.data.GuardState
import casa.moller.tandem.wear.data.WearProblem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The watch's protection against hurting the server or getting the household banned (plan §5.4,
 * §4.1), the same rules as the phone's SyncGuard, made smaller (one account, one list):
 *
 * - **Unauthorized** (a Tandoor 403 with `detail`, or a 401 from something in front of it): the
 *   token is expired or revoked. The FIRST such answer stops every request until the phone pairs
 *   the watch again ([Verdict.SignedOut]); no burst of 403s ever reaches a firewall.
 * - **Throttled** (429): nothing until `Retry-After` ([THROTTLE_DEFAULT] when absent), within
 *   [MIN_PAUSE]..[MAX_PAUSE].
 * - **Any other error** (blocked, 5xx, 404/400, a broken answer, TLS): pause [FIRST_PAUSE], doubled
 *   with every further error up to [MAX_PAUSE]; a success resets it.
 * - **No network** never counts: the checks wait, and WorkManager runs the sync again once the
 *   watch is online (network constraint).
 */
object WearGuard {
    val FIRST_PAUSE: Duration = 5.minutes
    val MAX_PAUSE: Duration = 2.hours
    val MIN_PAUSE: Duration = 1.minutes
    val THROTTLE_DEFAULT: Duration = 5.minutes

    /** The time between two automatic reads of the list (opening the app, the tile). */
    val MIN_READ_INTERVAL: Duration = 2.minutes

    /** How often the stores and their aisle order are read again. */
    val SUPERMARKETS_INTERVAL: Duration = 6.hours

    /** What an error means for the next requests. */
    sealed interface Verdict {
        data object SignedOut : Verdict

        data class Pause(val guard: GuardState) : Verdict

        data class Offline(val guard: GuardState) : Verdict
    }

    /** May a request go out at [nowMillis]? */
    fun mayRequest(guard: GuardState, nowMillis: Long): Boolean = guard.pausedUntilMillis <= nowMillis

    /** The guard after a success: no pause, no problem. */
    fun success(): GuardState = GuardState()

    /** The verdict for [error] at [nowMillis], given the [current] guard. */
    fun failure(current: GuardState, error: TandemError, nowMillis: Long): Verdict = when {
        error is TandemError.Unauthorized -> Verdict.SignedOut

        error.isConnectivityProblem -> Verdict.Offline(current.copy(problem = WearProblem.OFFLINE))

        error is TandemError.Throttled -> {
            val pause = (error.retryAfter ?: THROTTLE_DEFAULT).coerceIn(MIN_PAUSE, MAX_PAUSE)
            Verdict.Pause(
                GuardState(
                    pausedUntilMillis = nowMillis + pause.inWholeMilliseconds,
                    pauseMillis = current.pauseMillis,
                    problem = WearProblem.THROTTLED,
                ),
            )
        }

        else -> {
            val pause = if (current.pauseMillis <= 0L) FIRST_PAUSE.inWholeMilliseconds else current.pauseMillis * 2
            val bounded = pause.coerceAtMost(MAX_PAUSE.inWholeMilliseconds)
            Verdict.Pause(GuardState(pausedUntilMillis = nowMillis + bounded, pauseMillis = bounded, problem = WearProblem.SERVER))
        }
    }
}
