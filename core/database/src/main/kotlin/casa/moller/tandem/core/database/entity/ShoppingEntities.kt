package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

// Phase 2 (schema 4, grund lane): the shopping list (plan 5.7 `shopping_entry`, `shopping_history`,
// features #9-#14). Keys follow the meal plan's rule: rows from the server have
// `local_id = server_id`; a row created offline has a negative `local_id` and `server_id = null`
// until its CREATE is sent, then OutboxDao.completeCreate re-keys it to the server id in the same
// transaction (and records the old id in `id_mapping`). Times are epoch milliseconds (UTC).

/**
 * One entry of the shopping list (Tandoor `ShoppingListEntry`).
 *
 * @property foodId the server's food id; null for an offline entry whose food the server has not
 *   created yet (the CREATE sends `food: {name}` and Tandoor creates or reuses it by name).
 * @property foodName the food's name as shown (FACTS 2.6.13 "Fas 2": `food` is `FoodShopping`).
 * @property categoryId `food.supermarket_category.id` at the last fetch (the aisle), if any.
 * @property unitId the unit's server id, null without a unit (free text "tandkräm").
 * @property amount the amount (Tandoor answers decimals, `2.0`; 0 when the parser found none).
 * @property entryOrder the server's `order`.
 * @property completedAt the server's `completed_at`; null while unchecked. Set by the server only.
 * @property delayUntil "köp senare" (#14): `delay_until`; at or before now (Tandoor's undo value is
 *   1970-01-01) means not delayed.
 * @property createdBy id of the household member who added it (`created_by.id`).
 * @property createdByName that member's display name, for "Anna lade till Mjölk".
 * @property listRecipeId the `list_recipe` (group from one recipe or meal), server or local id.
 * @property ingredientId the recipe ingredient the entry came from, if any.
 * @property mealPlanId `list_recipe_data.mealplan` (the meal the entry is for), server or local id.
 * @property recipeId `list_recipe_data.recipe`, for the recipe label.
 * @property recipeName the recipe's name for the small label.
 * @property shoppingListIds JSON array of Tandoor shopping list ids (`[1,2]`), "listval" (#10).
 * @property fetchedAt when the row was last written from a server response (0 for offline rows).
 */
@Entity(
    tableName = "shopping_entry",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "list_recipe_id"]),
        Index(value = ["account_id", "meal_plan_id"]),
        Index(value = ["account_id", "checked"]),
    ],
)
data class ShoppingEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "food_name") val foodName: String,
    @ColumnInfo(name = "food_plural_name") val foodPluralName: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "unit_id") val unitId: Long? = null,
    @ColumnInfo(name = "unit_name") val unitName: String? = null,
    @ColumnInfo(name = "unit_plural_name") val unitPluralName: String? = null,
    @ColumnInfo(name = "amount") val amount: Double = 0.0,
    @ColumnInfo(name = "entry_order") val entryOrder: Int = 0,
    @ColumnInfo(name = "checked") val checked: Boolean = false,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "delay_until") val delayUntil: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
    @ColumnInfo(name = "created_by_name") val createdByName: String? = null,
    @ColumnInfo(name = "list_recipe_id") val listRecipeId: Long? = null,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long? = null,
    @ColumnInfo(name = "meal_plan_id") val mealPlanId: Long? = null,
    @ColumnInfo(name = "recipe_id") val recipeId: Long? = null,
    @ColumnInfo(name = "recipe_name") val recipeName: String? = null,
    @ColumnInfo(name = "shopping_list_ids") val shoppingListIds: String = "[]",
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
)

/**
 * A group of entries from one recipe or meal (Tandoor `ShoppingListRecipe`, `list_recipe`).
 *
 * @property mealPlanId the meal it belongs to (`mealplan`), server or local id, if any.
 * @property servings the servings the entries were scaled to.
 */
@Entity(
    tableName = "shopping_list_recipe",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "meal_plan_id"]),
    ],
)
data class ShoppingListRecipeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "name") val name: String = "",
    @ColumnInfo(name = "recipe_id") val recipeId: Long? = null,
    @ColumnInfo(name = "recipe_name") val recipeName: String? = null,
    @ColumnInfo(name = "meal_plan_id") val mealPlanId: Long? = null,
    @ColumnInfo(name = "servings") val servings: Double = 1.0,
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
)

/** A named Tandoor shopping list (`/api/shopping-list/`, "listval" #10); reference data, server ids only. */
@Entity(tableName = "shopping_list", primaryKeys = ["account_id", "id"])
data class ShoppingListEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "color") val color: String? = null,
)

/**
 * A checked entry that aged out of the server's list window (plan 3.5 window rule, 8.2 "Lokal
 * historik"): kept on the device for "brukar köpa" and recurring items (#29, Phase 4). Keyed by
 * the entry's server id, so an entry is archived once.
 */
@Entity(
    tableName = "shopping_history",
    primaryKeys = ["account_id", "entry_id"],
    indices = [
        Index(value = ["account_id", "food_name"]),
        Index(value = ["account_id", "completed_at"]),
    ],
)
data class ShoppingHistoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "food_name") val foodName: String,
    @ColumnInfo(name = "unit_id") val unitId: Long? = null,
    @ColumnInfo(name = "unit_name") val unitName: String? = null,
    @ColumnInfo(name = "amount") val amount: Double = 0.0,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "recipe_name") val recipeName: String? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "archived_at") val archivedAt: Long,
)
