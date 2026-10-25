package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.CookLogKey
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.CookLogProblems
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.dto.CookLogCreateRequestDto
import casa.moller.tandem.core.network.dto.CookLogDto
import casa.moller.tandem.core.network.dto.ViewLogCreateRequestDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

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
 * Sends the outbox (plan 3.5 "Skrivningar", 5.7) of one account, oldest row first: [OutboxOps.VIEW_LOG]
 * (`POST /api/view-log/` when a recipe is opened) and [OutboxOps.COOK_LOG] ("Klart!",
 * `POST /api/cook-log/` with the `created_at` of the moment it was logged, so an entry sent days
 * later keeps its date; FACTS "Fas 1 f").
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
 * - **Cook log:** the same rules, applied to the `cook_log` entry in the same transaction as the
 *   outbox row (`CookLogDao`): 201 makes it the server's entry, a deleted recipe (not stored, 404,
 *   or Tandoor's 400 on the `recipe` field) drops it with the reason, any other 400 marks it
 *   FAILED. `POST /api/cook-log/` is not idempotent, so a row that was attempted before first
 *   looks for its entry on the server (same recipe, author and `created_at`): an answer lost to a
 *   timeout never becomes a second entry.
 */
@Singleton
internal class OutboxSender @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val network: NetworkStatus,
    private val clock: SyncClock,
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
            if (row.op == OutboxOps.COOK_LOG) {
                sendCookLog(remote, row, storedRecipes)?.let { return@withLock it }
                return@repeat
            }
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

        // Nothing else is queued yet: such a row (from a newer app version) is marked FAILED and kept.
        else -> Outcome.Failure(TandemError.Unknown(UNSUPPORTED))
    }

    /**
     * Sends one `COOK_LOG` row and settles its `cook_log` entry; returns how the drain ends, or
     * null to go on with the next row.
     */
    private suspend fun sendCookLog(remote: ActiveSource.Remote, row: OutboxEntity, storedRecipes: Set<Long>): DrainResult? {
        val database = databases.get(remote.kind)
        val cookLog = database.cookLogDao()
        val accountId = remote.accountId
        val rules = remote.cookStatsRules
        val localId = cookLog.getEntryForOutbox(accountId, row.id)?.localId
        val recipeId = row.serverId
        val request = decodeCookLog(row.payload)
        if (localId == null || recipeId == null || request == null || request.recipe != recipeId) {
            // A row this version cannot read: kept as FAILED for "Pending changes" and its export.
            database.outboxDao().markFailed(row.id, UNSUPPORTED)
            return null
        }
        if (recipeId !in storedRecipes) {
            cookLog.markDropped(accountId, localId, row.id, CookLogProblems.RECIPE_GONE, rules)
            return null
        }
        val gone: suspend () -> Unit = { cookLog.markDropped(accountId, localId, row.id, CookLogProblems.NOT_FOUND, rules) }
        val rejected: suspend (String) -> Unit = { code -> cookLog.markRejected(accountId, localId, row.id, code, rules) }
        val failed: suspend (TandemError) -> DrainResult? = { error ->
            if (error is TandemError.BadRequest && FIELD_RECIPE in error.fieldErrors) {
                // Tandoor answers 400 "Invalid pk" (not 404) for a recipe deleted on the server (FACTS "Fas 1 f").
                cookLog.markDropped(accountId, localId, row.id, CookLogProblems.RECIPE_UNKNOWN, rules)
                null
            } else {
                handleFailure(remote, row, error, gone, rejected)
            }
        }
        if (row.attempts > 0) {
            // The last attempt may have reached the server with its answer lost: look before posting.
            when (val found = findOnServer(remote, request)) {
                is Outcome.Failure -> return failed(found.error)

                is Outcome.Success -> found.value?.let { existing ->
                    settleSent(remote, row, localId, existing, request)
                    return null
                }
            }
        }
        return when (val sent = caller.call(remote, background = true) { logs.createCookLog(request) }) {
            is Outcome.Success -> {
                settleSent(remote, row, localId, sent.value, request)
                null
            }

            is Outcome.Failure -> failed(sent.error)
        }
    }

    private suspend fun settleSent(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        localId: Long,
        created: CookLogDto,
        request: CookLogCreateRequestDto,
    ) {
        val accountId = remote.accountId
        val entry = created.toEntity(accountId) ?: CookLogEntity(
            accountId = accountId,
            localId = localId,
            serverId = null,
            recipeId = request.recipe,
            servings = request.servings,
            rating = request.rating,
            comment = request.comment,
            createdBy = remote.account.userId,
        )
        val cookedAt = TandoorDates.parseInstantOrNull(request.createdAt)?.toEpochMilli()
        databases.get(remote.kind).cookLogDao().markSent(
            accountId = accountId,
            localId = localId,
            outboxId = row.id,
            created = if (entry.cookedAtEpochMillis == null) entry.copy(cookedAtEpochMillis = cookedAt) else entry,
            sentAtEpochMillis = clock.nowMillis(),
            rules = remote.cookStatsRules,
        )
    }

    /** The user's entry for [request] if the server already has it (same recipe, author and `created_at`). */
    private suspend fun findOnServer(remote: ActiveSource.Remote, request: CookLogCreateRequestDto): Outcome<CookLogDto?> {
        val cookedAt = TandoorDates.parseInstantOrNull(request.createdAt) ?: return Outcome.Success(null)
        val listed = caller.call(remote, background = true) {
            val api = this
            Paginator.fetchAll { page -> api.logs.cookLogs(recipe = request.recipe, page = page.takeIf { it > 1 }) }
        }
        return when (listed) {
            is Outcome.Failure -> listed

            is Outcome.Success -> {
                // The same rule as CookLogDao.reconcile (CookLogKey): recipe, author, created_at to the second.
                val own = CookLogKey(request.recipe, remote.account.userId, cookedAt.toEpochMilli())
                Outcome.Success(
                    listed.value.items.firstOrNull { entry ->
                        val recipe = entry.recipe ?: return@firstOrNull false
                        val createdAt = TandoorDates.parseInstantOrNull(entry.createdAt)?.toEpochMilli()
                        own.matches(CookLogKey(recipe, entry.createdBy?.id, createdAt))
                    },
                )
            }
        }
    }

    private fun decodeCookLog(payload: String): CookLogCreateRequestDto? = try {
        TandoorJson.decodeFromString(CookLogCreateRequestDto.serializer(), payload)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Applies the failure to the row; returns how the drain ends, or null to go on with the next row.
     *
     * @param gone what a 404 does (default: the row is dropped).
     * @param rejected what a 400 (or an unsupported row) does with the error code (default: FAILED).
     */
    private suspend fun handleFailure(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        error: TandemError,
        gone: suspend () -> Unit = { databases.get(remote.kind).outboxDao().markDone(row.id) },
        rejected: suspend (String) -> Unit = { code -> databases.get(remote.kind).outboxDao().markFailed(row.id, code) },
    ): DrainResult? {
        val outbox = databases.get(remote.kind).outboxDao()
        return when {
            error == TandemError.NotFound -> {
                gone()
                null
            }

            error is TandemError.BadRequest || error == TandemError.Unknown(UNSUPPORTED) -> {
                rejected(error.code())
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

        /** The cook log's `recipe` field (a 400 on it means the recipe does not exist). */
        const val FIELD_RECIPE = "recipe"
    }
}
