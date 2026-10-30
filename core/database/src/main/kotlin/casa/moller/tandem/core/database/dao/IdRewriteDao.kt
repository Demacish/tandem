package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.IdMappingEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps

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
 * 5. [pruneMappings] (Fas S, rester lane) removes old mappings nothing can need any more; Phase 2
 *    never pruned them (one small row per offline create).
 */
@Dao
abstract class IdRewriteDao {
    /** The server id that temporary id [localId] of [entity] got, or null while it has none. */
    @Query("SELECT server_id FROM id_mapping WHERE account_id = :accountId AND entity = :entity AND local_id = :localId")
    abstract suspend fun resolve(accountId: String, entity: String, localId: Long): Long?

    /** [id] itself when it is a server id (>= 0), its server id when it is a mapped temporary id, else [id]. */
    open suspend fun current(accountId: String, entity: String, id: Long): Long = if (id >= 0) id else resolve(accountId, entity, id) ?: id

    /**
     * A new temporary (negative) id for [entity] (one of the [OutboxEntities] with offline creates;
     * Fas S adds locations, entries, aliases, keywords and drinks).
     */
    open suspend fun nextLocalId(accountId: String, entity: String): Long = when (entity) {
        OutboxEntities.SHOPPING_ENTRY -> lowestShoppingEntryId(accountId, entity)
        OutboxEntities.SHOPPING_LIST_RECIPE -> lowestListRecipeId(accountId, entity)
        OutboxEntities.MEAL_PLAN -> lowestMealPlanId(accountId, entity)
        OutboxEntities.MEAL_TYPE -> lowestMealTypeId(accountId, entity)
        OutboxEntities.RECIPE_BOOK -> lowestRecipeBookId(accountId, entity)
        OutboxEntities.RECIPE_BOOK_ENTRY -> lowestRecipeBookEntryId(accountId, entity)
        OutboxEntities.INVENTORY_LOCATION -> lowestInventoryLocationId(accountId, entity)
        OutboxEntities.INVENTORY_ENTRY -> lowestInventoryEntryId(accountId, entity)
        OutboxEntities.FOOD_ALIAS -> lowestFoodAliasId(accountId, entity)
        OutboxEntities.KEYWORD -> lowestKeywordId(accountId, entity)
        OutboxEntities.DRINK -> lowestDrinkDraftId(accountId, entity)
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

            else -> rewritePhaseSCreate(accountId, entity, localId, serverId)
        }
        deleteOutboxRow(outboxId)
    }

    /**
     * Fas S (schema 5): the local side of an answered CREATE of a location, an entry, a learned
     * alias, a keyword or a drink (part of [completeCreate]'s transaction).
     */
    open suspend fun rewritePhaseSCreate(accountId: String, entity: String, localId: Long, serverId: Long) {
        when (entity) {
            OutboxEntities.INVENTORY_LOCATION -> {
                deleteInventoryLocation(accountId, serverId, localId)
                rekeyInventoryLocation(accountId, localId, serverId)
                rewriteEntryLocation(accountId, localId, serverId)
            }

            OutboxEntities.INVENTORY_ENTRY -> {
                deleteInventoryEntry(accountId, serverId, localId)
                rekeyInventoryEntry(accountId, localId, serverId)
            }

            OutboxEntities.FOOD_ALIAS -> {
                deleteFoodAlias(accountId, serverId, localId)
                rekeyFoodAlias(accountId, localId, serverId)
            }

            OutboxEntities.KEYWORD -> {
                deleteKeyword(accountId, serverId, localId)
                rekeyKeyword(accountId, localId, serverId)
                rewriteKeywordParent(accountId, localId, serverId)
                rewriteRecipeKeywordRefs(accountId, localId, serverId)
            }

            // The recipe itself arrives with the sync (or the sender stores the answer); the draft is done.
            OutboxEntities.DRINK -> deleteDrinkDraft(accountId, localId)
        }
    }

    // ---------------------------------------------------------------- pruning (Fas S, rester lane)

    /**
     * Removes the mappings of [accountId] recorded before [createdBefore] (the CREATE's rewrite
     * finished then; callers pass now minus [MAPPING_RETENTION_MILLIS]) that nothing can need any
     * more, in ONE transaction, and returns how many went. A mapping stays while:
     *
     * - it is the lowest (most negative) temporary id of its entity: [nextLocalId] reads it, so a
     *   temporary id is never handed out twice;
     * - an outbox row of the object itself waits (same entity and `local_id`), a CREATE of the same
     *   entity waits (its lost-answer search leaves out the server ids other creates claimed,
     *   `claimedServerIds` in core:sync), or any outbox row mentions the temporary id in its payload,
     *   base snapshot or `refs` (a reference resolved just before sending, a `keyword:<id>` field);
     *   a text match is enough, so a mapping may stay longer than needed, never shorter;
     * - a local table still holds the temporary id (the object's own row, or a reference to it that
     *   a screen holding the old id wrote after the rewrite), see [stillReferenced].
     *
     * No schema change: `id_mapping` already has `created_at` (schema 4).
     */
    @Transaction
    open suspend fun pruneMappings(accountId: String, createdBefore: Long): Int {
        var removed = 0
        for (mapping in pruneCandidates(accountId, createdBefore)) {
            val id = mapping.localId
            if (outboxMayNeed(accountId, mapping.entity, id, id.toString())) continue
            if (stillReferenced(accountId, mapping.entity, id)) continue
            removed += deleteMapping(accountId, mapping.entity, id)
        }
        return removed
    }

    /** Whether a local table still holds temporary id [id] of [entity]; an unknown entity is kept. */
    open suspend fun stillReferenced(accountId: String, entity: String, id: Long): Boolean = when (entity) {
        OutboxEntities.SHOPPING_ENTRY -> shoppingEntryReferenced(accountId, id)
        OutboxEntities.SHOPPING_LIST_RECIPE -> listRecipeReferenced(accountId, id)
        OutboxEntities.MEAL_PLAN -> mealPlanReferenced(accountId, id)
        OutboxEntities.MEAL_TYPE -> mealTypeReferenced(accountId, id)
        OutboxEntities.RECIPE_BOOK -> recipeBookReferenced(accountId, id)
        OutboxEntities.RECIPE_BOOK_ENTRY -> recipeBookEntryReferenced(accountId, id)
        OutboxEntities.INVENTORY_LOCATION -> inventoryLocationReferenced(accountId, id)
        OutboxEntities.INVENTORY_ENTRY -> inventoryEntryReferenced(accountId, id)
        OutboxEntities.FOOD_ALIAS -> foodAliasReferenced(accountId, id)
        OutboxEntities.KEYWORD -> keywordReferenced(accountId, id)
        OutboxEntities.DRINK -> drinkDraftReferenced(accountId, id)
        else -> true
    }

    /** Mappings older than [createdBefore], without the lowest temporary id of each entity. */
    @Query(
        "SELECT * FROM id_mapping WHERE account_id = :accountId AND created_at < :createdBefore AND local_id > " +
            "(SELECT MIN(m.local_id) FROM id_mapping m WHERE m.account_id = id_mapping.account_id AND m.entity = id_mapping.entity) " +
            "ORDER BY entity, local_id",
    )
    abstract suspend fun pruneCandidates(accountId: String, createdBefore: Long): List<IdMappingEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM outbox WHERE account_id = :accountId AND (" +
            "(entity = :entity AND (local_id = :localId OR op = '" + OutboxOps.CREATE + "')) " +
            "OR instr(payload, :idText) > 0 OR instr(IFNULL(base_snapshot, ''), :idText) > 0 OR instr(IFNULL(refs, ''), :idText) > 0))",
    )
    abstract suspend fun outboxMayNeed(accountId: String, entity: String, localId: Long, idText: String): Boolean

    @Query("DELETE FROM id_mapping WHERE account_id = :accountId AND entity = :entity AND local_id = :localId")
    abstract suspend fun deleteMapping(accountId: String, entity: String, localId: Long): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM shopping_entry WHERE account_id = :accountId AND local_id = :id) " +
            "OR EXISTS(SELECT 1 FROM shopping_history WHERE account_id = :accountId AND entry_id = :id)",
    )
    abstract suspend fun shoppingEntryReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM shopping_list_recipe WHERE account_id = :accountId AND local_id = :id) " +
            "OR EXISTS(SELECT 1 FROM shopping_entry WHERE account_id = :accountId AND list_recipe_id = :id)",
    )
    abstract suspend fun listRecipeReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM meal_plan WHERE account_id = :accountId AND local_id = :id) " +
            "OR EXISTS(SELECT 1 FROM shopping_entry WHERE account_id = :accountId AND meal_plan_id = :id) " +
            "OR EXISTS(SELECT 1 FROM shopping_list_recipe WHERE account_id = :accountId AND meal_plan_id = :id)",
    )
    abstract suspend fun mealPlanReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM meal_type WHERE account_id = :accountId AND id = :id) " +
            "OR EXISTS(SELECT 1 FROM meal_plan WHERE account_id = :accountId AND meal_type_id = :id) " +
            "OR EXISTS(SELECT 1 FROM user_preference WHERE account_id = :accountId AND default_meal_type_id = :id)",
    )
    abstract suspend fun mealTypeReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM recipe_book WHERE account_id = :accountId AND local_id = :id) " +
            "OR EXISTS(SELECT 1 FROM recipe_book_entry WHERE account_id = :accountId AND book_id = :id)",
    )
    abstract suspend fun recipeBookReferenced(accountId: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM recipe_book_entry WHERE account_id = :accountId AND local_id = :id)")
    abstract suspend fun recipeBookEntryReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM inventory_location WHERE account_id = :accountId AND local_id = :id) " +
            "OR EXISTS(SELECT 1 FROM inventory_entry WHERE account_id = :accountId AND location_id = :id)",
    )
    abstract suspend fun inventoryLocationReferenced(accountId: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM inventory_entry WHERE account_id = :accountId AND local_id = :id)")
    abstract suspend fun inventoryEntryReferenced(accountId: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM food_alias WHERE account_id = :accountId AND local_id = :id)")
    abstract suspend fun foodAliasReferenced(accountId: String, id: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM keyword WHERE account_id = :accountId AND (id = :id OR parent_id = :id)) " +
            "OR EXISTS(SELECT 1 FROM recipe_keyword WHERE account_id = :accountId AND keyword_id = :id)",
    )
    abstract suspend fun keywordReferenced(accountId: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM drink_draft WHERE account_id = :accountId AND local_id = :id)")
    abstract suspend fun drinkDraftReferenced(accountId: String, id: Long): Boolean

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

    // --- Fas S (schema 5) ---

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM inventory_location WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestInventoryLocationId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM inventory_entry WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestInventoryEntryId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM food_alias WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestFoodAliasId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(id) FROM keyword WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestKeywordId(accountId: String, entity: String): Long

    @Query(LOWEST_PREFIX + "(SELECT MIN(local_id) FROM drink_draft WHERE account_id = :accountId), 0), " + LOWEST_SUFFIX)
    abstract suspend fun lowestDrinkDraftId(accountId: String, entity: String): Long

    @Query("DELETE FROM inventory_location WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteInventoryLocation(accountId: String, serverId: Long, localId: Long)

    @Query(
        "UPDATE inventory_location SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun rekeyInventoryLocation(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE inventory_entry SET location_id = :serverId WHERE account_id = :accountId AND location_id = :localId")
    abstract suspend fun rewriteEntryLocation(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteInventoryEntry(accountId: String, serverId: Long, localId: Long)

    @Query(
        "UPDATE inventory_entry SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId",
    )
    abstract suspend fun rekeyInventoryEntry(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM food_alias WHERE account_id = :accountId AND local_id = :serverId AND local_id != :localId")
    abstract suspend fun deleteFoodAlias(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE food_alias SET local_id = :serverId, server_id = :serverId WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun rekeyFoodAlias(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM keyword WHERE account_id = :accountId AND id = :serverId AND id != :localId")
    abstract suspend fun deleteKeyword(accountId: String, serverId: Long, localId: Long)

    @Query("UPDATE keyword SET id = :serverId WHERE account_id = :accountId AND id = :localId")
    abstract suspend fun rekeyKeyword(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE keyword SET parent_id = :serverId WHERE account_id = :accountId AND parent_id = :localId")
    abstract suspend fun rewriteKeywordParent(accountId: String, localId: Long, serverId: Long)

    @Query("UPDATE recipe_keyword SET keyword_id = :serverId WHERE account_id = :accountId AND keyword_id = :localId")
    abstract suspend fun rewriteRecipeKeywordRefs(accountId: String, localId: Long, serverId: Long)

    @Query("DELETE FROM drink_draft WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun deleteDrinkDraft(accountId: String, localId: Long)

    companion object {
        /**
         * Fas S (rester lane): how long a mapping is kept after its CREATE was rewritten at least
         * ([pruneMappings]). A screen or a saved back stack may still hold the old temporary id for
         * a while (`current`, the meal plan's lookup by an old id); after 30 days it is gone.
         */
        const val MAPPING_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** `MIN(0, <lowest in the table>, <lowest mapped>, <lowest in the outbox>)`; callers subtract 1. */
        private const val LOWEST_PREFIX: String = "SELECT MIN(0, IFNULL("
        private const val LOWEST_SUFFIX: String =
            "IFNULL((SELECT MIN(local_id) FROM id_mapping WHERE account_id = :accountId AND entity = :entity), 0), " +
                "IFNULL((SELECT MIN(local_id) FROM outbox WHERE account_id = :accountId AND entity = :entity), 0))"
    }
}
