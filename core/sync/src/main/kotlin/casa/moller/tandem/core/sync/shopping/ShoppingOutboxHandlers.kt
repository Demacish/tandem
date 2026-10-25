package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.outbox.OutboxRefs
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.ShoppingBulkCheckDto
import casa.moller.tandem.core.network.dto.ShoppingListEntryDto
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Sends `shopping_entry` rows (docs/BYGGE.md "Fas 2 – kontrakt"; plan 3.5, 5.7), under
 * [ShoppingReader.lock]:
 *
 * - **CREATE** `POST /api/shopping-list-entry/`. A `checked` merged into the CREATE offline is not
 *   sent with it (Tandoor sets `completed_at` only when `checked` flips on an existing entry, FACTS
 *   "Fas 2 b"): the entry is checked with a bulk call right after. A retry after a lost answer first
 *   looks for the entry on the server (POST is not idempotent).
 * - **PATCH / DELETE**: checked against a full read of the list first (at most one per minute,
 *   [ShoppingReader.stillOnServerLocked]): an entry another device deleted is dropped WITHOUT a
 *   request, so a list deleted elsewhere drains without a single 404 (Phase 2 "Klart när"). A 404
 *   that still happens (deleted between the read and the send) is an expected answer: done, recorded
 *   in [DroppedChanges], not counted by the circuit breaker.
 * - **BULK_CHECK**: every ready row with the same `batch_key` in one `POST .../bulk/` (deleted ids
 *   are ignored by the server: no 404 possible).
 */
internal class ShoppingEntryOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val reader: ShoppingReader,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        when (row.op) {
            OutboxOps.CREATE -> create(remote, row, payload)
            OutboxOps.PATCH -> patch(remote, row, payload)
            OutboxOps.DELETE -> delete(remote, row)
            OutboxOps.BULK_CHECK -> bulkCheck(remote, row, payload)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val body = parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val checked = (body["checked"] as? JsonPrimitive)?.booleanOrNull == true
        val createBody = JsonObject(body - "checked")
        val existing = if (row.attempts > 0) findLostCreate(remote, createBody) else null
        val created = existing ?: when (val sent = caller.call(remote, background = true) { shopping.createEntry(createBody) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val serverId = created.id ?: return OutboxResult.Failed(TandemError.Decode("shopping-entry-without-id"))
        reader.noteCreated(remote.accountId, serverId)
        storeAnswer(remote, row, created)
        if (checked && !created.checked) checkAfterCreate(remote, row, serverId)
        return OutboxResult.Created(serverId)
    }

    /**
     * The entry was checked offline before it reached the server: check it now. If that fails, a
     * BULK_CHECK row takes over (unless a later check row of the user's already decides).
     */
    private suspend fun checkAfterCreate(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long) {
        val sent = caller.call(remote, background = true) { shopping.bulkCheck(ShoppingBulkCheckDto(listOf(serverId), checked = true)) }
        if (sent is Outcome.Success) return
        val outbox = databases.get(remote.kind).outboxDao()
        val localId = row.localId ?: return
        val later = outbox.getAllFor(remote.accountId, OutboxEntities.SHOPPING_ENTRY)
            .any { it.id != row.id && it.localId == localId && it.op == OutboxOps.BULK_CHECK }
        if (later) return
        outbox.insert(
            OutboxEntity(
                accountId = remote.accountId,
                op = OutboxOps.BULK_CHECK,
                entity = OutboxEntities.SHOPPING_ENTRY,
                localId = localId,
                payload = ShoppingPayloads.check(localId, checked = true),
                baseSnapshot = ShoppingPayloads.checkSnapshot(checked = false),
                refs = ShoppingPayloads.checkRefs(localId),
                batchKey = ShoppingPayloads.checkBatchKey(checked = true),
                createdAtEpochMillis = clock.nowMillis(),
            ),
        )
    }

    private suspend fun patch(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        val body = parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        goneBeforeSending(remote, row, serverId)?.let { return it }
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { shopping.patchEntry(serverId, body) }) {
            is Outcome.Success -> {
                storeAnswer(remote, row, sent.value)
                OutboxResult.Sent
            }

            is Outcome.Failure -> notFoundOrFailed(remote, row, serverId, sent.error)
        }
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        goneBeforeSending(remote, row, serverId)?.let { return it }
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { shopping.deleteEntry(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent
            is Outcome.Failure -> notFoundOrFailed(remote, row, serverId, sent.error)
        }
    }

    private suspend fun notFoundOrFailed(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, error: TandemError): OutboxResult =
        if (error == TandemError.NotFound) gone(remote, row, serverId, DroppedChanges.NOT_FOUND) else OutboxResult.Failed(error)

    /** Null to send; a result when the entry is gone (dropped unsent) or the check failed. */
    private suspend fun goneBeforeSending(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long): OutboxResult? =
        when (val onServer = reader.stillOnServerLocked(remote, serverId)) {
            is Outcome.Failure -> OutboxResult.Failed(onServer.error)
            is Outcome.Success -> if (onServer.value) null else gone(remote, row, serverId, DroppedChanges.DELETED_ELSEWHERE)
        }

    /** The entry no longer exists: it goes locally with everything queued for it. */
    private suspend fun gone(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, reason: String): OutboxResult {
        val database = databases.get(remote.kind)
        database.shoppingDao().inTransaction {
            database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.SHOPPING_ENTRY, serverId)
            database.shoppingDao().deleteEntries(remote.accountId, listOf(serverId))
        }
        dropped.record(DroppedChange(row.entity, row.op, serverId, reason, clock.nowMillis()))
        return OutboxResult.Dropped
    }

    private suspend fun bulkCheck(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val outbox = databases.get(remote.kind).outboxDao()
        val batchKey = row.batchKey
        val others = if (batchKey == null) emptyList() else outbox.nextReadyBatch(remote.accountId, batchKey, MAX_BATCH - 1)
        val mapping = databases.get(remote.kind).idRewriteDao().getMappings(remote.accountId)
            .associate { (it.entity to it.localId) to it.serverId }
        val batch = others.mapNotNull { other ->
            OutboxRefs.resolve(other.payload, other.refs) { entity, localId -> mapping[entity to localId] }
                ?.takeIf { outbox.markInFlight(other.id) == 1 }
                ?.let { other to it }
        }
        val checked = parse(payload)?.get("checked")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true
        val ids = (listOf(payload) + batch.map { it.second }).flatMap { idsOf(it) }.filter { it > 0 }.distinct()
        if (ids.isEmpty()) {
            batch.forEach { (other, _) -> outbox.markDone(other.id) }
            return OutboxResult.Dropped
        }
        return when (val sent = caller.call(remote, background = true) { shopping.bulkCheck(ShoppingBulkCheckDto(ids, checked)) }) {
            is Outcome.Success -> {
                batch.forEach { (other, _) -> outbox.markDone(other.id) }
                OutboxResult.Sent
            }

            is Outcome.Failure -> {
                batch.forEach { (other, _) -> outbox.markRetry(other.id, null) }
                OutboxResult.Failed(sent.error)
            }
        }
    }

    /**
     * After an answer: the entry takes the server's values (ids of food and unit, aisle, who added
     * it, times), unless a newer change of it waits in the outbox (then the local state stays).
     */
    private suspend fun storeAnswer(remote: ActiveSource.Remote, row: OutboxEntity, answer: ShoppingListEntryDto) {
        val database = databases.get(remote.kind)
        val dao = database.shoppingDao()
        val localKey = row.localId?.takeIf { row.op == OutboxOps.CREATE } ?: row.serverId ?: return
        dao.inTransaction {
            val newer = database.outboxDao().getAllFor(remote.accountId, OutboxEntities.SHOPPING_ENTRY)
                .any { it.id != row.id && (it.localId == localKey || (row.serverId != null && it.serverId == row.serverId)) }
            val current = dao.getEntry(remote.accountId, localKey)
            val fresh = answer.toEntity(remote.accountId, clock.nowMillis())
            if (!newer && current != null && fresh != null) {
                // A CREATE's row keeps its temporary key until IdRewriteDao.completeCreate re-keys it.
                dao.upsertEntries(listOf(fresh.copy(localId = current.localId, serverId = current.serverId)))
            }
        }
    }

    /** An earlier attempt may have reached the server with its answer lost: the same new entry, if found. */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, body: JsonObject): ShoppingListEntryDto? {
        val food = ((body["food"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: return null
        val unit = ((body["unit"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
        val amount = (body["amount"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        val listed = caller.call(remote, background = true) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = ShoppingReader.MAX_PAGES) { page -> api.shopping.entries(null, page.takeIf { it > 1 }) }
        }
        val items = (listed as? Outcome.Success)?.value?.items ?: return null
        val known = databases.get(remote.kind).shoppingDao().getEntries(remote.accountId).mapNotNullTo(HashSet()) { it.serverId }
        return items.lastOrNull { entry ->
            entry.id != null && entry.id !in known && entry.createdBy?.id == remote.account.userId &&
                entry.food?.name.equals(food, ignoreCase = true) && entry.unit?.name == unit && entry.amount == amount
        }
    }

    private fun idsOf(payload: String): List<Long> =
        (parse(payload)?.get("ids") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }

    companion object {
        /** At most this many entries per bulk call. */
        const val MAX_BATCH: Int = 100
    }
}

/** `shopping_list_recipe` rows: a recipe or meal on the list (`/api/shopping-list-recipe/`). */
internal class ShoppingListRecipeOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val reader: ShoppingReader,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        val body = parse(payload) ?: return@withLock OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        when (row.op) {
            OutboxOps.CREATE -> when (val sent = caller.call(remote, background = true) { shopping.createListRecipe(body) }) {
                is Outcome.Success -> sent.value.id?.let { OutboxResult.Created(it) }
                    ?: OutboxResult.Failed(TandemError.Decode("list-recipe-without-id"))

                is Outcome.Failure -> OutboxResult.Failed(sent.error)
            }

            OutboxOps.PATCH -> expectGone(remote, row) { id -> shopping.patchListRecipe(id, body) }

            OutboxOps.DELETE -> expectGone(remote, row) { id -> shopping.deleteListRecipe(id) }

            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    /** An update or delete: a 404 means deleted elsewhere (its entries went with it): dropped, not counted. */
    private suspend fun expectGone(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        request: suspend TandoorApi.(Long) -> Any,
    ): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { this.request(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent

            is Outcome.Failure -> {
                if (sent.error != TandemError.NotFound) return OutboxResult.Failed(sent.error)
                val database = databases.get(remote.kind)
                database.shoppingDao().inTransaction {
                    val entries = database.shoppingDao().getEntriesOfListRecipe(remote.accountId, serverId)
                    entries.forEach { entry ->
                        entry.serverId?.let { database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.SHOPPING_ENTRY, it) }
                    }
                    database.shoppingDao().deleteEntries(remote.accountId, entries.map { it.localId })
                    database.shoppingDao().deleteListRecipes(remote.accountId, listOf(serverId))
                }
                dropped.record(DroppedChange(row.entity, row.op, serverId, DroppedChanges.NOT_FOUND, clock.nowMillis()))
                OutboxResult.Dropped
            }
        }
    }
}

/** `food` rows: a food's aisle and "har hemma" (`PATCH /api/food/{id}/`). */
internal class FoodOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val foodId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val body = parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        if (row.op != OutboxOps.PATCH) return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { supermarkets.patchFood(foodId, body) }) {
            is Outcome.Success -> OutboxResult.Sent

            is Outcome.Failure -> if (sent.error == TandemError.NotFound) {
                dropped.record(DroppedChange(row.entity, row.op, foodId, DroppedChanges.NOT_FOUND, clock.nowMillis()))
                databases.get(remote.kind).outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.FOOD, foodId)
                OutboxResult.Dropped
            } else {
                OutboxResult.Failed(sent.error)
            }
        }
    }
}

/** `supermarket_category_relation` rows: a store's aisle order (`PATCH .../{id}/` `{order}`). */
internal class AisleOrderOutboxHandler @Inject constructor(
    private val caller: RemoteCaller,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val relationId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val body = parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        if (row.op != OutboxOps.PATCH) return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val sent = caller.call(remote, background = true, expectedNotFound = true) { supermarkets.patchRelation(relationId, body) }
        return when (sent) {
            is Outcome.Success -> OutboxResult.Sent

            is Outcome.Failure -> if (sent.error == TandemError.NotFound) {
                dropped.record(DroppedChange(row.entity, row.op, relationId, DroppedChanges.NOT_FOUND, clock.nowMillis()))
                OutboxResult.Dropped
            } else {
                OutboxResult.Failed(sent.error)
            }
        }
    }
}

/** The same code as `OutboxSender`'s: an unreadable or unknown row is kept as FAILED (shown, exported). */
internal const val UNSUPPORTED: String = "tandem.sync.unsupported-outbox-op"

/** An update whose object has no server id yet (its CREATE is not answered): retried later. */
private const val WAITING_FOR_ID: String = "tandem.sync.waiting-for-server-id"

private val payloadJson = Json { ignoreUnknownKeys = true }

private fun parse(payload: String): JsonObject? = runCatching { payloadJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject
