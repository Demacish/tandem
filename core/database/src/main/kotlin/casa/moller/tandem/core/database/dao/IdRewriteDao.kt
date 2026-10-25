package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.IdMappingEntity
import casa.moller.tandem.core.database.entity.OutboxEntities

/**
 * Temporary ids of objects created offline and their rewrite to server ids (plan 3.5, 5.7; Phase 2,
 * docs/BYGGE.md "Fas 2 – kontrakt"):
 *
 * 1. [nextLocalId] gives a new negative id for a table. It is lower than every id the table, the
 *    outbox and `id_mapping` ever held for that entity, so a temporary id is never reused (a screen
 *    may still hold an old one).
 * 2. The object is stored with that `local_id` and `server_id = null`, and its CREATE is queued with
 *    `local_id` only. Later rows for it (a check, a PATCH) carry the same `local_id`; rows of OTHER
 *    objects that point at it list the payload field in `refs` and `depends_on` the CREATE.
 * 3. When the CREATE is answered, the sender calls [completeCreate] with the server id: in ONE
 *    transaction the mapping is recorded, waiting outbox rows of the object get `server_id`, the local
 *    row is re-keyed to `local_id = server_id` (the invariant of server rows, so the next full sync
 *    replaces it instead of duplicating it), references in other tables are rewritten and the CREATE
 *    row is removed (which releases its dependents).
 * 4. Payload references are resolved just before sending (`OutboxRefs.resolve` with [resolve]).
 *
 * Mappings are never pruned in Phase 2 (one small row per offline create).
 */
@Dao
abstract class IdRewriteDao {
    /** The server id that temporary id [localId] of [entity] got, or null while it has none. */
    @Query("SELECT server_id FROM id_mapping WHERE account_id = :accountId AND entity = :entity AND local_id = :localId")
    abstract suspend fun resolve(accountId: String, entity: String, localId: Long): Long?

    /** [id] itself when it is a server id (>= 0), its server id when it is a mapped temporary id, else [id]. */
    open suspend fun current(accountId: String, entity: String, id: Long): Long = if (id >= 0) id else resolve(accountId, entity, id) ?: id

    /** A new temporary (negative) id for [entity] (one of the [OutboxEntities] with offline creates). */
    open suspend fun nextLocalId(accountId: String, entity: String): Long = when (entity) {
        OutboxEntities.SHOPPING_ENTRY -> lowestShoppingEntryId(accountId, entity)
        OutboxEntities.SHOPPING_LIST_RECIPE -> lowestListRecipeId(accountId, entity)
        OutboxEntities.MEAL_PLAN -> lowestMealPlanId(accountId, entity)
        OutboxEntities.MEAL_TYPE -> lowestMealTypeId(accountId, entity)
        OutboxEntities.RECIPE_BOOK -> lowestRecipeBookId(accountId, entity)
        OutboxEntities.RECIPE_BOOK_ENTRY -> lowestRecipeBookEntryId(accountId, entity)
        else -> error("no offline creates for $entity")
    } - 1

    /**
     * The CREATE in outbox row [outboxId] for temporary id [localId] of [entity] was answered with
     * [serverId]: records the mapping, rewrites the waiting outbox rows, re-keys the local row
     * (dropping a copy of the same server row that a sync stored meanwhile), rewrites references
     * in other tables and removes the outbox row, all in one transaction.
     */
    @Transaction
    open suspend fun completeCreate(
        accountId: String,
        outboxId: Long,
        entity: String,
        localId: Long,
        serverId: Long,
        nowEpochMillis: Long,
    ) {
        upsertMapping(IdMappingEntity(accountId, entity, localId, serverId, nowEpochMillis))
        rewriteOutboxServerId(accountId, entity, localId, serverId)
        when (entity) {
            OutboxEntities.SHOPPING_ENTRY -> {
                deleteShoppingEntry(accountId, serverId, localId)
                rekeyShoppingEntry(accountId, localId, serverId)
            }

            OutboxEntities.SHOPPING_LIST_RECIPE -> {
                deleteListRecipe(accountId, serverId, localId)
                rekeyListRecipe(accountId, localId, serverId)
                rewriteEntryListRecipe(accountId, localId, serverId)
            }

            OutboxEntities.MEAL_PLAN -> {
                deleteMealPlan(accountId, serverId, localId)
                rekeyMealPlan(accountId, localId, serverId)
                rewriteEntryMealPlan(accountId, localId, serverId)
                rewriteListRecipeMealPlan(accountId, localId, serverId)
            }

            OutboxEntities.MEAL_TYPE -> {
                deleteMealType(accountId, serverId, localId)
                rekeyMealType(accountId, localId, serverId)
                rewriteMealPlanMealType(accountId, localId, serverId)
            }

            OutboxEntities.RECIPE_BOOK -> {
                deleteRecipeBook(accountId, serverId, localId)
                rekeyRecipeBook(accountId, localId, serverId)
                rewriteBookEntries(accountId, localId, serverId)
            }

            OutboxEntities.RECIPE_BOOK_ENTRY -> {
                deleteRecipeBookEntry(accountId, serverId, localId)
                rekeyRecipeBookEntry(accountId, localId, serverId)
            }
        }
        deleteOutboxRow(outboxId)
    }

    // ---------------------------------------------------------------- building blocks (Room)

    @Upsert
    abstract suspend fun upsertMapping(row: IdMappingEntity)

    @Query("SELECT * FROM id_mapping WHERE account_id = :accountId ORDER BY entity, local_id")
    abstract suspend fun getMappings(accountId: String): List<IdMappingEntity>

    @Query("UPDATE outbox SET server_id = :serverId WHERE account_id = :accountId AND entity = :entity AND local_id = :localId")
    abstract suspend fun rewriteOutboxServerId(accountId: String, entity: String, localId: Long, serverId: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun deleteOutboxRow(id: Long)

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM shopping_entry WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestShoppingEntryId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM shopping_list_recipe WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestListRecipeId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM meal_plan WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestMealPlanId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(id) FROM meal_type WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestMealTypeId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM recipe_book WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestRecipeBookId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM recipe_book_entry WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestRecipeBookEntryId(accountId: String, entity: String): Long

    @Query("DELETE FROM shopping_entry WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteShoppingEntry(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE shopping_entry SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun rekeyShoppingEntry(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM shopping_list_recipe WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteListRecipe(accountId: String, serverId: Long, localId: Long)

    @Query(
        "UPDATE shopping_list_recipe SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun rekeyListRecipe(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE shopping_entry SET list_recipe_id = :serverId WHERE account_id = :accountId AND list_recipe_id = :localId")
    abstract suspend fun rewriteEntryListRecipe(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM meal_plan WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteMealPlan(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE meal_plan SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun rekeyMealPlan(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE shopping_entry SET meal_plan_id = :serverId WHERE account_id = :accountId AND meal_plan_id = :localId")
    abstract suspend fun rewriteEntryMealPlan(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE shopping_list_recipe SET meal_plan_id = :serverId WHERE account_id = :accountId AND meal_plan_id = :localId")
    abstract suspend fun rewriteListRecipeMealPlan(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM meal_type WHERE account_id = :accountId AND id = :serverId AND id != :localId")
    abstract suspend fun deleteMealType(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE meal_type SET id = :serverId WHERE account_id = :accountId AND id = :localId")
    abstract suspend fun rekeyMealType(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE meal_plan SET meal_type_id = :serverId WHERE account_id = :accountId AND meal_type_id = :localId")
    abstract suspend fun rewriteMealPlanMealType(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM recipe_book WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteRecipeBook(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE recipe_book SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun rekeyRecipeBook(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE recipe_book_entry SET book_id = :serverId WHERE account_id = :accountId AND book_id = :localId")
    abstract suspend fun rewriteBookEntries(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteRecipeBookEntry(accountId: String, serverId: Long, localId: Long)

    @Query(
        "UPDATE recipe_book_entry SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun rekeyRecipeBookEntry(accountId: String, localId: Long, serverId: Long)

    private companion object {
        /** `MIN(0, <lowest in the table>, <lowest mapped>, <lowest in the outbox>)`; callers subtract 1. */
        const val LOWEST_PREFIX: String = "SELECT MIN(0, IFNULL("
        const val LOWEST_SUFFIX: String =
            "IFNULL((SELECT MIN(local_id) FROM id_mapping WHERE account_id = :accountId AND entity = :entity), 0), " +
                "IFNULL((SELECT MIN(local_id) FROM outbox WHERE account_id = :accountId AND entity = :entity), 0))"
    }
}
