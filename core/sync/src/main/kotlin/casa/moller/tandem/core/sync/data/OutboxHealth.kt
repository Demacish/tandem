package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.diagnostics.OutboxKind
import casa.moller.tandem.core.model.diagnostics.OutboxProblem
import casa.moller.tandem.core.model.diagnostics.OutboxSummary
import casa.moller.tandem.core.model.diagnostics.SyncHealth
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.sync.engine.CIRCUIT_BREAKER_CODE
import casa.moller.tandem.core.sync.guard.GuardState
import casa.moller.tandem.core.sync.mealplan.MealPlanPayloads
import kotlin.time.Instant

/**
 * Phase 2 (koppling lane): "blockerad"-diagnostik (plan §10 Fas 2, 4.1): the outbox per kind of
 * change with its last error, and why and until when the guard holds requests back. Pure, from
 * the rows' `op`/`entity`/`state`/`last_error` (stable codes, `ErrorCodes`) and the guard state.
 */
internal object OutboxHealth {
    fun of(rows: List<OutboxEntity>, pause: SyncPause?, guard: GuardState): SyncHealth = SyncHealth(
        pause = pause,
        pausedUntil = when (pause) {
            SyncPause.BLOCKED -> maxOf(guard.blockedUntil, guard.throttledUntil)
            SyncPause.CIRCUIT_BREAKER -> guard.breakerUntil
            else -> 0L
        }.takeIf { it > 0L }?.let { Instant.fromEpochMilliseconds(it) },
        recentClientErrors = guard.clientErrorTimes.size,
        outbox = summaries(rows),
    )

    fun summaries(rows: List<OutboxEntity>): List<OutboxSummary> = rows.groupBy(::kindOf)
        .map { (kind, list) ->
            OutboxSummary(
                kind = kind,
                waiting = list.count { it.state != OutboxState.FAILED },
                failed = list.count { it.state == OutboxState.FAILED },
                lastProblem = list.filter { it.lastError != null }.maxByOrNull { it.updatedAtEpochMillis ?: it.createdAtEpochMillis }
                    ?.lastError?.let(::problemOf),
            )
        }
        .sortedBy { it.kind.ordinal }

    fun kindOf(row: OutboxEntity): OutboxKind = when {
        row.op == OutboxOps.COOK_LOG -> OutboxKind.COOK_LOG
        row.op == OutboxOps.VIEW_LOG -> OutboxKind.VIEW_LOG
        row.op == OutboxOps.RECIPE_KEYWORDS -> OutboxKind.WANT_TO_COOK
        else -> when (row.entity) {
            OutboxEntities.SHOPPING_ENTRY -> OutboxKind.SHOPPING_ENTRY
            OutboxEntities.SHOPPING_LIST_RECIPE -> OutboxKind.SHOPPING_RECIPE
            OutboxEntities.MEAL_PLAN -> OutboxKind.MEAL_PLAN
            OutboxEntities.MEAL_TYPE -> OutboxKind.MEAL_TYPE
            OutboxEntities.RECIPE_BOOK -> OutboxKind.RECIPE_BOOK
            OutboxEntities.RECIPE_BOOK_ENTRY -> OutboxKind.RECIPE_BOOK_ENTRY
            OutboxEntities.FOOD -> OutboxKind.FOOD
            OutboxEntities.SUPERMARKET_CATEGORY_RELATION -> OutboxKind.AISLE_ORDER
            OutboxEntities.USER_PREFERENCE -> OutboxKind.PREFERENCES
            else -> OutboxKind.OTHER
        }
    }

    /** A row's `last_error` (an `ErrorCodes` code or a lane's own code) as a reason people can read. */
    fun problemOf(code: String): OutboxProblem = when {
        code == "network-unavailable" || code == "unknown-host" -> OutboxProblem.OFFLINE
        code == "timeout" -> OutboxProblem.TIMEOUT
        code == "http-404" -> OutboxProblem.NOT_FOUND
        code == "http-400" -> OutboxProblem.BAD_REQUEST
        code == "unauthorized" -> OutboxProblem.UNAUTHORIZED
        code == "blocked" -> OutboxProblem.BLOCKED
        code == "http-429" -> OutboxProblem.THROTTLED
        code.startsWith("http-5") -> OutboxProblem.SERVER_ERROR
        code == CIRCUIT_BREAKER_CODE -> OutboxProblem.CIRCUIT_BREAKER
        code == MealPlanPayloads.CONFLICT -> OutboxProblem.CONFLICT
        else -> OutboxProblem.OTHER
    }
}
