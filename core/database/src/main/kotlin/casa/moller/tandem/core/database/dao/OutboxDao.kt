package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import kotlinx.coroutines.flow.Flow

/**
 * The outbox of queued writes (plan 3.5 "Skrivningar", 5.7). The sync worker loops:
 * [nextReady] -> [markInFlight] -> send -> [markDone] / [markRetry] / [markFailed]. At start-up
 * it calls [resetInFlight]; in safe mode (plan 3.6) [pauseAll] and later [resumeAll].
 *
 * Ordering rules of [nextReady]: lowest [OutboxEntity.id] first; a row waits while its
 * `depends_on` row still exists (any state, so a FAILED dependency blocks it) and while an older
 * row for the same object (same `entity` and `server_id` or `local_id`) is still in the outbox.
 * A FAILED log row (`VIEW_LOG`, `COOK_LOG`) holds nothing back: log entries are independent of
 * each other and of the recipe's edits, so one rejected "Klart!" never blocks the next.
 */
@Dao
abstract class OutboxDao {
    /** Adds a row and returns its id. */
    @Insert
    abstract suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun get(id: Long): OutboxEntity?

    /** The next row to send for [accountId], or null when nothing is ready. */
    @Query(
        "SELECT * FROM outbox o WHERE o.account_id = :accountId AND o.state = 'PENDING' " +
            "AND (o.depends_on IS NULL OR NOT EXISTS (SELECT 1 FROM outbox dep WHERE dep.id = o.depends_on)) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.account_id = o.account_id AND p.id < o.id " +
            "AND p.entity = o.entity AND ((o.server_id IS NOT NULL AND p.server_id = o.server_id) " +
            "OR (o.local_id IS NOT NULL AND p.local_id = o.local_id)) " +
            "AND NOT (p.state = 'FAILED' AND p.op IN ('VIEW_LOG', 'COOK_LOG'))) " +
            "ORDER BY o.id LIMIT 1",
    )
    abstract suspend fun nextReady(accountId: String): OutboxEntity?

    /** Claims a PENDING row for sending; returns 1 when claimed, 0 when it was not PENDING any more. */
    @Query("UPDATE outbox SET state = 'IN_FLIGHT', attempts = attempts + 1 WHERE id = :id AND state = 'PENDING'")
    abstract suspend fun markInFlight(id: Long): Int

    /** The row was sent: it is removed, which releases rows that depend on it. */
    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun markDone(id: Long)

    /** A temporary failure (offline, 5xx, timeout): back to PENDING, to be retried with backoff. */
    @Query("UPDATE outbox SET state = 'PENDING', last_error = :error WHERE id = :id")
    abstract suspend fun markRetry(id: Long, error: String?)

    /**
     * A permanent failure (e.g. 400): the row and every row that (transitively) depends on it
     * become FAILED (plan 5.7) and are shown in "Pending changes". Returns the ids marked.
     */
    @Transaction
    open suspend fun markFailed(id: Long, error: String?): List<Long> {
        val marked = mutableListOf<Long>()
        var level = listOf(id)
        while (level.isNotEmpty()) {
            setFailed(level, error)
            marked += level
            level = getDependents(level).filter { it !in marked }
        }
        return marked
    }

    /** Puts a FAILED row back in the queue ("Try again" in "Pending changes"). */
    @Query("UPDATE outbox SET state = 'PENDING', last_error = NULL WHERE id = :id AND state = 'FAILED'")
    abstract suspend fun retryFailed(id: Long)

    /** After a crash or kill while sending: IN_FLIGHT rows go back to PENDING. */
    @Query("UPDATE outbox SET state = 'PENDING' WHERE account_id = :accountId AND state = 'IN_FLIGHT'")
    abstract suspend fun resetInFlight(accountId: String)

    /** Safe mode (plan 3.6): hold every PENDING row. */
    @Query("UPDATE outbox SET state = 'PAUSED' WHERE account_id = :accountId AND state = 'PENDING'")
    abstract suspend fun pauseAll(accountId: String)

    /** Leaves safe mode ("Send anyway"): PAUSED rows are queued again. */
    @Query("UPDATE outbox SET state = 'PENDING' WHERE account_id = :accountId AND state = 'PAUSED'")
    abstract suspend fun resumeAll(accountId: String)

    /** Number of unsent rows (any state), for `SyncStatus.pendingChanges`. */
    @Query("SELECT COUNT(*) FROM outbox WHERE account_id = :accountId")
    abstract fun observeCount(accountId: String): Flow<Int>

    /** Every row of the account in send order ("Pending changes"). */
    @Query("SELECT * FROM outbox WHERE account_id = :accountId ORDER BY id")
    abstract fun observeAll(accountId: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE account_id = :accountId AND state = :state ORDER BY id")
    abstract suspend fun getByState(accountId: String, state: OutboxState): List<OutboxEntity>

    /**
     * Queues `POST /api/view-log/` for a recipe that was opened (plan 3.3), coalescing
     * duplicates: older unsent VIEW_LOG rows of the same recipe are replaced by this one, so
     * opening a recipe ten times offline sends one request, in the order of the last view.
     */
    @Transaction
    open suspend fun enqueueViewLog(accountId: String, recipeId: Long, payload: String, nowEpochMillis: Long): Long {
        deleteUnsentViewLogs(accountId, recipeId)
        return insert(
            OutboxEntity(
                accountId = accountId,
                op = OutboxOps.VIEW_LOG,
                entity = OutboxEntities.RECIPE,
                serverId = recipeId,
                payload = payload,
                createdAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    /**
     * After the CREATE of an offline object succeeded (plan 5.7): every row that points to
     * [localId] gets [serverId]. Run it in the same transaction as the local id rewrite.
     */
    @Query("UPDATE outbox SET server_id = :serverId WHERE account_id = :accountId AND entity = :entity AND local_id = :localId")
    abstract suspend fun rewriteServerId(accountId: String, entity: String, localId: Long, serverId: Long)

    /** The object no longer exists on the server: drop its unsent rows without sending them (plan 3.5). */
    @Query(
        "DELETE FROM outbox WHERE account_id = :accountId AND entity = :entity AND server_id = :serverId " +
            "AND state != 'IN_FLIGHT'",
    )
    abstract suspend fun deleteUnsentFor(accountId: String, entity: String, serverId: Long)

    // ---------------------------------------------------------------- building blocks (Room)

    @Query(
        "DELETE FROM outbox WHERE account_id = :accountId AND op = 'VIEW_LOG' AND entity = 'recipe' " +
            "AND server_id = :recipeId AND state IN ('PENDING', 'PAUSED')",
    )
    abstract suspend fun deleteUnsentViewLogs(accountId: String, recipeId: Long)

    @Query("UPDATE outbox SET state = 'FAILED', last_error = :error WHERE id IN (:ids)")
    abstract suspend fun setFailed(ids: List<Long>, error: String?)

    @Query("SELECT id FROM outbox WHERE depends_on IN (:ids)")
    abstract suspend fun getDependents(ids: List<Long>): List<Long>
}
