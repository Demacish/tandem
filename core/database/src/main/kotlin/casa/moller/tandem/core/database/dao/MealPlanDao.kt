package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.relation.MealPlanRow
import kotlinx.coroutines.flow.Flow

/**
 * The meal plan (plan 3.5: the window -14..+42 days is re-read and compared). Dates are
 * `yyyy-MM-dd` strings in the server's time zone; string order is date order.
 */
@Dao
abstract class MealPlanDao {
    /**
     * Meals that touch the days [fromDate]..[toDate] (inclusive), with meal type and recipe, by
     * day, then meal type order.
     */
    @Query(SELECT_ROWS + "WHERE m.account_id = :accountId AND m.from_date <= :toDate AND m.to_date >= :fromDate " + ORDER)
    abstract fun observeRange(accountId: String, fromDate: String, toDate: String): Flow<List<MealPlanRow>>

    /** One-shot version of [observeRange]. */
    @Query(SELECT_ROWS + "WHERE m.account_id = :accountId AND m.from_date <= :toDate AND m.to_date >= :fromDate " + ORDER)
    abstract suspend fun getRange(accountId: String, fromDate: String, toDate: String): List<MealPlanRow>

    /**
     * Replaces the server's entries of one fetched window (the server filters on `to_date`, so
     * every stored server entry whose `to_date` lies in [fromDate]..[toDate] is replaced by
     * [entries]). Entries with unsent outbox rows are left alone (later phases edit offline).
     * Server entries are stored with `local_id = server_id`.
     */
    @Transaction
    open suspend fun replaceWindow(accountId: String, fromDate: String, toDate: String, entries: List<MealPlanEntity>) {
        val pending = getIdsWithUnsentChanges(accountId).toSet()
        deleteServerEntriesInWindow(accountId, fromDate, toDate)
        val rows = entries
            .filter { it.serverId != null && it.serverId !in pending }
            .map { it.copy(accountId = accountId, localId = requireNotNull(it.serverId)) }
            .distinctBy { it.localId }
        if (rows.isNotEmpty()) upsert(rows)
    }

    @Upsert
    abstract suspend fun upsert(rows: List<MealPlanEntity>)

    /** Server ids of meal plan entries with unsent outbox rows. */
    @Query(
        "SELECT server_id FROM outbox WHERE account_id = :accountId AND entity = 'meal_plan' AND server_id IS NOT NULL",
    )
    abstract suspend fun getIdsWithUnsentChanges(accountId: String): List<Long>

    @Query(
        "DELETE FROM meal_plan WHERE account_id = :accountId AND server_id IS NOT NULL " +
            "AND to_date >= :fromDate AND to_date <= :toDate AND NOT EXISTS " +
            "(SELECT 1 FROM outbox ob WHERE ob.account_id = meal_plan.account_id AND ob.entity = 'meal_plan' " +
            "AND ob.server_id = meal_plan.server_id)",
    )
    abstract suspend fun deleteServerEntriesInWindow(accountId: String, fromDate: String, toDate: String)

    // ---------------------------------------------------------------- Phase 2 (matsedel lane)

    /** Runs [block] in one transaction (a local change and its outbox row, plan 5.7). */
    @Transaction
    open suspend fun inTransaction(block: suspend () -> Unit) {
        block()
    }

    /** One stored meal by its local id (= server id for server rows, negative when created offline). */
    @Query("SELECT * FROM meal_plan WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun get(accountId: String, localId: Long): MealPlanEntity?

    /**
     * One meal with meal type and recipe, null while it is not stored. [id] may be a temporary id
     * that has since been rewritten to its server id (`id_mapping`): the meal is followed across
     * the rewrite.
     */
    @Query(
        SELECT_ROWS + "WHERE m.account_id = :accountId AND (m.local_id = :id OR m.local_id = (SELECT im.server_id FROM id_mapping im " +
            "WHERE im.account_id = :accountId AND im.entity = 'meal_plan' AND im.local_id = :id)) LIMIT 1",
    )
    abstract fun observeRow(accountId: String, id: Long): Flow<MealPlanRow?>

    @Query("DELETE FROM meal_plan WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun delete(accountId: String, localId: Long)

    /** The server's own values after it answered (`from_date`/`to_date` as sent, `shopping`); the user's fields stay. */
    @Query(
        "UPDATE meal_plan SET from_raw = :fromRaw, to_raw = :toRaw, shopping = :shopping " +
            "WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun updateServerValues(accountId: String, localId: Long, fromRaw: String?, toRaw: String?, shopping: Boolean)

    /**
     * Meals deleted on another device while this one had unsent changes for them (plan 3.5: a full
     * window read no longer lists them): their unsent outbox rows are dropped WITHOUT a request (no
     * 404) and the meals are removed, in one transaction.
     */
    @Transaction
    open suspend fun deleteGone(accountId: String, serverIds: List<Long>) {
        if (serverIds.isEmpty()) return
        deleteUnsentRowsOf(accountId, serverIds)
        deleteByServerIds(accountId, serverIds)
    }

    @Query(
        "DELETE FROM outbox WHERE account_id = :accountId AND entity = 'meal_plan' AND server_id IN (:serverIds) " +
            "AND state != 'IN_FLIGHT'",
    )
    abstract suspend fun deleteUnsentRowsOf(accountId: String, serverIds: List<Long>)

    @Query("DELETE FROM meal_plan WHERE account_id = :accountId AND server_id IN (:serverIds)")
    abstract suspend fun deleteByServerIds(accountId: String, serverIds: List<Long>)

    /**
     * Meals with entries on the stored shopping list ("Handla för veckan": "redan på listan"): an
     * entry or a list recipe linked to the meal (Phase 2 koppling lane: also a list recipe whose
     * entries are not stored yet, e.g. one the app queued or the server just created).
     */
    @Query(
        "SELECT meal_plan_id FROM shopping_entry WHERE account_id = :accountId AND meal_plan_id IS NOT NULL " +
            "UNION SELECT meal_plan_id FROM shopping_list_recipe WHERE account_id = :accountId AND meal_plan_id IS NOT NULL",
    )
    abstract suspend fun getMealIdsOnShoppingList(accountId: String): List<Long>

    private companion object {
        const val SELECT_ROWS: String =
            "SELECT m.*, " +
                "mt.account_id AS mt_account_id, mt.id AS mt_id, mt.name AS mt_name, mt.type_order AS mt_type_order, " +
                "mt.time AS mt_time, mt.color AS mt_color, " +
                "r.local_id AS r_local_id, r.account_id AS r_account_id, r.server_id AS r_server_id, r.name AS r_name, " +
                "r.description AS r_description, r.image_url AS r_image_url, r.working_minutes AS r_working_minutes, " +
                "r.waiting_minutes AS r_waiting_minutes, r.rating AS r_rating, r.last_cooked AS r_last_cooked, " +
                "r.times_cooked AS r_times_cooked, r.is_new AS r_is_new, r.is_private AS r_is_private, " +
                "r.created_at AS r_created_at, r.updated_at AS r_updated_at, r.search_name AS r_search_name " +
                "FROM meal_plan m " +
                "LEFT JOIN meal_type mt ON mt.account_id = m.account_id AND mt.id = m.meal_type_id " +
                "LEFT JOIN recipe_overview r ON r.account_id = m.account_id AND r.server_id = m.recipe_id "

        const val ORDER: String = "ORDER BY m.from_date, IFNULL(mt.type_order, 0), m.meal_type_id, m.local_id"
    }
}
