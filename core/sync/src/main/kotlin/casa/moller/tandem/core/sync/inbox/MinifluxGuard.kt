package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.sync.platform.SyncClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fas 5 (rss lane, plan §5.4 "skydda servrar från bannlysning"): the recipe inbox's OWN protection of
 * the user's Miniflux server. It shares nothing with the Tandoor sync's `SyncGuard`: a Miniflux
 * failure never pauses Tandoor requests, and a Tandoor pause never stops the inbox. Bookkeeping on a
 * clock only, so the rules are unit tested:
 *
 * - **Refused key** (401, or 403 with Miniflux's JSON): every request with that key stops until a
 *   different key is entered (a new fingerprint) or the inbox is disconnected, so a revoked key never
 *   sends a burst of 401s.
 * - **Throttled** (429): everything waits until `Retry-After` ([THROTTLED_DEFAULT] when missing,
 *   within [THROTTLED_MIN] .. [THROTTLED_MAX]).
 * - **Blocked** (a 403 from a firewall): everything waits [BLOCKED_FIRST], doubling up to [BLOCKED_MAX].
 * - **Circuit breaker:** more than [BREAKER_MAX_ERRORS] other errors within [BREAKER_WINDOW] pause the
 *   BACKGROUND refresh for [BREAKER_PAUSE]; what the user starts (open the inbox, pull to refresh)
 *   still goes. Connectivity problems never count.
 *
 * The state lives in memory; what must outlast the process (a refused key, a 429 or firewall pause)
 * the repository also stores in the account's `RecipeInboxRecord` ([isRefused], [holdUntil]) and
 * checks before anything is sent (fix-09).
 */
@Singleton
internal class MinifluxGuard @Inject constructor(private val clock: SyncClock) {
    private data class State(
        val accountId: String? = null,
        val refusedKey: Int? = null,
        val throttledUntil: Long = 0,
        val blockedUntil: Long = 0,
        val blockedPause: Long = 0,
        val breakerUntil: Long = 0,
        val errorTimes: List<Long> = emptyList(),
    )

    private val state = MutableStateFlow(State())

    /**
     * Whether a request of [accountId] with the key of fingerprint [keyFingerprint] may go out now:
     * null = go ahead, otherwise the problem to report instead (not recorded again).
     */
    fun check(accountId: String, keyFingerprint: Int, background: Boolean): InboxProblem? {
        val now = clock.nowMillis()
        val current = state.value.takeIf { it.accountId == accountId } ?: return null
        return when {
            current.refusedKey == keyFingerprint -> InboxProblem.AUTH
            current.throttledUntil > now -> InboxProblem.THROTTLED
            current.blockedUntil > now -> InboxProblem.PAUSED
            background && current.breakerUntil > now -> InboxProblem.PAUSED
            else -> null
        }
    }

    /** Records one request's result: null for a success. */
    fun record(accountId: String, keyFingerprint: Int, error: TandemError?) {
        val now = clock.nowMillis()
        state.update { old ->
            val current = if (old.accountId == accountId) old else State(accountId = accountId)
            when {
                error == null -> current.copy(blockedPause = 0, refusedKey = current.refusedKey.takeIf { it != keyFingerprint })

                error is TandemError.Unauthorized -> current.copy(refusedKey = keyFingerprint)

                error is TandemError.Throttled -> {
                    val pause = (error.retryAfter?.inWholeMilliseconds ?: THROTTLED_DEFAULT).coerceIn(THROTTLED_MIN, THROTTLED_MAX)
                    current.copy(throttledUntil = maxOf(current.throttledUntil, now + pause))
                }

                error == TandemError.Blocked -> {
                    val pause = if (current.blockedPause <= 0) BLOCKED_FIRST else (current.blockedPause * 2).coerceAtMost(BLOCKED_MAX)
                    current.copy(blockedUntil = now + pause, blockedPause = pause)
                }

                error.isConnectivityProblem -> current

                else -> {
                    val errors = (current.errorTimes + now).filter { now - it < BREAKER_WINDOW }
                    if (errors.size > BREAKER_MAX_ERRORS) {
                        current.copy(breakerUntil = now + BREAKER_PAUSE, errorTimes = emptyList())
                    } else {
                        current.copy(errorTimes = errors)
                    }
                }
            }
        }
    }

    /** Whether Miniflux refused [accountId]'s key with fingerprint [keyFingerprint] (fix-09: stored by the repository). */
    fun isRefused(accountId: String, keyFingerprint: Int): Boolean =
        state.value.let { it.accountId == accountId && it.refusedKey == keyFingerprint }

    /**
     * Until when (wall clock) everything of [accountId] waits for a 429 or a firewall pause, null when
     * nothing does (fix-09: stored by the repository). The breaker's pause is not included: it holds
     * back only the background, and only in this process.
     */
    fun holdUntil(accountId: String): Long? {
        val current = state.value.takeIf { it.accountId == accountId } ?: return null
        return maxOf(current.throttledUntil, current.blockedUntil).takeIf { it > clock.nowMillis() }
    }

    /** A new key, a disconnect or another account: a clean slate. */
    fun reset() {
        state.value = State()
    }

    companion object {
        const val THROTTLED_DEFAULT: Long = 5 * 60_000L
        const val THROTTLED_MIN: Long = 30_000L
        const val THROTTLED_MAX: Long = 60 * 60_000L
        const val BLOCKED_FIRST: Long = 5 * 60_000L
        const val BLOCKED_MAX: Long = 2 * 60 * 60_000L
        const val BREAKER_MAX_ERRORS: Int = 3
        const val BREAKER_WINDOW: Long = 10 * 60_000L
        const val BREAKER_PAUSE: Long = 30 * 60_000L

        /** fix-09: the longest stored pause; one further away (a clock set back) is not honoured. */
        const val LONGEST_HOLD: Long = BLOCKED_MAX
    }
}
