package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.BarcodeProductEntity
import casa.moller.tandem.core.database.entity.DrinkDraftEntity
import casa.moller.tandem.core.database.entity.FoodAliasEntity
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.MakeNowEntity
import casa.moller.tandem.core.database.entity.StapleEntity
import kotlinx.coroutines.flow.Flow

// Fas S (schema 5, grund lane): one DAO per lane, so the lanes add queries without editing the same
// file (docs/BYGGE.md "Fas S – kontrakt", "Vem bygger vad"). The tables are final; the queries are
// a first set the lanes extend. Every function takes the account id. A full re-read replaces the
// server rows (`local_id >= 0`) and keeps the rows created offline (negative `local_id`).

/** The pantry: locations and stock entries (hemma-data lane). */
@Dao
abstract class PantryDao {
    @Query("SELECT * FROM inventory_location WHERE account_id = :accountId ORDER BY location_order, name COLLATE NOCASE")
    abstract fun observeLocations(accountId: String): Flow<List<InventoryLocationEntity>>

    @Query("SELECT * FROM inventory_location WHERE account_id = :accountId ORDER BY location_order, name COLLATE NOCASE")
    abstract suspend fun getLocations(accountId: String): List<InventoryLocationEntity>

    @Query("SELECT * FROM inventory_location WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getLocation(accountId: String, localId: Long): InventoryLocationEntity?

    @Upsert
    abstract suspend fun upsertLocations(rows: List<InventoryLocationEntity>)

    @Query("DELETE FROM inventory_location WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun deleteLocation(accountId: String, localId: Long)

    /** Entries with amount > 0 (what the pantry shows), soonest expiry first, no date last. */
    @Query(
        "SELECT * FROM inventory_entry WHERE account_id = :accountId AND amount > 0 " +
            "ORDER BY expires IS NULL, expires, food_name COLLATE NOCASE",
    )
    abstract fun observeEntries(accountId: String): Flow<List<InventoryEntryEntity>>

    @Query("SELECT * FROM inventory_entry WHERE account_id = :accountId ORDER BY expires IS NULL, expires, food_name COLLATE NOCASE")
    abstract suspend fun getEntries(accountId: String): List<InventoryEntryEntity>

    @Query("SELECT * FROM inventory_entry WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getEntry(accountId: String, localId: Long): InventoryEntryEntity?

    /** Entries with amount > 0 that expire on or before [lastDay] (`yyyy-MM-dd`), for "Använd upp" (#28). */
    @Query(
        "SELECT * FROM inventory_entry WHERE account_id = :accountId AND amount > 0 AND expires IS NOT NULL " +
            "AND expires <= :lastDay ORDER BY expires",
    )
    abstract fun observeExpiring(accountId: String, lastDay: String): Flow<List<InventoryEntryEntity>>

    @Upsert
    abstract suspend fun upsertEntries(rows: List<InventoryEntryEntity>)

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun deleteEntry(accountId: String, localId: Long)

    /** A full re-read: the server's locations and entries replace the stored server rows; offline creates stay. */
    @Transaction
    open suspend fun replaceServerRows(accountId: String, locations: List<InventoryLocationEntity>, entries: List<InventoryEntryEntity>) {
        deleteServerLocations(accountId)
        deleteServerEntries(accountId)
        if (locations.isNotEmpty()) upsertLocations(locations)
        if (entries.isNotEmpty()) upsertEntries(entries)
    }

    @Query("DELETE FROM inventory_location WHERE account_id = :accountId AND local_id >= 0")
    abstract suspend fun deleteServerLocations(accountId: String)

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND local_id >= 0")
    abstract suspend fun deleteServerEntries(accountId: String)

    // --- hemma-data lane ---

    /** Runs [block] in one transaction (a local change and its outbox row are stored together). */
    @Transaction
    open suspend fun inTransaction(block: suspend () -> Unit) {
        block()
    }

    /** One entry as it changes (also with amount 0), null when it is gone. */
    @Query("SELECT * FROM inventory_entry WHERE account_id = :accountId AND local_id = :localId")
    abstract fun observeEntry(accountId: String, localId: Long): Flow<InventoryEntryEntity?>

    @Query("SELECT * FROM inventory_entry WHERE account_id = :accountId AND location_id = :locationId")
    abstract suspend fun getEntriesOfLocation(accountId: String, locationId: Long): List<InventoryEntryEntity>

    @Query("DELETE FROM inventory_entry WHERE account_id = :accountId AND location_id = :locationId")
    abstract suspend fun deleteEntriesOfLocation(accountId: String, locationId: Long)

    @Query("UPDATE inventory_location SET location_order = :order WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun setLocationOrder(accountId: String, localId: Long, order: Int)

    /** Every entry with amount > 0 of every location (the household filter is the caller's), for "har hemma" and makenow. */
    @Query("SELECT * FROM inventory_entry WHERE account_id = :accountId AND amount > 0")
    abstract fun observeStockedEntries(accountId: String): Flow<List<InventoryEntryEntity>>
}

/** Staples ("Basvaror", hemma-data lane). */
@Dao
abstract class StapleDao {
    @Query("SELECT * FROM staple WHERE account_id = :accountId ORDER BY name COLLATE NOCASE")
    abstract fun observeStaples(accountId: String): Flow<List<StapleEntity>>

    @Upsert
    abstract suspend fun upsertStaples(rows: List<StapleEntity>)

    @Query("DELETE FROM staple WHERE account_id = :accountId AND name_key = :nameKey")
    abstract suspend fun deleteStaple(accountId: String, nameKey: String)

    // --- hemma-data lane ---

    @Query("SELECT * FROM staple WHERE account_id = :accountId ORDER BY name COLLATE NOCASE")
    abstract suspend fun getStaples(accountId: String): List<StapleEntity>
}

/** "Kan laga nu" (#23): the server's makenow groups (hemma-data lane). */
@Dao
abstract class MakeNowDao {
    @Query("SELECT * FROM make_now WHERE account_id = :accountId ORDER BY missing_count, recipe_id")
    abstract fun observeAll(accountId: String): Flow<List<MakeNowEntity>>

    @Upsert
    abstract suspend fun upsert(rows: List<MakeNowEntity>)

    /** The three answers (`makenow=0/1/2`) of one refresh replace what was stored. */
    @Transaction
    open suspend fun replaceAll(accountId: String, rows: List<MakeNowEntity>) {
        deleteAll(accountId)
        if (rows.isNotEmpty()) upsert(rows)
    }

    @Query("UPDATE make_now SET missing_foods = :missingFoods WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract suspend fun setMissingFoods(accountId: String, recipeId: Long, missingFoods: String?)

    @Query("DELETE FROM make_now WHERE account_id = :accountId")
    abstract suspend fun deleteAll(accountId: String)

    // --- hemma-data lane ---

    @Query("SELECT * FROM make_now WHERE account_id = :accountId ORDER BY missing_count, recipe_id")
    abstract suspend fun getAll(accountId: String): List<MakeNowEntity>

    /** The stored ingredients of the recipes in "Kan laga nu" (for the missing foods and "Använd upp"). */
    @Query(
        "SELECT * FROM ingredient WHERE account_id = :accountId " +
            "AND recipe_id IN (SELECT recipe_id FROM make_now WHERE account_id = :accountId) ORDER BY recipe_id, step_position, position",
    )
    abstract fun observeIngredients(accountId: String): Flow<List<IngredientEntity>>

    /** The recipes of "Kan laga nu" whose detail is downloaded (their ingredients are known). */
    @Query(
        "SELECT recipe_id FROM recipe_detail WHERE account_id = :accountId AND raw_json IS NOT NULL " +
            "AND recipe_id IN (SELECT recipe_id FROM make_now WHERE account_id = :accountId)",
    )
    abstract fun observeDetailIds(accountId: String): Flow<List<Long>>

    /** The stored ingredients of [recipeId]. */
    @Query("SELECT * FROM ingredient WHERE account_id = :accountId AND recipe_id = :recipeId ORDER BY step_position, position")
    abstract suspend fun getIngredients(accountId: String, recipeId: Long): List<IngredientEntity>

    /** Whether [recipeId]'s detail is downloaded. */
    @Query("SELECT EXISTS(SELECT 1 FROM recipe_detail WHERE account_id = :accountId AND recipe_id = :recipeId AND raw_json IS NOT NULL)")
    abstract suspend fun hasDetail(accountId: String, recipeId: Long): Boolean

    /** Every recipe with a downloaded detail (the demo computes its groups from them). */
    @Query("SELECT recipe_id FROM recipe_detail WHERE account_id = :accountId AND raw_json IS NOT NULL ORDER BY recipe_id")
    abstract suspend fun getDetailIds(accountId: String): List<Long>
}

/** Learned barcodes and receipt names (`FOOD_ALIAS` automations, skanning lane). */
@Dao
abstract class FoodAliasDao {
    @Query("SELECT * FROM food_alias WHERE account_id = :accountId ORDER BY alias_key")
    abstract fun observeAliases(accountId: String): Flow<List<FoodAliasEntity>>

    /** The enabled aliases for [aliasKey] (lower-cased, trimmed), newest server row first. */
    @Query("SELECT * FROM food_alias WHERE account_id = :accountId AND alias_key = :aliasKey AND disabled = 0 ORDER BY local_id DESC")
    abstract suspend fun find(accountId: String, aliasKey: String): List<FoodAliasEntity>

    @Upsert
    abstract suspend fun upsert(rows: List<FoodAliasEntity>)

    @Query("DELETE FROM food_alias WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun delete(accountId: String, localId: Long)

    /** A full re-read of `automation?type=FOOD_ALIAS`: server rows are replaced, offline creates stay. */
    @Transaction
    open suspend fun replaceServerRows(accountId: String, rows: List<FoodAliasEntity>) {
        deleteServerRows(accountId)
        if (rows.isNotEmpty()) upsert(rows)
    }

    @Query("DELETE FROM food_alias WHERE account_id = :accountId AND local_id >= 0")
    abstract suspend fun deleteServerRows(accountId: String)

    // --- hemma-data lane ---

    @Query("SELECT * FROM food_alias WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun get(accountId: String, localId: Long): FoodAliasEntity?

    @Query("SELECT * FROM food_alias WHERE account_id = :accountId ORDER BY alias_key")
    abstract suspend fun getAll(accountId: String): List<FoodAliasEntity>
}

/** The Open Food Facts cache (skanning lane). */
@Dao
abstract class BarcodeProductDao {
    @Query("SELECT * FROM barcode_product WHERE account_id = :accountId AND ean = :ean")
    abstract suspend fun get(accountId: String, ean: String): BarcodeProductEntity?

    @Upsert
    abstract suspend fun upsert(row: BarcodeProductEntity)

    @Query("DELETE FROM barcode_product WHERE account_id = :accountId AND fetched_at < :olderThanEpochMillis")
    abstract suspend fun deleteOlderThan(accountId: String, olderThanEpochMillis: Long)
}

/** Drinks created offline (drycker lane). */
@Dao
abstract class DrinkDraftDao {
    @Query("SELECT * FROM drink_draft WHERE account_id = :accountId ORDER BY created_at")
    abstract fun observeDrafts(accountId: String): Flow<List<DrinkDraftEntity>>

    @Query("SELECT * FROM drink_draft WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun get(accountId: String, localId: Long): DrinkDraftEntity?

    @Upsert
    abstract suspend fun upsert(row: DrinkDraftEntity)

    @Query("DELETE FROM drink_draft WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun delete(accountId: String, localId: Long)
}
