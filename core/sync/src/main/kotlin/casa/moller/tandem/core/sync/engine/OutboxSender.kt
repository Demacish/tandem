package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.dto.ViewLogCreateRequestDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a run of [OutboxSender.drain] ended. */
internal enum class DrainResult {
    /** Nothing (more) to send. */
    DONE,

    /** A temporary failure (offline, timeout, 5xx): try again later with backoff. */
    RETRY,

    /** The guard holds requests back (unauthorized, blocked, throttled, circuit breaker): wait for the next sync. */
    STOPPED,

    /** Safe mode (plan 3.6): the rows are held as PAUSED and not sent. */
    PAUSED,
}

/**
 * Sends the outbox (plan 3.5 "Skrivningar", 5.7) of one account, oldest row first. Phase 1 only
 * queues [OutboxOps.VIEW_LOG] (`POST /api/view-log/` when a recipe is opened).
 *
 * - **Safe mode** (plan 3.6): when the server's write policy is not SEND, every pending row is
 *   set to PAUSED and nothing is sent; when it is SEND again, paused rows are queued again.
 * - **Deleted objects** (plan 3.5): a row whose recipe is no longer stored (the index removed it)
 *   is dropped without sending, so offline views of deleted recipes never become a 404 burst.
 * - **Answers**: 2xx done; 404 = deleted elsewhere, dropped; 400 = FAILED (kept for "Pending
 *   changes"); 401/403, blocked and 429 stop the drain and keep the row (a 429 is never retried
 *   automatically: the next sync after its `Retry-After` sends it, plan 4.1); offline, timeouts
 *   and 5xx keep the row for a retry with backoff. 404 and 400 count in the circuit breaker
 *   (`SyncGuard`), which stops the drain after 5 within 100 s.
 */
@Singleton
internal class OutboxSender @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val network: NetworkStatus,
) {
    private val mutex = Mutex()

    suspend fun drain(remote: ActiveSource.Remote, maxRows: Int = MAX_ROWS_PER_DRAIN): DrainResult = mutex.withLock {
        val database = databases.get(remote.kind)
        val outbox = database.outboxDao()
        val accountId = remote.accountId
        if (remote.writePolicy != WritePolicy.SEND) {
            outbox.pauseAll(accountId)
            return@withLock DrainResult.PAUSED
        }
        outbox.resumeAll(accountId)
        outbox.resetInFlight(accountId)
        val storedRecipes = database.recipeDao().getRecipeIds(accountId).toHashSet()
        repeat(maxRows) {
            if (!network.online.value) return@withLock DrainResult.RETRY
            val row = outbox.nextReady(accountId) ?: return@withLock DrainResult.DONE
            if (outbox.markInFlight(row.id) != 1) return@repeat
            val result = send(remote, row, storedRecipes) ?: run {
                // Nothing to send for it (unknown operation or a recipe that is gone).
                outbox.markDone(row.id)
                return@repeat
            }
            when (result) {
                is Outcome.Success -> outbox.markDone(row.id)
                is Outcome.Failure -> handleFailure(remote, row, result.error)?.let { return@withLock it }
            }
        }
        DrainResult.DONE
    }

    /** Sends one row; null when the row needs no request (it is dropped). */
    private suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, storedRecipes: Set<Long>): Outcome<Unit>? = when (row.op) {
        OutboxOps.VIEW_LOG -> {
            val recipeId = row.serverId
            if (recipeId == null || recipeId !in storedRecipes) {
                null
            } else {
                val sent = caller.call(remote, background = true) { logs.createViewLog(ViewLogCreateRequestDto(recipe = recipeId)) }
                when (sent) {
                    is Outcome.Success -> Outcome.Success(Unit)
                    is Outcome.Failure -> sent
                }
            }
        }

        // Phase 1 queues nothing else: such a row (from a newer app version) is marked FAILED and kept.
        else -> Outcome.Failure(TandemError.Unknown(UNSUPPORTED))
    }

    /** Applies the failure to the row; returns how the drain ends, or null to go on with the next row. */
    private suspend fun handleFailure(remote: ActiveSource.Remote, row: OutboxEntity, error: TandemError): DrainResult? {
        val outbox = databases.get(remote.kind).outboxDao()
        return when {
            error == TandemError.NotFound -> {
                outbox.markDone(row.id)
                null
            }

            error is TandemError.BadRequest || error == TandemError.Unknown(UNSUPPORTED) -> {
                outbox.markFailed(row.id, error.code())
                null
            }

            error is TandemError.Unauthorized || error == TandemError.Blocked || error is TandemError.Throttled -> {
                outbox.markRetry(row.id, error.code())
                DrainResult.STOPPED
            }

            error.isConnectivityProblem || error is TandemError.ServerError -> {
                outbox.markRetry(row.id, error.code())
                DrainResult.RETRY
            }

            else -> {
                // Circuit breaker, an import, or an unexpected answer: keep the row for later.
                outbox.markRetry(row.id, error.code())
                DrainResult.STOPPED
            }
        }
    }

    private companion object {
        /** At most this many rows per drain; the next drain continues. */
        const val MAX_ROWS_PER_DRAIN = 50
        const val UNSUPPORTED = "tandem.sync.unsupported-outbox-op"
    }
}
