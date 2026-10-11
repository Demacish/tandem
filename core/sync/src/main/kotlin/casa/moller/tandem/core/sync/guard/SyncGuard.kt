package casa.moller.tandem.core.sync.guard

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.platform.SyncClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's protection against hurting the server or getting banned (plan 4.1, 5.4). Every API
 * request of the sync and the repositories passes it ([check] before, [record] after; see
 * `RemoteCaller`). Pure bookkeeping on a clock, so the rules are unit tested:
 *
 * - **Unauthorized** (401 or a JSON 403): every request of that account stops until a new token
 *   is loaded (a new login), so a revoked token never sends a burst of 403s (CrowdSec
 *   `http-generic-403-bf`). The outbox is kept.
 * - **Blocked** (403 without JSON, a firewall): pause [SyncSchedule.BLOCKED_FIRST_PAUSE],
 *   doubling with every new block up to [SyncSchedule.BLOCKED_MAX_PAUSE]; a success resets it.
 * - **Circuit breaker**: more than [SyncSchedule.BREAKER_MAX_ERRORS] errors within
 *   [SyncSchedule.BREAKER_ERROR_WINDOW], or [SyncSchedule.BREAKER_MAX_CLIENT_ERRORS] answers
 *   404/400 within [SyncSchedule.BREAKER_CLIENT_ERROR_WINDOW] (half of CrowdSec's
 *   `http-probing`), pause BACKGROUND requests for [SyncSchedule.BREAKER_PAUSE]. 404 means
 *   "deleted", never "try again", but it still counts. Connectivity problems never count.
 * - **Import running** (plan 4.1): background requests (periodic sync, prefetch) wait while the
 *   user's import has the server's other worker thread.
 *
 * Requests the user starts (opening a recipe, pull to refresh, search) are only stopped by an
 * unauthorized or blocked state, never by the breaker or an import.
 */
@Singleton
internal class SyncGuard @Inject constructor(private val clock: SyncClock) {
    private val mutableState = MutableStateFlow(GuardState())

    /** The guard's state, for [pauseAt] in status flows. */
    val state: StateFlow<GuardState> = mutableState.asStateFlow()

    /**
     * Whether a request for [accountId] may go out now. Null = go ahead; otherwise the error the
     * caller reports instead of sending (it is not recorded again).
     *
     * @param background true for requests the user did not start.
     */
    fun check(accountId: String, background: Boolean): TandemError? {
        val now = clock.nowMillis()
        val current = mutableState.value
        return when {
            current.unauthorizedAccount == accountId -> current.unauthorizedError ?: TandemError.Unauthorized(UnauthorizedSource.TANDOOR)
            current.blockedUntil > now -> TandemError.Blocked
            background && current.breakerUntil > now -> BREAKER_ERROR
            background && current.importsRunning > 0 -> IMPORT_ERROR
            else -> null
        }
    }

    /**
     * Records the result of one request of [accountId]: null for a success.
     *
     * @param tokenFingerprint fingerprint of the token the request carried (see
     *   `ActiveSources.tokenFingerprint`), so that a later new login can lift an unauthorized pause.
     */
    fun record(accountId: String, tokenFingerprint: Int?, error: TandemError?) {
        val now = clock.nowMillis()
        mutableState.update { current ->
            when {
                error == null -> current.copy(blockedPauseMillis = 0L)

                error is TandemError.Unauthorized -> current.copy(
                    unauthorizedAccount = accountId,
                    unauthorizedFingerprint = tokenFingerprint,
                    unauthorizedError = error,
                )

                error == TandemError.Blocked -> {
                    val pause = if (current.blockedPauseMillis <= 0L) {
                        SyncSchedule.BLOCKED_FIRST_PAUSE.inWholeMilliseconds
                    } else {
                        (current.blockedPauseMillis * 2).coerceAtMost(SyncSchedule.BLOCKED_MAX_PAUSE.inWholeMilliseconds)
                    }
                    current.copy(blockedUntil = now + pause, blockedPauseMillis = pause)
                }

                error.isConnectivityProblem -> current

                else -> current.withError(now, isClientError = error == TandemError.NotFound || error is TandemError.BadRequest)
            }
        }
    }

    /**
     * Lifts an unauthorized pause of [accountId] when its token changed since the rejected
     * request (the user logged in again). Returns true when requests may go out again.
     */
    fun liftUnauthorizedIfTokenChanged(accountId: String, tokenFingerprint: Int?): Boolean {
        val current = mutableState.value
        if (current.unauthorizedAccount != accountId) return true
        if (tokenFingerprint == null || tokenFingerprint == current.unauthorizedFingerprint) return false
        mutableState.update { it.copy(unauthorizedAccount = null, unauthorizedFingerprint = null, unauthorizedError = null) }
        return true
    }

    /** An import starts: background requests wait (plan 4.1). Pair with [importFinished]. */
    fun importStarted() {
        mutableState.update { it.copy(importsRunning = it.importsRunning + 1) }
    }

    fun importFinished() {
        mutableState.update { it.copy(importsRunning = (it.importsRunning - 1).coerceAtLeast(0)) }
    }

    /** Another account or server became active: its server and token start with a clean slate. */
    fun reset() {
        mutableState.update { GuardState(importsRunning = it.importsRunning) }
    }

    /** Why requests of [accountId] are paused at [nowMillis], null when they are not. */
    fun pauseAt(state: GuardState, accountId: String, nowMillis: Long): SyncPause? = when {
        state.unauthorizedAccount == accountId -> SyncPause.UNAUTHORIZED
        state.blockedUntil > nowMillis -> SyncPause.BLOCKED
        state.breakerUntil > nowMillis -> SyncPause.CIRCUIT_BREAKER
        state.importsRunning > 0 -> SyncPause.IMPORT_RUNNING
        else -> null
    }

    private fun GuardState.withError(now: Long, isClientError: Boolean): GuardState {
        val errors = (errorTimes + now).filter { now - it < SyncSchedule.BREAKER_ERROR_WINDOW.inWholeMilliseconds }
        val clientErrors = if (isClientError) clientErrorTimes + now else clientErrorTimes
        val recentClientErrors = clientErrors.filter { now - it < SyncSchedule.BREAKER_CLIENT_ERROR_WINDOW.inWholeMilliseconds }
        val trips = errors.size > SyncSchedule.BREAKER_MAX_ERRORS || recentClientErrors.size >= SyncSchedule.BREAKER_MAX_CLIENT_ERRORS
        return if (trips) {
            copy(
                breakerUntil = now + SyncSchedule.BREAKER_PAUSE.inWholeMilliseconds,
                errorTimes = emptyList(),
                clientErrorTimes = emptyList(),
            )
        } else {
            copy(errorTimes = errors, clientErrorTimes = recentClientErrors)
        }
    }

    companion object {
        /** Reported for background requests while the circuit breaker is open. */
        val BREAKER_ERROR: TandemError = TandemError.Unknown("tandem.sync.circuit-breaker")

        /** Reported for background requests while an import runs. */
        val IMPORT_ERROR: TandemError = TandemError.Unknown("tandem.sync.import-running")
    }
}

/**
 * What [SyncGuard] remembers. Times are epoch milliseconds of the guard's clock.
 *
 * @property unauthorizedAccount the account whose token was rejected, null when none.
 * @property unauthorizedFingerprint fingerprint of the rejected token.
 * @property unauthorizedError the rejection as classified (401 proxy or 403 Tandoor).
 * @property blockedUntil requests wait until then after a non-JSON 403.
 * @property blockedPauseMillis the last blocked pause (doubles with every block).
 * @property breakerUntil background requests wait until then (circuit breaker).
 * @property errorTimes recent errors (breaker window).
 * @property clientErrorTimes recent 404/400 answers (breaker window).
 * @property importsRunning imports in progress.
 */
internal data class GuardState(
    val unauthorizedAccount: String? = null,
    val unauthorizedFingerprint: Int? = null,
    val unauthorizedError: TandemError? = null,
    val blockedUntil: Long = 0L,
    val blockedPauseMillis: Long = 0L,
    val breakerUntil: Long = 0L,
    val errorTimes: List<Long> = emptyList(),
    val clientErrorTimes: List<Long> = emptyList(),
    val importsRunning: Int = 0,
)
