package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryEntity
import casa.moller.tandem.core.database.entity.SupermarketCategoryRelationEntity
import casa.moller.tandem.core.database.entity.SupermarketEntity
import kotlinx.coroutines.flow.Flow

/** Stores, aisles and the aisle order per store (Phase 2, schema 4; feature #10). Skeleton: the shopping lane adds queries. */
@Dao
abstract class SupermarketDao {
    @Query("SELECT * FROM supermarket WHERE account_id = :accountId ORDER BY name, id")
    abstract fun observeSupermarkets(accountId: String): Flow<List<SupermarketEntity>>

    @Query("SELECT * FROM supermarket_category WHERE account_id = :accountId ORDER BY name, id")
    abstract fun observeCategories(accountId: String): Flow<List<SupermarketCategoryEntity>>

    /** The aisle order of one store, walking order first. */
    @Query(
        "SELECT * FROM supermarket_category_relation WHERE account_id = :accountId AND supermarket_id = :supermarketId " +
            "ORDER BY relation_order, id",
    )
    abstract fun observeRelations(accountId: String, supermarketId: Long): Flow<List<SupermarketCategoryRelationEntity>>

    /**
     * Replaces stores, categories and relations with the full lists from the server, in one
     * transaction (small tables, plan 3.5; the store list carries `category_to_supermarket`).
     */
    @Transaction
    open suspend fun replaceAll(
        accountId: String,
        supermarkets: List<SupermarketEntity>,
        categories: List<SupermarketCategoryEntity>,
        relations: List<SupermarketCategoryRelationEntity>,
    ) {
        deleteSupermarkets(accountId)
        deleteCategories(accountId)
        deleteRelations(accountId)
        if (supermarkets.isNotEmpty()) upsertSupermarkets(supermarkets.map { it.copy(accountId = accountId) }.distinctBy { it.id })
        if (categories.isNotEmpty()) upsertCategories(categories.map { it.copy(accountId = accountId) }.distinctBy { it.id })
        if (relations.isNotEmpty()) upsertRelations(relations.map { it.copy(accountId = accountId) }.distinctBy { it.id })
    }

    // ---------------------------------------------------------------- inköp-data lane (Phase 2)

    /** Every store's aisle order, for the store list (walking order first). */
    @Query("SELECT * FROM supermarket_category_relation WHERE account_id = :accountId ORDER BY supermarket_id, relation_order, id")
    abstract fun observeAllRelations(accountId: String): Flow<List<SupermarketCategoryRelationEntity>>

    @Query(
        "SELECT * FROM supermarket_category_relation WHERE account_id = :accountId AND supermarket_id = :supermarketId " +
            "ORDER BY relation_order, id",
    )
    abstract suspend fun getRelations(accountId: String, supermarketId: Long): List<SupermarketCategoryRelationEntity>

    @Query("UPDATE supermarket_category_relation SET relation_order = :order WHERE account_id = :accountId AND id = :id")
    abstract suspend fun setRelationOrder(accountId: String, id: Long, order: Int)

    @Query("SELECT * FROM supermarket WHERE account_id = :accountId AND id = :id")
    abstract suspend fun getSupermarket(accountId: String, id: Long): SupermarketEntity?

    @Query("SELECT * FROM supermarket_category WHERE account_id = :accountId AND id = :id")
    abstract suspend fun getCategory(accountId: String, id: Long): SupermarketCategoryEntity?

    /** The stored foods (suggestions, "har hemma", aisles), by name. */
    @Query("SELECT * FROM food WHERE account_id = :accountId ORDER BY name, id")
    abstract fun observeFoods(accountId: String): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food WHERE account_id = :accountId AND id = :foodId")
    abstract suspend fun getFood(accountId: String, foodId: Long): FoodEntity?

    /** "Har hemma" (`food_onhand`, per household), changed on this device before it is sent. */
    @Query("UPDATE food SET on_hand = :onHand WHERE account_id = :accountId AND id = :foodId")
    abstract suspend fun setFoodOnHand(accountId: String, foodId: Long, onHand: Boolean)

    /** A food's aisle (`supermarket_category`, per space), changed on this device before it is sent. */
    @Query("UPDATE food SET supermarket_category_id = :categoryId WHERE account_id = :accountId AND id = :foodId")
    abstract suspend fun setFoodCategory(accountId: String, foodId: Long, categoryId: Long?)

    // ---------------------------------------------------------------- building blocks (Room)

    @Upsert
    abstract suspend fun upsertSupermarkets(rows: List<SupermarketEntity>)

    @Upsert
    abstract suspend fun upsertCategories(rows: List<SupermarketCategoryEntity>)

    @Upsert
    abstract suspend fun upsertRelations(rows: List<SupermarketCategoryRelationEntity>)

    @Query("DELETE FROM supermarket WHERE account_id = :accountId")
    abstract suspend fun deleteSupermarkets(accountId: String)

    @Query("DELETE FROM supermarket_category WHERE account_id = :accountId")
    abstract suspend fun deleteCategories(accountId: String)

    @Query("DELETE FROM supermarket_category_relation WHERE account_id = :accountId")
    abstract suspend fun deleteRelations(accountId: String)
}
