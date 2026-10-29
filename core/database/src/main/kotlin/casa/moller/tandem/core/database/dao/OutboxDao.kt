package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.outbox.OutboxMerge
import casa.moller.tandem.core.database.outbox.OutboxRefs
import kotlinx.coroutines.flow.Flow

/**
 * The outbox of queued writes (plan 3.5 "Skrivningar", 5.7). The sync worker loops:
 * [nextReady] -> [markInFlight] -> send -> [markDone] / [markRetry] / [markFailed]. At start-up
 * it calls [resetInFlight]; in safe mode (plan 3.6) [pauseAll] and later [resumeAll].
 *
 * Ordering rules of [nextReady]: lowest [OutboxEntity.id] first; a row waits while its
 * `depends_on` row still exists (any state, so a FAILED dependency blocks it) and while an older
 * row for the same object (same `entity` and `server_id` or `local_id`) OF THE SAME KIND is still
 * in the outbox: log rows (`VIEW_LOG`, `COOK_LOG`) wait only for log rows and edits only for edits
 * (0.3.0 review, fix-data: a FAILED "Vill laga" `RECIPE_KEYWORDS` row of a recipe never holds back
 * its "Klart!" or view log, and the reverse). A FAILED log row holds nothing back: log entries are
 * independent of each other, so one rejected "Klart!" never blocks the next.
 */
@Dao
abstract class OutboxDao {
    /** Adds a row and returns its id. */
    @Insert
    abstract suspend fun insert(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun get(id: Long): OutboxEntity?

    /** The next row to send for [accountId], or null when nothing is ready. */
    @Query("SELECT * FROM outbox o WHERE o.account_id = :accountId AND o.state = 'PENDING' AND " + READY + " ORDER BY o.id LIMIT 1")
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

    // ---------------------------------------------------------------- Phase 2 (schema 4, grund lane)

    /**
     * Queues [row] with the merge rules of [OutboxMerge] (plan 3.5/5.7; docs/BYGGE.md "Fas 2 –
     * kontrakt"): a PATCH after an unsent CREATE becomes part of the CREATE, check + uncheck cancel,
     * a DELETE of an object that never reached the server removes everything queued for it, and so
     * on. A row for an object that has no server id yet carries only `local_id`; once it has one,
     * only `server_id` (resolve stale temporary ids with [IdRewriteDao.resolve] first).
     *
     * When the change merges into an older row, that row keeps its place in the queue, gets the
     * merged payload, the union of both rows' [OutboxEntity.refs], the merged `base_snapshot` (PATCH
     * into PATCH, see [OutboxMerge]) and, if it had none, the new row's `depends_on`. A new row of an
     * object whose CREATE was attempted before (it may be on the server) `depends_on` that CREATE,
     * so it is sent by server id after it and fails with it.
     *
     * @return the id of the row that carries the change, or null when the change cancelled out.
     */
    @Transaction
    open suspend fun enqueue(row: OutboxEntity): Long? {
        val unsent = getUnsentFor(row.accountId, row.entity, row.localId, row.serverId)
        val attempted = OutboxMerge.attemptedCreateOf(unsent)
        val waitsFor = attempted?.takeIf { row.op != OutboxOps.CREATE && row.dependsOn == null }
        val inserted = if (waitsFor != null) row.copy(dependsOn = waitsFor.id) else row
        return when (val decision = OutboxMerge.decide(row, unsent)) {
            OutboxMerge.Decision.Insert -> insert(inserted)

            is OutboxMerge.Decision.Update -> {
                val existing = unsent.first { it.id == decision.id }
                val refs = OutboxRefs.encode(OutboxRefs.decode(existing.refs) + OutboxRefs.decode(row.refs))
                val dependsOn = existing.dependsOn ?: row.dependsOn
                val base = decision.baseSnapshot ?: existing.baseSnapshot
                updateMerged(decision.id, decision.payload, decision.batchKey, refs, dependsOn, row.createdAtEpochMillis, base)
                decision.id
            }

            is OutboxMerge.Decision.Cancel -> {
                deleteWithDependents(decision.ids)
                null
            }

            is OutboxMerge.Decision.Replace -> {
                deleteWithDependents(decision.ids)
                insert(inserted)
            }
        }
    }

    /**
     * Up to [limit] ready rows with [batchKey], in send order (e.g. every pending BULK_CHECK with
     * `checked = true`, sent as one `POST /api/shopping-list-entry/bulk/`). Same readiness rules as
     * [nextReady].
     */
    @Query(
        "SELECT * FROM outbox o WHERE o.account_id = :accountId AND o.state = 'PENDING' AND o.batch_key = :batchKey AND " +
            READY + " ORDER BY o.id LIMIT :limit",
    )
    abstract suspend fun nextReadyBatch(accountId: String, batchKey: String, limit: Int): List<OutboxEntity>

    /** Unsent rows (PENDING or PAUSED) of one object, oldest first: what [enqueue] merges with. */
    @Query(
        "SELECT * FROM outbox WHERE account_id = :accountId AND entity = :entity AND state IN ('PENDING', 'PAUSED') " +
            "AND ((:localId IS NOT NULL AND local_id = :localId) OR (:serverId IS NOT NULL AND server_id = :serverId)) ORDER BY id",
    )
    abstract suspend fun getUnsentFor(accountId: String, entity: String, localId: Long?, serverId: Long?): List<OutboxEntity>

    /** Every unsent row of [entity] (e.g. to keep a sync from overwriting objects with local changes). */
    @Query("SELECT * FROM outbox WHERE account_id = :accountId AND entity = :entity ORDER BY id")
    abstract suspend fun getAllFor(accountId: String, entity: String): List<OutboxEntity>

    /**
     * Removes the rows [ids] and every row that (transitively) depends on them; returns the ids removed.
     *
     * An object whose CREATE goes this way never reaches the server, so its local row (still without
     * a server id) goes too, in the same transaction (0.3.0 review, fix-data): an unsent meal deleted
     * after "Handla för veckan" takes its list recipe and entries with it instead of leaving rows on
     * the list that can never be sent (and would hold up the outbox when touched).
     */
    @Transaction
    open suspend fun deleteWithDependents(ids: List<Long>): List<Long> {
        val removed = mutableListOf<Long>()
        var level = ids
        while (level.isNotEmpty()) {
            removed += level
            level = getDependents(level).filter { it !in removed }
        }
        if (removed.isNotEmpty()) {
            val creates = getRows(removed).filter { it.op == OutboxOps.CREATE && (it.localId ?: 0L) < 0L }
            deleteRows(removed)
            creates.groupBy { it.accountId to it.entity }.forEach { (key, rows) ->
                deleteUnsentObjects(key.first, key.second, rows.mapNotNull { it.localId })
            }
        }
        return removed
    }

    /**
     * Removes every row of one object that is not being sent right now, with what depends on them
     * (and the local rows of creates among them, see [deleteWithDependents]): the object is gone.
     */
    @Transaction
    open suspend fun deleteObjectRows(accountId: String, entity: String, localId: Long?, serverId: Long?) {
        val rows = getAllFor(accountId, entity).filter { row ->
            row.state != OutboxState.IN_FLIGHT &&
                ((localId != null && row.localId == localId) || (serverId != null && row.serverId == serverId))
        }
        if (rows.isNotEmpty()) deleteWithDependents(rows.map { it.id })
    }

    /** True while the CREATE of temporary id [localId] of [entity] is in the outbox (any state). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE account_id = :accountId AND entity = :entity AND op = 'CREATE' " +
            "AND local_id = :localId)",
    )
    abstract suspend fun hasCreate(accountId: String, entity: String, localId: Long): Boolean

    /**
     * Deletes the local rows (never sent: `server_id IS NULL`) of objects created offline whose
     * CREATE was removed, by entity. Entities without offline creates of their own are left alone.
     */
    open suspend fun deleteUnsentObjects(accountId: String, entity: String, localIds: List<Long>) {
        if (localIds.isEmpty()) return
        when (entity) {
            OutboxEntities.SHOPPING_ENTRY -> deleteUnsentShoppingEntries(accountId, localIds)

            OutboxEntities.SHOPPING_LIST_RECIPE -> {
                deleteUnsentEntriesOfListRecipes(accountId, localIds)
                deleteUnsentListRecipes(accountId, localIds)
            }

            OutboxEntities.MEAL_PLAN -> deleteUnsentMeals(accountId, localIds)

            OutboxEntities.RECIPE_BOOK -> {
                deleteUnsentEntriesOfBooks(accountId, localIds)
                deleteUnsentBooks(accountId, localIds)
            }

            OutboxEntities.RECIPE_BOOK_ENTRY -> deleteUnsentBookEntries(accountId, localIds)

            // Fas S (hemma-data lane): a location's entries go with it, as on the server (CASCADE).
            OutboxEntities.INVENTORY_LOCATION -> {
                deleteUnsentEntriesOfLocations(accountId, localIds)
                deleteUnsentLocations(accountId, localIds)
            }

            OutboxEntities.INVENTORY_ENTRY -> deleteUnsentInventoryEntries(accountId, localIds)

            OutboxEntities.FOOD_ALIAS -> deleteUnsentFoodAliases(accountId, localIds)
        }
    }

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND location_id IN (:locationIds)")
    abstract suspend fun deleteUnsentEntriesOfLocations(accountId: String, locationIds: List<Long>)

    @Query("DELETE FROM inventory_location WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentLocations(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentInventoryEntries(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM food_alias WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentFoodAliases(accountId: String, localIds: List<Long>)

    @Query(
        "UPDATE outbox SET payload = :payload, batch_key = :batchKey, refs = :refs, depends_on = :dependsOn, " +
            "updated_at = :nowEpochMillis, base_snapshot = :baseSnapshot WHERE id = :id",
    )
    @Suppress("LongParameterList") // The merged row's own columns.
    abstract suspend fun updateMerged(
        id: Long,
        payload: String,
        batchKey: String?,
        refs: String?,
        dependsOn: Long?,
        nowEpochMillis: Long,
        baseSnapshot: String?,
    )

    @Query("SELECT * FROM outbox WHERE id IN (:ids)")
    abstract suspend fun getRows(ids: List<Long>): List<OutboxEntity>

    @Query("DELETE FROM shopping_entry WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentShoppingEntries(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM shopping_entry WHERE account_id = :accountId AND server_id IS NULL AND list_recipe_id IN (:listRecipeIds)")
    abstract suspend fun deleteUnsentEntriesOfListRecipes(accountId: String, listRecipeIds: List<Long>)

    @Query("DELETE FROM shopping_list_recipe WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentListRecipes(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM meal_plan WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentMeals(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND server_id IS NULL AND book_id IN (:bookIds)")
    abstract suspend fun deleteUnsentEntriesOfBooks(accountId: String, bookIds: List<Long>)

    @Query("DELETE FROM recipe_book WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentBooks(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND server_id IS NULL AND local_id IN (:localIds)")
    abstract suspend fun deleteUnsentBookEntries(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    abstract suspend fun deleteRows(ids: List<Long>)

    private companion object {
        /**
         * When a row `o` is ready: its `depends_on` row is gone (sent), and no older row for the same
         * object and of the same kind (log rows `VIEW_LOG`/`COOK_LOG` or edits) is still in the
         * outbox, except a FAILED log row.
         */
        const val READY: String =
            "(o.depends_on IS NULL OR NOT EXISTS (SELECT 1 FROM outbox dep WHERE dep.id = o.depends_on)) " +
                "AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.account_id = o.account_id AND p.id < o.id " +
                "AND p.entity = o.entity AND ((o.server_id IS NOT NULL AND p.server_id = o.server_id) " +
                "OR (o.local_id IS NOT NULL AND p.local_id = o.local_id)) " +
                "AND ((p.op IN ('VIEW_LOG', 'COOK_LOG')) = (o.op IN ('VIEW_LOG', 'COOK_LOG'))) " +
                "AND NOT (p.state = 'FAILED' AND p.op IN ('VIEW_LOG', 'COOK_LOG')))"
    }
}
