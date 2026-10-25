package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

// Phase 2 (schema 4, grund lane): stores and the aisle order (plan 5.7 small reference tables,
// feature #10). Full re-read like the other small tables (plan 3.5); server ids only.

/** A store (Tandoor `Supermarket`). [shoppingListIds] is a JSON array of shopping list ids. */
@Entity(tableName = "supermarket", primaryKeys = ["account_id", "id"])
data class SupermarketEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "shopping_list_ids") val shoppingListIds: String = "[]",
)

/** An aisle / category (Tandoor `SupermarketCategory`), e.g. "Mejeri". */
@Entity(tableName = "supermarket_category", primaryKeys = ["account_id", "id"])
data class SupermarketCategoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
)

/**
 * Where a category comes in a store (Tandoor `SupermarketCategoryRelation`, the store's
 * `category_to_supermarket`). [relationOrder] is the server's `order` (ascending = walking order).
 */
@Entity(
    tableName = "supermarket_category_relation",
    primaryKeys = ["account_id", "id"],
    indices = [Index(value = ["account_id", "supermarket_id"])],
)
data class SupermarketCategoryRelationEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "supermarket_id") val supermarketId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "relation_order") val relationOrder: Int = 0,
)
