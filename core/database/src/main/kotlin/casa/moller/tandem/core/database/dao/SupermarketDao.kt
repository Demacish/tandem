package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
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
