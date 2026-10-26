package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.domain.mealplan.MealPlanDates
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.dto.MealPlanDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.network.paging.Paginator
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Sends `meal_plan` rows (docs/BYGGE.md "Fas 2 – kontrakt"; plan 3.5, 5.7; FACTS "Fas 2 f"):
 *
 * - **CREATE** `POST /api/meal-plan/`; the answer's dates and `shopping` are stored on the meal
 *   (the user's fields stay), then the id is rewritten everywhere ([OutboxResult.Created]). POST is
 *   not idempotent: a retry after a lost answer first looks for the meal on its day.
 * - **PATCH** reads the meal first (`MealPlan` has no `updated_at`) and compares each changed field
 *   with the value the change started from ([MealPlanPayloads.compare]): unchanged fields are sent,
 *   fields that already have the user's value are left out, and fields someone else changed
 *   meanwhile are held back as a conflict (a FAILED row with [MealPlanPayloads.CONFLICT], shown by
 *   `MealPlanRepository.observeConflicts`) while the rest is sent. Unrelated fields are never sent.
 * - **DELETE** `DELETE /api/meal-plan/{id}/`.
 *
 * Before a PATCH or DELETE, the meal is checked against a complete window read after the last
 * reconnect (at most a minute old, [MealPlanWindowReader.stillOnServerLocked]; 0.3.0 review,
 * fix-data, plan 3.5): a meal deleted elsewhere is dropped WITHOUT a request, also when only the
 * outbox worker runs after the network came back. A 404 that still comes (deleted since the read,
 * or a meal outside the window) means "deleted elsewhere": the row is done, the next update or
 * delete reads the window again first, and the 404 counts in the circuit breaker like every 404.
 *
 * Every row is sent under [MealPlanWindowReader.lock], so a window read never sees the plan from
 * before a CREATE that is answered meanwhile (and removes the new meal).
 */
internal class MealPlanOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
    private val reader: MealPlanWindowReader,
    private val dropped: DroppedChanges,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        val body = MealPlanPayloads.parse(payload) ?: return@withLock OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        when (row.op) {
            OutboxOps.CREATE -> create(remote, row, body)
            OutboxOps.PATCH -> patch(remote, row, body)
            OutboxOps.DELETE -> delete(remote, row, body)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val existing = if (row.attempts > 0) findLostCreate(remote, body) else null
        val created = existing ?: when (val sent = caller.write(remote) { mealPlanEdits.createMealPlan(body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val serverId = created.id ?: return OutboxResult.Failed(TandemError.Decode("meal-plan-without-id"))
        row.localId?.let { localId -> storeServerValues(remote, localId, created) }
        reader.noteCreated(remote.accountId, serverId)
        return OutboxResult.Created(serverId)
    }

    private suspend fun patch(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        goneBeforeSending(remote, row, serverId, body)?.let { return it }
        val read = caller.call(remote, background = true) { mealPlanEdits.mealPlan(serverId) }
        val current = when (read) {
            is Outcome.Failure -> return notFoundOrFailed(remote, row, serverId, read.error)
            is Outcome.Success -> read.value
        }
        val server = TandoorJson.encodeToJsonElement(MealPlanDto.serializer(), current).jsonObject
        val base = MealPlanPayloads.parse(row.baseSnapshot)
        val comparison = MealPlanPayloads.compare(body, base, server)
        if (comparison.conflicting.isNotEmpty()) holdConflict(remote, row, serverId, comparison, base)
        if (comparison.send.isEmpty()) return OutboxResult.Dropped
        val sent = caller.write(remote) {
            mealPlanEdits.patchMealPlan(serverId, comparison.send)
        }
        return when (sent) {
            is Outcome.Success -> {
                storeServerValues(remote, serverId, sent.value)
                OutboxResult.Sent
            }

            is Outcome.Failure -> notFoundOrFailed(remote, row, serverId, sent.error)
        }
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Dropped
        goneBeforeSending(remote, row, serverId, body)?.let { return it }
        return when (val sent = caller.write(remote) { mealPlanEdits.deleteMealPlan(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent
            is Outcome.Failure -> notFoundOrFailed(remote, row, serverId, sent.error)
        }
    }

    /** Null to send; a result when the meal is gone (dropped unsent, with its other queued rows) or the check failed. */
    private suspend fun goneBeforeSending(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, body: JsonObject): OutboxResult? {
        val stored = databases.get(remote.kind).mealPlanDao().get(remote.accountId, serverId)
        // The meal's last known server day; a DELETE remembers it in its payload (the meal is gone here).
        val day = stored?.let { MealPlanDates.serverDay(it.toRaw) ?: MealPlanDates.serverDay(it.toDate) }
            ?: MealPlanDates.serverDay(text(body, MealPlanPayloads.TO_DATE))
        return when (val onServer = reader.stillOnServerLocked(remote, serverId, day)) {
            is Outcome.Failure -> OutboxResult.Failed(onServer.error)
            is Outcome.Success -> if (onServer.value) null else gone(remote, row, serverId, DroppedChanges.DELETED_ELSEWHERE)
        }
    }

    private suspend fun notFoundOrFailed(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, error: TandemError): OutboxResult {
        if (error != TandemError.NotFound) return OutboxResult.Failed(error)
        reader.invalidateSeen()
        return gone(remote, row, serverId, DroppedChanges.NOT_FOUND)
    }

    /** The meal no longer exists on the server: it goes here too, with its other queued rows (Tandoor deleted its entries). */
    private suspend fun gone(remote: ActiveSource.Remote, row: OutboxEntity, serverId: Long, reason: String): OutboxResult {
        databases.get(remote.kind).mealPlanDao().deleteGone(remote.accountId, listOf(serverId))
        dropped.record(DroppedChange(row.entity, row.op, serverId, reason, clock.nowMillis()))
        return OutboxResult.Dropped
    }

    /**
     * The fields someone else changed wait in their own FAILED row until "behåll min" or "ta deras".
     * In the same transaction the row being sent is narrowed to the fields that go out now (0.3.0
     * review, fix-data), so a retry after a failed PATCH never meets the conflict again and never
     * adds a second conflict row.
     */
    private suspend fun holdConflict(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        serverId: Long,
        comparison: MealPlanPayloads.Comparison,
        base: JsonObject?,
    ) {
        val database = databases.get(remote.kind)
        val outbox = database.outboxDao()
        val now = clock.nowMillis()
        database.mealPlanDao().inTransaction {
            val held = outbox.getAllFor(remote.accountId, OutboxEntities.MEAL_PLAN).any { other ->
                other.serverId == serverId && other.state == OutboxState.FAILED && other.lastError == MealPlanPayloads.CONFLICT &&
                    MealPlanPayloads.parse(other.payload)?.keys?.containsAll(comparison.conflicting.keys) == true
            }
            if (!held) {
                outbox.insert(
                    OutboxEntity(
                        accountId = remote.accountId,
                        op = OutboxOps.PATCH,
                        entity = OutboxEntities.MEAL_PLAN,
                        serverId = serverId,
                        payload = comparison.conflicting.toString(),
                        baseSnapshot = comparison.theirs.toString(),
                        createdAtEpochMillis = row.createdAtEpochMillis,
                        state = OutboxState.FAILED,
                        lastError = MealPlanPayloads.CONFLICT,
                        updatedAtEpochMillis = now,
                    ),
                )
            }
            val sentBase = base?.let { JsonObject(it.filterKeys { field -> field in comparison.send }) }
            outbox.updateMerged(row.id, comparison.send.toString(), row.batchKey, row.refs, row.dependsOn, now, sentBase?.toString())
        }
    }

    /** The server's own values of the meal (its date-times and `shopping`); the user's fields stay as they are. */
    private suspend fun storeServerValues(remote: ActiveSource.Remote, localId: Long, answer: MealPlanDto) {
        databases.get(remote.kind).mealPlanDao()
            .updateServerValues(remote.accountId, localId, answer.fromDate, answer.toDate, answer.shopping)
    }

    /** A meal like [body] on its day that this device does not know yet: the answer to an earlier attempt that was lost. */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, body: JsonObject): MealPlanDto? {
        val day = MealPlanDates.serverDay(text(body, MealPlanPayloads.TO_DATE) ?: text(body, MealPlanPayloads.FROM_DATE)) ?: return null
        val listed = caller.call(remote, background = true) {
            val api = this
            Paginator.fetchAll { page -> api.mealPlans.mealPlans(day, day, page.takeIf { it > 1 }) }
        }
        val meals = (listed as? Outcome.Success)?.value?.items ?: return null
        // 0.3.0 review (fix-data): only meals another create of this device claimed are excluded.
        // A copy of the lost meal that a window read stored meanwhile is THE candidate
        // (`completeCreate` drops that copy), so the retry never makes a second meal.
        val claimed = claimedServerIds(databases, remote, OutboxEntities.MEAL_PLAN)
        return meals.firstOrNull { meal ->
            val id = meal.id ?: return@firstOrNull false
            val server = TandoorJson.encodeToJsonElement(MealPlanDto.serializer(), meal).jsonObject
            id !in claimed && (meal.createdBy == null || meal.createdBy == remote.account.userId) &&
                SAME_MEAL.all { field ->
                    MealPlanPayloads.normalized(field, server[field]) == MealPlanPayloads.normalized(field, body[field])
                }
        }
    }

    private fun text(body: JsonObject, field: String): String? = (body[field] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val UNSUPPORTED = "tandem.sync.unsupported-outbox-op"
        const val WAITING_FOR_ID = "tandem.sync.waiting-for-server-id"

        /** The fields that identify a meal created by a lost attempt. */
        val SAME_MEAL = listOf(MealPlanPayloads.FROM_DATE, MealPlanPayloads.MEAL_TYPE, MealPlanPayloads.RECIPE, MealPlanPayloads.TITLE)
    }
}

/**
 * Sends `meal_type` rows: CREATE `POST /api/meal-type/` (shared by the whole space). A retry after a
 * lost answer first looks for a meal type with the same name. Under [MealPlanWindowReader.lock],
 * like the meals.
 */
internal class MealTypeOutboxHandler @Inject constructor(
    private val caller: RemoteCaller,
    private val reader: MealPlanWindowReader,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult =
        reader.lock.withLock { sendLocked(remote, row, payload) }

    private suspend fun sendLocked(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        if (row.op != OutboxOps.CREATE) return OutboxResult.Failed(TandemError.Unknown("tandem.sync.unsupported-outbox-op"))
        val body = MealPlanPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown("tandem.sync.unsupported-outbox-op"))
        if (row.attempts > 0) {
            val name = (body["name"] as? JsonPrimitive)?.contentOrNull
            val listed = caller.call(remote, background = true) {
                val api = this
                Paginator.fetchAll { page -> api.taxonomy.mealTypes(page.takeIf { it > 1 }) }
            }
            val found = (listed as? Outcome.Success)?.value?.items?.firstOrNull { it.name == name }?.id
            if (found != null) return OutboxResult.Created(found)
        }
        return when (val sent = caller.write(remote) { mealPlanEdits.createMealType(body) }) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)

            is Outcome.Success -> sent.value.id?.let { OutboxResult.Created(it) }
                ?: OutboxResult.Failed(TandemError.Decode("meal-type-without-id"))
        }
    }
}
