package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.InventoryEntryDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.DroppedChange
import casa.moller.tandem.core.sync.shopping.DroppedChanges
import casa.moller.tandem.core.sync.shopping.claimedServerIds
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A row whose payload this version cannot read, or an unknown op: kept as FAILED (the sender's code). */
private const val UNSUPPORTED: String = "tandem.sync.unsupported-outbox-op"

/** An update whose object has no server id yet: retried later (the sender's code). */
private const val WAITING_FOR_ID: String = "tandem.sync.waiting-for-server-id"

/**
 * The whole location object a nested write needs (FACTS "Fas S b": only `{id}` is a 400, a
 * different name renames the location, and the nested household is written too): built from the
 * stored row, with the household from the row or else the account. Null when the location or its
 * household is not known on this device.
 */
internal class PantryLocations @Inject constructor(private val databases: TandemDatabaseFactory) {
    suspend fun expand(remote: ActiveSource.Remote, locationId: Long): JsonObject? {
        val database = databases.get(remote.kind)
        val row = database.pantryDao().getLocation(remote.accountId, locationId) ?: return null
        val household = household(remote, row) ?: return null
        return buildJsonObject {
            put("id", locationId)
            put("name", row.name)
            put("is_freezer", row.isFreezer)
            put("household", household)
        }
    }

    /** `{id, name}` of the location's household: its own, else the account's (all_personal). */
    suspend fun household(remote: ActiveSource.Remote, row: InventoryLocationEntity?): JsonObject? {
        val ownId = row?.householdId
        val ownName = row?.householdName
        if (ownId != null && ownName != null) return householdObject(ownId, ownName)
        val account = databases.get(remote.kind).accountDao().get(remote.accountId)
        val id = account?.householdId ?: return null
        val name = account.householdName ?: return null
        return householdObject(id, name)
    }

    private fun householdObject(id: Long, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    /** [body] with its `inventory_location` id replaced by the whole object; null when the location is unknown. */
    suspend fun expandIn(remote: ActiveSource.Remote, body: JsonObject): JsonObject? {
        val id = (body[PantryPayloads.FIELD_LOCATION] as? JsonPrimitive)?.longOrNull ?: return body
        val location = expand(remote, id) ?: return null
        return JsonObject(body + (PantryPayloads.FIELD_LOCATION to location))
    }
}

/**
 * `inventory_location` rows (docs/BYGGE.md "Fas S – kontrakt"), under [PantryReader.lock]:
 * CREATE `POST /api/inventory-location/` with the household added (`{id, name}`, required); a retry
 * after a lost answer first looks for the same new location (name and household) that no other
 * create of this device claimed. PATCH `{name, is_freezer}`; DELETE (the server deletes its entries
 * too). A 404 means deleted elsewhere: the location goes locally with its entries and everything
 * queued for them.
 */
internal class InventoryLocationOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val reader: PantryReader,
    private val locations: PantryLocations,
    private val changes: PantryChanges,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        val body = PantryPayloads.parse(payload) ?: return@withLock OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val result = when (row.op) {
            OutboxOps.CREATE -> create(remote, row, body)
            OutboxOps.PATCH -> update(remote, row) { id -> inventory.patchLocation(id, body) }
            OutboxOps.DELETE -> update(remote, row) { id -> inventory.deleteLocation(id) }
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
        if (result !is OutboxResult.Failed) changes.notify(remote.accountId)
        result
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val database = databases.get(remote.kind)
        val localId = row.localId ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val stored = database.pantryDao().getLocation(remote.accountId, localId)
        // Without a household the server answers 400 (FACTS "Fas S b"): FAILED, shown in "Pending changes".
        val household = locations.household(remote, stored)
            ?: return OutboxResult.Failed(TandemError.BadRequest(mapOf(FIELD_HOUSEHOLD to emptyList())))
        val createBody = JsonObject(body + (FIELD_HOUSEHOLD to household))
        val householdId = (household["id"] as? JsonPrimitive)?.longOrNull
        val name = (body["name"] as? JsonPrimitive)?.content
        if (row.attempts > 0) findLostCreate(remote, name, householdId)?.let { return OutboxResult.Created(it) }
        return when (val sent = caller.write(remote) { inventory.createLocation(createBody) }) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)

            is Outcome.Success -> {
                val created = sent.value
                val serverId = created.id ?: return OutboxResult.Failed(TandemError.Decode("inventory-location-without-id"))
                if (stored != null) {
                    // The server's household name (the nested write may have normalised it).
                    val updated = stored.copy(
                        householdId = created.household?.id ?: stored.householdId,
                        householdName = created.household?.name ?: stored.householdName,
                    )
                    database.pantryDao().upsertLocations(listOf(updated))
                }
                OutboxResult.Created(serverId)
            }
        }
    }

    private suspend fun findLostCreate(remote: ActiveSource.Remote, name: String?, householdId: Long?): Long? {
        if (name == null) return null
        val listed = caller.call(remote, background = true) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = PantryReader.MAX_PAGES) { page -> api.inventory.locations(page.takeIf { it > 1 }) }
        }
        val items = (listed as? Outcome.Success)?.value?.items ?: return null
        val claimed = claimedServerIds(databases, remote, OutboxEntities.INVENTORY_LOCATION)
        // A location of the same name in the household that no create of this device claimed is taken
        // (also one a read stored meanwhile: completeCreate drops that copy). Same place, same name.
        return items.filter { it.id != null && it.id !in claimed && it.name == name && it.household?.id == householdId }
            .mapNotNull { it.id }
            .maxOrNull()
    }

    private suspend fun update(remote: ActiveSource.Remote, row: OutboxEntity, request: suspend TandoorApi.(Long) -> Any): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        return when (val sent = caller.write(remote) { this.request(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent

            is Outcome.Failure -> {
                if (sent.error != TandemError.NotFound) return OutboxResult.Failed(sent.error)
                val database = databases.get(remote.kind)
                database.pantryDao().inTransaction {
                    for (entry in database.pantryDao().getEntriesOfLocation(remote.accountId, serverId)) {
                        entry.serverId?.let { database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.INVENTORY_ENTRY, it) }
                    }
                    database.pantryDao().deleteEntriesOfLocation(remote.accountId, serverId)
                    database.pantryDao().deleteLocation(remote.accountId, serverId)
                    database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.INVENTORY_LOCATION, serverId)
                }
                dropped.record(DroppedChange(row.entity, row.op, serverId, DroppedChanges.NOT_FOUND, clock.nowMillis()))
                OutboxResult.Dropped
            }
        }
    }

    private companion object {
        const val FIELD_HOUSEHOLD = "household"
    }
}

/**
 * `inventory_entry` rows, under [PantryReader.lock]:
 *
 * - **CREATE** `POST /api/inventory-entry/` with the location expanded to the whole object (FACTS
 *   "Fas S b"); a retry after a lost answer first looks for this user's new entry with the same
 *   food, location and amount, created after the change was made, that no other create claimed.
 * - **PATCH / DELETE**: checked against a complete read (amount 0 included, at most one a minute,
 *   [PantryReader.entryStillOnServerLocked]) first: an entry another phone deleted is dropped
 *   without a request. A 404 that still comes is done for the row; the entry goes locally.
 *
 * The answer (food and unit ids, Tandoor's code) is stored unless a newer change of the entry waits.
 */
internal class InventoryEntryOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val reader: PantryReader,
    private val locations: PantryLocations,
    private val changes: PantryChanges,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        val body = PantryPayloads.parse(payload) ?: return@withLock OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val result = when (row.op) {
            OutboxOps.CREATE -> create(remote, row, body)
            OutboxOps.PATCH -> patch(remote, row, body)
            OutboxOps.DELETE -> delete(remote, row)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
        if (result !is OutboxResult.Failed) changes.notify(remote.accountId)
        result
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val full = locations.expandIn(remote, body) ?: return unknownLocation()
        val existing = if (row.attempts > 0) findLostCreate(remote, row, body) else null
        val created = existing ?: when (val sent = caller.write(remote) { inventory.createEntry(full) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val serverId = created.id ?: return OutboxResult.Failed(TandemError.Decode("inventory-entry-without-id"))
        reader.noteEntryCreated(remote.accountId, serverId)
        storeAnswer(remote, row, created)
        return OutboxResult.Created(serverId)
    }

    private suspend fun patch(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        val full = locations.expandIn(remote, body) ?: return unknownLocation()
        goneBeforeSending(remote, row, serverId)?.let { return it }
        return when (val sent = caller.write(remote) { inventory.patchEntry(serverId, full) }) {
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
        return when (val sent = caller.write(remote) { inventory.deleteEntry(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent
            is Outcome.Failure -> notFoundOrFailed(remote, row, serverId, sent.error)
        }
    }

    /** The location of a payload is not stored (deleted on this device): a 400-like FAILED, the drain goes on. */
    private fun unknownLocation(): OutboxResult =
        OutboxResult.Failed(TandemError.BadRequest(mapOf(PantryPayloads.FIELD_LOCATION to emptyList())))

    private suspend fun goneBeforeSending(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long): OutboxResult? =
        when (val onServer = reader.entryStillOnServerLocked(remote, serverId)) {
            is Outcome.Failure -> OutboxResult.Failed(onServer.error)
            is Outcome.Success -> if (onServer.value) null else gone(remote, row, serverId, DroppedChanges.DELETED_ELSEWHERE)
        }

    private suspend fun notFoundOrFailed(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, error: TandemError): OutboxResult {
        if (error != TandemError.NotFound) return OutboxResult.Failed(error)
        reader.invalidateSeen(remote.accountId)
        return gone(remote, row, serverId, DroppedChanges.NOT_FOUND)
    }

    private suspend fun gone(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, reason: String): OutboxResult {
        val database = databases.get(remote.kind)
        database.pantryDao().inTransaction {
            database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.INVENTORY_ENTRY, serverId)
            database.pantryDao().deleteEntry(remote.accountId, serverId)
        }
        dropped.record(DroppedChange(row.entity, row.op, serverId, reason, clock.nowMillis()))
        return OutboxResult.Dropped
    }

    private suspend fun storeAnswer(remote: ActiveSource.Remote, row: OutboxEntity, answer: InventoryEntryDto) {
        val database = databases.get(remote.kind)
        val dao = database.pantryDao()
        val localKey = row.localId?.takeIf { row.op == OutboxOps.CREATE } ?: row.serverId ?: return
        dao.inTransaction {
            val newer = database.outboxDao().getAllFor(remote.accountId, OutboxEntities.INVENTORY_ENTRY)
                .any { it.id != row.id && (it.localId == localKey || (row.serverId != null && it.serverId == row.serverId)) }
            val current = dao.getEntry(remote.accountId, localKey)
            val fresh = answer.toEntity(remote.accountId, clock.nowMillis())
            if (!newer && current != null && fresh != null) {
                // A CREATE's row keeps its temporary key (and location id) until IdRewriteDao.completeCreate re-keys it.
                val kept = fresh.copy(localId = current.localId, serverId = current.serverId, locationId = current.locationId)
                dao.upsertEntries(listOf(kept))
            }
        }
    }

    private suspend fun findLostCreate(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): InventoryEntryDto? {
        val food = ((body["food"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: return null
        val location = (body[PantryPayloads.FIELD_LOCATION] as? JsonPrimitive)?.longOrNull
        val amount = PantryPayloads.number(body, PantryPayloads.FIELD_AMOUNT) ?: 0.0
        val listed = caller.call(remote, background = true) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = PantryReader.MAX_PAGES) { page -> api.inventory.entries(page.takeIf { it > 1 }, empty = true) }
        }
        val items = (listed as? Outcome.Success)?.value?.items ?: return null
        val claimed = claimedServerIds(databases, remote, OutboxEntities.INVENTORY_ENTRY)
        val notBefore = row.createdAtEpochMillis - SyncSchedule.CLOCK_SKEW_TOLERANCE.inWholeMilliseconds
        return items.filter { entry ->
            val createdAt = TandoorDates.parseInstantOrNull(entry.createdAt)?.toEpochMilli()
            entry.id != null && entry.id !in claimed && entry.createdBy == remote.account.userId &&
                (createdAt == null || createdAt >= notBefore) &&
                entry.food?.name.equals(food, ignoreCase = true) && entry.inventoryLocation?.id == location && entry.amount == amount
        }.minByOrNull { it.id ?: Long.MAX_VALUE }
    }
}

/**
 * `BATCH_ON_HAND` rows of `food` ("har hemma" for many foods at once: staples, FACTS "Fas S e"):
 * every ready row with the same batch key goes out in one `PUT /api/food/batch_update/`
 * `{foods: [ids], on_hand}` (answer `{}`). Called by the `food` handler (`FoodOutboxHandler`), which
 * owns the entity's other ops.
 */
internal class FoodOnHandBatchSender @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val changes: PantryChanges,
) {
    suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val outbox = databases.get(remote.kind).outboxDao()
        val onHand = (PantryPayloads.parse(payload)?.get(PantryPayloads.FIELD_ON_HAND) as? JsonPrimitive)?.booleanOrNull
            ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val batchKey = row.batchKey ?: PantryPayloads.onHandBatchKey(onHand)
        val others = outbox.nextReadyBatch(remote.accountId, batchKey, MAX_BATCH - 1)
            .filter { it.id != row.id && outbox.markInFlight(it.id) == 1 }
        val ids = (listOf(row) + others).mapNotNull { it.serverId }.filter { it > 0 }.distinct()
        if (ids.isEmpty()) {
            others.forEach { outbox.markDone(it.id) }
            return OutboxResult.Dropped
        }
        val body = buildJsonObject {
            put("foods", JsonArray(ids.map { JsonPrimitive(it) }))
            put(PantryPayloads.FIELD_ON_HAND, onHand)
        }
        return when (val sent = caller.write(remote) { pantryFoods.batchUpdate(body) }) {
            is Outcome.Success -> {
                others.forEach { outbox.markDone(it.id) }
                changes.notify(remote.accountId)
                OutboxResult.Sent
            }

            is Outcome.Failure -> {
                others.forEach { outbox.markRetry(it.id, null) }
                OutboxResult.Failed(sent.error)
            }
        }
    }

    companion object {
        /** At most this many foods per batch call. */
        const val MAX_BATCH: Int = 100
    }
}
