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
import javax.inject.Inject
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
 * A 404 means "deleted elsewhere": the row is done (an expected answer, outside the circuit breaker).
 * Most never happen: the window read drops queued changes of meals that disappeared
 * (`MealPlanWindowReader`).
 */
internal class MealPlanOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val body = MealPlanPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        return when (row.op) {
            OutboxOps.CREATE -> create(remote, row, body)
            OutboxOps.PATCH -> patch(remote, row, body)
            OutboxOps.DELETE -> delete(remote, row)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val existing = if (row.attempts > 0) findLostCreate(remote, body) else null
        val created = existing ?: when (val sent = caller.call(remote, background = true) { mealPlanEdits.createMealPlan(body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        val serverId = created.id ?: return OutboxResult.Failed(TandemError.Decode("meal-plan-without-id"))
        row.localId?.let { localId -> storeServerValues(remote, localId, created) }
        return OutboxResult.Created(serverId)
    }

    private suspend fun patch(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        val read = caller.call(remote, background = true) { mealPlanEdits.mealPlan(serverId) }
        val current = when (read) {
            is Outcome.Failure -> return if (read.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(read.error)
            is Outcome.Success -> read.value
        }
        val server = TandoorJson.encodeToJsonElement(MealPlanDto.serializer(), current).jsonObject
        val comparison = MealPlanPayloads.compare(body, MealPlanPayloads.parse(row.baseSnapshot), server)
        if (comparison.conflicting.isNotEmpty()) holdConflict(remote, row, serverId, comparison)
        if (comparison.send.isEmpty()) return OutboxResult.Dropped
        val sent = caller.call(remote, background = true) {
            mealPlanEdits.patchMealPlan(serverId, comparison.send)
        }
        return when (sent) {
            is Outcome.Success -> {
                storeServerValues(remote, serverId, sent.value)
                OutboxResult.Sent
            }

            is Outcome.Failure -> if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)
        }
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Dropped
        val sent = caller.call(remote, background = true) { mealPlanEdits.deleteMealPlan(serverId) }
        return when {
            sent is Outcome.Success -> OutboxResult.Sent
            (sent as Outcome.Failure).error == TandemError.NotFound -> OutboxResult.Dropped
            else -> OutboxResult.Failed(sent.error)
        }
    }

    /** The fields someone else changed wait in their own FAILED row until "behåll min" or "ta deras". */
    private suspend fun holdConflict(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        serverId: Long,
        comparison: MealPlanPayloads.Comparison,
    ) {
        databases.get(remote.kind).outboxDao().insert(
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
                updatedAtEpochMillis = clock.nowMillis(),
            ),
        )
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
        val known = databases.get(remote.kind).mealPlanDao().getRange(remote.accountId, day.toString(), day.toString())
            .mapNotNullTo(HashSet()) { it.entry.serverId }
        return meals.firstOrNull { meal ->
            val id = meal.id ?: return@firstOrNull false
            val server = TandoorJson.encodeToJsonElement(MealPlanDto.serializer(), meal).jsonObject
            id !in known &&
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
 * lost answer first looks for a meal type with the same name.
 */
internal class MealTypeOutboxHandler @Inject constructor(private val caller: RemoteCaller) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
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
        return when (val sent = caller.call(remote, background = true) { mealPlanEdits.createMealType(body) }) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)

            is Outcome.Success -> sent.value.id?.let { OutboxResult.Created(it) }
                ?: OutboxResult.Failed(TandemError.Decode("meal-type-without-id"))
        }
    }
}
