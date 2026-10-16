package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.CookLogState
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.query.RecipeSql
import kotlinx.coroutines.flow.Flow

/**
 * How [CookLogDao] writes an account's cook statistics into `recipe_overview`.
 *
 * @property ownUserId the logged-in Tandoor user: their entries (and entries without an author)
 *   are "own", and only own ratings count. Null counts every entry as own (demo mode has one user).
 * @property ratingsFromLog true for a real account: `rating` is the own average from the log (null
 *   without own ratings), because the server's lists carry no rating (FACTS "Fas 1 c"). False for
 *   demo mode: the stored ratings (from the demo details) stay, except for recipes rated on this
 *   device, which get the average of the log.
 */
data class CookStatsRules(val ownUserId: Long?, val ratingsFromLog: Boolean)

/**
 * The cook log (`cook_log`) and the statistics derived from it (plan 3.3, 5.7; feature #8).
 *
 * - **Offline first:** [logLocally] stores "Klart!" as a PENDING entry with a negative local id,
 *   queues `COOK_LOG` in the outbox (real accounts) and updates the recipe's statistics, all in
 *   one transaction, so lists, sort and filters see it at once.
 * - **Sending** (the outbox sender): [markSent] turns the entry into the server's (its id) and
 *   removes the outbox row in one transaction, so a crash can never send it twice from here;
 *   [markDropped] and [markRejected] record why an entry was never accepted.
 * - **Reconciling** (the sync): [reconcile] replaces the server's part of the log with a complete
 *   list and recomputes every recipe's statistics from entries that are SYNCED (from the list),
 *   SENT (sent after the list was requested) or PENDING (not sent yet). An entry is counted once:
 *   a sent entry has the server's id, so the list's copy replaces it.
 * - **Before the first complete list** (an update from 0.1.0, whose database has no entries yet)
 *   [logLocally] only adds to the stored statistics (+1, later "last cooked", the rating if none),
 *   because recomputing from an empty table would undo the server's counts. [isComplete] tells
 *   which mode applies.
 *
 * Every write that touches statistics takes the account's [CookStatsRules].
 */
@Dao
abstract class CookLogDao {

    // ---------------------------------------------------------------- high level

    /**
     * Logs a cooked recipe locally and returns the entry's (negative) local id.
     *
     * @param entry the entry; its `localId`, `serverId`, `state`, `sentAt` and `problem` are ignored.
     * @param outboxPayload the body of `POST /api/cook-log/` to queue, or null when the entry is
     *   never sent (demo mode).
     */
    @Transaction
    open suspend fun logLocally(entry: CookLogEntity, outboxPayload: String?, nowEpochMillis: Long, rules: CookStatsRules): Long {
        val accountId = entry.accountId
        val localId = minOf(lowestLocalId(accountId) ?: 0L, 0L) - 1
        val outboxId = outboxPayload?.let { payload ->
            insertOutbox(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.COOK_LOG,
                    entity = OutboxEntities.RECIPE,
                    serverId = entry.recipeId,
                    payload = payload,
                    createdAtEpochMillis = nowEpochMillis,
                ),
            )
        }
        insertEntry(
            entry.copy(
                localId = localId,
                serverId = null,
                state = CookLogState.PENDING,
                outboxId = outboxId,
                sentAtEpochMillis = null,
                problem = null,
            ),
        )
        if (isComplete(accountId)) {
            applyStats(accountId, listOf(entry.recipeId), rules)
        } else {
            addToStats(accountId, entry.recipeId, entry.cookedAtEpochMillis ?: nowEpochMillis, entry.rating?.toDouble())
        }
        return localId
    }

    /**
     * The server created the entry ([created], with its `serverId`): the local entry [localId]
     * becomes the server's (SENT, keyed by the server id) and the outbox row [outboxId] is removed.
     * An answer without an id keeps the local key; the next list then replaces the entry.
     */
    @Transaction
    open suspend fun markSent(
        accountId: String,
        localId: Long,
        outboxId: Long,
        created: CookLogEntity,
        sentAtEpochMillis: Long,
        rules: CookStatsRules,
    ) {
        val key = created.serverId ?: localId
        val local = getEntry(accountId, localId)
        deleteEntry(accountId, localId)
        upsertEntries(
            listOf(
                created.copy(
                    accountId = accountId,
                    localId = key,
                    cookedAtEpochMillis = created.cookedAtEpochMillis ?: local?.cookedAtEpochMillis,
                    state = CookLogState.SENT,
                    outboxId = null,
                    sentAtEpochMillis = sentAtEpochMillis,
                    problem = null,
                ),
            ),
        )
        deleteOutboxRow(outboxId)
        // Usually no change; fewer when a list already brought the server's copy (counted once now).
        if (isComplete(accountId)) applyStats(accountId, listOfNotNull(local?.recipeId, created.recipeId).distinct(), rules)
    }

    /**
     * The entry is never sent because its recipe is gone ([problem], see `CookLogProblems`): the
     * outbox row is removed, the entry stays as DROPPED and no longer counts.
     */
    @Transaction
    open suspend fun markDropped(accountId: String, localId: Long, outboxId: Long, problem: String, rules: CookStatsRules) {
        deleteOutboxRow(outboxId)
        setEntryState(accountId, localId, CookLogState.DROPPED, problem, outboxId = null)
        recountEntry(accountId, localId, rules)
    }

    /**
     * The server rejected the entry (400, [error] is the short error code): the outbox row is
     * FAILED (kept for "Pending changes" and its export, never retried automatically), the entry
     * is FAILED and no longer counts.
     */
    @Transaction
    open suspend fun markRejected(accountId: String, localId: Long, outboxId: Long, error: String?, rules: CookStatsRules) {
        failOutboxRow(outboxId, error)
        setEntryState(accountId, localId, CookLogState.FAILED, error, outboxId)
        recountEntry(accountId, localId, rules)
    }

    /**
     * Replaces the account's server entries with the complete list [serverEntries] (each with its
     * `serverId`), requested at [listRequestedAtEpochMillis], and recomputes the statistics of
     * every recipe in one transaction. Entries this device sent before the list was requested
     * and that the list lacks were deleted on the server (e.g. on the web) and are removed.
     */
    @Transaction
    open suspend fun reconcile(
        accountId: String,
        serverEntries: List<CookLogEntity>,
        listRequestedAtEpochMillis: Long,
        nowEpochMillis: Long,
        rules: CookStatsRules,
    ) {
        deleteSynced(accountId)
        deleteSentBefore(accountId, listRequestedAtEpochMillis)
        val rows = serverEntries.mapNotNull { entry ->
            entry.serverId?.let { id ->
                entry.copy(
                    accountId = accountId,
                    localId = id,
                    state = CookLogState.SYNCED,
                    outboxId = null,
                    sentAtEpochMillis = null,
                    problem = null,
                )
            }
        }
        if (rows.isNotEmpty()) upsertEntries(rows.distinctBy { it.localId })
        upsertSyncState(SyncStateEntity(accountId = accountId, entity = SyncEntities.COOK_LOG, lastFullRelistEpochMillis = nowEpochMillis))
        replaceAllStats(accountId, rules)
    }

    /**
     * After a recipe detail was stored (its `rating` is the server's, without this device's unsent
     * entries): recomputes the recipe's statistics when it has entries the server does not know yet.
     */
    @Transaction
    open suspend fun reapplyUnsent(accountId: String, recipeId: Long, rules: CookStatsRules) {
        if (isComplete(accountId) && hasUnsent(accountId, recipeId)) applyStats(accountId, listOf(recipeId), rules)
    }

    /** True once a complete server list has been stored for [accountId] (see the class comment). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM sync_state WHERE account_id = :accountId AND entity = '${SyncEntities.COOK_LOG}' " +
            "AND last_full_relist IS NOT NULL)",
    )
    abstract suspend fun isComplete(accountId: String): Boolean

    /** Entries of [accountId] that wait in the outbox (`COOK_LOG` rows in any state). */
    @Query("SELECT COUNT(*) FROM outbox WHERE account_id = :accountId AND op = 'COOK_LOG'")
    abstract fun observePendingCount(accountId: String): Flow<Int>

    /** Every entry of [accountId], logged ones (negative ids) first. */
    @Query("SELECT * FROM cook_log WHERE account_id = :accountId ORDER BY local_id")
    abstract suspend fun getEntries(accountId: String): List<CookLogEntity>

    @Query("SELECT * FROM cook_log WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getEntry(accountId: String, localId: Long): CookLogEntity?

    /** The entry a `COOK_LOG` outbox row sends, if it still exists. */
    @Query("SELECT * FROM cook_log WHERE account_id = :accountId AND outbox_id = :outboxId")
    abstract suspend fun getEntryForOutbox(accountId: String, outboxId: Long): CookLogEntity?

    // ---------------------------------------------------------------- statistics

    private suspend fun applyStats(accountId: String, recipeIds: List<Long>, rules: CookStatsRules) {
        recipeIds.distinct().chunked(RecipeSql.CHUNK).forEach { chunk ->
            val stats = CookStatistics.of(getCountedEntries(accountId, chunk).map { it.toFact(rules) }).associateBy { it.recipeId }
            val locallyRated = if (rules.ratingsFromLog) emptySet() else getLocallyRatedRecipes(accountId).toSet()
            for (recipeId in chunk) {
                val row = stats[recipeId]
                if (rules.ratingsFromLog || recipeId in locallyRated) {
                    setStatsAndRating(accountId, recipeId, row?.lastCookedEpochMillis, row?.timesCooked, row?.rating)
                } else {
                    setStats(accountId, recipeId, row?.lastCookedEpochMillis, row?.timesCooked)
                }
            }
        }
    }

    private suspend fun replaceAllStats(accountId: String, rules: CookStatsRules) {
        val stats = CookStatistics.of(getAllCountedEntries(accountId).map { it.toFact(rules) })
        if (rules.ratingsFromLog) resetStatsAndRatings(accountId) else resetStats(accountId)
        val locallyRated = if (rules.ratingsFromLog) emptySet() else getLocallyRatedRecipes(accountId).toSet()
        for (row in stats) {
            if (rules.ratingsFromLog || row.recipeId in locallyRated) {
                setStatsAndRating(accountId, row.recipeId, row.lastCookedEpochMillis, row.timesCooked, row.rating)
            } else {
                setStats(accountId, row.recipeId, row.lastCookedEpochMillis, row.timesCooked)
            }
        }
    }

    /** An entry stopped counting: recompute its recipe (only possible once the log is complete). */
    private suspend fun recountEntry(accountId: String, localId: Long, rules: CookStatsRules) {
        val entry = getEntry(accountId, localId) ?: return
        if (isComplete(accountId)) applyStats(accountId, listOf(entry.recipeId), rules)
    }

    private fun CookLogEntity.toFact(rules: CookStatsRules): CookLogFact = CookLogFact(
        recipeId = recipeId,
        cookedAtEpochMillis = cookedAtEpochMillis,
        rating = rating,
        own = rules.ownUserId == null || createdBy == null || createdBy == rules.ownUserId,
    )

    // ---------------------------------------------------------------- building blocks (Room)

    @Insert
    abstract suspend fun insertEntry(row: CookLogEntity)

    @Upsert
    abstract suspend fun upsertEntries(rows: List<CookLogEntity>)

    @Insert
    abstract suspend fun insertOutbox(row: OutboxEntity): Long

    @Upsert
    abstract suspend fun upsertSyncState(row: SyncStateEntity)

    @Query("SELECT MIN(local_id) FROM cook_log WHERE account_id = :accountId")
    abstract suspend fun lowestLocalId(accountId: String): Long?

    @Query("DELETE FROM cook_log WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun deleteEntry(accountId: String, localId: Long)

    @Query(
        "UPDATE cook_log SET state = :state, problem = :problem, outbox_id = :outboxId " +
            "WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun setEntryState(accountId: String, localId: Long, state: CookLogState, problem: String?, outboxId: Long?)

    @Query("DELETE FROM cook_log WHERE account_id = :accountId AND state = 'SYNCED'")
    abstract suspend fun deleteSynced(accountId: String)

    @Query("DELETE FROM cook_log WHERE account_id = :accountId AND state = 'SENT' AND (sent_at IS NULL OR sent_at < :before)")
    abstract suspend fun deleteSentBefore(accountId: String, before: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun deleteOutboxRow(id: Long)

    @Query("UPDATE outbox SET state = 'FAILED', last_error = :error WHERE id = :id")
    abstract suspend fun failOutboxRow(id: Long, error: String?)

    @Query("SELECT * FROM cook_log WHERE account_id = :accountId AND state IN ($COUNTED)")
    abstract suspend fun getAllCountedEntries(accountId: String): List<CookLogEntity>

    @Query("SELECT * FROM cook_log WHERE account_id = :accountId AND recipe_id IN (:recipeIds) AND state IN ($COUNTED)")
    abstract suspend fun getCountedEntries(accountId: String, recipeIds: List<Long>): List<CookLogEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM cook_log WHERE account_id = :accountId AND recipe_id = :recipeId " +
            "AND state IN ('PENDING', 'SENT'))",
    )
    abstract suspend fun hasUnsent(accountId: String, recipeId: Long): Boolean

    /** Recipes with a rating logged on this device (demo mode: the log decides their rating). */
    @Query(
        "SELECT DISTINCT recipe_id FROM cook_log WHERE account_id = :accountId AND local_id < 0 " +
            "AND rating IS NOT NULL AND state = 'PENDING'",
    )
    abstract suspend fun getLocallyRatedRecipes(accountId: String): List<Long>

    @Query(
        "UPDATE recipe_overview SET times_cooked = COALESCE(times_cooked, 0) + 1, " +
            "last_cooked = MAX(COALESCE(last_cooked, :cookedAt), :cookedAt), rating = COALESCE(rating, :rating) " +
            "WHERE account_id = :accountId AND server_id = :recipeId",
    )
    abstract suspend fun addToStats(accountId: String, recipeId: Long, cookedAt: Long, rating: Double?)

    @Query(
        "UPDATE recipe_overview SET last_cooked = :lastCookedEpochMillis, times_cooked = :timesCooked " +
            "WHERE account_id = :accountId AND server_id = :recipeId",
    )
    abstract suspend fun setStats(accountId: String, recipeId: Long, lastCookedEpochMillis: Long?, timesCooked: Int?)

    @Query(
        "UPDATE recipe_overview SET last_cooked = :lastCookedEpochMillis, times_cooked = :timesCooked, rating = :rating " +
            "WHERE account_id = :accountId AND server_id = :recipeId",
    )
    abstract suspend fun setStatsAndRating(
        accountId: String,
        recipeId: Long,
        lastCookedEpochMillis: Long?,
        timesCooked: Int?,
        rating: Double?,
    )

    @Query(
        "UPDATE recipe_overview SET last_cooked = NULL, times_cooked = NULL " +
            "WHERE account_id = :accountId AND (last_cooked IS NOT NULL OR times_cooked IS NOT NULL)",
    )
    abstract suspend fun resetStats(accountId: String)

    @Query(
        "UPDATE recipe_overview SET last_cooked = NULL, times_cooked = NULL, rating = NULL " +
            "WHERE account_id = :accountId AND (last_cooked IS NOT NULL OR times_cooked IS NOT NULL OR rating IS NOT NULL)",
    )
    abstract suspend fun resetStatsAndRatings(accountId: String)
}

/** Entry states that count in the statistics (see `CookLogState`). */
private const val COUNTED = "'SYNCED', 'SENT', 'PENDING'"
