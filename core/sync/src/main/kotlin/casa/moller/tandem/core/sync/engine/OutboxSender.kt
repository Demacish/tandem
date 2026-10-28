package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.CookLogKey
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.CookLogProblems
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.outbox.OutboxRefs
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
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.permissions.SpaceRoleSync
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import casa.moller.tandem.core.sync.shopping.DroppedChange
import casa.moller.tandem.core.sync.shopping.DroppedChanges
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * - **Guests** (#42; 0.3.0 review, fix-data): rows queued before the role was known (the first
 *   sync after a login, a failed role read, a member demoted on the web) are never sent once the
 *   stored role is `guest`: edits and "Klart!" become FAILED with [READ_ONLY], view logs are
 *   dropped. A JSON 403 on a write whose token still works (the request is refused, the login is
 *   fine) marks the row FAILED with [FORBIDDEN], reads the role again and ends the drain, so the
 *   account is never sent back to the login and one refused row is not sent again and again.
 * - **Rows that can never be sent** (0.3.0 review, fix-data): a row that points at an object
 *   created offline whose CREATE is gone (cancelled) and that has no server id is dropped with its
 *   local row ([DroppedChanges.ORPHAN]) instead of holding up every later row of the account.
 * - A handler's request and the bookkeeping of its answer run to the end even when the caller is
 *   cancelled (a screen's poller, a stopped worker), so an answered CREATE is never forgotten.
 */
@Singleton
internal class OutboxSender @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    // Phase 2 (grund lane): the lanes' senders by outbox entity (OutboxHandlerModule).
    private val handlers: Map<String, @JvmSuppressWildcards OutboxHandler>,
    // 0.3.0 review (fix-data): the role is read again after a refused write; rows that can never be sent are recorded.
    private val roles: SpaceRoleSync,
    private val dropped: DroppedChanges,
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
        if (isGuest(database, accountId)) refuseForGuest(remote)
        val storedRecipes = database.recipeDao().getRecipeIds(accountId).toHashSet()
        repeat(maxRows) {
            // Phase 2 (konto lane): the user switched to another account: its own drain follows.
            if (!caller.isCurrent(remote)) return@withLock DrainResult.STOPPED
            if (!network.online.value) return@withLock DrainResult.RETRY
            val row = outbox.nextReady(accountId) ?: return@withLock DrainResult.DONE
            if (outbox.markInFlight(row.id) != 1) return@repeat
            if (row.op == OutboxOps.COOK_LOG) {
                sendCookLog(remote, row, storedRecipes)?.let { return@withLock it }
                return@repeat
            }
            val handler = handlers[row.entity]?.takeIf { row.op != OutboxOps.VIEW_LOG }
            if (handler != null) {
                sendWithHandler(remote, handler, row)?.let { return@withLock it }
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

    /**
     * The stored role is `guest` (#42): nothing queued is sent. Edits become FAILED with [READ_ONLY]
     * (shown in "Pending changes"), a "Klart!" is kept as a rejected local entry, and view logs are
     * dropped (a guest's views are not logged, see `markViewed`).
     */
    private suspend fun refuseForGuest(remote: ActiveSource.Remote) {
        val database = databases.get(remote.kind)
        val outbox = database.outboxDao()
        val cookLog = database.cookLogDao()
        for (row in outbox.getByState(remote.accountId, OutboxState.PENDING)) {
            when (row.op) {
                OutboxOps.VIEW_LOG -> outbox.markDone(row.id)

                OutboxOps.COOK_LOG -> {
                    val entry = cookLog.getEntryForOutbox(remote.accountId, row.id)
                    if (entry != null) {
                        cookLog.markRejected(remote.accountId, entry.localId, row.id, READ_ONLY, remote.cookStatsRules)
                    } else {
                        outbox.markFailed(row.id, READ_ONLY)
                    }
                }

                else -> outbox.markFailed(row.id, READ_ONLY)
            }
        }
    }

    /** Sends one row; null when the row needs no request (it is dropped). */
    private suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, storedRecipes: Set<Long>): Outcome<Unit>? = when (row.op) {
        OutboxOps.VIEW_LOG -> {
            val recipeId = row.serverId
            if (recipeId == null || recipeId !in storedRecipes) {
                null
            } else {
                // A log keeps the plain 403 rule: a guest's logs never get here (see refuseForGuest).
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
     * Phase 2: one row of an entity with an [OutboxHandler]: resolves the payload's temporary ids
     * (a row whose reference has no server id yet waits for the next drain), sends it and applies
     * the result; returns how the drain ends, or null to go on with the next row.
     */
    private suspend fun sendWithHandler(remote: ActiveSource.Remote, handler: OutboxHandler, row: OutboxEntity): DrainResult? {
        val database = databases.get(remote.kind)
        val outbox = database.outboxDao()
        val ids = database.idRewriteDao()
        val mapping = ids.getMappings(remote.accountId).associate { (it.entity to it.localId) to it.serverId }
        val missing = mutableListOf<Pair<String, Long>>()
        val resolved = OutboxRefs.resolve(row.payload, row.refs) { entity, localId ->
            mapping[entity to localId] ?: run {
                missing += entity to localId
                0L
            }
        }
        val payload = resolved?.takeIf { missing.isEmpty() }
        if (payload == null) {
            // Waiting for a CREATE that is still queued; a reference whose CREATE is gone can never resolve.
            if (missing.any { (entity, localId) -> !outbox.hasCreate(remote.accountId, entity, localId) }) return dropOrphan(remote, row)
            outbox.markRetry(row.id, WAITING_FOR_ID)
            return DrainResult.RETRY
        }
        // A change of an object that was created offline and whose CREATE is gone: it never gets a server id.
        if (isOrphanChange(remote, row, mapping)) return dropOrphan(remote, row)
        val localId = row.localId
        // The request and what its answer means for the database belong together (a CREATE answered
        // but not recorded would be sent again): they finish even when the caller is cancelled.
        return withContext(NonCancellable) {
            when (val result = handler.send(remote, row, payload)) {
                OutboxResult.Sent, OutboxResult.Dropped -> {
                    outbox.markDone(row.id)
                    null
                }

                is OutboxResult.Created -> {
                    if (localId != null && localId < 0) {
                        ids.completeCreate(remote.accountId, row.id, row.entity, localId, result.serverId, clock.nowMillis())
                    } else {
                        outbox.markDone(row.id)
                    }
                    null
                }

                is OutboxResult.Failed -> handleFailure(remote, row, result.error)
            }
        }
    }

    /** An update or delete of an object created offline that has no server id, no mapping and no CREATE any more. */
    private suspend fun isOrphanChange(remote: ActiveSource.Remote, row: OutboxEntity, mapping: Map<Pair<String, Long>, Long>): Boolean {
        val localId = row.localId ?: return false
        if (row.op == OutboxOps.CREATE || row.serverId != null || localId >= 0) return false
        if ((row.entity to localId) in mapping) return false
        return !databases.get(remote.kind).outboxDao().hasCreate(remote.accountId, row.entity, localId)
    }

    /**
     * [row] can never be sent (its object, or an object it points at, was created offline and its
     * CREATE is gone): it is removed with the object's other rows and its local row that never
     * reached the server, and recorded; the drain goes on with the next row.
     */
    private suspend fun dropOrphan(remote: ActiveSource.Remote, row: OutboxEntity): DrainResult? {
        val database = databases.get(remote.kind)
        val outbox = database.outboxDao()
        database.shoppingDao().inTransaction {
            outbox.markDone(row.id)
            row.localId?.takeIf { it < 0 }?.let { localId ->
                outbox.deleteObjectRows(remote.accountId, row.entity, localId, serverId = null)
                outbox.deleteUnsentObjects(remote.accountId, row.entity, listOf(localId))
            }
        }
        dropped.record(DroppedChange(row.entity, row.op, row.serverId, DroppedChanges.ORPHAN, clock.nowMillis()))
        return null
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

            error == SyncGuard.FORBIDDEN_ERROR -> {
                // The token works, but this write was refused (FACTS "Fas 2": a guest gets 403 on bulk
                // and recipe edits): FAILED, the role is read again, and the drain ends here so
                // several refused writes never go out in a row (plan 4.1).
                rejected(FORBIDDEN)
                roles.sync(remote, background = true)
                DrainResult.STOPPED
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

        /** A payload points at an object whose CREATE has not been answered yet (should not happen: `depends_on`). */
        const val WAITING_FOR_ID = "tandem.sync.waiting-for-server-id"

        /** A row of a guest (#42): never sent. */
        const val READ_ONLY = "tandem.sync.read-only"

        /** Tandoor refused the write with a 403 although the token works. */
        const val FORBIDDEN = "tandem.sync.forbidden"

        /** The cook log's `recipe` field (a 400 on it means the recipe does not exist). */
        const val FIELD_RECIPE = "recipe"
    }
}
